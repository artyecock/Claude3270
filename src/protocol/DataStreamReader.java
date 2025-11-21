import java.io.*;

/**
 * 
 * Reads and parses incoming Telnet/3270 data stream. Handles IAC command
 * escaping and EOR detection.
 */
public class DataStreamReader {
	/**
	 * 
	 * Interface for data stream events.
	 */
	public interface DataStreamListener {
		/**
		 * 
		 * Telnet command received (DO/DONT/WILL/WONT).
		 */

		void onTelnetCommand(byte command, byte option) throws IOException;

		/**
		 * 
		 * Telnet subnegotiation received.
		 */
		void onSubnegotiation(byte[] data) throws IOException;

		/**
		 * 
		 * 3270 data stream received (after IAC EOR).
		 */
		void on3270Data(byte[] data);

		/**
		 * 
		 * Connection error occurred.
		 */
		void onConnectionError(IOException e);

		/**
		 * 
		 * Connection closed.
		 */
		void onConnectionClosed();
	}

	private InputStream input;
	private DataStreamListener listener;
	private volatile boolean connected = true;

	/**
	 * 
	 * Create a new data stream reader.
	 */
	public DataStreamReader(InputStream input, DataStreamListener listener) {
		this.input = input;
		this.listener = listener;
	}

	/**
	 * 
	 * Main read loop - processes incoming data. Runs in a separate thread.
	 */
	public void readLoop() {
		byte[] buf = new byte[8192];
		ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
		boolean inTelnetCommand = false;
		boolean inSubnegotiation = false;
		ByteArrayOutputStream subnegBuffer = new ByteArrayOutputStream();
		try {
			while (connected) {
				int n = input.read(buf);
				if (n <= 0) {
					break;
				}
				System.out.println("=== ALL RECEIVED BYTES ===");
				for (int j = 0; j < n; j++) {
					System.out.print(String.format("%02X ", buf[j]));
					if ((j + 1) % 16 == 0)
						System.out.println();
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
								listener.onSubnegotiation(cleanData);
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
							dataStream.write((byte) 0xFF);
						} else if (b == Constants.SB) {
							// Start subnegotiation
							inSubnegotiation = true;
							subnegBuffer.reset();
						} else if (b == Constants.DO || b == Constants.DONT || b == Constants.WILL
								|| b == Constants.WONT) {
							// Two-byte telnet command
							if (i + 1 < n) {
								listener.onTelnetCommand(b, buf[++i]);
							}
						} else if (b == (byte) 0xEF) {
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
			if (connected) {
				listener.onConnectionError(e);
			}
		} finally {
			connected = false;
			listener.onConnectionClosed();
		}
	}

	/**
	 * 
	 * Stop the read loop.
	 */
	public void stop() {
		connected = false;
	}

	/**
	 * 
	 * Check if still connected.
	 */
	public boolean isConnected() {
		return connected;
	}
}
