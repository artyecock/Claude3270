package protocol;

import java.io.*;
import java.awt.Toolkit;

import config.Constants;
import terminal.ScreenBuffer;
import terminal.CursorManager;
import callbacks.ProtocolCallback;
import util.EbcdicConverter;
import util.AddressEncoder;

/**
 * 
 * Handles TN3270 protocol operations with full Reply Mode support.
 */
public class TN3270Protocol {
	private final OutputStream output;
	private final ScreenBuffer screenBuffer;
	private final CursorManager cursorManager;
	private final TelnetProtocol telnetProtocol;
	private ProtocolCallback callback;
	private boolean keyboardLocked = false;
	private byte lastAID = Constants.AID_ENTER;
	private char[] currentCharSet;
	private int rows;
	private int cols;
	private int primaryRows;
	private int primaryCols;
	private int alternateRows;
	private int alternateCols;
	private boolean useAlternateSize = false;

// ===== REPLY MODE SUPPORT =====
	private enum ReplyMode {
		FIELD, // Default: SBA + field data
		EXTENDED_FIELD, // SBA + extended attributes + field data
		CHARACTER // Character stream with SA orders
	}

	private ReplyMode currentReplyMode = ReplyMode.FIELD;
	private int replyModeFlags = 0;

// ===== CONSTRUCTOR =====
	public TN3270Protocol(OutputStream output, ScreenBuffer screenBuffer, TelnetProtocol telnetProtocol,
			int primaryRows, int primaryCols, int alternateRows, int alternateCols) {
		this.output = output;
		this.screenBuffer = screenBuffer;
		this.cursorManager = new CursorManager(screenBuffer);
		this.telnetProtocol = telnetProtocol;
		this.primaryRows = primaryRows;
		this.primaryCols = primaryCols;
		this.alternateRows = alternateRows;
		this.alternateCols = alternateCols;

		// Start with alternate size
		this.rows = alternateRows;
		this.cols = alternateCols;
		this.useAlternateSize = true;

		this.currentCharSet = EbcdicConverter.getAsciiTable();
	}

	public void setCallback(ProtocolCallback callback) {
		this.callback = callback;
	}

	public boolean isKeyboardLocked() {
		return keyboardLocked;
	}

	public void resetKeyboardLock() {
		keyboardLocked = false;
		if (callback != null) {
			callback.onKeyboardLockChanged(false);
			callback.updateStatus("Keyboard unlocked");
		}
	}

	public void setKeyboardLocked(boolean locked) {
		this.keyboardLocked = locked;
		if (callback != null) {
			callback.setKeyboardLocked(locked);
		}
	}

	public int getCursorPos() {
		return cursorManager.getCursorPos();
	}

	public void setCursorPos(int pos) {
		cursorManager.setCursorPos(pos);
	}

	public int getRows() {
		return rows;
	}

	public int getCols() {
		return cols;
	}

// ===== 3270 DATA PROCESSING =====
	public void process3270Data(byte[] data) {
		if (data.length < 1)
			return;

		int offset = 0;

		// Handle TN3270E header if present
		if (telnetProtocol.isTN3270EMode() && data.length >= 5) {
			byte dataType = data[0];
			offset = 5;
			System.out.println("TN3270E Data type: " + String.format("%02X", dataType));

			if (dataType != Constants.TN3270E_DT_3270_DATA) {
				System.out.println("Non-3270-DATA type received, ignoring");
				return;
			}
		}

		if (offset >= data.length) {
			System.out.println("No 3270 command after TN3270E header");
			return;
		}

		byte command = data[offset++];
		System.out.println("3270 Command: 0x" + String.format("%02X", command));

		switch (command) {
		case Constants.CMD_ERASE_WRITE_05:
		case Constants.CMD_ERASE_WRITE_F5:
			if (useAlternateSize) {
				switchToPrimarySize();
			}
			currentCharSet = EbcdicConverter.getAsciiTable();
			screenBuffer.clearScreen();
			// Fall through

		case Constants.CMD_WRITE_01:
		case Constants.CMD_WRITE_F1:
			if (offset < data.length) {
				byte wcc = data[offset++];
				processWCC(wcc);
				processOrders(data, offset);
			}
			keyboardLocked = false;
			break;

		case Constants.CMD_ERASE_WRITE_ALTERNATE_0D:
		case Constants.CMD_ERASE_WRITE_ALTERNATE_7E:
			if (!useAlternateSize) {
				switchToAlternateSize();
			}
			currentCharSet = EbcdicConverter.getAsciiTable();
			screenBuffer.clearScreen();
			if (offset < data.length) {
				byte wcc = data[offset++];
				processWCC(wcc);
				processOrders(data, offset);
			}
			keyboardLocked = false;
			break;

		case Constants.CMD_READ_BUFFER_02:
		case Constants.CMD_READ_BUFFER_F2:
			sendReadBuffer();
			break;

		case Constants.CMD_READ_MODIFIED_06:
		case Constants.CMD_READ_MODIFIED_F6:
			sendAID(lastAID);
			break;

		case Constants.CMD_WSF_11:
		case Constants.CMD_WSF_F3:
			processWSF(data, offset);
			break;

		case Constants.CMD_ERASE_ALL_UNPROTECTED_0F:
		case Constants.CMD_ERASE_ALL_UNPROTECTED_6F:
			eraseAllUnprotected();
			break;

		default:
			System.out.println("Unknown 3270 command: 0x" + String.format("%02X", command));
		}

		if (callback != null) {
			callback.requestRepaint();
			callback.updateStatus("");
		}
		
		System.out.println("After processing: cursor at " + cursorManager.getCursorPos() + 
                " (row=" + (cursorManager.getCursorPos()/cols) + 
                " col=" + (cursorManager.getCursorPos()%cols) + ")");
	}

// ===== SCREEN SIZE SWITCHING =====
	private void switchToPrimarySize() {
		useAlternateSize = false;
		rows = primaryRows;
		cols = primaryCols;
		screenBuffer.resize(rows, cols);
		System.out.println("Switched to primary size: " + cols + "x" + rows);
		if (callback != null) {
			callback.onScreenSizeChanged(rows, cols);
		}
	}

