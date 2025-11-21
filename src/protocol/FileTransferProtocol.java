import java.io.*;

/**
 * 
 * IND$FILE file transfer protocol handler. Handles Data Chain operations for
 * file upload/download.
 */
public class FileTransferProtocol {
	/**
	 * 
	 * Callback interface for transfer progress and completion.
	 */
	public interface TransferCallback {
		void onProgressUpdate(String message, int bytesTransferred);

		void onTransferComplete(String message);

		void onTransferError(String errorMessage, int errorCode);

		void onHostMessage(String message);

		File getCurrentFile();

		boolean isCancelled();
	}

	private OutputStream output;
	private TelnetProtocol telnetProtocol;
	private FileTransferManager transferManager;
	private TransferCallback callback;

	/**
	 * 
	 * Create a new file transfer protocol handler.
	 */
	public FileTransferProtocol(OutputStream output, TelnetProtocol telnetProtocol,
			FileTransferManager transferManager) {
		this.output = output;
		this.telnetProtocol = telnetProtocol;
		this.transferManager = transferManager;
	}

	/**
	 * 
	 * Set the callback for transfer events.
	 */
	public void setCallback(TransferCallback callback) {
		this.callback = callback;
	}

	/**
	 * 
	 * Handle Data Chain structured field.
	 */
	public void handleDataChain(byte[] data, int offset, int length) {
		// This dispatches to specific DC operation handlers
		if (offset + 3 >= data.length)
			return;

		byte operation = data[offset + 3];
		// System.out.println("Data Chain operation: 0x" + String.format("%02X",
		// operation));
		System.out.println("Data Chain operation: 0x" + String.format("%02X", operation) + " at offset " + offset
				+ ", length " + length);

		// Debug: print the structured field
		System.out.print("SF data: ");
		for (int i = offset; i < Math.min(offset + length, data.length) && i < offset + 30; i++) {
			System.out.print(String.format("%02X ", data[i]));
		}
		System.out.println();

		switch (operation) {
		case DC_OPEN:
			handleDCOpen(data, offset, length);
			break;

		case DC_CLOSE:
			handleDCClose(data, offset, length);
			break;

		case DC_SET_CURSOR:
			handleDCSetCursor(data, offset, length);
			break;

		case DC_GET:
			handleDCGet(data, offset, length);
			break;

		case DC_INSERT:
			handleDCInsert(data, offset, length);
			break;

		default:
			System.out.println("Unknown Data Chain operation: 0x" + String.format("%02X", operation));
		}
	}

