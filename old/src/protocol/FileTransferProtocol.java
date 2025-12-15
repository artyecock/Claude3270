package protocol;

import config.Constants;
import callbacks.TransferCallback;
import util.EbcdicConverter;
import util.AddressEncoder;

import java.io.*;

/**
 * Handles IND$FILE protocol operations for file transfer.
 * Processes Data Chain structured fields for upload/download.
 */
public class FileTransferProtocol {
    private final OutputStream output;
    private final TelnetProtocol telnetProtocol;
    private final FileTransferManager manager;
    private TransferCallback callback;
    
    public FileTransferProtocol(OutputStream output, 
                               TelnetProtocol telnetProtocol,
                               FileTransferManager manager) {
        this.output = output;
        this.telnetProtocol = telnetProtocol;
        this.manager = manager;
    }
    
    public void setCallback(TransferCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Process Data Chain structured field.
     */
    public void handleDataChain(byte[] data, int offset, int length) {
        if (offset + 3 >= data.length) return;
        
        byte operation = data[offset + 3];
        System.out.println("Data Chain operation: 0x" + String.format("%02X", operation) +
                         " at offset " + offset + ", length " + length);
        
        switch (operation) {
            case Constants.DC_OPEN:
                handleDCOpen(data, offset, length);
                break;
            case Constants.DC_CLOSE:
                handleDCClose(data, offset, length);
                break;
            case Constants.DC_SET_CURSOR:
                handleDCSetCursor(data, offset, length);
                break;
            case Constants.DC_GET:
                handleDCGet(data, offset, length);
                break;
            case Constants.DC_INSERT:
                handleDCInsert(data, offset, length);
                break;
            default:
                System.out.println("Unknown Data Chain operation: 0x" + 
                                 String.format("%02X", operation));
        }
    }
    
    /**
     * Handle DC_OPEN - Open file transfer session.
     */
    private void handleDCOpen(byte[] data, int offset, int length) {
        System.out.println("=== DC_OPEN received ===");
        
        // Parse filename from the Open command
        String filename = extractFilename(data, offset, length);
        System.out.println("Open filename: " + filename);
        manager.setCurrentFilename(filename);
        
        // Check if this is data transfer or message
        boolean isData = filename != null && filename.contains("FT:DATA");
        boolean isMsg = filename != null && filename.contains("FT:MSG");
        
        if (isMsg) {
            System.out.println("FT:MSG detected - preparing for completion message");
            manager.setState(FileTransferManager.TransferState.IDLE);
            manager.setMessage(true);
            manager.resetBlockSequence();
            sendDCOpenResponse(true, 0);
            return;
        }
        
        if (!isData) {
            System.out.println("Unknown file type in Open");
            sendDCOpenResponse(false, 0x5D00);
            if (callback != null) {
                callback.onTransferError("Unknown file transfer type");
            }
            return;
        }
        
        // Reset message flag when opening FT:DATA
        manager.setMessage(false);
        
        // Determine direction from the Open header
        boolean hostWillGet = false;
        int directionByteOffset = offset + 14;
        
        if (directionByteOffset < data.length) {
            hostWillGet = (data[directionByteOffset] == 0x01);
            System.out.println("Direction byte: 0x" + 
                             String.format("%02X", data[directionByteOffset]) +
                             " -> hostWillGet=" + hostWillGet);
        }
        
        // Open the file streams
        try {
            manager.openStreams();
            manager.setState(FileTransferManager.TransferState.TRANSFER_IN_PROGRESS);
            manager.resetBlockSequence();
            sendDCOpenResponse(true, 0);
            
            if (callback != null) {
                callback.onTransferStart(
                    manager.getCurrentFile().getName(),
                    hostWillGet
                );
            }
            
        } catch (IOException e) {
            e.printStackTrace();
            sendDCOpenResponse(false, e instanceof FileNotFoundException ? 0x1B00 : 0x2000);
            if (callback != null) {
                callback.onTransferError("File open error: " + e.getMessage());
            }
        }
    }
    
    /**
     * Extract filename from DC_OPEN command.
     */
    private String extractFilename(byte[] data, int offset, int length) {
        for (int i = offset; i < offset + length - 3; i++) {
            if (data[i] == 0x46 && data[i+1] == 0x54 && data[i+2] == 0x3A) {
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < offset + length; j++) {
                    byte b = data[j];
                    if (b == 0x00 || b == (byte)0xFF) break;
                    char c = (char)(b & 0xFF);
                    sb.append(c);
                }
                return sb.toString().trim();
            }
        }
        return null;
    }
    