	private void switchToAlternateSize() {
		useAlternateSize = true;
		rows = alternateRows;
		cols = alternateCols;
		screenBuffer.resize(rows, cols);
		System.out.println("Switched to alternate size: " + cols + "x" + rows);
		if (callback != null) {
			callback.onScreenSizeChanged(rows, cols);
		}
	}

// ===== WCC PROCESSING =====
	private void processWCC(byte wcc) {
		if ((wcc & Constants.WCC_RESET) != 0) {
			keyboardLocked = false;
			resetReplyModeToDefault();
			if (callback != null) {
				callback.onKeyboardLockChanged(false);
			}
		}
		if ((wcc & Constants.WCC_RESET_MDT) != 0) {
			screenBuffer.resetMDT();
		}
		if ((wcc & Constants.WCC_ALARM) != 0) {
			if (callback != null) {
				callback.playAlarm();
			} else {
				Toolkit.getDefaultToolkit().beep();
			}
		}
		if (callback != null) {
			callback.setKeyboardLocked(keyboardLocked);
		}
	}

// ===== ORDER PROCESSING =====
	private void processOrders(byte[] data, int offset) {
		int pos = 0;
		int i = offset;
		int currentFieldStart = -1;
		byte currentColor = 0;
		byte currentHighlight = 0;
		int bufferSize = rows * cols;

		while (i < data.length) {
			byte b = data[i++];

			switch (b) {
			case Constants.ORDER_SF:
				// Start Field
				if (i < data.length) {
					byte attr = data[i++];
					screenBuffer.setChar(pos, ' ');
					screenBuffer.setAttribute(pos, attr);
					screenBuffer.setExtendedColor(pos, (byte) 0);
					screenBuffer.setHighlighting(pos, (byte) 0);
					currentFieldStart = pos;
					currentColor = 0;
					currentHighlight = 0;
					pos = (pos + 1) % bufferSize;
				}
				break;

			case Constants.ORDER_SFE:
				// Start Field Extended
				if (i < data.length) {
					int count = data[i++] & 0xFF;
					byte attr = 0;
					byte color = 0;
					byte highlight = 0;

					for (int j = 0; j < count && i + 1 < data.length; j++) {
						byte type = data[i++];
						byte value = data[i++];

						if (type == Constants.ATTR_FIELD || type == (byte) 0xC0) {
							attr = value;
						} else if (type == Constants.ATTR_FOREGROUND || type == (byte) 0x42) {
							if (value >= (byte) 0xF1 && value <= (byte) 0xF7) {
								color = (byte) (value - 0xF0);
							} else if (value >= 0x01 && value <= 0x07) {
								color = value;
							}
							currentColor = color;
						} else if (type == Constants.ATTR_HIGHLIGHTING || type == (byte) 0x41) {
							currentHighlight = value;
							highlight = value;
						}
					}

					screenBuffer.setChar(pos, ' ');
					screenBuffer.setAttribute(pos, attr);
					screenBuffer.setExtendedColor(pos, color);
					screenBuffer.setHighlighting(pos, highlight);
					currentFieldStart = pos;
					pos = (pos + 1) % bufferSize;
				}
				break;

			case Constants.ORDER_SA:
				// Set Attribute
				if (i + 1 < data.length) {
					byte attrType = data[i++];
					byte attrValue = data[i++];

					if (attrType == Constants.ATTR_FOREGROUND || attrType == (byte) 0x42) {
						if (attrValue >= (byte) 0xF1 && attrValue <= (byte) 0xF7) {
							currentColor = (byte) (attrValue - 0xF0);
						} else if (attrValue >= 0x01 && attrValue <= 0x07) {
							currentColor = attrValue;
						}
					} else if (attrType == Constants.ATTR_HIGHLIGHTING || attrType == (byte) 0x41) {
						currentHighlight = attrValue;
					}
				}
				break;

			case Constants.ORDER_SBA:
				// Set Buffer Address
				if (i + 1 < data.length) {
					pos = AddressEncoder.decode3270Address(data[i], data[i + 1], bufferSize);
					i += 2;
				}
				break;

			case Constants.ORDER_RA:
				// Repeat to Address
				if (i + 2 < data.length) {
					int endPos = AddressEncoder.decode3270Address(data[i], data[i + 1], bufferSize);
					byte ch = data[i + 2];
					i += 3;

					char c;
					if (ch == Constants.ORDER_GE) {
						if (i < data.length) {
							ch = data[i++];
							c = EbcdicConverter.ebcdicToApl(ch);
						} else {
							break;
						}
					} else {
						c = EbcdicConverter.ebcdicToAscii(ch);
					}

					while (pos != endPos) {
						screenBuffer.setChar(pos, c);
						screenBuffer.setExtendedColor(pos, currentColor);
						screenBuffer.setHighlighting(pos, currentHighlight);
						pos = (pos + 1) % bufferSize;
					}
				}
				break;

			case Constants.ORDER_IC:
				// Insert Cursor
				System.out.println("ORDER_IC: Setting cursor to pos=" + pos);
				cursorManager.setCursorPos(pos);
				if (screenBuffer.isFieldStart(cursorManager.getCursorPos())) {
					cursorManager.moveCursor(1);
					System.out.println("ORDER_IC: Moved past field attr to " + cursorManager.getCursorPos());
				}
				break;

			case Constants.ORDER_EUA:
				// Erase Unprotected to Address
				if (i + 1 < data.length) {
					int endPos = AddressEncoder.decode3270Address(data[i], data[i + 1], bufferSize);
					i += 2;

					int startPos = pos;
					while (pos != endPos) {
						if (!screenBuffer.isProtected(pos) && !screenBuffer.isFieldStart(pos)) {
							screenBuffer.setChar(pos, '\0');
						}
						pos = (pos + 1) % bufferSize;
					}
					pos = startPos;
				}
				break;

			default:
				// Regular character or GE
				char c;
				if (b == Constants.ORDER_GE) {
					if (i < data.length) {
						b = data[i++];
						c = EbcdicConverter.ebcdicToApl(b);
					} else {
						break;
					}
				} else {
					c = EbcdicConverter.ebcdicToAscii(b);
				}

				screenBuffer.setChar(pos, c);
				screenBuffer.setExtendedColor(pos, currentColor);
				screenBuffer.setHighlighting(pos, currentHighlight);
				pos = (pos + 1) % bufferSize;
				break;
			}
		}
	}

// ===== WSF PROCESSING =====
	private void processWSF(byte[] data, int offset) {
		System.out.println("=== processWSF: offset=" + offset + ", length=" + data.length);
		int i = offset;

		while (i + 2 < data.length) {
			int length = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);

			if (length < 3 || i + length > data.length) {
				System.out.println("WSF: Invalid length " + length);
				break;
			}

			byte sfid = data[i + 2];
			System.out.println("WSF: SFID=0x" + String.format("%02X", sfid) + " length=" + length);

			if (sfid == 0x01) {
				// Read Partition - Query
				if (i + 3 < data.length) {
					byte pid = data[i + 3];
					byte qcode = i + 4 < data.length ? data[i + 4] : 0;
					System.out.println("WSF: Read Partition, PID=0x" + String.format("%02X", pid) + ", Qcode=0x"
							+ String.format("%02X", qcode));

					if (qcode == (byte) 0xFF || qcode == 0x02 || qcode == 0x03) {
						System.out.println("WSF: Sending Query Response");
						sendQueryResponse();
					}
				}
			} else if (sfid == Constants.SF_ID_SET_REPLY_MODE) {
				handleSetReplyMode(data, i, length);
			} else if (sfid == Constants.SFID_DATA_CHAIN) {
				System.out.println("WSF: Data Chain (handled by FileTransferProtocol)");
			} else if (sfid == 0x40) {
				System.out.println("WSF: Outbound 3270DS - contains embedded 3270 data");
			} else {
				System.out.println("WSF: Unhandled SFID 0x" + String.format("%02X", sfid));
			}

			i += length;
		}
	}

