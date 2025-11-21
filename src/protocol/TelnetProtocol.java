import java.io.*;

/**
 * 
 * Telnet protocol negotiation handler. Handles DO/DONT/WILL/WONT commands and
 * subnegotiation.
 */
public class TelnetProtocol {
	private OutputStream output;
	private boolean tn3270eMode = false;
	private boolean tn3270eNegotiationComplete = false;
	private boolean tn3270eOffered = false;
	private boolean tn3270eAttempted = false;
	private boolean tn3270eFailed = false;
	private String terminalModel;

	/**
	 * 
	 * Create a new Telnet protocol handler.
	 */
	public TelnetProtocol(OutputStream output, String terminalModel) {
		this.output = output;
		this.terminalModel = terminalModel;
	}

	/**
	 * 
	 * Handle a Telnet command (DO/DONT/WILL/WONT).
	 */
	public void handleTelnetCommand(byte command, byte option) throws IOException {
		System.out.println("Telnet: " + String.format("%02X %02X", command, option));

		if (command == DO) {
			if (option == OPT_TERMINAL_TYPE || option == OPT_BINARY || option == OPT_EOR) {
				sendTelnet(WILL, option);
			} else if (option == OPT_TN3270E) {
				if (!tn3270eAttempted && !tn3270eFailed) {
					tn3270eAttempted = true;
					sendTelnet(WILL, option);
					System.out.println("Server requested TN3270E - attempting");
				} else {
					sendTelnet(WONT, option);
					System.out.println("Server requested TN3270E - already failed, declining");
				}
			} else {
				sendTelnet(WONT, option);
			}
		} else if (command == DONT) {
			if (option == OPT_TN3270E) {
				System.out.println("Server rejected TN3270E");
				tn3270eMode = false;
				tn3270eFailed = true;
			}
			sendTelnet(WONT, option);
		} else if (command == WILL) {
			if (option == OPT_BINARY || option == OPT_EOR) {
				sendTelnet(DO, option);
			} else if (option == OPT_TN3270E) {
				if (!tn3270eAttempted && !tn3270eFailed) {
					tn3270eAttempted = true;
					sendTelnet(DO, option);
					System.out.println("Server offers TN3270E - accepting");
				} else {
					sendTelnet(DONT, option);
					System.out.println("Server offers TN3270E - already failed, declining");
				}
			} else {
				sendTelnet(DONT, option);
			}
		} else if (command == WONT) {
			if (option == OPT_TN3270E) {
				System.out.println("Server won't do TN3270E");
				tn3270eMode = false;
				tn3270eFailed = true;
			}
			sendTelnet(DONT, option);
		}

	}

	/**
	 * 
	 * Handle Telnet subnegotiation.
	 */
	public void handleSubnegotiation(byte[] data) throws IOException {
		if (data.length < 2)
			return;

		byte option = data[0];
		if (option == OPT_TERMINAL_TYPE && data.length > 1 && data[1] == 1) {
			sendTerminalType();
		} else if (option == OPT_TN3270E) {
			handleTN3270ESubneg(data);
		}
	}

	/**
	 * 
	 * Handle TN3270E subnegotiation.
	 */
	private void handleTN3270ESubneg(byte[] data) throws IOException {
		if (data.length < 2)
			return;

		byte function = data[1];
		System.out.println("TN3270E subnegotiation: function=0x" + String.format("%02X", function) + " ("
				+ getFunctionName(function) + ")");

		// Debug: print the subnegotiation data
		System.out.print("TN3270E SUBNEG DATA: ");
		for (int i = 0; i < data.length; i++) {
			System.out.print(String.format("%02X ", data[i]));
		}
		System.out.println();

		switch (function) {
		case 2: // SEND DEVICE-TYPE
			ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
			baos1.write(IAC);
			baos1.write(SB);
			baos1.write(OPT_TN3270E);
			baos1.write(4); // DEVICE-TYPE IS
			baos1.write("IBM-".getBytes());
			baos1.write(model.getBytes());
			baos1.write(IAC);
			baos1.write(SE);
			output.write(baos1.toByteArray());
			output.flush();
			System.out.println("Sent DEVICE-TYPE IS: IBM-" + model);
			break;

		case 4: // DEVICE-TYPE IS (server confirming device type)
			System.out.println("Server confirmed device type");
			break;

		case 7: // FUNCTIONS REQUEST
		case 8: // FUNCTIONS IS/SEND
			// Check if this is SEND (0x02) or IS
			if (data.length > 2 && data[2] == 0x02) {
				// This is actually a SEND DEVICE-TYPE disguised as function 0x08
				// Send device info
				ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
				baos2.write(IAC);
				baos2.write(SB);
				baos2.write(OPT_TN3270E);
				baos2.write(0x07); // INFO
				// baos2.write(0x00); // CONNECT-TYPE: ASSOCIATE (ADDED THIS!)
				baos2.write("IBM-".getBytes());
				baos2.write(model.getBytes());
				baos2.write("-E".getBytes());
				baos2.write(IAC);
				baos2.write(SE);
				output.write(baos2.toByteArray());
				output.flush();
				System.out.println("Sent DEVICE-TYPE INFO with CONNECT-TYPE: IBM-" + model + "-E");
				// Server will likely reject and fall back to TN3270
				// Don't mark TN3270E as active yet
				// After sending INFO, immediately reject TN3270E
				try {
					Thread.sleep(100); // Wait a bit
					System.out.println("Preemptively falling back to TN3270");
					sendTelnet(WONT, OPT_TN3270E);
					tn3270eMode = false;
					tn3270eFailed = true;
				} catch (Exception e) {
					e.printStackTrace();
				}
			} else {
				// Normal FUNCTIONS IS
				ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
				baos2.write(IAC);
				baos2.write(SB);
				baos2.write(OPT_TN3270E);
				baos2.write(8); // FUNCTIONS IS
				baos2.write(IAC);
				baos2.write(SE);
				output.write(baos2.toByteArray());
				output.flush();
				System.out.println("Sent FUNCTIONS IS (empty)");

				tn3270eMode = true;
				tn3270eNegotiationComplete = true;
				statusBar.setStatus("TN3270E negotiation complete");
				System.out.println("*** TN3270E MODE ACTIVE ***");
			}
			break;

		default:
			System.out.println("Unknown TN3270E function: 0x" + String.format("%02X", function));
			break;
		}
	}