    /**
     * Handle DC_CLOSE - Close file transfer session.
     */
    private void handleDCClose(byte[] data, int offset, int length) {
        System.out.println("=== DC_CLOSE received ===");
        
        try {
            manager.closeStreams();
            
            if (manager.hadSuccessfulTransfer() && 
                manager.getCurrentFile() != null) {
                if (callback != null) {
                    callback.onTransferComplete(
                        true,
                        "Transfer complete: " + manager.getCurrentFile().getName()
                    );
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendDCCloseResponse(false, 0x7100);
            if (callback != null) {
                callback.onTransferError("File close error: " + e.getMessage());
            }
            return;
        }
        
        manager.setState(FileTransferManager.TransferState.IDLE);
        manager.setMessage(false);
        sendDCCloseResponse(true, 0);
    }
    
    /**
     * Handle DC_SET_CURSOR - Cursor positioning (no response needed).
     */
    private void handleDCSetCursor(byte[] data, int offset, int length) {
        System.out.println("=== DC_SET_CURSOR received ===");
        // No response needed - this precedes DC_GET
    }
    
    /**
     * Handle DC_GET - Host requests data from PC (upload).
     */
    private void handleDCGet(byte[] data, int offset, int length) {
        System.out.println("=== DC_GET received ===");
        
        try {
            byte[] fileData = manager.readUploadData(2000);
            
            if (fileData == null) {
                // End of file
                System.out.println("End of file reached");
                manager.closeStreams();
                sendDCGetResponse(false, 0x2200, null, 0);
                return;
            }
            
            // Send data block
            manager.incrementBlockSequence();
            sendDCGetResponse(true, 0, fileData, fileData.length);
            
            if (callback != null) {
                callback.onTransferProgress(
                    manager.getBlockSequence(),
                    manager.getBytesTransferred()
                );
            }
            
        } catch (IOException e) {
            e.printStackTrace();
            sendDCGetResponse(false, 0x2000, null, 0);
            if (callback != null) {
                callback.onTransferError("Read error: " + e.getMessage());
            }
            manager.closeStreams();
        }
    }
    
    /**
     * Handle DC_INSERT - Host sends data to PC (download).
     */
    private void handleDCInsert(byte[] data, int offset, int length) {
        System.out.println("=== DC_INSERT received ===");
        System.out.println("Offset: " + offset + ", Length: " + length);
        
        // Check if this is the header Insert (length = 10)
        if (length == 10) {
            System.out.println("DC_INSERT header (length=10) - skipping");
            return;
        }
        
        // Handle FT:MSG - display completion message
        if (manager.isMessage()) {
            System.out.println("Processing FT:MSG completion message");
            
            // Only process the first FT:MSG block
            if (manager.getBlockSequence() > 0) {
                System.out.println("Ignoring subsequent FT:MSG block");
                manager.incrementBlockSequence();
                sendDCInsertResponse(true, 0);
                return;
            }
            
            // Extract and display message
            String message = extractMessageData(data, offset, length);
            if (message != null && !message.isEmpty()) {
                System.out.println("Transfer completion message: " + message);
                
                manager.incrementBlockSequence();
                sendDCInsertResponse(true, 0);
                
                manager.closeStreams();
                
                boolean isSuccess = message.contains("complete") || message.contains("TRANS03");
                boolean isError = message.contains("Error") || message.contains("TRANS13") || 
                                message.contains("TRANS14");
                
                if (callback != null) {
                    if (isSuccess && !isError) {
                        //callback.onTransferComplete(message);
                        callback.onTransferComplete(true, message);  // or false for errors
                    } else if (isError) {
                        callback.onTransferError(message);
                    } else {
                        callback.onStatusMessage(message);
                    }
                }
                
                // Reset state
                manager.setState(FileTransferManager.TransferState.IDLE);
                manager.setMessage(false);
            }
            return;
        }
        
        // Handle file data transfers
        try {
            byte[] fileData = extractFileData(data, offset, length);
            
            if (fileData != null && fileData.length > 0) {
                manager.writeDownloadData(fileData);
                manager.incrementBlockSequence();
                sendDCInsertResponse(true, 0);
                
                if (callback != null) {
                    callback.onTransferProgress(
                        manager.getBlockSequence(),
                        manager.getBytesTransferred()
                    );
                }
            } else {
                System.out.println("Invalid data length or insufficient buffer");
                sendDCInsertResponse(false, 0x6E00);
            }
            
        } catch (IOException e) {
            e.printStackTrace();
            sendDCInsertResponse(false, 0x4700);
            if (callback != null) {
                callback.onTransferError("Write error: " + e.getMessage());
            }
        }
    }
    
    /**
     * Extract message text from FT:MSG Insert.
     */
    private String extractMessageData(byte[] data, int offset, int length) {
        int markerOffset = offset + 7;
        
        if (markerOffset + 2 >= data.length || data[markerOffset] != 0x61) {
            return null;
        }
        
        int dataLen = ((data[markerOffset + 1] & 0xFF) << 8) | 
                     (data[markerOffset + 2] & 0xFF);
        dataLen -= 5;
        
        if (dataLen > 0 && markerOffset + 3 + dataLen <= data.length) {
            StringBuilder msgText = new StringBuilder();
            for (int j = 0; j < dataLen; j++) {
                char c = (char)(data[markerOffset + 3 + j] & 0xFF);
                if (c >= 32 && c < 127) {
                    msgText.append(c);
                }
            }
            return msgText.toString().trim();
        }
        
        return null;
    }
    
    /**
     * Extract file data from DC_INSERT.
     */
    private byte[] extractFileData(byte[] data, int offset, int length) {
        int markerOffset = offset + 7;
        
        if (markerOffset + 2 >= data.length || data[markerOffset] != 0x61) {
            return null;
        }
        
        int dataLen = ((data[markerOffset + 1] & 0xFF) << 8) | 
                     (data[markerOffset + 2] & 0xFF);
        dataLen -= 5;
        
        if (dataLen > 0 && markerOffset + 3 + dataLen <= data.length) {
            byte[] fileData = new byte[dataLen];
            System.arraycopy(data, markerOffset + 3, fileData, 0, dataLen);
            return fileData;
        }
        
        return null;
    }
    
    // Response sending methods
    
    private void sendDCOpenResponse(boolean success, int errorCode) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            
            if (success) {
                baos.write(0x00); baos.write(0x05);
                baos.write(Constants.SFID_DATA_CHAIN);
                baos.write(Constants.DC_OPEN);
                baos.write(Constants.RESP_POSITIVE);
            } else {
                baos.write(0x00); baos.write(0x09);
                baos.write(Constants.SFID_DATA_CHAIN);
                baos.write(Constants.DC_OPEN);
                baos.write(Constants.RESP_NEGATIVE);
                baos.write(0x69); baos.write(0x04);
                baos.write((byte)((errorCode >> 8) & 0xFF));
                baos.write((byte)(errorCode & 0xFF));
            }
            
            sendStructuredFieldResponse(baos.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private void sendDCCloseResponse(boolean success, int errorCode) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            
            if (success) {
                baos.write(0x00); baos.write(0x05);
                baos.write(Constants.SFID_DATA_CHAIN);
                baos.write(Constants.DC_CLOSE);
                baos.write(Constants.RESP_POSITIVE);
            } else {
                baos.write(0x00); baos.write(0x09);
                baos.write(Constants.SFID_DATA_CHAIN);
                baos.write(Constants.DC_CLOSE);
                baos.write(Constants.RESP_NEGATIVE);
                baos.write(0x69); baos.write(0x04);
                baos.write((byte)((errorCode >> 8) & 0xFF));
                baos.write((byte)(errorCode & 0xFF));
            }
            
            sendStructuredFieldResponse(baos.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private void sendDCGetResponse(boolean success, int errorCode, 
                                   byte[] data, int dataLen) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            
            if (success && data != null && dataLen > 0) {
                int dataLenField = dataLen + 5;
                int responseLen = 2 + 1 + 1 + 1 + 1 + 1 + 4 + 1 + 1 + 1 + 2 + dataLen;
                
                baos.write((byte)((responseLen >> 8) & 0xFF));
                baos.write((byte)(responseLen & 0xFF));
                baos.write(Constants.SFID_DATA_CHAIN);
                baos.write(Constants.DC_GET);
                baos.write(0x05); baos.write(0x63); baos.write(0x06);
                
                int blockSeq = manager.getBlockSequence();
                baos.write((byte)((blockSeq >> 24) & 0xFF));
                baos.write((byte)((blockSeq >> 16) & 0xFF));
                baos.write((byte)((blockSeq >> 8) & 0xFF));
                baos.write((byte)(blockSeq & 0xFF));
                
                baos.write((byte)0xC0); baos.write((byte)0x80); baos.write((byte)0x61);
                baos.write((byte)((dataLenField >> 8) & 0xFF));
                baos.write((byte)(dataLenField & 0xFF));
                baos.write(data, 0, dataLen);
            } else {
                baos.write(0x00); baos.write(0x09);
                baos.write(Constants.SFID_DATA_CHAIN);
                baos.write(Constants.DC_GET);
                baos.write(Constants.RESP_NEGATIVE);
                baos.write(0x69); baos.write(0x04);
                baos.write((byte)((errorCode >> 8) & 0xFF));
                baos.write((byte)(errorCode & 0xFF));
            }
            
            sendStructuredFieldResponse(baos.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private void sendDCInsertResponse(boolean success, int errorCode) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            
            if (success) {
                baos.write(0x00); baos.write(0x0B);
                baos.write(Constants.SFID_DATA_CHAIN);
                baos.write(Constants.DC_INSERT);
                baos.write(0x05); baos.write(0x63); baos.write(0x06);
                
                int blockSeq = manager.getBlockSequence();
                baos.write((byte)((blockSeq >> 24) & 0xFF));
                baos.write((byte)((blockSeq >> 16) & 0xFF));
                baos.write((byte)((blockSeq >> 8) & 0xFF));
                baos.write((byte)(blockSeq & 0xFF));
            } else {
                baos.write(0x00); baos.write(0x09);
                baos.write(Constants.SFID_DATA_CHAIN);
                baos.write(Constants.DC_INSERT);
                baos.write(Constants.RESP_NEGATIVE);
                baos.write(0x69); baos.write(0x04);
                baos.write((byte)((errorCode >> 8) & 0xFF));
                baos.write((byte)(errorCode & 0xFF));
            }
            
            sendStructuredFieldResponse(baos.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private void sendStructuredFieldResponse(byte[] sfData) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(Constants.AID_STRUCTURED_FIELD);
        baos.write(sfData);
        
        byte[] response = baos.toByteArray();
        
        System.out.println("=== SENDING STRUCTURED FIELD RESPONSE ===");
        for (int j = 0; j < response.length && j < 100; j++) {
            System.out.print(String.format("%02X ", response[j]));
            if ((j + 1) % 16 == 0) System.out.println();
        }
        System.out.println();
        
        sendData(response);
    }
    
    private void sendData(byte[] data) throws IOException {
        ByteArrayOutputStream fullPacket = new ByteArrayOutputStream();
        
        if (telnetProtocol.isTN3270EMode()) {
            fullPacket.write(Constants.TN3270E_DT_3270_DATA);
            fullPacket.write(0); fullPacket.write(0);
            fullPacket.write(0); fullPacket.write(0);
        }
        
        fullPacket.write(data);
        fullPacket.write(Constants.IAC);
        fullPacket.write((byte)0xEF);
        
        byte[] completePacket = fullPacket.toByteArray();
        output.write(completePacket);
        output.flush();
    }
}
