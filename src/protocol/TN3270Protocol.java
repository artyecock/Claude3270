import java.io.*;
import java.awt.Toolkit;

/**
 * 
 * 3270 data stream protocol handler. Processes 3270 commands and orders.
 */
public class TN3270Protocol {
	private OutputStream output;
	private ScreenBuffer screenBuffer;
	private TelnetProtocol telnetProtocol;
	private CursorManager cursorManager;
	private int cursorPos = 0;
	private boolean keyboardLocked = false;
	private byte lastAID = Constants.AID_ENTER;
	// Character set state
	private char[] currentCharSet;

	/**
	 * 
	 * Create a new TN3270 protocol handler.
	 */
	public TN3270Protocol(OutputStream output, ScreenBuffer screenBuffer, TelnetProtocol telnetProtocol,
			CursorManager cursorManager) {
		this.output = output;
		this.screenBuffer = screenBuffer;
		this.telnetProtocol = telnetProtocol;
		this.cursorManager = cursorManager;
		this.currentCharSet = EbcdicConverter.getAsciiTable();
	}

	/**
	 * 
	 * Process received 3270 data stream.
	 */
	public void process3270Data(byte[] data) {
		if (data.length < 1)
			return;
		int offset = 0;
		if (telnetProtocol.isTN3270eMode() && data.length >= 5) {
			byte dataType = data[0];
			offset = 5;
			System.out.println("TN3270E Data type: " + String.format("%02X", dataType));
			if (dataType != 0x00) { // TN3270E_DT_3270_DATA
				System.out.println("Non-3270-DATA type received, ignoring");
				return;
			}
		}
		if (offset >= data.length) {
			System.out.println("No 3270 command after TN3270E header");
			return;
		}
		byte command = data[offset++];
		System.out.println("3270 Command: " + String.format("%02X", command));
		switch (command) {
		case Constants.CMD_ERASE_WRITE_05:
		case Constants.CMD_ERASE_WRITE_F5:
			// Erase Write - use primary size
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
			// Erase Write Alternate
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
		}
	}

	/**
	 * 
	 * Process Write Control Character.
	 */
	private void processWCC(byte wcc) {
		if ((wcc & Constants.WCC_RESET) != 0) {
			keyboardLocked = false;
		}
		if ((wcc & Constants.WCC_RESET_MDT) != 0) {
			screenBuffer.resetMDT();
		}
		if ((wcc & Constants.WCC_ALARM) != 0) {
			Toolkit.getDefaultToolkit().beep();
		}
	}