// ===== SET REPLY MODE HANDLING =====
	private void handleSetReplyMode(byte[] data, int offset, int length) {
		System.out.println("=== Set Reply Mode SF received ===");
		if (offset + 3 >= data.length) {
			System.err.println("SetReplyMode: truncated SF");
			return;
		}

		int p = offset + 3;
		int end = offset + length;
		int flags = 0;

		while (p + 1 < end) {
			int tag = data[p++] & 0xFF;
			int slen = data[p++] & 0xFF;

			if (p + slen > end) {
				System.err.println("SetReplyMode: truncated subfield");
				break;
			}

			for (int i = 0; i < Math.min(4, slen); i++) {
				flags = (flags << 8) | (data[p + i] & 0xFF);
			}
			p += slen;
		}

		this.replyModeFlags = flags;

		if ((flags & 0x02) != 0) {
			currentReplyMode = ReplyMode.CHARACTER;
		} else if ((flags & 0x01) != 0) {
			currentReplyMode = ReplyMode.EXTENDED_FIELD;
		} else {
			currentReplyMode = ReplyMode.FIELD;
		}

		System.out.printf("SetReplyMode: rawFlags=0x%X mode=%s%n", flags, currentReplyMode);

		if (callback != null) {
			callback.updateStatus("Reply mode: " + currentReplyMode);
		}
	}

	private void resetReplyModeToDefault() {
		currentReplyMode = ReplyMode.FIELD;
		replyModeFlags = 0;
		System.out.println("Reply Mode reset to FIELD (default)");
	}

