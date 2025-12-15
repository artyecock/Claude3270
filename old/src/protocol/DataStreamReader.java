package protocol;

import config.Constants;
import callbacks.DataStreamListener;

import java.io.*;

/**
 * Reads and processes incoming data stream from the host.
 * Handles Telnet command parsing and 3270 data extraction.
 */
public class DataStreamReader implements Runnable {
    private final InputStream input;
    private final TelnetProtocol telnetProtocol;
    private final DataStreamListener listener;
    private volatile boolean running = false;
    
    public DataStreamReader(InputStream input, 
                          TelnetProtocol telnetProtocol, 
                          DataStreamListener listener) {
        this.input = input;
        this.telnetProtocol = telnetProtocol;
        this.listener = listener;
    }
    
    /**
     * Start reading from the input stream.
     */
    public void start() {
        running = true;
        Thread thread = new Thread(this, "DataStreamReader");
        thread.setDaemon(true);
        thread.start();
    }
    
    /**
     * Stop reading.
     */
    public void stop() {
        running = false;
    }
    
    @Override
    public void run() {
        byte[] buf = new byte[8192];
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        boolean inTelnetCommand = false;
        boolean inSubnegotiation = false;
        ByteArrayOutputStream subnegBuffer = new ByteArrayOutputStream();
        
        try {
            while (running) {
                int n = input.read(buf);
                if (n <= 0) break;
                
                System.out.println("=== ALL RECEIVED BYTES ===");
                for (int j = 0; j < n; j++) {
                    System.out.print(String.format("%02X ", buf[j]));
                    if ((j + 1) % 16 == 0) System.out.println();
                }
                System.out.println();
                
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    
                    // Handle subnegotiation mode
                    if (inSubnegotiation) {
                        subnegBuffer.write(b);
                        if (b == Constants.SE && subnegBuffer.size() > 1) {
                            byte[] subnegData = subnegBuffer.toByteArray();
                            if (subnegData[subnegData.length - 2] == Constants.IAC) {
                                byte[] cleanData = new byte[subnegData.length - 2];
                                System.arraycopy(subnegData, 0, cleanData, 0, cleanData.length);
                                telnetProtocol.handleSubnegotiation(cleanData);
                                subnegBuffer.reset();
                                inSubnegotiation = false;
                            }
                        }
                        continue;
                    }
                    
                    // Detect start of telnet command
                    if (b == Constants.IAC && !inTelnetCommand) {
                        inTelnetCommand = true;
                        continue;
                    }
                    
                    // Process telnet command
                    if (inTelnetCommand) {
                        if (b == Constants.IAC) {
                            // IAC IAC = escaped literal 0xFF byte in data
                            dataStream.write((byte)0xFF);
                        } else if (b == Constants.SB) {
                            // Start subnegotiation
                            inSubnegotiation = true;
                            subnegBuffer.reset();
                        } else if (b == Constants.DO || b == Constants.DONT || 
                                 b == Constants.WILL || b == Constants.WONT) {
                            // Two-byte telnet command
                            if (i + 1 < n) {
                                telnetProtocol.handleTelnetCommand(b, buf[++i]);
                            }
                        } else if (b == (byte)0xEF) {
                            // EOR - End of Record, process accumulated 3270 data
                            if (dataStream.size() > 0) {
                                listener.on3270Data(dataStream.toByteArray());
                                dataStream.reset();
                            }
                        }
                        inTelnetCommand = false;
                        continue;
                    }
                    
                    // Regular data byte - add to data stream
                    dataStream.write(b);
                }
            }
        } catch (IOException e) {
            if (running) {
                listener.onConnectionLost("Connection lost: " + e.getMessage());
            }
        }
    }
}
