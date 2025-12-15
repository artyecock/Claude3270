package protocol;

import config.Constants;
import callbacks.TelnetCallback;

import java.io.*;

/**
 * Handles Telnet protocol negotiation and TN3270E support.
 */
public class TelnetProtocol {
    private final OutputStream output;
    private final String model;
    private TelnetCallback callback;
    
    // TN3270E state
    private boolean tn3270eMode = false;
    private boolean tn3270eNegotiationComplete = false;
    private boolean tn3270eAttempted = false;
    private boolean tn3270eFailed = false;
    
    public TelnetProtocol(OutputStream output, String model) {
        this.output = output;
        this.model = model;
    }
    
    public void setCallback(TelnetCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Handle incoming Telnet command.
     */
    public void handleTelnetCommand(byte command, byte option) throws IOException {
        System.out.println("Telnet: " + String.format("%02X %02X", command, option));
        
        if (command == Constants.DO) {
            if (option == Constants.OPT_TERMINAL_TYPE || 
                option == Constants.OPT_BINARY || 
                option == Constants.OPT_EOR) {
                sendTelnet(Constants.WILL, option);
            } else if (option == Constants.OPT_TN3270E) {
                if (!tn3270eAttempted && !tn3270eFailed) {
                    tn3270eAttempted = true;
                    sendTelnet(Constants.WILL, option);
                    System.out.println("Server requested TN3270E - attempting");
                } else {
                    sendTelnet(Constants.WONT, option);
                    System.out.println("Server requested TN3270E - already failed, declining");
                }
            } else {
                sendTelnet(Constants.WONT, option);
            }
        } else if (command == Constants.DONT) {
            if (option == Constants.OPT_TN3270E) {
                System.out.println("Server rejected TN3270E");
                tn3270eMode = false;
                tn3270eFailed = true;
                if (callback != null) {
                    callback.onTN3270EModeFailed();
                }
            }
            sendTelnet(Constants.WONT, option);
        } else if (command == Constants.WILL) {
            if (option == Constants.OPT_BINARY || option == Constants.OPT_EOR) {
                sendTelnet(Constants.DO, option);
            } else if (option == Constants.OPT_TN3270E) {
                if (!tn3270eAttempted && !tn3270eFailed) {
                    tn3270eAttempted = true;
                    sendTelnet(Constants.DO, option);
                    System.out.println("Server offers TN3270E - accepting");
                } else {
                    sendTelnet(Constants.DONT, option);
                    System.out.println("Server offers TN3270E - already failed, declining");
                }
            } else {
                sendTelnet(Constants.DONT, option);
            }
        } else if (command == Constants.WONT) {
            if (option == Constants.OPT_TN3270E) {
                System.out.println("Server won't do TN3270E");
                tn3270eMode = false;
                tn3270eFailed = true;
                if (callback != null) {
                    callback.onTN3270EModeFailed();
                }
            }
            sendTelnet(Constants.DONT, option);
        }
    }
    
    /**
     * Handle Telnet subnegotiation.
     */
    public void handleSubnegotiation(byte[] data) throws IOException {
        if (data.length < 2) return;
        
        byte option = data[0];
        if (option == Constants.OPT_TERMINAL_TYPE && data.length > 1 && data[1] == 1) {
            sendTerminalType();
        } else if (option == Constants.OPT_TN3270E) {
            handleTN3270ESubneg(data);
        }
    }
    
    /**
     * Handle TN3270E subnegotiation.
     */
    private void handleTN3270ESubneg(byte[] data) throws IOException {
        if (data.length < 2) return;
        
        byte function = data[1];
        System.out.println("TN3270E subnegotiation: function=0x" + 
                         String.format("%02X", function) + " (" +
                         getFunctionName(function) + ")");
        
        // Debug: print the subnegotiation data
        System.out.print("TN3270E SUBNEG DATA: ");
        for (int i = 0; i < data.length; i++) {
            System.out.print(String.format("%02X ", data[i]));
        }
        System.out.println();
        
        switch (function) {
            case 2: // SEND DEVICE-TYPE
                sendDeviceType();
                break;
                
            case 4: // DEVICE-TYPE IS (server confirming device type)
                System.out.println("Server confirmed device type");
                break;
                
            case 7: // FUNCTIONS REQUEST
            case 8: // FUNCTIONS IS/SEND
                handleFunctionsNegotiation(data);
                break;
                
            default:
                System.out.println("Unknown TN3270E function: 0x" + 
                                 String.format("%02X", function));
                break;
        }
    }
    
    /**
     * Handle functions negotiation.
     */
    private void handleFunctionsNegotiation(byte[] data) throws IOException {
        // Check if this is SEND (0x02) or IS
        if (data.length > 2 && data[2] == 0x02) {
            // Send device info
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(Constants.IAC);
            baos.write(Constants.SB);
            baos.write(Constants.OPT_TN3270E);
            baos.write(0x07); // INFO
            baos.write("IBM-".getBytes());
            baos.write(model.getBytes());
            baos.write("-E".getBytes());
            baos.write(Constants.IAC);
            baos.write(Constants.SE);
            output.write(baos.toByteArray());
            output.flush();
            System.out.println("Sent DEVICE-TYPE INFO with CONNECT-TYPE: IBM-" + 
                             model + "-E");
            
            // Preemptively fall back to TN3270
            try {
                Thread.sleep(100);
                System.out.println("Preemptively falling back to TN3270");
                sendTelnet(Constants.WONT, Constants.OPT_TN3270E);
                tn3270eMode = false;
                tn3270eFailed = true;
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            // Normal FUNCTIONS IS
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(Constants.IAC);
            baos.write(Constants.SB);
            baos.write(Constants.OPT_TN3270E);
            baos.write(8); // FUNCTIONS IS
            baos.write(Constants.IAC);
            baos.write(Constants.SE);
            output.write(baos.toByteArray());
            output.flush();
            System.out.println("Sent FUNCTIONS IS (empty)");
            
            tn3270eMode = true;
            tn3270eNegotiationComplete = true;
            System.out.println("*** TN3270E MODE ACTIVE ***");
            if (callback != null) {
                callback.onTN3270EModeEnabled();
            }
        }
    }
    
    /**
     * Send terminal type in response to query.
     */
    private void sendTerminalType() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(Constants.IAC);
        baos.write(Constants.SB);
        baos.write(Constants.OPT_TERMINAL_TYPE);
        baos.write(0); // IS
        baos.write("IBM-".getBytes());
        baos.write(model.getBytes());
        baos.write("-E".getBytes());
        baos.write(Constants.IAC);
        baos.write(Constants.SE);
        output.write(baos.toByteArray());
        output.flush();
    }
    
    /**
     * Send device type in response to TN3270E negotiation.
     */
    private void sendDeviceType() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(Constants.IAC);
        baos.write(Constants.SB);
        baos.write(Constants.OPT_TN3270E);
        baos.write(4); // DEVICE-TYPE IS
        baos.write("IBM-".getBytes());
        baos.write(model.getBytes());
        baos.write(Constants.IAC);
        baos.write(Constants.SE);
        output.write(baos.toByteArray());
        output.flush();
        System.out.println("Sent DEVICE-TYPE IS: IBM-" + model);
    }
    
    /**
     * Send a Telnet command.
     */
    private void sendTelnet(byte command, byte option) throws IOException {
        output.write(new byte[] { Constants.IAC, command, option });
        output.flush();
    }
    
    /**
     * Get human-readable name for TN3270E function code.
     */
    private String getFunctionName(byte function) {
        switch (function) {
            case 2: return "SEND DEVICE-TYPE";
            case 3: return "SEND FUNCTIONS";
            case 4: return "DEVICE-TYPE IS";
            case 7: return "FUNCTIONS IS";
            case 8: return "FUNCTIONS IS (alt)";
            default: return "UNKNOWN";
        }
    }
    
    // Getters
    public boolean isTN3270EMode() { return tn3270eMode; }
    public boolean isTN3270ENegotiationComplete() { return tn3270eNegotiationComplete; }
}