// ===== ERASE ALL UNPROTECTED =====
	private void eraseAllUnprotected() {
		int bufferSize = rows * cols;
		for (int i = 0; i < bufferSize; i++) {
			if (screenBuffer.isFieldStart(i)) {
				screenBuffer.clearModified(i);
			} else if (!screenBuffer.isProtected(i)) {
				screenBuffer.setChar(i, '\0');
			}
		}
		keyboardLocked = false;
		if (callback != null) {
			callback.setKeyboardLocked(false);
			callback.requestRepaint();
		}
	}

// ===== SEND AID WITH REPLY MODE SUPPORT =====
	public void sendAID(byte aid) {
		lastAID = aid;
		System.out.println("Sending AID: 0x" + String.format("%02X", aid));
		keyboardLocked = true;
		if (callback != null) {
			callback.setKeyboardLocked(true);
		}
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();

			baos.write(aid);

			int cursorPos = cursorManager.getCursorPos();
			byte[] cursorAddr = AddressEncoder.encode3270Address(cursorPos);
			baos.write(cursorAddr[0]);
			baos.write(cursorAddr[1]);

			if (aid == Constants.AID_CLEAR) {
				resetReplyModeToDefault();
			}

			if (isReadModifiedAID(aid)) {
				buildReadModifiedResponse(baos);
			}

			sendData(baos.toByteArray());

		} catch (IOException e) {
			System.err.println("Error sending AID: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private boolean isReadModifiedAID(byte aid) {
		return aid == Constants.AID_ENTER || (aid >= Constants.AID_PF1 && aid <= Constants.AID_PF12)
				|| (aid >= Constants.AID_PF13 && aid <= Constants.AID_PF24) || aid == Constants.AID_PA1
				|| aid == Constants.AID_PA2 || aid == Constants.AID_PA3;
	}

// ===== BUILD READ MODIFIED RESPONSE =====
	private void buildReadModifiedResponse(ByteArrayOutputStream baos) throws IOException {
		int bufferSize = rows * cols;
		System.out.println("Building Read Modified response: mode=" + currentReplyMode);

		if (currentReplyMode == ReplyMode.FIELD || currentReplyMode == ReplyMode.EXTENDED_FIELD) {

			for (int i = 0; i < bufferSize; i++) {
				if (screenBuffer.isFieldStart(i) && screenBuffer.isModified(i)) {
					int fieldStart = i;
					int dataStart = fieldStart + 1;
					int fieldEnd = screenBuffer.findNextField(fieldStart);

					while (dataStart < fieldEnd && screenBuffer.getChar(dataStart) == '\0') {
						dataStart++;
					}

					int dataEnd = fieldEnd - 1;
					while (dataEnd > fieldStart
							&& (screenBuffer.getChar(dataEnd) == '\0' || screenBuffer.getChar(dataEnd) == ' ')) {
						dataEnd--;
					}

					if (dataStart <= dataEnd) {
						baos.write(Constants.ORDER_SBA);
						byte[] addr = AddressEncoder.encode3270Address(dataStart);
						baos.write(addr[0]);
						baos.write(addr[1]);

						for (int j = dataStart; j <= dataEnd; j++) {
							if (!screenBuffer.isFieldStart(j)) {
								char c = screenBuffer.getChar(j);
								if (c != '\0') {
									baos.write(EbcdicConverter.asciiToEbcdic(c));
								}
							}
						}
					}
				}
			}

		} else if (currentReplyMode == ReplyMode.CHARACTER) {

			int lastFieldAttr = -1;
			boolean inModifiedField = false;

			for (int p = 0; p < bufferSize; p++) {
				if (screenBuffer.isFieldStart(p)) {
					inModifiedField = screenBuffer.isModified(p);
					if (inModifiedField) {
						lastFieldAttr = screenBuffer.getAttribute(p);
					}
					continue;
				}

				if (!inModifiedField)
					continue;

				int currentFieldStart = screenBuffer.findFieldStart(p);
				int currentFieldAttr = screenBuffer.getAttribute(currentFieldStart);

				if (currentFieldAttr != lastFieldAttr) {
					baos.write(Constants.ORDER_SA);
					baos.write((byte) Constants.ATTR_FIELD);
					baos.write((byte) currentFieldAttr);
					lastFieldAttr = currentFieldAttr;
				}

				char c = screenBuffer.getChar(p);
				if (c != '\0') {
					baos.write(EbcdicConverter.asciiToEbcdic(c));
				}
			}

			System.out.println("Sent character mode response");
		}
	}

// ===== SEND READ BUFFER WITH REPLY MODE SUPPORT =====
	private void sendReadBuffer() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write(lastAID);

			int cursorPos = cursorManager.getCursorPos();
			byte[] cursorAddr = AddressEncoder.encode3270Address(cursorPos);
			baos.write(cursorAddr[0]);
			baos.write(cursorAddr[1]);

			buildReadBufferResponse(baos);

			sendData(baos.toByteArray());

			keyboardLocked = true;
			if (callback != null) {
				callback.onKeyboardLockChanged(true);
			}

		} catch (IOException e) {
			System.err.println("Error sending Read Buffer: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private void buildReadBufferResponse(ByteArrayOutputStream baos) throws IOException {
		int bufferSize = rows * cols;
		System.out.println("Building Read Buffer response: mode=" + currentReplyMode);

		if (currentReplyMode == ReplyMode.FIELD || currentReplyMode == ReplyMode.EXTENDED_FIELD) {

			for (int i = 0; i < bufferSize; i++) {
				if (screenBuffer.isFieldStart(i)) {
					baos.write(Constants.ORDER_SF);
					baos.write(screenBuffer.getAttribute(i));
				} else {
					char c = screenBuffer.getChar(i);
					if (c == '\0') {
						baos.write(0x00);
					} else {
						baos.write(EbcdicConverter.asciiToEbcdic(c));
					}
				}
			}

		} else if (currentReplyMode == ReplyMode.CHARACTER) {

			int lastFieldAttr = -1;

			for (int p = 0; p < bufferSize; p++) {
				if (screenBuffer.isFieldStart(p)) {
					int attr = screenBuffer.getAttribute(p);

					baos.write(Constants.ORDER_SA);
					baos.write((byte) Constants.ATTR_FIELD);
					baos.write((byte) attr);

					lastFieldAttr = attr;
					continue;
				}

				int currentFieldStart = screenBuffer.findFieldStart(p);
				int currentFieldAttr = screenBuffer.getAttribute(currentFieldStart);

				if (currentFieldAttr != lastFieldAttr) {
					baos.write(Constants.ORDER_SA);
					baos.write((byte) Constants.ATTR_FIELD);
					baos.write((byte) currentFieldAttr);
					lastFieldAttr = currentFieldAttr;
				}

				char c = screenBuffer.getChar(p);
				if (c == '\0') {
					baos.write(0x00);
				} else {
					baos.write(EbcdicConverter.asciiToEbcdic(c));
				}
			}
		}
	}

// ===== SEND DATA TO HOST =====
	public void sendData(byte[] data) throws IOException {
		ByteArrayOutputStream fullPacket = new ByteArrayOutputStream();
		if (telnetProtocol.isTN3270EMode()) {
			fullPacket.write(Constants.TN3270E_DT_3270_DATA);
			fullPacket.write(0);
			fullPacket.write(0);
			fullPacket.write(0);
			fullPacket.write(0);
		}

		fullPacket.write(data);
		fullPacket.write(Constants.IAC);
		fullPacket.write((byte) 0xEF);

		byte[] completePacket = fullPacket.toByteArray();

		System.out.println("=== ALL BYTES SENT ===");
		int n = completePacket.length;
		for (int j = 0; j < n && j < 100; j++) {
			System.out.print(String.format("%02X ", completePacket[j]));
			if ((j + 1) % 16 == 0)
				System.out.println();
		}
		System.out.println();

		output.write(completePacket);
		output.flush();
	}

// ===== QUERY RESPONSE (YOUR EXISTING FULL IMPLEMENTATION) =====
	private void sendQueryResponse() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			int reportRows = alternateRows;
			int reportCols = alternateCols;

			// ===== QUERY REPLY (SUMMARY) =====
			baos.write(0x88);
			baos.write(0x00);
			baos.write(0x18);
			baos.write((byte) 0x81);
			baos.write((byte) 0x80);
			baos.write((byte) 0x81);
			baos.write((byte) 0x84);
			baos.write((byte) 0x85);
			baos.write((byte) 0x86);
			baos.write((byte) 0x87);
			baos.write((byte) 0x88);
			baos.write((byte) 0x8C);
			baos.write((byte) 0x8F);
			baos.write((byte) 0x95);
			baos.write((byte) 0x99);
			baos.write((byte) 0x9D);
			baos.write((byte) 0xA6);
			baos.write((byte) 0xA8);
			baos.write((byte) 0xAB);
			baos.write((byte) 0xB0);
			baos.write((byte) 0xB1);
			baos.write((byte) 0xB2);
			baos.write((byte) 0xB3);
			baos.write((byte) 0xB4);
			baos.write((byte) 0xB6);

			// ===== QUERY REPLY (USABLE AREA) =====
			baos.write(0x00);
			baos.write(0x17);
			baos.write((byte) 0x81); // Query Reply
			baos.write((byte) 0x81); // Usable Area QRID
			baos.write(0x01); // 12/14-bit addressing
			// baos.write(0x00); // Flags1
			baos.write(0x00); // Flags2
			baos.write((byte) ((reportCols >> 8) & 0xFF)); // 0x00
			baos.write((byte) (reportCols & 0xFF)); // 0x50 (80)
			baos.write((byte) ((reportRows >> 8) & 0xFF)); // 0x00
			baos.write((byte) (reportRows & 0xFF)); // 0x20 (32)
			baos.write(0x00); // Units
			baos.write(0x00); // Xr MSB
			baos.write(0x02); // Xr LSB
			baos.write(0x00); // Yr MSB
			baos.write((byte) 0x89); // Yr LSB
			baos.write(0x00); // AW MSB
			baos.write(0x02); // AW LSB
			baos.write(0x00); // AH MSB
			baos.write(0x85); // AH LSB
			// baos.write((byte)((reportBufsize >> 8) & 0xFF)); // 0x0A (2560>>8=10)
			// baos.write((byte)(reportBufsize & 0xFF)); // 0x00 (2560&0xFF=0)
			baos.write(0x09); // Xm MSB
			baos.write(0x10); // Xm LSB
			baos.write(0x0A); // Ym MSB (CHANGED from 0x0D)
			baos.write(0x00); // Ym LSB (CHANGED from 0x70)

			// ===== QUERY REPLY (DEVICE CHARACTERISTICS 0x84) =====
			// PCOMM: Data='0008 8184 001E0004'x
			baos.write(0x00);
			baos.write(0x08); // Length = 8 bytes
			baos.write(0x81); // Query Reply
			baos.write(0x84); // Device Characteristics QRID
			baos.write(0x00); // Byte 4: Device type/features
			baos.write(0x0D); // Byte 5: Model (0x0D = Model 4, 0x70 for others)
			baos.write(0x70); // Byte 6: Extended features
			baos.write(0x00); // Byte 7: Reserved

			// 00 1b 81 85 82 00 09 0c 00 00 00 00 07 00 10 00 02 b9 00 25 01 00 f1 03 c3 01
			// 36
			// 00 13 81 85 B0 00 09 10 40 00 00 00 03 00 00 00 01 00 F1
			// ===== QUERY REPLY (CHARACTER SET) =====
			// PCOMM: Data='001B 8185 82001622000000000700000002B900250100F103C30136'x
			baos.write(0x00);
			baos.write(0x13);
			baos.write((byte) 0x81); // Query Reply
			baos.write((byte) 0x85); // Character Sets
			baos.write((byte) 0xB0); // Flags: CGCSGID, Multiple LCIDs
			baos.write(0x00); // SDW
			baos.write(0x09); // SDH
			baos.write(0x10); // Form: 0x10 = 40 characters
			baos.write(0x40); // DL
			baos.write(0x00); // DL MSB
			baos.write(0x00); // DL LSB
			baos.write(0x00); // Descriptor length
			baos.write(0x03); // Number of char sets
			baos.write(0x00); // Reserved
			baos.write(0x00); // Reserved
			baos.write(0x00); // Reserved
			baos.write(0x01); // Reserved
			baos.write(0x00); // Reserved
			baos.write((byte) 0xF1); // APL character set

			// 00 26 81 86 00 10 00 f4 f1 f1 f2 f2 f3 f3 f4 f4 f5 f5 f6 f6 f7
			// f7 f8 f8 f9 f9 fa fa fb fb fc fc fd fd fe fe ff ff ff ff
			// ===== QUERY REPLY (COLOR) =====
			baos.write(0x00);
			baos.write(0x26); // 22 bytes (your format)
			baos.write((byte) 0x81); // Query Reply
			baos.write((byte) 0x86); // Color
			baos.write(0x00); // Flags
			baos.write(0x10); // 8 colors
			baos.write(0x00); // Default color
			baos.write((byte) 0xF4); // Green
			baos.write((byte) 0xF1); // Blue
			baos.write((byte) 0xF1); // Blue
			baos.write((byte) 0xF2); // Red
			baos.write((byte) 0xF2); // Red
			baos.write((byte) 0xF3); // Pink
			baos.write((byte) 0xF3); // Pink
			baos.write((byte) 0xF4); // Green
			baos.write((byte) 0xF4); // Green
			baos.write((byte) 0xF5); // Turquoise
			baos.write((byte) 0xF5); // Turquoise
			baos.write((byte) 0xF6); // Yellow
			baos.write((byte) 0xF6); // Yellow
			baos.write((byte) 0xF7); // White
			baos.write((byte) 0xF7); // White
			baos.write((byte) 0xF8); // Black
			baos.write((byte) 0xF8);
			baos.write((byte) 0xF9); // Deep Blue
			baos.write((byte) 0xF9);
			baos.write((byte) 0xFA); // Orange
			baos.write((byte) 0xFA);
			baos.write((byte) 0xFB); // Purple
			baos.write((byte) 0xFB);
			baos.write((byte) 0xFC); // Pale Green
			baos.write((byte) 0xFC);
			baos.write((byte) 0xFD); // Pale Turquoise
			baos.write((byte) 0xFD);
			baos.write((byte) 0xFE); // Grey
			baos.write((byte) 0xFE);
			baos.write((byte) 0xFF); // White
			baos.write((byte) 0xFF);
			baos.write((byte) 0xFF);
			baos.write((byte) 0xFF);

			// 00 0f 81 87 05 00 f0 f1 f1 f2 f2 f4 f4 f8 f8
			// ===== QUERY REPLY (HIGHLIGHTING) =====
			baos.write(0x00);
			baos.write(0x0F); // 15 bytes (your format)
			baos.write((byte) 0x81); // Query Reply
			baos.write((byte) 0x87); // Highlighting
			baos.write(0x05); // 5 pairs
			baos.write(0x00); // Default
			baos.write((byte) 0xF0); // Normal
			baos.write((byte) 0xF1); // Blink
			baos.write((byte) 0xF1); // ?
			baos.write((byte) 0xF2); // Reverse
			baos.write((byte) 0xF2); // ?
			baos.write((byte) 0xF4); // Underscore
			baos.write((byte) 0xF4); // ?
			baos.write((byte) 0xF8);
			baos.write((byte) 0xF8);

			// 00 07 81 88 00 01 02
			// ===== QUERY REPLY (REPLY MODES) =====
			baos.write(0x00);
			baos.write(0x07); // 7 bytes
			baos.write((byte) 0x81); // Query Reply
			baos.write((byte) 0x88); // Reply Modes
			baos.write(0x00); // Field mode
			baos.write(0x01); // Extended Field mode
			baos.write(0x02); // Character Mode

			// ===== QUERY REPLY (FORMAT PRESENTATION 0x8C) =====
			baos.write(0x00);
			baos.write(0x07); // Length = 7 bytes
			baos.write(0x81); // Query Reply
			baos.write(0x8C); // Format Presentation QRID
			baos.write(0x00); // Reserved
			baos.write(0x00); // Supports Format Presentation (was 0x01)
			baos.write(0x00); // Reserved

			// 00 0c 81 95 00 00 40 00 40 00 01 01
			// ===== QUERY REPLY (DDM) =====
			// PCOMM: Data='000C 8195 000009C409C40101'x
			baos.write(0x00);
			baos.write(0x0C); // 12 bytes (your format)
			baos.write((byte) 0x81); // Query Reply
			baos.write((byte) 0x95); // DDM
			baos.write(0x00); // Flags
			baos.write(0x00); // ?
			baos.write(0x40); // LIMIN
			baos.write(0x00); // ?
			baos.write(0x40); // LIMOUT
			baos.write(0x00); // ?
			baos.write(0x01); // ?
			baos.write(0x01); // ?

			// ===== QUERY REPLY (STORAGE POOLS 0x99) =====
			baos.write(0x00);
			baos.write(0x06); // Length = 6 bytes
			baos.write(0x81); // Query Reply
			baos.write(0x99); // Storage Pools QRID
			baos.write(0x00); // Reserved
			baos.write(0x00); // Reserved

			// 00 11 81 A6 00 00 0B 01 00 0050 0018 0050 0020'
			// ===== IMPLICIT PARTITION ======
			baos.write(0x00);
			baos.write(0x11);
			baos.write((byte) 0x81);
			baos.write((byte) 0xA6);
			baos.write(0x00);
			baos.write(0x00);
			baos.write(0x0B);
			baos.write(0x01);
			baos.write(0x00);
			baos.write((byte) ((reportCols >> 8) & 0xFF)); // 0x00
			baos.write((byte) (reportCols & 0xFF)); // 0x50
			baos.write(0x00);
			baos.write(0x18);
			baos.write((byte) ((reportCols >> 8) & 0xFF)); // 0x00
			baos.write((byte) (reportCols & 0xFF)); // 0x50
			baos.write((byte) ((reportRows >> 8) & 0xFF)); // 0x00
			baos.write((byte) (reportRows & 0xFF)); // 0x20 (32 rows alternate)

			// OEM Auxiliary Device
			// 001A 81 8F 00 00 CL8'tn3270' CL8'ClaudeAI' 04 01 00 AE
			baos.write(0x00);
			baos.write(0x1A);
			baos.write(0x81);
			baos.write(0x8F);
			baos.write(0x00);
			baos.write(0x00);
			baos.write(0xA3); // 1
			baos.write(0x95); // 2
			baos.write(0xF3); // 3
			baos.write(0xF2); // 4
			baos.write(0xF7); // 5
			baos.write(0xF0); // 6
			baos.write(0x40); // 7
			baos.write(0x40); // 8
			baos.write(0xC3); // 1
			baos.write(0x93); // 2
			baos.write(0x81); // 3
			baos.write(0xA4); // 4
			baos.write(0x84); // 5
			baos.write(0x85); // 6
			baos.write(0xC1); // 7
			baos.write(0xC9); // 8
			baos.write(0x04);
			baos.write(0x01);
			baos.write(0x00);
			baos.write(0xAE);

			// Anomally Implementation
			// 0019 81 9D 00 01 0E 00 0E 00 0F 00 00 CL12'Claude/AI'
			baos.write(0x00);
			baos.write(0x19);
			baos.write(0x81);
			baos.write(0x9D);
			baos.write(0x00); // Reserved, must be 0
			baos.write(0x01); // Anomaly reference number
			baos.write(0x0E); // Max inbound
			baos.write(0x00);
			baos.write(0x0E); // Max outbound
			baos.write(0x00);
			baos.write(0x0F); // Length of data
			baos.write(0xC3); // 1
			baos.write(0x93); // 2
			baos.write(0x81); // 3
			baos.write(0xA4); // 4
			baos.write(0x84); // 5
			baos.write(0x85); // 6
			baos.write(0x61); // 7
			baos.write(0xC1); // 8
			baos.write(0xC9); // 9
			baos.write(0x40); // 10
			baos.write(0x40); // 11
			baos.write(0x40); // 12
			baos.write(0x40); // 13
			baos.write(0x40); // 14

			// ===== QUERY REPLY (DDM 0xA8) =====
			baos.write(0x00);
			baos.write(0x06); // Length = 6 bytes
			baos.write(0x81); // Query Reply
			baos.write(0xA8); // DDM QRID
			baos.write(0x00); // Flags
			baos.write(0x01); // DDM subset identifier

			// Cooperative Processing Requestor
			// 0011 81 AB 00 00 00 00 00 00 02 00 00 04 01 00 01
			baos.write(0x00);
			baos.write(0x11);
			baos.write(0x81);
			baos.write(0xAB);
			baos.write(0x00);
			baos.write(0x00);
			baos.write(0x00);
			baos.write(0x00);
			baos.write(0x00);
			baos.write(0x00);
			baos.write(0x02);
			baos.write(0x00);
			baos.write(0x00);
			baos.write(0x04);
			baos.write(0x01);
			baos.write(0x00);
			baos.write(0x01);

			// ===== QUERY REPLY (0xB0 - Begin/End of File) =====
			baos.write(0x00);
			baos.write(0x04); // Length = 4 bytes
			baos.write(0x81); // Query Reply
			baos.write(0xB0); // Begin/End of File QRID

			// ===== QUERY REPLY (0xB1 - Data Chaining) =====
			baos.write(0x00);
			baos.write(0x06); // Length = 6 bytes
			baos.write(0x81); // Query Reply
			baos.write(0xB1); // Data Chaining QRID
			baos.write(0x00); // Maximum number of requests
			baos.write(0x00); // Reserved

			// ===== QUERY REPLY (0xB2 - Destination/Origin) =====
			baos.write(0x00);
			baos.write(0x04); // Length = 4 bytes
			baos.write(0x81); // Query Reply
			baos.write(0xB2); // Destination/Origin QRID

			// ===== QUERY REPLY (0xB3 - Object Control) =====
			baos.write(0x00);
			baos.write(0x04); // Length = 4 bytes
			baos.write(0x81); // Query Reply
			baos.write(0xB3); // Object Control QRID

			// ===== QUERY REPLY (0xB4 - Object Picture) =====
			baos.write(0x00);
			baos.write(0x04); // Length = 4 bytes
			baos.write(0x81); // Query Reply
			baos.write(0xB4); // Object Picture QRID

			// ===== QUERY REPLY (0xB6 - Save/Restore Format) =====
			baos.write(0x00);
			baos.write(0x04); // Length = 4 bytes
			baos.write(0x81); // Query Reply
			baos.write(0xB6); // Save/Restore Format QRID

			byte[] response = baos.toByteArray();

			// DEBUG OUTPUT
			System.out.println("=== COMPLETE QUERY RESPONSE ===");
			for (int i = 0; i < response.length; i++) {
				System.out.print(String.format("%02X", response[i]));
				if ((i + 1) % 16 == 0)
					System.out.println();
			}
			System.out.println();
			System.out.println("Total: " + response.length + " bytes");

			output.write(response);
			output.write(Constants.IAC);
			output.write((byte) 0xEF);
			output.flush();

			System.out.println("Query response sent");

		} catch (Exception e) {
			System.err.println("Error sending Query Response: " + e.getMessage());
			e.printStackTrace();
		}
	}
}
