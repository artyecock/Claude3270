package protocol;

import java.io.*;

/**
 * Manages file transfer state and file I/O operations.
 * Handles both upload (PC to host) and download (host to PC) operations.
 */
public class FileTransferManager {
    
    // Transfer state
    public enum TransferState {
        IDLE, 
        OPEN_SENT, 
        TRANSFER_IN_PROGRESS, 
        CLOSE_SENT, 
        ERROR
    }
    
    public enum TransferDirection {
        UPLOAD,   // PC to Host (IND$FILE PUT)
        DOWNLOAD  // Host to PC (IND$FILE GET)
    }
    
    public enum HostType {
        TSO,  // z/OS
        CMS   // z/VM
    }
    
    private TransferState state = TransferState.IDLE;
    private TransferDirection direction;
    private HostType hostType = HostType.CMS; // Default
    
    private File currentFile;
    private String currentFilename;
    private boolean isTextMode = true;
    private boolean isMessage = false;
    
    private FileInputStream uploadStream;
    private FileOutputStream downloadStream;
    
    private int blockSequence = 0;
    private long bytesTransferred = 0;
    private boolean hadSuccessfulTransfer = false;
    
    /**
     * Initialize a file transfer operation.
     */
    public void initializeTransfer(File file, TransferDirection direction, 
                                   boolean isTextMode, HostType hostType) {
        this.currentFile = file;
        this.direction = direction;
        this.isTextMode = isTextMode;
        this.hostType = hostType;
        this.blockSequence = 0;
        this.bytesTransferred = 0;
        this.hadSuccessfulTransfer = false;
        this.isMessage = false;
        this.state = TransferState.IDLE;
    }
    
    /**
     * Open file streams for transfer.
     */
    public void openStreams() throws IOException {
        if (direction == TransferDirection.UPLOAD) {
            if (!currentFile.exists()) {
                throw new FileNotFoundException("File not found: " + currentFile.getName());
            }
            uploadStream = new FileInputStream(currentFile);
        } else {
            downloadStream = new FileOutputStream(currentFile);
        }
        state = TransferState.TRANSFER_IN_PROGRESS;
    }
    
    /**
     * Close all open streams.
     */
    public void closeStreams() {
        try {
            if (uploadStream != null) {
                uploadStream.close();
                uploadStream = null;
            }
            if (downloadStream != null) {
                downloadStream.flush();
                downloadStream.close();
                downloadStream = null;
            }
        } catch (IOException e) {
            System.err.println("Error closing streams: " + e.getMessage());
        }
    }
    
    /**
     * Read data for upload (text mode).
     * Reads line by line, stripping original line endings and adding ASCII CRLF.
     */
    public byte[] readUploadData(int maxSize) throws IOException {
        if (uploadStream == null) {
            return null;
        }
        
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        
        if (isTextMode) {
            // Text mode - read line by line
            int ch;
            boolean foundLine = false;
            
            while (buffer.size() < maxSize - 2 && (ch = uploadStream.read()) != -1) {
                if (ch == '\n') {
                    foundLine = true;
                    break;
                } else if (ch == '\r') {
                    uploadStream.mark(1);
                    int next = uploadStream.read();
                    if (next != '\n' && next != -1) {
                        uploadStream.reset();
                    }
                    foundLine = true;
                    break;
                } else {
                    buffer.write(ch);
                }
            }
            
            if (buffer.size() == 0 && !foundLine) {
                return null; // EOF
            }
            
            // Append ASCII CRLF
            buffer.write(0x0D); // CR
            buffer.write(0x0A); // LF
            
        } else {
            // Binary mode - read raw bytes
            byte[] data = new byte[maxSize];
            int bytesRead = uploadStream.read(data);
            
            if (bytesRead <= 0) {
                return null; // EOF
            }
            
            buffer.write(data, 0, bytesRead);
        }
        
        byte[] result = buffer.toByteArray();
        bytesTransferred += result.length;
        return result;
    }
    