	/**
	 * 
	 * Handle DC_OPEN command.
	 */
	private void handleDCOpen(byte[] data, int offset, int length) {
		// CRITICAL: This is complex - parses filename, direction, opens files
// You'll need to adapt it to use transferManager methods
		System.out.println("=== DC_OPEN received ===");
		System.out.println("Offset: " + offset + ", Length: " + length);

		// Debug: dump the entire Open command
		System.out.print("Open data: ");
		for (int i = offset; i < offset + length && i < data.length; i++) {
			System.out.print(String.format("%02X ", data[i]));
		}
		System.out.println();

		// Parse filename from the Open command
		String filename = null;

		for (int i = offset; i < offset + length - 3; i++) {
			if (data[i] == 0x46 && data[i + 1] == 0x54 && data[i + 2] == 0x3A) {
				StringBuilder sb = new StringBuilder();
				for (int j = i; j < offset + length; j++) {
					byte b = data[j];
					if (b == 0x00 || b == (byte) 0xFF)
						break;
					char c = (char) (b & 0xFF);
					sb.append(c);
				}
				filename = sb.toString().trim();
				break;
			}
		}

		System.out.println("Open filename: " + filename);
		currentFilename = filename;

		// Check if this is data transfer or message
		boolean isData = filename != null && filename.contains("FT:DATA");
		boolean isMsg = filename != null && filename.contains("FT:MSG");

		System.out.println("isData: " + isData + ", isMsg: " + isMsg);

		if (isMsg) {
			// FT:MSG - reset state for message processing
			System.out.println("FT:MSG detected - preparing for completion message");

			ftState = FileTransferState.IDLE;
			ftIsMessage = true;
			blockSequence = 0;
			sendDCOpenResponse(true, 0);
			return;
		}

		if (!isData) {
			System.out.println("Unknown file type in Open");
			closeProgressDialog();
			showMessageDialog("Unknown file transfer type", "Transfer Error", true);
			sendDCOpenResponse(false, 0x5D00);
			return;
		}

		// *** Reset message flag when opening FT:DATA ***
		ftIsMessage = false;
		System.out.println("FT:DATA detected - reset ftIsMessage to false");

		// Determine direction from the Open header
		boolean hostWillGet = false;
		int directionByteOffset = offset + 14;

		if (directionByteOffset < data.length) {
			hostWillGet = (data[directionByteOffset] == 0x01);
			System.out.println("Direction byte at offset " + directionByteOffset + ": 0x"
					+ String.format("%02X", data[directionByteOffset]) + " -> hostWillGet=" + hostWillGet);
		}

		// Open the actual file for data transfer
		try {
			if (currentFile == null) {
				closeProgressDialog();
				showMessageDialog("No file specified", "Transfer Error", true);
				sendDCOpenResponse(false, 0x1B00);
				return;
			}

			if (hostWillGet) {
				// Host will GET data = Upload from PC to Host
				if (!currentFile.exists()) {
					closeProgressDialog();
					showMessageDialog("File not found: " + currentFile.getName(), "Transfer Error", true);
					sendDCOpenResponse(false, 0x1B00);
					return;
				}
				uploadStream = new FileInputStream(currentFile);
				long fileSize = currentFile.length();
				System.out.println("Opened file for READING (upload to host): " + currentFile.getAbsolutePath());
				updateProgressDialog("Sending data to host...", fileSize + " bytes total");
			} else {
				// Host will INSERT data = Download from Host to PC
				downloadStream = new FileOutputStream(currentFile);
				System.out.println("Opened file for WRITING (download from host): " + currentFile.getAbsolutePath());
				updateProgressDialog("Receiving data from host...", "Block 0");
			}

			ftState = FileTransferState.TRANSFER_IN_PROGRESS;
			blockSequence = 0;
			sendDCOpenResponse(true, 0);
			System.out.println("Sent positive DC_OPEN response");

		} catch (IOException e) {
			e.printStackTrace();
			closeProgressDialog();

			if (e instanceof FileNotFoundException) {
				showMessageDialog("File not found: " + currentFile.getName(), "Transfer Error", true);
				sendDCOpenResponse(false, 0x1B00);
			} else {
				showMessageDialog("File open error: " + e.getMessage(), "Transfer Error", true);
				sendDCOpenResponse(false, 0x2000);
			}
		}
	}