	/**
	 * 
	 * Process 3270 orders. CRITICAL: This is a large method (~160 lines) that needs
	 * to be pasted.
	 */
	private void processOrders(byte[] data, int offset) {
		// This is the heart of 3270 data stream processing
		// Handles: SF, SFE, SA, SBA, RA, IC, MF, GE, EUA, FM orders
// IMPORTANT: Replace buffer/attributes array access with screenBuffer methods
// IMPORTANT: Replace cursorPos with cursorManager.getCursorPos()/setCursorPos()
		int pos = 0;
		int i = offset;
		int currentFieldStart = -1;
		byte currentColor = 0;
		byte currentHighlight = 0;
		boolean graphicEscape = false; // Track if we're in APL mode
		char c;

		while (i < data.length) {
			byte b = data[i++];

			switch (b) {
			case ORDER_SF:
				if (i < data.length) {
					byte attr = data[i++];
					buffer[pos] = ' ';
					attributes[pos] = attr;
					extendedColors[pos] = 0;
					highlighting[pos] = 0;
					currentFieldStart = pos;
					currentColor = 0;
					currentHighlight = 0;
					pos = (pos + 1) % (rows * cols);
					graphicEscape = false; // Reset on new field
				}
				break;

			case ORDER_SFE:
				if (i < data.length) {
					int count = data[i++] & 0xFF;
					byte attr = 0;
					byte color = 0;
					byte highlight = 0;

					for (int j = 0; j < count && i + 1 < data.length; j++) {
						byte type = data[i++];
						byte value = data[i++];

						if (type == ATTR_FIELD || type == (byte) 0xC0) {
							attr = value;
						} else if (type == ATTR_FOREGROUND || type == (byte) 0x42) {
							// Map color values: 0xF1-0xF7 -> indices 1-7
							if (value >= (byte) 0xF1 && value <= (byte) 0xF7) {
								color = (byte) (value - 0xF0);
							} else if (value >= 0x01 && value <= 0x07) {
								color = value;
							}
							currentColor = color;
						} else if (type == ATTR_HIGHLIGHTING || type == (byte) 0x41) {
							currentHighlight = value;
							highlight = value;
						}
					}

					buffer[pos] = ' ';
					attributes[pos] = attr;
					extendedColors[pos] = color;
					highlighting[pos] = highlight;
					currentFieldStart = pos;
					pos = (pos + 1) % (rows * cols);
					graphicEscape = false; // Reset on new field
				}
				break;

			case ORDER_SA:
				if (i + 1 < data.length) {
					byte attrType = data[i++];
					byte attrValue = data[i++];

					if (attrType == ATTR_FOREGROUND || attrType == (byte) 0x42) {
						// Map color value to array index
						if (attrValue >= (byte) 0xF1 && attrValue <= (byte) 0xF7) {
							currentColor = (byte) (attrValue - 0xF0);
						} else if (attrValue >= 0x01 && attrValue <= 0x07) {
							currentColor = attrValue;
						}
					} else if (attrType == ATTR_HIGHLIGHTING || attrType == (byte) 0x41) {
						currentHighlight = attrValue;
					}
				}
				break;

			case ORDER_SBA:
				if (i + 1 < data.length) {
					// pos = decode3270Address(data[i], data[i + 1]);
					byte b1 = data[i];
					byte b2 = data[i + 1];
					pos = decode3270Address(b1, b2);
					// System.out.println("SBA: bytes=" + String.format("%02X %02X", b1, b2) +
					// " -> pos=" + pos + " (row=" + (pos/cols) + " col=" + (pos%cols) + ")");
					i += 2;
				}
				break;

			case ORDER_RA: {
				// Need: 2 bytes address + 1 byte repeat-char (or GE + operand)
				if (!safeConsume(data, i, 3)) {
					// truncated RA sequence - ignore safely
					break;
				}

				// read address (two bytes)
				int endPos = decode3270Address(data[i], data[i + 1]);
				int idxForChar = i + 2; // index of repeat char (or GE)
				// We'll use idxRef to let fetchDisplayChar advance properly
				int[] idxRef = new int[] { idxForChar };
				c = fetchDisplayChar(data, idxRef);
				// Advance main stream index 'i' to where fetchDisplayChar left off
				i = idxRef[0];

				// Now repeat from current pos up to (but not including) endPos.
				// If endPos == pos, this is a no-op.
				while (pos != endPos) {
					buffer[pos] = c;
					extendedColors[pos] = currentColor;
					highlighting[pos] = currentHighlight;
					// Do NOT set MDT for host writes.
					pos = (pos + 1) % (rows * cols);
				}
				break;
			}

			/*
			 * case ORDER_RA: if (i + 2 < data.length) { int endPos =
			 * decode3270Address(data[i], data[i + 1]); byte ch = data[i + 2]; i += 3;
			 * 
			 * if (ch == ORDER_GE) { // Is char a GE order? if (i + 1 < data.length) { ch =
			 * data[i + 1]; // Yes, grab next char (APL) i++; // Account for GE order c =
			 * EBCDIC_TO_APL[ch & 0xFF]; // Translate to APL char } else { break; // or
			 * safely ignore truncated GE } } else { c = EBCDIC_TO_ASCII[ch & 0xFF]; }
			 * 
			 * while (pos != endPos) { buffer[pos] = c; extendedColors[pos] = currentColor;
			 * highlighting[pos] = currentHighlight; pos = (pos + 1) % (rows * cols); } }
			 * break;
			 */
			case ORDER_IC:
				cursorPos = pos;

				// If we're on a field attribute, move to next position
				if (isFieldStart(cursorPos)) {
					cursorPos = (cursorPos + 1) % (rows * cols);
				}
				break;

			case ORDER_MF:
				if (i < data.length) {
					int count = data[i++] & 0xFF;
					for (int j = 0; j < count && i + 1 < data.length; j++) {
						byte type = data[i++];
						byte value = data[i++];
						if (currentFieldStart >= 0) {
							if (type == ATTR_FIELD || type == (byte) 0xC0) {
								attributes[currentFieldStart] = value;
							}
						}
					}
				}
				break;

			case ORDER_EUA: // EUA - Erase Unprotected to Address
				if (i + 1 < data.length) {
					int endPos = decode3270Address(data[i], data[i + 1]);
					i += 2;
					System.out.println("EUA: Erase unprotected to pos=" + endPos);

					// Erase from current position to endPos (unprotected fields only)
					int startPos = pos;
					while (pos != endPos) {
						if (!isProtected(pos) && !isFieldStart(pos)) {
							buffer[pos] = '\0';
						}
						pos = (pos + 1) % (rows * cols);
					}
					pos = startPos; // Reset position
				}
				break;

			case ORDER_FM: // FM - Field Mark (rarely used)
				System.out.println("FM order at offset " + i);
				// Just skip it
				break;

			default:
				if (b == ORDER_GE) {
					b = data[i++];
					c = EBCDIC_TO_APL[b & 0xFF];
					graphicEscape = false;
					// System.out.println("GE: " + c + "\n");
				} else
					c = EBCDIC_TO_ASCII[b & 0xFF];

				// c = currentCharSet[b & 0xFF]; // Use current character set
				// char c = EBCDIC_TO_ASCII[b & 0xFF];
				/*
				 * if (graphicEscape) { c = EBCDIC_TO_APL[b & 0xFF]; graphicEscape = false;
				 * System.out.println("GE: " + c + "\n"); } else c = EBCDIC_TO_ASCII[b & 0xFF];
				 */
				buffer[pos] = c;
				extendedColors[pos] = currentColor;
				highlighting[pos] = currentHighlight;

				// Don't set MDT indiscriminately!!!
				// if (currentFieldStart >= 0 && c != '\0' && c != ' ' &&
				// !isProtected(currentFieldStart)) {
				// attributes[currentFieldStart] |= 0x01;
				// }

				pos = (pos + 1) % (rows * cols);
				break;
			}
		}
	}