    /**
     * Write downloaded data to file.
     */
    public void writeDownloadData(byte[] data) throws IOException {
        if (downloadStream == null) {
            throw new IOException("Download stream not open");
        }
        
        if (isTextMode) {
            // Text mode - convert CRLF to platform line endings
            ByteArrayOutputStream processed = new ByteArrayOutputStream();
            
            for (int i = 0; i < data.length; i++) {
                byte b = data[i];
                
                // Check for CRLF sequence (0x0D 0x0A)
                if (b == 0x0D && i + 1 < data.length && data[i + 1] == 0x0A) {
                    processed.write('\n'); // Platform line ending
                    i++; // Skip LF
                } else if (b == 0x0D) {
                    processed.write('\n');
                } else if (b == 0x0A) {
                    processed.write('\n');
                } else if (b == 0x1A) {
                    // EOF marker - skip it
                    continue;
                } else {
                    processed.write(b);
                }
            }
            
            byte[] processedData = processed.toByteArray();
            downloadStream.write(processedData);
            bytesTransferred += processedData.length;
        } else {
            // Binary mode - write raw
            downloadStream.write(data);
            bytesTransferred += data.length;
        }
        
        hadSuccessfulTransfer = true;
    }
    
    /**
     * Build IND$FILE command string.
     */
    public String buildIndFileCommand(String hostDataset, boolean append,
                                     String recfm, String lrecl, 
                                     String blksize, String space) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("IND$FILE ");
        
        // Command verb
        if (direction == TransferDirection.DOWNLOAD) {
            cmd.append("GET ");
        } else {
            cmd.append("PUT ");
        }
        
        // Dataset/filename
        cmd.append(hostDataset);
        
        if (hostType == HostType.CMS) {
            // CMS format - parameters in parentheses
            StringBuilder params = new StringBuilder();
            
            if (isTextMode) {
                params.append(" ASCII");
            }
            if (isTextMode) {
                params.append(" CRLF");
            }
            if (append) {
                params.append(" APPEND");
            }
            
            // For PUT (upload), add RECFM and LRECL
            if (direction == TransferDirection.UPLOAD && !recfm.isEmpty()) {
                params.append(" RECFM ").append(recfm);
                if (!lrecl.isEmpty()) {
                    params.append(" LRECL ").append(lrecl);
                }
            }
            
            if (params.length() > 0) {
                cmd.append(" (").append(params.toString().trim()).append(")");
            }
            
        } else {
            // TSO format - parameters with parentheses around values
            if (isTextMode) {
                cmd.append(" ASCII");
            }
            if (isTextMode) {
                cmd.append(" CRLF");
            }
            if (append) {
                cmd.append(" APPEND");
            }
            
            // For PUT (upload), add RECFM, LRECL, BLKSIZE, SPACE
            if (direction == TransferDirection.UPLOAD) {
                if (!recfm.isEmpty()) {
                    cmd.append(" RECFM(").append(recfm).append(")");
                }
                if (!lrecl.isEmpty()) {
                    cmd.append(" LRECL(").append(lrecl).append(")");
                }
                if (!blksize.isEmpty()) {
                    cmd.append(" BLKSIZE(").append(blksize).append(")");
                }
                if (!space.isEmpty()) {
                    cmd.append(" SPACE(").append(space).append(")");
                }
            } else {
                // For GET (download)
                if (isTextMode && !recfm.isEmpty()) {
                    cmd.append(" RECFM(").append(recfm).append(")");
                }
                if (isTextMode && !lrecl.isEmpty()) {
                    cmd.append(" LRECL(").append(lrecl).append(")");
                }
                if (!blksize.isEmpty()) {
                    cmd.append(" BLKSIZE(").append(blksize).append(")");
                }
            }
        }
        
        return cmd.toString();
    }
    
    // Getters and setters
    public TransferState getState() { return state; }
    public void setState(TransferState state) { this.state = state; }
    public TransferDirection getDirection() { return direction; }
    public File getCurrentFile() { return currentFile; }
    public String getCurrentFilename() { return currentFilename; }
    public void setCurrentFilename(String filename) { this.currentFilename = filename; }
    public boolean isTextMode() { return isTextMode; }
    public boolean isMessage() { return isMessage; }
    public void setMessage(boolean isMessage) { this.isMessage = isMessage; }
    public int getBlockSequence() { return blockSequence; }
    public void incrementBlockSequence() { blockSequence++; }
    public void resetBlockSequence() { blockSequence = 0; }
    public long getBytesTransferred() { return bytesTransferred; }
    public boolean hadSuccessfulTransfer() { return hadSuccessfulTransfer; }
    public HostType getHostType() { return hostType; }
    public void setHostType(HostType type) { this.hostType = type; }
}