	/**
	 * 
	 * Handle DC_CLOSE command.
	 */
	private void handleDCClose(byte[] data, int offset, int length) {
// Adapt to use: transferManager.closeFile() and callback methods
		System.out.println("=== DC_CLOSE received ===");

		try {
			if (downloadStream != null) {
				downloadStream.flush();
				downloadStream.close();
				downloadStream = null;

				if (currentFile != null) {
					System.out.println("Download complete: " + currentFile.getAbsolutePath());
					System.out.println("File size: " + currentFile.length() + " bytes");

					// Show success message
					closeProgressDialog();
					if (ftHadSuccessfulTransfer && currentFile.length() > 0) {
						showMultilineMessageDialog("Download complete!\n\nFile: " + currentFile.getName() + "\nSize: "
								+ currentFile.length() + " bytes", "Transfer Complete");
						statusBar.setStatus("Download complete: " + currentFile.getName());
					} else {
						showMessageDialog("Transfer completed but no data received", "Transfer Warning");
					}
				}
			}

			if (uploadStream != null) {
				uploadStream.close();
				uploadStream = null;

				if (currentFile != null && ftHadSuccessfulTransfer) {
					closeProgressDialog();
					showMultilineMessageDialog("Upload complete!\n\nFile: " + currentFile.getName(),
							"Transfer Complete");
					statusBar.setStatus("Upload complete: " + currentFile.getName());
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
			closeProgressDialog();
			showMultilineMessageDialog("File close error: " + e.getMessage(), "Transfer Error");
			sendDCCloseResponse(false, 0x7100);
			statusBar.setStatus("File close error");
			return;
		}

		ftState = FileTransferState.IDLE;
		ftIsMessage = false;
		ftHadSuccessfulTransfer = false;
		currentFile = null;
		currentFilename = null;
		blockSequence = 0;
		sendDCCloseResponse(true, 0);
	}

	/**
	 * 
	 * Handle DC_SET_CURSOR command.
	 */
	private void handleDCSetCursor(byte[] data, int offset, int length) {
		System.out.println("=== DC_SET_CURSOR received ===");
		// No response needed - just a positioning command
	}

	/**
	 * 
	 * Handle DC_GET command (upload from PC to host).
	 */
	private void handleDCGet(byte[] data, int offset, int length) {
		// CRITICAL: Complex text/binary handling, CRLF processing
// Uses: transferManager.getUploadStream(), transferManager.isText()
		System.out.println("=== DC_GET received ===");

		if (uploadStream == null) {
			System.out.println("Upload stream is null - sending EOF");
			sendDCGetResponse(false, 0x2200, null, 0);
			return;
		}

		try {
			byte[] fileData = new byte[2000]; // Max ~2K per block
			int bytesRead = 0;

			if (ftIsText) {
				// Text mode - read line by line and send in ASCII with CRLF
				ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();

				// Read characters until we hit newline or fill buffer
				int ch;
				boolean foundLine = false;

				while (lineBuffer.size() < 1900 && (ch = uploadStream.read()) != -1) {
					if (ch == '\n') {
						// Found end of line - append CRLF and send
						foundLine = true;
						break;
					} else if (ch == '\r') {
						// Check for CRLF
						uploadStream.mark(1);
						int next = uploadStream.read();
						if (next == '\n') {
							// Found CRLF - consume both, end of line
							// foundLine = true;
							// break;
						} else // {
								// Bare CR followed by something else - reset to keep the next char
								// lineBuffer.write(ch);
						if (next != -1) {
							uploadStream.reset();
						}
						// }
						// Either way, CR marks end of line
						foundLine = true;
						break;
					} else {
						// Regular character
						lineBuffer.write(ch);
					}
				}

				if (lineBuffer.size() == 0 && !foundLine) {
					// End of file with no data
					System.out.println("End of file reached (no data read)");
					uploadStream.close();
					uploadStream = null;
					sendDCGetResponse(false, 0x2200, null, 0);
					return;
				}

				// Append ASCII CRLF to the line
				lineBuffer.write(0x0D); // CR (ASCII)
				lineBuffer.write(0x0A); // LF (ASCII)

				byte[] lineData = lineBuffer.toByteArray();
				System.arraycopy(lineData, 0, fileData, 0, lineData.length);
				bytesRead = lineData.length;

				System.out.println("Read line: " + lineBuffer.size() + " chars + CRLF = " + bytesRead + " bytes");

			} else {
				// Binary mode - read raw bytes
				bytesRead = uploadStream.read(fileData);

				if (bytesRead <= 0) {
					System.out.println("End of file reached (binary mode)");
					uploadStream.close();
					uploadStream = null;
					sendDCGetResponse(false, 0x2200, null, 0);
					return;
				}
			}

			if (bytesRead > 0) {
				// Send data block
				blockSequence++;
				sendDCGetResponse(true, 0, fileData, bytesRead);
				System.out.println("Sent block " + blockSequence + ": " + bytesRead + " bytes");
				updateProgressDialog("Sending block " + blockSequence + "...", bytesRead + " bytes");
			} else {
				// Shouldn't reach here, but handle EOF
				System.out.println("End of file reached (bytesRead=" + bytesRead + ")");
				uploadStream.close();
				uploadStream = null;
				sendDCGetResponse(false, 0x2200, null, 0);
			}

		} catch (IOException e) {
			e.printStackTrace();
			sendDCGetResponse(false, 0x2000, null, 0);
			closeProgressDialog();
			showMessageDialog("Read error: " + e.getMessage(), "Transfer Error", true);
			statusBar.setStatus("Read error");

			// Clean up
			try {
				if (uploadStream != null) {
					uploadStream.close();
					uploadStream = null;
				}
			} catch (IOException ex) {
				// Ignore
			}
		}
	}

	/**
	 * 
	 * Handle DC_INSERT command (download from host to PC).
	 */
	private void handleDCInsert(byte[] data, int offset, int length) {
		// CRITICAL: Very complex - handles both FT:MSG and FT:DATA
// Uses: transferManager.getDownloadStream(), transferManager.isMessage()
		System.out.println("=== DC_INSERT received ===");
		System.out.println("Offset: " + offset + ", Length: " + length);

		// Debug: show what we're looking at
		System.out.print("DC_INSERT bytes: ");
		for (int i = offset; i < Math.min(offset + 20, data.length); i++) {
			System.out.print(String.format("%02X ", data[i]));
		}
		System.out.println();

		// Check if this is the header Insert (length = 10)
		// Structure: [00 0A] [D0 47] [11 01 05 00 80 00]
		if (length == 10) {
			System.out.println("DC_INSERT header (length=10) - skipping");
			return; // Don't send response yet
		}

		// This must be the data Insert
		// Structure at offset:
		// [0-1] Length (ll ll)
		// [2] SFID (D0)
		// [3] Operation (47)
		// [4] 0x04
		// [5] 0xC0
		// [6] 0x80 (not compressed)
		// [7] 0x61 (data marker)
		// [8-9] Data length + 5 (dd dd)
		// [10+] Actual data

		// Handle FT:MSG - display completion message
		if (ftIsMessage) {
			System.out.println("Processing FT:MSG completion message");

			// Only process the first FT:MSG block
			if (blockSequence > 0) {
				System.out.println("Ignoring subsequent FT:MSG block " + (blockSequence + 1));
				blockSequence++;
				sendDCInsertResponse(true, 0);
				return;
			}

			// The 0x61 marker should be at offset + 7
			int markerOffset = offset + 7;

			if (markerOffset + 2 >= data.length) {
				System.out.println("No data marker found at offset " + markerOffset);
				sendDCInsertResponse(true, 0);
				return;
			}

			if (data[markerOffset] != 0x61) {
				System.out.println("Expected 0x61 at offset " + markerOffset + ", found 0x"
						+ String.format("%02X", data[markerOffset]));
				sendDCInsertResponse(true, 0);
				return;
			}

			int dataLen = ((data[markerOffset + 1] & 0xFF) << 8) | (data[markerOffset + 2] & 0xFF);
			dataLen -= 5;

			System.out.println("FT:MSG data length: " + dataLen);

			if (dataLen > 0 && markerOffset + 3 + dataLen <= data.length) {
				// FT:MSG is already in ASCII - just extract it directly
				StringBuilder msgText = new StringBuilder();
				for (int j = 0; j < dataLen; j++) {
					char c = (char) (data[markerOffset + 3 + j] & 0xFF);
					if (c >= 32 && c < 127) {
						msgText.append(c);
					}
				}

				String message = msgText.toString().trim();
				System.out.println("Transfer completion message: " + message);

				blockSequence++;
				sendDCInsertResponse(true, 0);

				// Close streams
				try {
					if (downloadStream != null) {
						downloadStream.flush();
						downloadStream.close();
						downloadStream = null;
						System.out.println("Closed download stream");
					}
					if (uploadStream != null) {
						uploadStream.close();
						uploadStream = null;
						System.out.println("Closed upload stream");
					}
				} catch (IOException e) {
					System.err.println("Error closing streams: " + e.getMessage());
				}

				closeProgressDialog();

				// Check if transfer was successful based on message content
				boolean isSuccess = message.contains("complete") || message.contains("TRANS03");
				boolean isError = message.contains("Error") || message.contains("TRANS13")
						|| message.contains("TRANS14");

				if (isSuccess && !isError) {
					// Success - show completion message
					String successMsg = "Transfer complete!\n\n" + "Host message: " + message;
					if (currentFile != null) {
						successMsg = "Transfer complete!\n\nFile: " + currentFile.getName() + "\n\nHost message: "
								+ message;
					}
					showMessageDialog(successMsg, "Transfer Complete");
					statusBar
							.setStatus("Transfer complete" + (currentFile != null ? ": " + currentFile.getName() : ""));
				} else if (isError) {
					// Failure - show error
					showMessageDialog("Transfer failed:\n\n" + message, "Transfer Error");
					statusBar.setStatus("Transfer failed");
				} else {
					// Unknown status - show message as-is
					showMessageDialog("Transfer status:\n\n" + message, "Transfer Status");
					statusBar.setStatus("Transfer completed");
				}

				// Reset state LAST
				ftState = FileTransferState.IDLE;
				ftIsMessage = false;
				ftHadSuccessfulTransfer = false;
				currentFile = null;
				currentFilename = null;
			}

			return;
		}

		// Handle file data transfers (not FT:MSG)
		if (downloadStream == null) {
			System.out.println("ERROR: Download stream is null but not in FT:MSG mode!");
			sendDCInsertResponse(false, 0x4700);
			return;
		}

		// Parse the Insert command to extract data
		// The 0x61 marker is at offset + 7
		try {
			int markerOffset = offset + 7;

			if (markerOffset + 2 >= data.length) {
				System.out.println("No data marker found at offset " + markerOffset);
				sendDCInsertResponse(false, 0x6E00);
				return;
			}

			if (data[markerOffset] != 0x61) {
				System.out.println("Expected 0x61 at offset " + markerOffset + ", found 0x"
						+ String.format("%02X", data[markerOffset]));
				sendDCInsertResponse(false, 0x6E00);
				return;
			}

			int dataLen = ((data[markerOffset + 1] & 0xFF) << 8) | (data[markerOffset + 2] & 0xFF);
			dataLen -= 5;

			System.out.println("Found data marker at offset " + markerOffset + ", data length: " + dataLen);

			if (dataLen > 0 && markerOffset + 3 + dataLen <= data.length) {
				byte[] fileData = new byte[dataLen];
				System.arraycopy(data, markerOffset + 3, fileData, 0, dataLen);

				// Process data based on transfer mode
				if (ftIsText) {
					// Text mode - strip ASCII CRLF markers and convert to platform line endings
					ByteArrayOutputStream cleanData = new ByteArrayOutputStream();

					for (int i = 0; i < dataLen; i++) {
						byte b = fileData[i];

						// Check for CRLF sequence (0x0D 0x0A in ASCII)
						if (b == 0x0D && i + 1 < dataLen && fileData[i + 1] == 0x0A) {
							// Found CRLF - write platform line ending
							cleanData.write('\n'); // Platform-native line ending
							i++; // Skip the LF
						}
						// Check for bare CR (shouldn't happen but handle it)
						else if (b == 0x0D) {
							cleanData.write('\n');
						}
						// Check for bare LF (shouldn't happen but handle it)
						else if (b == 0x0A) {
							cleanData.write('\n');
						}
						// Skip EOF marker if present (0x1A)
						else if (b == 0x1A) {
							System.out.println("Found EOF marker (0x1A) at position " + i);
							// Don't write EOF marker to file, just note it
							continue;
						}
						// Regular character - write as-is
						else {
							cleanData.write(b);
						}
					}

					byte[] processedData = cleanData.toByteArray();
					downloadStream.write(processedData);
					System.out.println("Wrote " + processedData.length + " bytes (after CRLF conversion from " + dataLen
							+ " bytes)");

				} else {
					// Binary mode - write raw bytes, no conversion
					downloadStream.write(fileData);
					System.out.println("Wrote " + dataLen + " bytes (binary mode)");
				}

				// Mark that we successfully received data
				ftHadSuccessfulTransfer = true;

				blockSequence++;
				sendDCInsertResponse(true, 0);
				System.out.println("Successfully processed block " + blockSequence + ": " + dataLen + " bytes");
				updateProgressDialog("Received block " + blockSequence + "...", dataLen + " bytes");
			} else {
				System.out.println("Invalid data length or insufficient buffer");
				sendDCInsertResponse(false, 0x6E00);
			}

		} catch (IOException e) {
			e.printStackTrace();
			sendDCInsertResponse(false, 0x4700);
			closeProgressDialog();
			showMessageDialog("Write error: " + e.getMessage(), "Transfer Error", true);
			statusBar.setStatus("Write error");
		}
	}

	/**
	 * 
	 * Send DC_OPEN response.
	 */
	private void sendDCOpenResponse(boolean success, int errorCode) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			if (success) {
				// Positive response
				baos.write(0x00); // Length MSB
				baos.write(0x05); // Length LSB
				baos.write(Constants.SFID_DATA_CHAIN);
				baos.write(Constants.DC_OPEN);
				baos.write(Constants.RESP_POSITIVE);
			} else {
				// Negative response
				baos.write(0x00); // Length MSB
				baos.write(0x09); // Length LSB
				baos.write(Constants.SFID_DATA_CHAIN);
				baos.write(Constants.DC_OPEN);
				baos.write(Constants.RESP_NEGATIVE);
				baos.write(0x69);
				baos.write(0x04);
				baos.write((byte) ((errorCode >> 8) & 0xFF));
				baos.write((byte) (errorCode & 0xFF));
			}

			byte[] response = baos.toByteArray();
			sendStructuredFieldResponse(response);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 *
	 * Send DC_CLOSE response.
	 */
	private void sendDCCloseResponse(boolean success, int errorCode) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream()
			if (success) {
				baos.write(0x00);
				baos.write(0x05);
				baos.write(Constants.SFID_DATA_CHAIN);
				baos.write(Constants.DC_CLOSE);
				baos.write(Constants.RESP_POSITIVE);
			} else {
				baos.write(0x00);
				baos.write(0x09);
				baos.write(Constants.SFID_DATA_CHAIN);
				baos.write(Constants.DC_CLOSE);
				baos.write(Constants.RESP_NEGATIVE);
				baos.write(0x69);
				baos.write(0x04);
				baos.write((byte) ((errorCode >> 8) & 0xFF));
				baos.write((byte) (errorCode & 0xFF));
			}

			sendStructuredFieldResponse(baos.toByteArray());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 
	 * Send DC_GET response.
	 */
	private void sendDCGetResponse(boolean success, int errorCode, byte[] data, int dataLen) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			if (success && data != null && dataLen > 0) {
				// Calculate lengths
				int dataLenField = dataLen + 5;
				int responseLen = 2 + 1 + 1 + 1 + 1 + 1 + 4 + 1 + 1 + 1 + 2 + dataLen;

				baos.write((byte) ((responseLen >> 8) & 0xFF));
				baos.write((byte) (responseLen & 0xFF));
				baos.write(Constants.SFID_DATA_CHAIN);
				baos.write(Constants.DC_GET);
				baos.write(0x05);
				baos.write(0x63);
				baos.write(0x06);

				int blockSeq = transferManager.getBlockSequence();
				baos.write((byte) ((blockSeq >> 24) & 0xFF));
				baos.write((byte) ((blockSeq >> 16) & 0xFF));
				baos.write((byte) ((blockSeq >> 8) & 0xFF));
				baos.write((byte) (blockSeq & 0xFF));

				baos.write((byte) 0xC0);
				baos.write((byte) 0x80);
				baos.write((byte) 0x61);
				baos.write((byte) ((dataLenField >> 8) & 0xFF));
				baos.write((byte) (dataLenField & 0xFF));
				baos.write(data, 0, dataLen);
			} else {
				baos.write(0x00);
				baos.write(0x09);
				baos.write(Constants.SFID_DATA_CHAIN);
				baos.write(Constants.DC_GET);
				baos.write(Constants.RESP_NEGATIVE);
				baos.write(0x69);
				baos.write(0x04);
				baos.write((byte) ((errorCode >> 8) & 0xFF));
				baos.write((byte) (errorCode & 0xFF));
			}

			sendStructuredFieldResponse(baos.toByteArray());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 
	 * Send DC_INSERT response.
	 */
	private void sendDCInsertResponse(boolean success, int errorCode) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			if (success) {
				baos.write(0x00);
				baos.write(0x0B);
				baos.write(Constants.SFID_DATA_CHAIN);
				baos.write(Constants.DC_INSERT);
				baos.write(0x05);
				baos.write(0x63);
				baos.write(0x06);

				int blockSeq = transferManager.getBlockSequence();
				baos.write((byte) ((blockSeq >> 24) & 0xFF));
				baos.write((byte) ((blockSeq >> 16) & 0xFF));
				baos.write((byte) ((blockSeq >> 8) & 0xFF));
				baos.write((byte) (blockSeq & 0xFF));
			} else {
				baos.write(0x00);
				baos.write(0x09);
				baos.write(Constants.SFID_DATA_CHAIN);
				baos.write(Constants.DC_INSERT);
				baos.write(Constants.RESP_NEGATIVE);
				baos.write(0x69);
				baos.write(0x04);
				baos.write((byte) ((errorCode >> 8) & 0xFF));
				baos.write((byte) (errorCode & 0xFF));
			}

			sendStructuredFieldResponse(baos.toByteArray());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 
	 * Send structured field response with AID 0x88.
	 */
	private void sendStructuredFieldResponse(byte[] sfData) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(Constants.AID_STRUCTURED_FIELD);
		baos.write(sfData);
		byte[] response = baos.toByteArray();
		System.out.println("=== SENDING STRUCTURED FIELD RESPONSE ===");
		for (int j = 0; j < response.length && j < 100; j++) {
			System.out.print(String.format("%02X ", response[j]));
			if ((j + 1) % 16 == 0)
				System.out.println();
		}
		System.out.println();
		// Send with proper TN3270E framing if needed
		if (telnetProtocol.isTN3270eMode()) {
			ByteArrayOutputStream fullPacket = new ByteArrayOutputStream();
			fullPacket.write(0x00); // TN3270E_DT_3270_DATA
			fullPacket.write(0);
			fullPacket.write(0);
			fullPacket.write(0);
			fullPacket.write(0);
			fullPacket.write(response);
			fullPacket.write(Constants.IAC);
			fullPacket.write((byte) 0xEF);
			output.write(fullPacket.toByteArray());
		} else {
			output.write(response);
			output.write(Constants.IAC);
			output.write((byte) 0xEF);
		}
		output.flush();
	}
}