	/**
	 * 
	 * Send a Telnet command.
	 */
	public void sendTelnet(byte command, byte option) throws IOException {
		output.write(new byte[] { Constants.IAC, command, option });
		output.flush();
	}

	/**
	 * 
	 * Send terminal type response.
	 */
	public void sendTerminalType() throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(Constants.IAC);
		baos.write(Constants.SB);
		baos.write(Constants.OPT_TERMINAL_TYPE);
		baos.write(0); // IS
		baos.write("IBM-".getBytes());
		baos.write(terminalModel.getBytes());
		baos.write("-E".getBytes());
		baos.write(Constants.IAC);
		baos.write(Constants.SE);
		output.write(baos.toByteArray());
		output.flush();
	}

	/**
	 * 
	 * Send TN3270E device type.
	 */
	private void sendTN3270EDeviceType() throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(IAC);
		baos.write(SB);
		baos.write(OPT_TN3270E);
		baos.write(4); // DEVICE-TYPE IS
		baos.write("IBM-".getBytes());
		baos.write(model.getBytes());
		baos.write("-E".getBytes());
		baos.write(IAC);
		baos.write(SE);
		output.write(baos.toByteArray());
		output.flush();
		System.out.println("Sent TN3270E DEVICE-TYPE IS: IBM-" + model + "-E");
	}

	/**
	 * 
	 * Send TN3270E functions response.
	 */
	private void sendTN3270EFunctions() throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(IAC);
		baos.write(SB);
		baos.write(OPT_TN3270E);
		baos.write(8); // FUNCTIONS IS
		baos.write(IAC);
		baos.write(SE);
		output.write(baos.toByteArray());
		output.flush();
		System.out.println("Sent TN3270E FUNCTIONS IS (empty)");
	}

	/**
	 * 
	 * Send TN3270E connect reply.
	 */
	private void sendTN3270EConnectReply() throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		// Start subnegotiation
		baos.write(IAC);
		baos.write(SB);
		baos.write(OPT_TN3270E);

		// Function = CONNECT REPLY
		baos.write(6); // 0x06

		// CONNECT REPLY data fields:
		// Format: [ConnectType (1)] [Response (1)] [LU name (variable, EBCDIC)]

		// ConnectType = 0x00 (default device)
		baos.write(0x00);

		// Response = 0x00 (success)
		baos.write(0x00);

		// LU Name — optional. Leave blank unless your host requires one.
		// If you need to send one, encode it in EBCDIC.
		// Example:
		// byte[] luName = "LUNAME".getBytes("Cp037");
		// baos.write(luName);

		// End subnegotiation
		baos.write(IAC);
		baos.write(SE);

		// Send it
		output.write(baos.toByteArray());
		output.flush();

		System.out.println("Sent TN3270E CONNECT REPLY (OK, default device)");
	}

	/**
	 * 
	 * Fall back to standard TN3270 mode.
	 */
	public void fallBackToTN3270() {
		if (tn3270eFailed)
			return;
		try {
			System.out.println("=== FALLING BACK TO TN3270 ===");
			tn3270eFailed = true;
			tn3270eMode = false;
			tn3270eNegotiationComplete = false;
			sendTelnet(Constants.WONT, Constants.OPT_TN3270E);
			System.out.println("Sent WONT TN3270E");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 
	 * Get function name for debug output.
	 */
	private String getFunctionName(byte function) {
		switch (function) {
		case 2:
			return "SEND DEVICE-TYPE";
		case 3:
			return "SEND FUNCTIONS";
		case 4:
			return "DEVICE-TYPE IS";
		case 7:
			return "FUNCTIONS IS";
		case 8:
			return "FUNCTIONS IS (alt)";
		default:
			return "UNKNOWN";
		}
	}

	// Getters
	public boolean isTN3270eMode() {
		return tn3270eMode;
	}

	public boolean isTN3270eNegotiationComplete() {
		return tn3270eNegotiationComplete;
	}

	public boolean isTN3270eFailed() {
		return tn3270eFailed;
	}
}