	/**
	 * 
	 * Process Write Structured Field command.
	 */
	private void processWSF(byte[] data, int offset) {
		// Handles Query and Data Chain structured fields
		System.out.println("=== processWSF called, offset=" + offset + ", data.length=" + data.length);

		int i = offset;

		while (i + 2 < data.length) {
			// Read length (2 bytes, big-endian, includes the length field itself)
			int length = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
			System.out.println("WSF: SF at offset " + i + ", Length = " + length);

			if (length < 3 || i + length > data.length) {
				System.out.println("WSF: Invalid length, stopping");
				break;
			}

			// SFID is at i+2
			byte sfid = data[i + 2];
			System.out.println("WSF: SFID = 0x" + String.format("%02X", sfid));

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
			} else if (sfid == SFID_DATA_CHAIN) {
				// Data Chain - IND$FILE operations
				handleDataChain(data, i, length);
			} else if (sfid == 0x40) {
				// Outbound 3270DS - contains embedded 3270 data
				System.out.println("WSF: Outbound 3270DS - contains embedded 3270 data");
			} else {
				System.out.println("WSF: Unknown/unhandled SFID");
			}

			// Move to next structured field
			i += length;
		}
	}

	/**
	 * 
	 * Send Query Response. CRITICAL: This is ~160 lines of query reply structure.
	 */
	public void sendQueryResponse() {
		// This builds the complete Query Reply with all SFIDs
		// IMPORTANT: Use screenBuffer.getRows()/getCols() for dimensions
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();

			// AID and cursor address
			// baos.write(AID_ENTER);
			// byte[] cursorAddr = encode3270Address(cursorPos);
			// baos.write(cursorAddr[0]);
			// baos.write(cursorAddr[1]);

			int reportRows = alternateRows; // 32 for 3279-3
			int reportCols = alternateCols; // 80
			// int reportBufsize = reportRows * reportCols; // 2560
			int reportBufsize = screenBuffer.getRows() * screenBuffer.getCols();

			System.out.println("Query Reply: reporting " + reportCols + "x" + reportRows + " (model: " + model + ")");

			// ===== QUERY REPLY (SUMMARY) =====
			baos.write(0x88); // WSF
			baos.write(0x00); // Length MSB
			baos.write(0x18); // Length LSB (24 bytes)
			baos.write((byte) 0x81); // Query Reply
			baos.write((byte) 0x80); // Summary
			baos.write((byte) 0x81); // Usable Area
			baos.write((byte) 0x84); // Device Characteristics (Alphanumeric Partitions)
			baos.write((byte) 0x85); // Character Sets
			baos.write((byte) 0x86); // Color
			baos.write((byte) 0x87); // Highlight
			baos.write((byte) 0x88); // Reply Modes
			baos.write((byte) 0x8C); // Format Presentation (Field Outlining)
			baos.write((byte) 0x8F); // OEM Auxiliary Device (optional)
			baos.write((byte) 0x95); // Distributed Data Management (DDM)
			baos.write((byte) 0x99); // Storage Pools (Auxiliary device (AUXDA))
			baos.write((byte) 0x9D); // Segment (optional) (Anomally Implementation)
			baos.write((byte) 0xA6); // Implicit Partition
			baos.write((byte) 0xA8); // Distributed Data Management (Transparency)
			baos.write((byte) 0xAB); // Product Defined (optional) (Cooperative Processing Requestor (CPR))
			baos.write((byte) 0xB0); // Begin/End of File (Segment)
			baos.write((byte) 0xB1); // Data Chaining (Procedure)
			baos.write((byte) 0xB2); // Destination/Origin (Line Type)
			baos.write((byte) 0xB3); // Object Control (Port)
			baos.write((byte) 0xB4); // Object Picture (Graphic Color)
			baos.write((byte) 0xB6); // Save/Restore Format (Graphic Symbol Sets)

			// ===== QUERY REPLY (USABLE AREA) =====
			// PCOMM: Data='0017 8181 01000050002000000100600001006016220A00'x
			// 0017 8181 01 0000 5000 2000 00 0A 02 E5 00 02 00 6F 09 0C 0A 00
			// 88 00 0e 81 80 80 81 84 85 86 87 88 95 a1 a6
			// 00 17 81 81 01 00 00 50 00 2b 01 00 0a 02 e5 00 02 00 6f 09 0c 0d 70
			// 00 08 81 84 00 0d 70 00
			// 00 1b 81 85 82 00 09 0c 00 00 00 00 07 00 10 00 02 b9 00 25 01 00 f1 03 c3 01
			// 36
			// 00 26 81 86 00 10 00 f4 f1 f1 f2 f2 f3 f3 f4 f4 f5 f5 f6 f6 f7
			// f7 f8 f8 f9 f9 fa fa fb fb fc fc fd fd fe fe ff ff ff ff
			// 00 0f 81 87 05 00 f0 f1 f1 f2 f2 f4 f4 f8 f8
			// 00 07 81 88 00 01 02
			// 00 0c 81 95 00 00 40 00 40 00 01 01
			// 00 12 81 a1 00 00 00 00 00 00 00 00 06 a7 f3 f2 f7 f0
			// 00 11 81 a6 00 00 0b 01 00 00 50 00 18 00 50 00 2b
			//
			// For 3279-3: 80x32
			// ll ll 81 81 01 00 00 00 00 00 01 00 D3 03 20 00 9E 02 58 07 0C 0780
			// 00 17 81 81 01 00 0050 0020 00 00 02 00 89 00 02 00 85 09 10 0A00
			// ===== QUERY REPLY (USABLE AREA) =====
			// 00 17 81 81 01 00 0050 002b 00 00 02 00 89 00 02 00 85 09 10 0a00
			baos.write(0x00); // Length MSB
			baos.write(0x17); // Length LSB (23 bytes)
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
			baos.write(0x01); // Extended field mode
			baos.write(0x02); // ?

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
			output.write(IAC);
			output.write((byte) 0xEF);
			output.flush();

			System.out.println("Query response sent");

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 
	 * Send Read Buffer response.
	 */
	public void sendReadBuffer() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write(lastAID);
			int pos = cursorManager.getCursorPos();
			byte[] cursorAddr = AddressEncoder.encode3270Address(pos);
			baos.write(cursorAddr[0]);
			baos.write(cursorAddr[1]);

			for (int i = 0; i < screenBuffer.getBufferSize(); i++) {
				if (screenBuffer.isFieldStart(i)) {
					baos.write(Constants.ORDER_SF);
					baos.write(screenBuffer.getAttribute(i));
				} else {
					char c = screenBuffer.getChar(i);
					if (c == '\0') {
						baos.write(0x00);
					} else {
						byte ebcdic = EbcdicConverter.asciiToEbcdic(c);
						baos.write(ebcdic);
					}
				}
			}

			sendData(baos.toByteArray());
			keyboardLocked = true;
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 
	 * Send AID response.
	 */
	public void sendAID(byte aid) {
		lastAID = aid;
		System.out.println("Sending AID: 0x" + String.format("%02X", aid));
		int pos = cursorManager.getCursorPos();
		System.out.println("Cursor position: " + pos);
		byte[] encodedCursor = AddressEncoder.encode3270Address(pos);
		System.out.println("Encoded cursor: " + String.format("%02X %02X", encodedCursor[0], encodedCursor[1]));
		keyboardLocked = true;
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write(aid);
			baos.write(encodedCursor[0]);
			baos.write(encodedCursor[1]);
			// For read-modified operations, include modified fields
			if (aid == Constants.AID_ENTER || (aid >= Constants.AID_PF1 && aid <= Constants.AID_PF12)) {

				int screenSize = screenBuffer.getBufferSize();
				for (int i = 0; i < screenSize; i++) {
					if (screenBuffer.isFieldStart(i) && screenBuffer.isModified(i)) {
						// Found a modified field
						int fieldStart = i;
						int end = screenBuffer.findNextField(i);

						// Find first non-null character in field
						int dataStart = fieldStart + 1;
						while (dataStart < end && screenBuffer.getChar(dataStart) == '\0') {
							dataStart++;
						}

						// Find last non-null character in field
						int dataEnd = end - 1;
						while (dataEnd > fieldStart
								&& (screenBuffer.getChar(dataEnd) == '\0' || screenBuffer.getChar(dataEnd) == ' ')) {
							dataEnd--;
						}

						// Only send if there's actual data
						if (dataStart <= dataEnd) {
							baos.write(Constants.ORDER_SBA);
							byte[] addr = AddressEncoder.encode3270Address(dataStart);
							baos.write(addr[0]);
							baos.write(addr[1]);

							System.out.println("Sending modified field: start=" + dataStart + " end=" + dataEnd
									+ " fieldAttr=" + fieldStart);

							for (int j = dataStart; j <= dataEnd; j++) {
								if (!screenBuffer.isFieldStart(j)) {
									char c = screenBuffer.getChar(j);
									if (c != '\0') {
										byte ebcdic = EbcdicConverter.asciiToEbcdic(c);
										baos.write(ebcdic);
									}
								}
							}
						}
					}
				}
			}

			sendData(baos.toByteArray());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 
	 * Send 3270 data with proper framing.
	 */
	private void sendData(byte[] data) throws IOException {
		ByteArrayOutputStream fullPacket = new ByteArrayOutputStream();
		if (telnetProtocol.isTN3270eMode()) {
			fullPacket.write(0x00); // TN3270E_DT_3270_DATA
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

	/**
	 * 
	 * Erase all unprotected fields.
	 */
	public void eraseAllUnprotected() {
		for (int i = 0; i < screenBuffer.getBufferSize(); i++) {
			if (screenBuffer.isFieldStart(i)) {
				// Reset MDT bit
				byte attr = screenBuffer.getAttribute(i);
				screenBuffer.setAttribute(i, (byte) (attr & ~0x01));
			} else if (!screenBuffer.isProtected(i)) {
				// Clear unprotected field data
				screenBuffer.setChar(i, '\0');
			}
		}
		screenBuffer.clearUnprotectedModifiedFlags();
		keyboardLocked = false;
	}

	// Getters and setters
	public int getCursorPos() {
		return cursorManager.getCursorPos();
	}

	public void setCursorPos(int pos) {
		cursorManager.setCursorPos(pos);
	}

	public boolean isKeyboardLocked() {
		return keyboardLocked;
	}

	public void setKeyboardLocked(boolean locked) {
		this.keyboardLocked = locked;
	}

	public byte getLastAID() {
		return lastAID;
	}
}
