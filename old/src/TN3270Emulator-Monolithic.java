import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.security.Key;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.Timer;
import javax.xml.crypto.Data;

import javax.net.ssl.*;
import javax.print.attribute.standard.Compression;

import java.awt.datatransfer.*;

import java.awt.image.BufferedImage;
import java.awt.geom.*;

/*
 * =============================================================================
 * IND$FILE FILE TRANSFER PROTOCOL DOCUMENTATION
 * =============================================================================
 * 
 * IND$FILE is a file transfer protocol used with IBM 3270 terminal emulators
 * to transfer files between a PC and a mainframe host (z/OS, z/VM, z/VSE).
 * 
 * This implementation supports both TSO (z/OS) and CMS (z/VM) environments.
 * 
 * =============================================================================
 * PROTOCOL OVERVIEW
 * =============================================================================
 * 
 * The protocol uses IBM's Structured Field (WSF) commands within the 3270
 * data stream. All file transfer operations are performed using the Data Chain
 * structured field (SFID 0xD0) with various operation codes.
 * 
 * Transfer Direction Terminology:
 * -------------------------------
 * From PC perspective:
 *   - UPLOAD   = IND$FILE PUT = PC sends file TO host   = Host sends DC_GET
 *   - DOWNLOAD = IND$FILE GET = PC receives file FROM host = Host sends DC_INSERT
 * 
 * From Host perspective (reversed):
 *   - GET  = Host receives file from PC (PC uploads)
 *   - PUT  = Host sends file to PC (PC downloads)
 * 
 * =============================================================================
 * COMMAND SYNTAX
 * =============================================================================
 * 
 * TSO (z/OS) Format:
 * ------------------
 * Upload:   IND$FILE PUT dataset.name ASCII CRLF RECFM(F) LRECL(80) BLKSIZE(6160) SPACE(1,1)
 * Download: IND$FILE GET dataset.name ASCII CRLF RECFM(F) LRECL(80) BLKSIZE(6160)
 * 
 * Parameters:
 *   - ASCII      = Text transfer with ASCII/EBCDIC conversion
 *   - BINARY     = Binary transfer, no conversion
 *   - CRLF       = Add CR/LF line endings (text mode only)
 *   - APPEND     = Append to existing file
 *   - RECFM(x)   = Record format: F=Fixed, V=Variable, U=Undefined
 *   - LRECL(n)   = Logical record length in bytes
 *   - BLKSIZE(n) = Block size in bytes
 *   - SPACE(p,s) = Primary and secondary space allocation (cylinders)
 * 
 * CMS (z/VM) Format:
 * ------------------
 * Upload:   IND$FILE PUT filename filetype filemode (ASCII CRLF RECFM F LRECL 80)
 *           IND$FILE PUT filename filetype filemode (ASCII CRLF RECFM V LRECL 133)
 * Download: IND$FILE GET filename filetype filemode (ASCII CRLF)
 * 
 * Parameters (in parentheses):
 *   - ASCII      = Text transfer
 *   - CRLF       = Add CR/LF line endings
 *   - APPEND     = Append to existing file
 *   - RECFM x    = Record format (F, V, or U) - no parentheses around value
 *   - LRECL n    = Logical record length - no parentheses around value
 * 
 * Note: CMS parameters must be in parentheses and values are NOT in parentheses
 * 
 * =============================================================================
 * TRANSFER SEQUENCE
 * =============================================================================
 * 
 * UPLOAD (IND$FILE PUT - PC to Host):
 * -----------------------------------
 * 1. PC types: IND$FILE PUT dataset.name ASCII CRLF RECFM(F) LRECL(80)
 * 2. PC sends: ENTER (AID 0x7D)
 * 3. Host queries capabilities (Query/Query Reply exchange)
 * 4. Host sends: DC_OPEN with FT:DATA (direction byte = 0x01)
 * 5. PC responds: Positive acknowledgement (AID 0x88)
 * 6. Host sends: DC_SET_CURSOR + DC_GET (TWO SFs in one WSF - requests data)
 * 7. PC responds: DC_GET response with data block and sequence number
 * 8. Repeat steps 6-7 until file is complete
 * 9. Host sends: DC_SET_CURSOR + DC_GET (PC responds with EOF error 0x2200)
 * 10. Host may skip DC_CLOSE and go directly to FT:MSG
 * 11. Host sends: DC_OPEN with FT:MSG (completion message)
 * 12. Host sends: DC_INSERT header + DC_INSERT data (TWO SFs in one WSF)
 * 13. PC displays message on screen
 * 
 * DOWNLOAD (IND$FILE GET - Host to PC):
 * -------------------------------------
 * 1. PC types: IND$FILE GET dataset.name ASCII CRLF
 * 2. PC sends: ENTER (AID 0x7D)
 * 3. Host queries capabilities (Query/Query Reply exchange)
 * 4. Host sends: DC_OPEN with FT:DATA (direction byte = 0x00)
 * 5. PC responds: Positive acknowledgement (AID 0x88)
 * 6. Host sends: DC_INSERT header (length 10 bytes) + DC_INSERT with data block
 * 7. PC responds: Positive acknowledgement with sequence number
 * 8. Repeat steps 6-7 until file is complete
 * 9. Host sends: DC_CLOSE
 * 10. PC responds: Positive acknowledgement
 * 11. Host sends: DC_OPEN with FT:MSG (completion message)
 * 12. Host sends: DC_INSERT header + DC_INSERT data (TWO SFs in one WSF)
 * 13. PC displays message on screen
 * 
 * =============================================================================
 * DATA CHAIN OPERATIONS (SFID 0xD0)
 * =============================================================================
 * 
 * DC_OPEN (0x00) - Open File Transfer Session
 * -------------------------------------------
 * Host -> PC:
 *   Format: 00 23 D0 00 [fixed headers] 03 09 [filename]
 *   
 *   Fixed Header (35 bytes total):
 *     Bytes 0-1:   Length (0x0023 = 35 bytes)
 *     Bytes 2-3:   SFID and operation (0xD0 0x00)
 *     Bytes 4-13:  Fixed protocol data
 *     Byte 14:     Direction flag
 *                  0x01 = Host will GET (upload from PC)
 *                  0x00 = Host will INSERT (download to PC)
 *     Bytes 15-20: Buffer size and compression flags
 *     Bytes 21-22: Filename header (0x03 0x09)
 *     Bytes 23-30: Filename in ASCII
 *                  "FT:DATA" = Actual file data transfer
 *                  "FT:MSG " = Status/completion message
 * 
 * PC -> Host (Response):
 *   Success: 88 00 05 D0 00 09
 *     0x88 = Structured field response AID
 *     0x09 = Positive acknowledgement
 *   
 *   Failure: 88 00 09 D0 00 08 69 04 xx xx
 *     0x08 69 04 = Negative response header
 *     xx xx = Error code (see error codes below)
 * 
 * DC_CLOSE (0x41) - Close File Transfer Session
 * ---------------------------------------------
 * Host -> PC:
 *   Format: 00 05 D0 41 12
 * 
 * PC -> Host (Response):
 *   Success: 88 00 05 D0 41 09
 *   Failure: 88 00 09 D0 41 08 69 04 xx xx
 * 
 * DC_SET_CURSOR (0x45) - Position Cursor (precedes DC_GET)
 * --------------------------------------------------------
 * Host -> PC - TWO structured fields sent together:
 *   First SF (Set Cursor): 00 0F D0 45 11 01 05 00 06 00 09 05 01 03 00
 *   Second SF (Get):       00 09 D0 46 11 01 04 00 80
 *   
 *   The Set Cursor command positions the cursor but requires no response.
 *   It is ALWAYS immediately followed by DC_GET in the same WSF.
 *   The PC should:
 *     1. Process DC_SET_CURSOR (no response needed)
 *     2. Process DC_GET (send data response)
 *   
 *   IMPORTANT: Only respond to the DC_GET, not the DC_SET_CURSOR!
 * 
 * DC_GET (0x46) - Request Data from PC (Upload)
 * ---------------------------------------------
 * Host -> PC (Request) - Always preceded by DC_SET_CURSOR in same WSF:
 *   Format: 00 09 D0 46 11 01 04 00 80
 *   
 *   This is the SECOND structured field in the WSF.
 *   The offset passed to handleDCGet points to this SF's length bytes.
 * 
 * PC -> Host (Response with data):
 *   Format: ll ll D0 46 05 63 06 nn nn nn nn C0 80 61 dd dd [data]
 *   
 *   ll ll = Total length (big-endian)
 *   nn nn nn nn = Block sequence number (4 bytes, big-endian)
 *   0xC0 = Fixed byte
 *   0x80 = Data not compressed
 *   0x61 = Data marker
 *   dd dd = Data length + 5 (big-endian, max 2048 bytes)
 *   [data] = Actual file data (ASCII for text, raw for binary)
 * 
 * PC -> Host (End of file):
 *   Format: 88 00 09 D0 46 08 69 04 22 00
 *     0x2200 = "Get past end of file" error code
 * 
 * DC_INSERT (0x47) - Send Data to PC (Download)
 * ---------------------------------------------
 * Host -> PC (Data) - TWO structured fields sent together:
 *   First SF (Header): 00 0A D0 47 11 01 05 00 80 00
 *   Second SF (Data):  ll ll D0 47 04 C0 80 61 dd dd [data]
 *   
 *   First part is Insert header (10 bytes, no response needed)
 *   Second part contains actual data:
 *     ll ll = Data structure length
 *     0xD0 0x47 = SFID and operation
 *     0x04 0xC0 = Fixed bytes
 *     0x80 = Data not compressed
 *     0x61 = Data marker (always at offset+7 from second SF start)
 *     dd dd = Data length + 5
 *     [data] = File data (ASCII for text, raw for binary)
 * 
 * PC -> Host (Response):
 *   Success: 88 00 0B D0 47 05 63 06 nn nn nn nn
 *     nn nn nn nn = Block sequence number (acknowledgement)
 *   
 *   Failure: 88 00 09 D0 47 08 69 04 xx xx
 * 
 * =============================================================================
 * ERROR CODES
 * =============================================================================
 * 
 * Open/Close Errors:
 *   0x0100 - Open failed exception
 *   0x0200 - Arrival sequence not aligned
 *   0x0300 - Close of an unopened file
 *   0x1A00 - File name invalid
 *   0x1B00 - File not found
 *   0x1C00 - File size invalid
 *   0x2000 - Function/open error
 *   0x2A00 - Path not found
 *   0x5D00 - Unsupported type
 *   0x6000 - Command syntax error
 *   0x6200 - Parameter is missing
 *   0x6300 - Parameter not supported
 *   0x6500 - Parameter value not supported
 *   0x7100 - Invalid format
 * 
 * Get/Insert Errors:
 *   0x2200 - Get past end of file (normal EOF indicator)
 *   0x3E00 - Operation not authorized
 *   0x4700 - Record not added, storage full
 *   0x6E00 - Data element missing
 *   0x7000 - Record length = 0
 * 
 * =============================================================================
 * TEXT MODE PROCESSING (ASCII MODE)
 * =============================================================================
 * 
 * CRITICAL: IND$FILE transfers file data in ASCII format, NOT EBCDIC!
 * EBCDIC conversion only occurs for screen display purposes.
 * 
 * Upload (PC to Host) - ASCII TEXT Mode:
 * --------------------------------------
 * 1. Read file byte-by-byte looking for line endings
 * 2. Strip original line endings (\n, \r, or \r\n from source file)
 * 3. Send line content in ASCII format (ISO-8859-1 encoding)
 * 4. Append CRLF in ASCII to each line:
 *    - CR = 0x0D (ASCII carriage return)
 *    - LF = 0x0A (ASCII line feed)
 *    NOTE: NOT EBCDIC! Use 0x0D 0x0A, not 0x0D 0x25
 * 5. Send up to ~1900 chars per line (plus CRLF = ~1902 bytes per block)
 * 6. Track block sequence numbers
 * 7. When file complete, respond to next DC_GET with error 0x2200 (EOF)
 * 
 * Download (Host to PC) - ASCII TEXT Mode:
 * ----------------------------------------
 * 1. Receive data in ASCII format from host
 * 2. Data contains ASCII CRLF markers (0x0D 0x0A)
 * 3. Process line endings:
 *    - Strip CRLF (0x0D 0x0A) sequences
 *    - Replace with platform-native line endings (\n for Unix, \r\n for Windows)
 * 4. Write converted data to local file
 * 5. Host sends final block with EOF marker (often 0x1A)
 * 
 * =============================================================================
 * LINE-BY-LINE PROCESSING (TEXT MODE)
 * =============================================================================
 * 
 * Upload (PC to Host):
 * -------------------
 * The upload process reads the file line by line, stripping existing line
 * endings and replacing them with consistent ASCII CRLF:
 * 
 * 1. Read bytes until line ending detected (\n, \r, or \r\n)
 * 2. Strip the original line ending (do NOT include it in the buffer)
 * 3. Append ASCII CRLF (0x0D 0x0A) to the line content
 * 4. Send the line with exactly ONE CRLF sequence
 * 
 * This ensures:
 * - All lines have consistent line endings regardless of source platform
 * - No doubled CRLF sequences (which would double the file size)
 * - The host receives properly formatted ASCII text with CRLF line endings
 * 
 * Example processing:
 *   Input line:  "File RXTESTS  EXEC     A1\n"      (Unix LF)
 *   Processing:  Strip the \n, keep "File RXTESTS  EXEC     A1"
 *   Output:      "File RXTESTS  EXEC     A1\r\n"    (ASCII CRLF)
 * 
 *   Input line:  "File RXTESTS  EXEC     A1\r\n"    (Windows CRLF)
 *   Processing:  Strip the \r\n, keep "File RXTESTS  EXEC     A1"
 *   Output:      "File RXTESTS  EXEC     A1\r\n"    (ASCII CRLF)
 * 
 * Download (Host to PC):
 * ---------------------
 * The download process receives ASCII data with CRLF line endings:
 * 
 * 1. Receive block with ASCII CRLF markers (0x0D 0x0A)
 * 2. Scan for CRLF sequences
 * 3. Replace CRLF with platform-native line ending (\n for Unix/Mac, \r\n for Windows)
 * 4. Strip EOF marker (0x1A) if present in final block
 * 5. Write processed data to file
 * 
 * This ensures:
 * - Downloaded files use native line endings for the PC platform
 * - No extraneous CRLF markers in the file
 * - Clean text files compatible with local editors
 * 
 * Empty Lines:
 * -----------
 * Empty lines are handled correctly:
 * - Upload: Empty line (just \n) becomes just CRLF (0x0D 0x0A)
 * - Download: CRLF alone becomes platform line ending
 * 
 * EOF Handling:
 * ------------
 * - Upload: When uploadStream.read() returns -1, send error 0x2200
 * - Download: 0x1A byte (Ctrl-Z) marks EOF, should be stripped
 * - Both: Close streams after EOF/error response
 * 
 * =============================================================================
 * BINARY MODE PROCESSING
 * =============================================================================
 * 
 * Upload and Download:
 * -------------------
 * - No character conversion
 * - No line ending processing
 * - Raw byte transfer
 * - Maximum 2048 bytes per block
 * 
 * =============================================================================
 * FT:MSG HANDLING
 * =============================================================================
 * 
 * After completing FT:DATA transfer (both upload and download), the host
 * sends a completion message via FT:MSG:
 * 
 * 1. Host sends: DC_OPEN with filename "FT:MSG "
 * 2. PC responds: Positive acknowledgement
 * 3. Host sends: DC_INSERT with message text (already in ASCII)
 * 4. PC responds: Positive acknowledgement with block sequence
 * 5. PC displays message to user (not written to file)
 * 6. Host may send multiple FT:MSG blocks - only first contains actual message
 * 7. No DC_CLOSE is sent after FT:MSG - transfer is complete
 * 
 * Common FT:MSG Messages:
 * - "TRANS03   File transfer complete$" - Success
 * - "TRANS13   Error writing file to host: file transfer canceled" - Upload failure
 * - "TRANS14   Error reading file from host: file transfer canceled" - Download failure
 * 
 * =============================================================================
 * PAIRED STRUCTURED FIELDS
 * =============================================================================
 * 
 * The host always sends certain operations as PAIRS of structured fields
 * within a single WSF transmission:
 * 
 * Upload Operations (DC_GET):
 * ---------------------------
 * The host sends DC_SET_CURSOR followed immediately by DC_GET:
 *   
 *   Packet structure:
 *   [F3] [00 06 40 00 F1 C2]              <- WSF wrapper
 *   [00 0F D0 45 ...]                     <- SF #1: DC_SET_CURSOR (15 bytes)
 *   [00 09 D0 46 11 01 04 00 80]          <- SF #2: DC_GET (9 bytes)
 *   [FF EF]                               <- IAC EOR
 * 
 *   PC processing:
 *   1. processWSF() loops through both SFs
 *   2. handleDCSetCursor() is called - does nothing, returns
 *   3. handleDCGet() is called - sends data response
 *   
 *   IMPORTANT: Only ONE response is sent (for DC_GET only)
 * 
 * Download Operations (DC_INSERT):
 * --------------------------------
 * The host sends DC_INSERT header followed immediately by DC_INSERT data:
 *   
 *   Packet structure:
 *   [F3] [00 06 40 00 F1 C2]              <- WSF wrapper
 *   [00 0A D0 47 11 01 05 00 80 00]       <- SF #1: DC_INSERT header (10 bytes)
 *   [ll ll D0 47 04 C0 80 61 dd dd ...]   <- SF #2: DC_INSERT data (variable)
 *   [FF EF]                               <- IAC EOR
 * 
 *   PC processing:
 *   1. processWSF() loops through both SFs
 *   2. handleDCInsert() is called for header (length=10) - skips it, returns
 *   3. handleDCInsert() is called for data - writes data, sends response
 *   
 *   IMPORTANT: Only ONE response is sent (for data SF only)
 * 
 * Why Paired SFs?
 * ---------------
 * This pairing pattern is part of the IBM 3270 data stream architecture:
 * - DC_SET_CURSOR positions cursor for data entry context
 * - DC_INSERT header establishes the data context
 * - The actual operation (GET/INSERT) follows immediately
 * - Only the operation command requires a response
 * 
 * Implementation Pattern:
 * ----------------------
 * Both handlers should detect and skip the "setup" SF:
 * 
 *   // In handleDCSetCursor:
 *   System.out.println("=== DC_SET_CURSOR received ===");
 *   return; // No response needed
 *   
 *   // In handleDCInsert:
 *   if (length == 10) {
 *       System.out.println("DC_INSERT header (length=10) - skipping");
 *       return; // No response needed
 *   }
 *   // Process actual data...
 * 
 * =============================================================================
 * IMPLEMENTATION NOTES
 * =============================================================================
 * 
 * Critical Points:
 * ---------------
 * 1. After sending DC_OPEN response, do NOT send any unsolicited data
 * 2. Wait for host to send DC_GET or DC_INSERT commands
 * 3. Always respond with AID 0x88 for structured field responses
 * 4. Block sequence numbers start at 1 and increment for each block
 * 5. Maximum data block size is 2048 bytes (2K)
 * 6. FT:MSG transfers are displayed on screen, not saved to file
 * 7. Host always sends FT:MSG after completion for status
 * 8. Query Reply must include Data Streaming capability
 * 9. **UPLOADS: DC_SET_CURSOR + DC_GET always sent together (TWO SFs)**
 * 10. **DOWNLOADS: DC_INSERT header + DC_INSERT data sent together (TWO SFs)**
 * 11. The 0x61 data marker is always at offset+7 from the data SF start
 * 12. **Only respond to DC_GET (not DC_SET_CURSOR) during uploads**
 * 13. **Skip the 10-byte DC_INSERT header during downloads**
 * 
 * Character Sets:
 * --------------
 * - File data is transferred in ASCII (ISO-8859-1), NOT EBCDIC
 * - CRLF line endings are ASCII (0x0D 0x0A), NOT EBCDIC (0x0D 0x25)
 * - Only use EBCDIC conversion for terminal screen display
 * - The host's CMS/TSO file system handles any needed EBCDIC conversion
 * 
 * Buffer Management:
 * -----------------
 * - Use ByteArrayOutputStream for building structured fields
 * - Calculate lengths including length field itself
 * - All lengths are big-endian (MSB first)
 * - Include proper IAC EOR (0xFF 0xEF) after each transmission
 * 
 * Upload (DC_GET) Processing:
 * ---------------------------
 * - Read file byte-by-byte looking for line endings
 * - Strip original line endings (\n, \r, or \r\n from source file)
 * - Send line content with ASCII CRLF (0x0D 0x0A) appended
 * - Maximum ~1900 chars per line (plus CRLF = ~1902 bytes)
 * - Respond to final DC_GET with error code 0x2200 (EOF)
 * - Close file stream after sending EOF response
 * - CRITICAL: Do NOT double line endings - strip originals first!
 * 
 * Download (DC_INSERT) Processing:
 * --------------------------------
 * - Ignore the first DC_INSERT (header, length=10)
 * - Process the second DC_INSERT (data)
 * - Data marker (0x61) is at known offset (data SF start + 7)
 * - Strip CRLF markers when writing text files
 * - Accumulate block sequence for acknowledgement
 * - Watch for EOF marker (0x1A) in final block
 * 
 * WSF Processing:
 * --------------
 * - Multiple structured fields can arrive in one packet
 * - Each SF has format: [length(2)] [SFID(1)] [operation(1)] [data...]
 * - Process all SFs in the WSF sequentially
 * - Track offset correctly when processing multiple SFs
 * - The offset parameter passed to handlers points to the SF length bytes
 * 
 * =============================================================================
 * DEBUGGING TIPS
 * =============================================================================
 * 
 * Common Issues:
 * -------------
 * 1. "Error writing file to host" - Check ASCII/EBCDIC conversion
 * 2. "Error reading file from host" - Verify file exists and is readable
 * 3. Truncated uploads - Ensure proper EOF handling with 0x2200 response
 * 4. Missing data on downloads - Make sure to skip the 10-byte Insert header
 * 5. Hung transfers - Verify you're sending acknowledgements for every block
 * 6. **Doubled file size on upload** - Ensure line endings are stripped before adding CRLF
 * 7. **Wrong line endings on download** - Make sure CRLF is replaced with platform native
 * 
 * Protocol Verification:
 * ---------------------
 * - Watch for direction byte in DC_OPEN (byte 14): 0x01=upload, 0x00=download
 * - Verify CRLF is ASCII (0x0D 0x0A) not EBCDIC (0x0D 0x25)
 * - Check that FT:MSG sets ftIsMessage=true and keeps it set
 * - Ensure block sequence starts at 1 and increments consistently
 * - Confirm data marker (0x61) detection at correct offset
 * 
 * =============================================================================
 * REFERENCES
 * =============================================================================
 * 
 * - IBM 3270 Data Stream Programmer's Reference (GA23-0059)
 * - IBM File Transfer Program for z/OS and CMS (IND$FILE)
 * - RFC 1041: Telnet 3270 Regime Option
 * - RFC 1576: TN3270 Current Practices
 * - RFC 2355: TN3270 Enhancements
 * - IBM VM/CMS INDFILE ASSEMBLE source code (definitive implementation)
 * 
 * =============================================================================
 */

/**
 * TN3270 Terminal Emulator with TN3270E and TLS support Supports IBM 3270
 * terminal emulation with models 2, 3, 4, and 5
 */
public class TN3270Emulator extends Frame implements KeyListener {

	// Terminal models and dimensions
	private static final Map<String, Dimension> MODELS = new HashMap<>();
	static {
		MODELS.put("3278-2", new Dimension(80, 24));
		MODELS.put("3278-3", new Dimension(80, 32));
		MODELS.put("3278-4", new Dimension(80, 43));
		MODELS.put("3278-5", new Dimension(132, 27));
		MODELS.put("3279-2", new Dimension(80, 24)); // Color
		MODELS.put("3279-3", new Dimension(80, 32)); // Color
	}

	// Telnet commands
	private static final byte IAC = (byte) 0xFF;
	private static final byte DO = (byte) 0xFD;
	private static final byte DONT = (byte) 0xFE;
	private static final byte WILL = (byte) 0xFB;
	private static final byte WONT = (byte) 0xFC;
	private static final byte SB = (byte) 0xFA;
	private static final byte SE = (byte) 0xF0;

	// Telnet options
	private static final byte OPT_BINARY = (byte) 0x00;
	private static final byte OPT_TERMINAL_TYPE = (byte) 0x18;
	private static final byte OPT_EOR = (byte) 0x19;
	private static final byte OPT_TN3270E = (byte) 0x28;

	// TN3270E header
	private static final byte TN3270E_DT_3270_DATA = 0x00;
	private static final byte TN3270E_DT_SCS_DATA = 0x01;
	private static final byte TN3270E_DT_RESPONSE = 0x02;
	private static final byte TN3270E_DT_BIND_IMAGE = 0x03;
	private static final byte TN3270E_DT_UNBIND = 0x04;

	// 3270 Commands
	// CCWs with "high" bits are for Remote attached 3270 devices
	private static final byte CMD_WRITE_01 = (byte) 0x01;
	private static final byte CMD_WRITE_F1 = (byte) 0xF1;
	private static final byte CMD_ERASE_WRITE_05 = (byte) 0x05;
	private static final byte CMD_ERASE_WRITE_F5 = (byte) 0xF5;
	private static final byte CMD_ERASE_WRITE_ALTERNATE_0D = (byte) 0x0D;
	private static final byte CMD_ERASE_WRITE_ALTERNATE_7E = (byte) 0x7E;
	private static final byte CMD_READ_BUFFER_02 = (byte) 0x02;
	private static final byte CMD_READ_BUFFER_F2 = (byte) 0xF2;
	private static final byte CMD_READ_MODIFIED_06 = (byte) 0x06;
	private static final byte CMD_READ_MODIFIED_F6 = (byte) 0xF6;
	private static final byte CMD_READ_MODIFIED_ALL_0E = (byte) 0x0E;
	private static final byte CMD_READ_MODIFIED_ALL_6E = (byte) 0x6E;
	private static final byte CMD_WSF_11 = (byte) 0x11;
	private static final byte CMD_WSF_F3 = (byte) 0xF3;
	private static final byte CMD_ERASE_ALL_UNPROTECTED_0F = (byte) 0x0F;
	private static final byte CMD_ERASE_ALL_UNPROTECTED_6F = (byte) 0x6F;

	// 3270 Orders
	private static final byte ORDER_SF = (byte) 0x1D;
	private static final byte ORDER_SFE = (byte) 0x29;
	private static final byte ORDER_SA = (byte) 0x28;
	private static final byte ORDER_SBA = (byte) 0x11;
	private static final byte ORDER_IC = (byte) 0x13;
	private static final byte ORDER_RA = (byte) 0x3C;
	private static final byte ORDER_MF = (byte) 0x2C;
	private static final byte ORDER_GE = (byte) 0x08;
	private static final byte ORDER_EUA = (byte) 0x12;
	private static final byte ORDER_FM = (byte) 0x0E;

	// Attribute types for SA/SFE
	private static final byte ATTR_FIELD = (byte) 0xC0;
	private static final byte ATTR_HIGHLIGHTING = (byte) 0x41;
	private static final byte ATTR_FOREGROUND = (byte) 0x42;
	private static final byte ATTR_BACKGROUND = (byte) 0x45;

	// 3270 AIDs
	private static final byte AID_ENTER = (byte) 0x7D;
	private static final byte AID_CLEAR = (byte) 0x6D;
	private static final byte AID_SYSREQ = (byte) 0xF0;
	private static final byte AID_ATTN   = (byte) 0x6A;
	private static final byte AID_CURSOR_SELECT = (byte) 0x7E;
	private static final byte AID_PA1 = (byte) 0x6C;
	private static final byte AID_PA2 = (byte) 0x6E;
	private static final byte AID_PA3 = (byte) 0x6B;
	private static final byte AID_PF1 = (byte) 0xF1;
	private static final byte AID_PF2 = (byte) 0xF2;
	private static final byte AID_PF3 = (byte) 0xF3;
	private static final byte AID_PF4 = (byte) 0xF4;
	private static final byte AID_PF5 = (byte) 0xF5;
	private static final byte AID_PF6 = (byte) 0xF6;
	private static final byte AID_PF7 = (byte) 0xF7;
	private static final byte AID_PF8 = (byte) 0xF8;
	private static final byte AID_PF9 = (byte) 0xF9;
	private static final byte AID_PF10 = (byte) 0x7A;
	private static final byte AID_PF11 = (byte) 0x7B;
	private static final byte AID_PF12 = (byte) 0x7C;
	private static final byte AID_PF13 = (byte) 0xC1;
	private static final byte AID_PF14 = (byte) 0xC2;
	private static final byte AID_PF15 = (byte) 0xC3;
	private static final byte AID_PF16 = (byte) 0xC4;
	private static final byte AID_PF17 = (byte) 0xC5;
	private static final byte AID_PF18 = (byte) 0xC6;
	private static final byte AID_PF19 = (byte) 0xC7;
	private static final byte AID_PF20 = (byte) 0xC8;
	private static final byte AID_PF21 = (byte) 0xC9;
	private static final byte AID_PF22 = (byte) 0x4A;
	private static final byte AID_PF23 = (byte) 0x4B;
	private static final byte AID_PF24 = (byte) 0x4C;

	// PF1–PF24 for keyboard panel
	private static final byte[] PF_AID = new byte[] {
		AID_PF1, AID_PF2, AID_PF3, AID_PF4, AID_PF5, AID_PF6,
		AID_PF7, AID_PF8, AID_PF9, AID_PF10, AID_PF11, AID_PF12,
		AID_PF13, AID_PF14, AID_PF15, AID_PF16, AID_PF17, AID_PF18,
		AID_PF19, AID_PF20, AID_PF21, AID_PF22, AID_PF23, AID_PF24
	};

	// WCC bits
	private static final byte WCC_RESET = (byte) 0x40;
	private static final byte WCC_ALARM = (byte) 0x04;
	private static final byte WCC_RESET_MDT = (byte) 0x01;

	// Components
	private TerminalCanvas canvas;
	private StatusBar statusBar;
	private ModernKeyboardPanel keyboardPanel;
	private Timer blinkTimer;

	// Connection
	private Socket socket;
	private InputStream input;
	private OutputStream output;
	private volatile boolean connected = false;
	private Thread readerThread;
	private boolean useTLS = false;

	// Terminal state
	private String model = "3278-2";
	private int rows;
	private int cols;
	private int primaryRows;
	private int primaryCols;
	private int alternateRows;
	private int alternateCols;
	private boolean useAlternateSize = false;
	private char[] buffer;
	private byte[] attributes;
	private byte[] extendedColors;
	private byte[] highlighting;
	private int cursorPos = 0;
	private boolean insertMode = false;
	private boolean keyboardLocked = false;
	private byte lastAID = AID_ENTER;
	// Reply mode flags recorded from host (bitfield or structured)
	private int replyModeFlags = 0;

	private enum ReplyMode {
    	FIELD,
    	EXTENDED_FIELD,
    	CHARACTER
	}

// Current reply mode selected by host (default to FIELD for compatibility)
private ReplyMode currentReplyMode = ReplyMode.FIELD;

	// TN3270E state
	private boolean tn3270eMode = false;
	private boolean tn3270eNegotiationComplete = false;
	private boolean tn3270eOffered = false;
	private boolean tn3270eAttempted = false;
	private boolean tn3270eFailed = false;

	// File Transfer
	private static final byte AID_STRUCTURED_FIELD = (byte) 0x88;

	// WSF Structured Field IDs
	private static final byte SFID_DATA_CHAIN = (byte) 0xD0;
	private static final byte SFID_INBOUND_3270DS = (byte) 0x61;

	// Structured Field IDs (verify exact values from manuals)
private static final byte SF_ID_SET_REPLY_MODE = (byte)0x09;   
private static final byte SF_ID_READ_PARTITION_QUERY_LIST = (byte)0xA1; // placeholder

// Common SF tags we will check for
private static final byte SF_SUBTYPE_QUERY_LIST = (byte)0x01;
private static final byte SF_SUBTYPE_REPLY = (byte)0x02;

	// Data Chain operations
	private static final byte DC_OPEN = 0x00;
	private static final byte DC_CLOSE = 0x41;
	private static final byte DC_SET_CURSOR = 0x45;
	private static final byte DC_GET = 0x46;
	private static final byte DC_INSERT = 0x47;

	// Response codes
	private static final byte RESP_POSITIVE = 0x09;
	private static final byte RESP_NEGATIVE = 0x08;

	// File transfer state
	private enum FileTransferState {
		IDLE, OPEN_SENT, TRANSFER_IN_PROGRESS, CLOSE_SENT, ERROR
	}

	private FileTransferState ftState = FileTransferState.IDLE;
	private int blockSequence = 0;
	private FileOutputStream downloadStream = null;
	private FileInputStream uploadStream = null;
	private String currentFilename = null;

	private enum FileTransferDirection {
		UPLOAD, // Host -> PC
		DOWNLOAD // PC -> Host
	}

	// Add host type enum
	private enum HostType {
		TSO, CMS
	}

	private HostType hostType = HostType.CMS; // Default

	private FileTransferDirection ftDirection = FileTransferDirection.DOWNLOAD;
	private File currentFile = null;
	private boolean ftIsText = true; // true for TEXT/ASCII, false for BINARY
	private boolean ftIsMessage = false;
	private boolean ftHadSuccessfulTransfer = false;

	// Add File menu and transfer dialog
	private MenuBar menuBar;
	private Menu fileMenu;

	// Add a progress dialog for file transfers
	private Dialog progressDialog = null;
	private Label progressLabel = null;
	private Label statusLabel = null;
	private Button cancelTransferButton = null;

	// EBCDIC to ASCII translation table (Code Page 037)
	private static final char[] EBCDIC_TO_ASCII = new char[256];
	private static final byte[] ASCII_TO_EBCDIC = new byte[256];
	private static final char[] EBCDIC_TO_APL = new char[256];
	private char[] currentCharSet = EBCDIC_TO_ASCII;

	private MenuItem uploadIndicator;
	private MenuItem downloadIndicator;
	private MenuItem sessionIndicator;

	// Keyboard remapping
	private Map<Integer, KeyMapping> keyMap = new HashMap<>();

	// Keymap persistence
	private static final String KEYMAP_FILE = System.getProperty("user.home") + File.separator + ".tn3270keymap";

	private void saveKeyMappings() {
		try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(KEYMAP_FILE))) {
			out.writeObject(new HashMap<>(keyMap));
			System.out.println("Keymaps saved to " + KEYMAP_FILE);
		} catch (IOException e) {
			System.err.println("Could not save keymaps: " + e.getMessage());
		}
	}

	@SuppressWarnings("unchecked")
	private void loadKeyMappings() {
		File file = new File(KEYMAP_FILE);
		if (!file.exists()) {
			return;
		}

		try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
			Map<Integer, KeyMapping> loaded = (Map<Integer, KeyMapping>) in.readObject();
			keyMap.clear();
			keyMap.putAll(loaded);
			System.out.println("Keymaps loaded from " + KEYMAP_FILE);
		} catch (IOException | ClassNotFoundException e) {
			System.err.println("Could not load keymaps: " + e.getMessage());
		}
	}

// Make KeyMapping serializable
	private static class KeyMapping implements java.io.Serializable {
		private static final long serialVersionUID = 1L;
		char character;
		Byte aid;
		String description;

		KeyMapping(char character, String description) {
			this.character = character;
			this.aid = null;
			this.description = description;
		}

		KeyMapping(byte aid, String description) {
			this.character = '\0';
			this.aid = aid;
			this.description = description;
		}
	}

// Update initializeKeyMappings to load saved mappings
	private void initializeKeyMappings() {
		// Default mappings
		keyMap.put(KeyEvent.VK_BACK_QUOTE, new KeyMapping('¬', "Not sign"));
		keyMap.put(KeyEvent.VK_BACK_SLASH, new KeyMapping('|', "Pipe"));

		// Try to load saved mappings
		loadKeyMappings();
	}

	// Selection state
	private boolean selecting = false;
	private int selectionStart = -1;
	private int selectionEnd = -1;
	private Point dragStart = null;

	// Color scheme settings (instance-specific)
	private Color screenBackground = Color.BLACK;
	private Color defaultForeground = Color.GREEN;
	private Color cursorColor = Color.WHITE;
	private Color[] colors;

	// Color scheme presets (static - shared across all instances)
	private static final Map<String, ColorScheme> COLOR_SCHEMES = new HashMap<>();

	private static class ColorScheme {
		Color background;
		Color defaultFg;
		Color cursor;
		Color[] colors;

		ColorScheme(Color bg, Color defaultFg, Color cursor, Color[] colors) {
			this.background = bg;
			this.defaultFg = defaultFg;
			this.cursor = cursor;
			this.colors = colors;
		}
	}

	private static final String PROFILES_FILE = System.getProperty("user.home") + File.separator + ".tn3270profiles";
	private static Map<String, ConnectionProfile> savedProfiles = new HashMap<>();

	private static class ConnectionProfile {
		String name;
		String hostname;
		int port;
		String model;
		boolean useTLS;

		ConnectionProfile(String name, String hostname, int port, String model, boolean useTLS) {
			this.name = name;
			this.hostname = hostname;
			this.port = port;
			this.model = model;
			this.useTLS = useTLS;
		}

		@Override
		public String toString() {
			return name + " (" + hostname + ":" + port + ")";
		}
	}

	static {
		loadProfiles();
	}

	static {
		// Initialize with nulls
		Arrays.fill(EBCDIC_TO_ASCII, '\0');
		Arrays.fill(ASCII_TO_EBCDIC, (byte) 0x00);

		// EBCDIC to ASCII mapping (CP037)
		EBCDIC_TO_ASCII[0x00] = '\0';
		EBCDIC_TO_ASCII[0x0B] = '\u000B';
		EBCDIC_TO_ASCII[0x0C] = '\f';
		EBCDIC_TO_ASCII[0x0D] = '\r';
		EBCDIC_TO_ASCII[0x0E] = '\u000E';
		EBCDIC_TO_ASCII[0x0F] = '\u000F';
		EBCDIC_TO_ASCII[0x16] = '\u0016';
		EBCDIC_TO_ASCII[0x25] = '\n';
		EBCDIC_TO_ASCII[0x40] = ' ';
		EBCDIC_TO_ASCII[0x4A] = '[';
		EBCDIC_TO_ASCII[0x4B] = '.';
		EBCDIC_TO_ASCII[0x4C] = '<';
		EBCDIC_TO_ASCII[0x4D] = '(';
		EBCDIC_TO_ASCII[0x4E] = '+';
		EBCDIC_TO_ASCII[0x4F] = '|';
		EBCDIC_TO_ASCII[0x50] = '&';
		EBCDIC_TO_ASCII[0x5A] = '!';
		EBCDIC_TO_ASCII[0x5B] = '$';
		EBCDIC_TO_ASCII[0x5C] = '*';
		EBCDIC_TO_ASCII[0x5D] = ')';
		EBCDIC_TO_ASCII[0x5E] = ';';
		EBCDIC_TO_ASCII[0x5F] = '^';
		EBCDIC_TO_ASCII[0x60] = '-';
		EBCDIC_TO_ASCII[0x61] = '/';
		EBCDIC_TO_ASCII[0x6A] = '|';
		EBCDIC_TO_ASCII[0x6B] = ',';
		EBCDIC_TO_ASCII[0x6C] = '%';
		EBCDIC_TO_ASCII[0x6D] = '_';
		EBCDIC_TO_ASCII[0x6E] = '>';
		EBCDIC_TO_ASCII[0x6F] = '?';
		EBCDIC_TO_ASCII[0x79] = '`';
		EBCDIC_TO_ASCII[0x7A] = ':';
		EBCDIC_TO_ASCII[0x7B] = '#';
		EBCDIC_TO_ASCII[0x7C] = '@';
		EBCDIC_TO_ASCII[0x7D] = '\'';
		EBCDIC_TO_ASCII[0x7E] = '=';
		EBCDIC_TO_ASCII[0x7F] = '"';
		EBCDIC_TO_ASCII[0x80] = ' ';

		// Lowercase letters
		EBCDIC_TO_ASCII[0x81] = 'a';
		EBCDIC_TO_ASCII[0x82] = 'b';
		EBCDIC_TO_ASCII[0x83] = 'c';
		EBCDIC_TO_ASCII[0x84] = 'd';
		EBCDIC_TO_ASCII[0x85] = 'e';
		EBCDIC_TO_ASCII[0x86] = 'f';
		EBCDIC_TO_ASCII[0x87] = 'g';
		EBCDIC_TO_ASCII[0x88] = 'h';
		EBCDIC_TO_ASCII[0x89] = 'i';
		EBCDIC_TO_ASCII[0x91] = 'j';
		EBCDIC_TO_ASCII[0x92] = 'k';
		EBCDIC_TO_ASCII[0x93] = 'l';
		EBCDIC_TO_ASCII[0x94] = 'm';
		EBCDIC_TO_ASCII[0x95] = 'n';
		EBCDIC_TO_ASCII[0x96] = 'o';
		EBCDIC_TO_ASCII[0x97] = 'p';
		EBCDIC_TO_ASCII[0x98] = 'q';
		EBCDIC_TO_ASCII[0x99] = 'r';
		EBCDIC_TO_ASCII[0xA1] = '~';
		EBCDIC_TO_ASCII[0xA2] = 's';
		EBCDIC_TO_ASCII[0xA3] = 't';
		EBCDIC_TO_ASCII[0xA4] = 'u';
		EBCDIC_TO_ASCII[0xA5] = 'v';
		EBCDIC_TO_ASCII[0xA6] = 'w';
		EBCDIC_TO_ASCII[0xA7] = 'x';
		EBCDIC_TO_ASCII[0xA8] = 'y';
		EBCDIC_TO_ASCII[0xA9] = 'z';

		// Uppercase letters
		EBCDIC_TO_ASCII[0xC1] = 'A';
		EBCDIC_TO_ASCII[0xC2] = 'B';
		EBCDIC_TO_ASCII[0xC3] = 'C';
		EBCDIC_TO_ASCII[0xC4] = 'D';
		EBCDIC_TO_ASCII[0xC5] = 'E';
		EBCDIC_TO_ASCII[0xC6] = 'F';
		EBCDIC_TO_ASCII[0xC7] = 'G';
		EBCDIC_TO_ASCII[0xC8] = 'H';
		EBCDIC_TO_ASCII[0xC9] = 'I';
		EBCDIC_TO_ASCII[0xD1] = 'J';
		EBCDIC_TO_ASCII[0xD2] = 'K';
		EBCDIC_TO_ASCII[0xD3] = 'L';
		EBCDIC_TO_ASCII[0xD4] = 'M';
		EBCDIC_TO_ASCII[0xD5] = 'N';
		EBCDIC_TO_ASCII[0xD6] = 'O';
		EBCDIC_TO_ASCII[0xD7] = 'P';
		EBCDIC_TO_ASCII[0xD8] = 'Q';
		EBCDIC_TO_ASCII[0xD9] = 'R';
		EBCDIC_TO_ASCII[0xE2] = 'S';
		EBCDIC_TO_ASCII[0xE3] = 'T';
		EBCDIC_TO_ASCII[0xE4] = 'U';
		EBCDIC_TO_ASCII[0xE5] = 'V';
		EBCDIC_TO_ASCII[0xE6] = 'W';
		EBCDIC_TO_ASCII[0xE7] = 'X';
		EBCDIC_TO_ASCII[0xE8] = 'Y';
		EBCDIC_TO_ASCII[0xE9] = 'Z';

		// Numbers
		EBCDIC_TO_ASCII[0xF0] = '0';
		EBCDIC_TO_ASCII[0xF1] = '1';
		EBCDIC_TO_ASCII[0xF2] = '2';
		EBCDIC_TO_ASCII[0xF3] = '3';
		EBCDIC_TO_ASCII[0xF4] = '4';
		EBCDIC_TO_ASCII[0xF5] = '5';
		EBCDIC_TO_ASCII[0xF6] = '6';
		EBCDIC_TO_ASCII[0xF7] = '7';
		EBCDIC_TO_ASCII[0xF8] = '8';
		EBCDIC_TO_ASCII[0xF9] = '9';

		// Additional characters
		EBCDIC_TO_ASCII[0xBA] = '[';
		EBCDIC_TO_ASCII[0xBB] = ']';
		EBCDIC_TO_ASCII[0xC0] = '{';
		EBCDIC_TO_ASCII[0xD0] = '}';
		EBCDIC_TO_ASCII[0xE0] = '\\';

		// Build reverse mapping
		ASCII_TO_EBCDIC[' '] = 0x40;
		ASCII_TO_EBCDIC['\0'] = 0x00;
		for (int i = 0; i < 256; i++) {
			char c = EBCDIC_TO_ASCII[i];
			if (c > 0 && c < 256 && c != ' ' && c != '\0') {
				ASCII_TO_EBCDIC[c] = (byte) i;
			}
		}

		// Initialize color schemes
		initializeColorSchemes();
	}

	static {
		// Initialize APL character set
		System.arraycopy(EBCDIC_TO_ASCII, 0, EBCDIC_TO_APL, 0, 256);

		// Override with APL/box-drawing characters
		EBCDIC_TO_APL[0xAD] = '┌'; // Top-left 'E'
		EBCDIC_TO_APL[0xC5] = '┌'; // Top-left 'E' (corrected)
		EBCDIC_TO_APL[0xAE] = '┐'; // Top-right 'N'
		EBCDIC_TO_APL[0xD5] = '┐'; // Top-right 'N' (corrected)
		EBCDIC_TO_APL[0xBD] = '└'; // Bottom-left 'D'
		EBCDIC_TO_APL[0xC4] = '└'; // Bottom-left 'D' (corrected)
		EBCDIC_TO_APL[0xBE] = '┘'; // Bottom-right 'M'
		EBCDIC_TO_APL[0xD4] = '┘'; // Bottom-right 'M' (corrected)
		// EBCDIC_TO_APL[0xBF] = '├'; // Left T \u22a2
		EBCDIC_TO_APL[0xC6] = '├'; // Left T \u22a2 (corrected)
		// EBCDIC_TO_APL[0xC6] = '┤'; // Right T \u22a3
		EBCDIC_TO_APL[0xD6] = '┤'; // Right T 'O' (corrected) \u22a3
		EBCDIC_TO_APL[0xD7] = '┬'; // Top T \u22a4
		// EBCDIC_TO_APL[0xD8] = '┴'; // Bottom T \u22a5
		EBCDIC_TO_APL[0xC7] = '┴'; // Bottom T \u22a5 (corrected)
		// EBCDIC_TO_APL[0xCE] = '┼'; // Cross
		EBCDIC_TO_APL[0xD3] = '┼'; // Cross 'L' (corrected)
		// EBCDIC_TO_APL[0xA2] = '─'; // Horizontal '-' \u2500 or \u2501
		// I think \u2501 might be too "thick":
		EBCDIC_TO_APL[0xA2] = '\u2500'; // Horizontal '-' \u2500 or \u2501
		// EBCDIC_TO_APL[0x85] = '│'; // Vertical '|'. \u2223 or \u2502
		EBCDIC_TO_APL[0x85] = '\u2502'; // Vertical '|'. \u2223 or \u2502
		// EBCDIC_TO_APL[0xA3] = '\u2022'; // 't' should be a bullet \u25cf is small
		EBCDIC_TO_APL[0xA3] = '\u25cf'; // 't' should be a bullet (black circle) or \u2b24?
		// Add more APL characters as needed
	}

	// Add this translation table as a static field
	private static final byte[] ADDRESS_TABLE = { (byte) 0x40, (byte) 0xC1, (byte) 0xC2, (byte) 0xC3, (byte) 0xC4,
			(byte) 0xC5, (byte) 0xC6, (byte) 0xC7, // 0x00-0x07
			(byte) 0xC8, (byte) 0xC9, (byte) 0x4A, (byte) 0x4B, (byte) 0x4C, (byte) 0x4D, (byte) 0x4E, (byte) 0x4F, // 0x08-0x0F
			(byte) 0x50, (byte) 0xD1, (byte) 0xD2, (byte) 0xD3, (byte) 0xD4, (byte) 0xD5, (byte) 0xD6, (byte) 0xD7, // 0x10-0x17
			(byte) 0xD8, (byte) 0xD9, (byte) 0x5A, (byte) 0x5B, (byte) 0x5C, (byte) 0x5D, (byte) 0x5E, (byte) 0x5F, // 0x18-0x1F
			(byte) 0x60, (byte) 0x61, (byte) 0xE2, (byte) 0xE3, (byte) 0xE4, (byte) 0xE5, (byte) 0xE6, (byte) 0xE7, // 0x20-0x27
			(byte) 0xE8, (byte) 0xE9, (byte) 0x6A, (byte) 0x6B, (byte) 0x6C, (byte) 0x6D, (byte) 0x6E, (byte) 0x6F, // 0x28-0x2F
			(byte) 0xF0, (byte) 0xF1, (byte) 0xF2, (byte) 0xF3, (byte) 0xF4, (byte) 0xF5, (byte) 0xF6, (byte) 0xF7, // 0x30-0x37
			(byte) 0xF8, (byte) 0xF9, (byte) 0x7A, (byte) 0x7B, (byte) 0x7C, (byte) 0x7D, (byte) 0x7E, (byte) 0x7F // 0x38-0x3F
	};

	public TN3270Emulator(String modelName) {
		super("TN3270 Emulator");

		this.model = modelName;
		Dimension dim = MODELS.get(model);

		if (dim == null) {
			// Fallback to 3278-2
			System.err.println("Unknown model: " + model + ", using 3278-2");
			this.model = "3278-2";
			dim = MODELS.get(this.model);
		}

		// For models like 3279-3, primary is 24x80, alternate is 32x80
		if (!model.endsWith("-2")) {
			primaryRows = 24;
			primaryCols = 80;
			alternateRows = dim.height;
			alternateCols = dim.width;
		} else {
			// Other models use same size for both
			primaryRows = dim.height;
			primaryCols = dim.width;
			alternateRows = dim.height;
			alternateCols = dim.width;
		}

		// Start with alternate size
		rows = alternateRows;
		cols = alternateCols;
		useAlternateSize = true;

		colors = new Color[] { Color.BLACK, // 0 - Default
				Color.BLUE, // 1           Or maybe: Color.DODGERBLUE
				Color.RED, // 2
				Color.MAGENTA, // 3                  Color.PINK
				Color.GREEN, // 4                    Color.LIME
				Color.CYAN, // 5                     Color.TURQUOISE
				Color.YELLOW, // 6
				Color.WHITE // 7                     Color.WHITESMOKE
		};

		int maxSize = Math.max(primaryRows * primaryCols, alternateRows * alternateCols);
		buffer = new char[maxSize];
		attributes = new byte[maxSize];
		extendedColors = new byte[maxSize];
		highlighting = new byte[maxSize];
		clearScreen();

		setLayout(new BorderLayout());

		// RibbonToolbar ribbon = new RibbonToolbar(this);
		EnhancedRibbonToolbar ribbon = new EnhancedRibbonToolbar(this);
		add(ribbon, BorderLayout.NORTH);

		canvas = new TerminalCanvas();
		add(canvas, BorderLayout.CENTER);
		canvas.updateSize();

		// Create menu bar
		createMenuBar();

		Panel bottomPanel = new Panel(new BorderLayout());

		statusBar = new StatusBar();
		bottomPanel.add(statusBar, BorderLayout.NORTH);

		// keyboardPanel = new KeyboardPanel();
		keyboardPanel = new ModernKeyboardPanel(this);
		
		bottomPanel.add(keyboardPanel, BorderLayout.SOUTH);

		add(bottomPanel, BorderLayout.SOUTH);

		canvas.addKeyListener(this);

		initializeKeyMappings();

		// Start blink timer
		blinkTimer = new Timer(500, e -> canvas.repaint());
		blinkTimer.start();

		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				disconnect();
				dispose(); // Just dispose this window, don't exit the application

				// Check if this is the last window
				Frame[] frames = Frame.getFrames();
				int visibleCount = 0;
				for (Frame f : frames) {
					if (f.isVisible() && f != TN3270Emulator.this) {
						visibleCount++;
					}
				}

				// If no other windows are visible, exit
				if (visibleCount == 0) {
					System.exit(0);
				}
			}
		});

		pack();
		setVisible(true);
		canvas.requestFocus();
	}

	public void setUseTLS(boolean useTLS) {
		this.useTLS = useTLS;
	}

	public TN3270Emulator() {
		this("3279-3"); // Default model
	}

	private static void loadProfiles() {
		File file = new File(PROFILES_FILE);
		if (!file.exists()) {
			// Create default profiles
			savedProfiles.put("Local z/VM", new ConnectionProfile("Local z/VM", "localhost", 23, "3279-3", false));
			savedProfiles.put("Local z/OS", new ConnectionProfile("Local z/OS", "localhost", 23, "3279-3", false));
			return;
		}

		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split("\\|");
				if (parts.length == 5) {
					String name = parts[0];
					savedProfiles.put(name, new ConnectionProfile(name, parts[1], Integer.parseInt(parts[2]), parts[3],
							Boolean.parseBoolean(parts[4])));
				}
			}
		} catch (IOException e) {
			System.err.println("Could not load profiles: " + e.getMessage());
		}
	}

	private static void saveProfiles() {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(PROFILES_FILE))) {
			for (ConnectionProfile profile : savedProfiles.values()) {
				writer.write(profile.name + "|" + profile.hostname + "|" + profile.port + "|" + profile.model + "|"
						+ profile.useTLS);
				writer.newLine();
			}
		} catch (IOException e) {
			System.err.println("Could not save profiles: " + e.getMessage());
		}
	}

	private void createMenuBar() {
		menuBar = new MenuBar();

		// ===== FILE MENU =====
		fileMenu = new Menu("File");

		MenuItem newConnItem = new MenuItem("New Connection...");
		newConnItem.setShortcut(new MenuShortcut(KeyEvent.VK_N));
		newConnItem.addActionListener(e -> showConnectionDialog());
		fileMenu.add(newConnItem);

		fileMenu.addSeparator();

		MenuItem uploadItem = new MenuItem("Upload to Host...");
		uploadItem.setShortcut(new MenuShortcut(KeyEvent.VK_U));
		uploadItem.addActionListener(e -> showFileTransferDialog(false));
		fileMenu.add(uploadItem);

		MenuItem downloadItem = new MenuItem("Download from Host...");
		downloadItem.setShortcut(new MenuShortcut(KeyEvent.VK_D));
		downloadItem.addActionListener(e -> showFileTransferDialog(true));
		fileMenu.add(downloadItem);

		fileMenu.addSeparator();

		MenuItem disconnectItem = new MenuItem("Disconnect");
		disconnectItem.addActionListener(e -> disconnect());
		fileMenu.add(disconnectItem);

		MenuItem reconnectItem = new MenuItem("Reconnect");
		reconnectItem.addActionListener(e -> reconnect());
		fileMenu.add(reconnectItem);

		fileMenu.addSeparator();

		MenuItem exitItem = new MenuItem("Exit");
		exitItem.setShortcut(new MenuShortcut(KeyEvent.VK_Q));
		exitItem.addActionListener(e -> {
			disconnect();
			System.exit(0);
		});
		fileMenu.add(exitItem);

		menuBar.add(fileMenu);

		// ===== EDIT MENU =====
		Menu editMenu = new Menu("Edit");

		MenuItem copyItem = new MenuItem("Copy");
		copyItem.setShortcut(new MenuShortcut(KeyEvent.VK_C));
		copyItem.addActionListener(e -> copySelection());
		editMenu.add(copyItem);

		MenuItem pasteItem = new MenuItem("Paste");
		pasteItem.setShortcut(new MenuShortcut(KeyEvent.VK_V));
		pasteItem.addActionListener(e -> pasteFromClipboard());
		editMenu.add(pasteItem);

		editMenu.addSeparator();

		MenuItem selectAllItem = new MenuItem("Select All");
		selectAllItem.setShortcut(new MenuShortcut(KeyEvent.VK_A));
		selectAllItem.addActionListener(e -> selectAll());
		editMenu.add(selectAllItem);

		menuBar.add(editMenu);

		// ===== VIEW MENU =====
		Menu viewMenu = new Menu("View");

		CheckboxMenuItem showKeyboardItem = new CheckboxMenuItem("Show Keyboard Panel", true);
		showKeyboardItem.addItemListener(e -> {
			keyboardPanel.setVisible(showKeyboardItem.getState());
			pack();
		});
		viewMenu.add(showKeyboardItem);

		viewMenu.addSeparator();

		MenuItem fontSizeItem = new MenuItem("Font Size...");
		fontSizeItem.addActionListener(e -> showFontSizeDialog());
		viewMenu.add(fontSizeItem);

		MenuItem colorSchemeItem = new MenuItem("Color Scheme...");
		colorSchemeItem.addActionListener(e -> showColorSchemeDialog());
		viewMenu.add(colorSchemeItem);

		menuBar.add(viewMenu);

		// ===== SETTINGS MENU =====
		Menu settingsMenu = new Menu("Settings");

		MenuItem keyboardMapItem = new MenuItem("Keyboard Mapping...");
		keyboardMapItem.addActionListener(e -> showKeyboardMappingDialog());
		settingsMenu.add(keyboardMapItem);

		MenuItem terminalSettingsItem = new MenuItem("Terminal Settings...");
		terminalSettingsItem.addActionListener(e -> showTerminalSettingsDialog());
		settingsMenu.add(terminalSettingsItem);

		menuBar.add(settingsMenu);

		// ===== HELP MENU =====
		Menu helpMenu = new Menu("Help");

		MenuItem aboutItem = new MenuItem("About");
		aboutItem.addActionListener(e -> showAboutDialog());
		helpMenu.add(aboutItem);

		MenuItem keyboardHelpItem = new MenuItem("Keyboard Reference");
		keyboardHelpItem.setShortcut(new MenuShortcut(KeyEvent.VK_F1));
		keyboardHelpItem.addActionListener(e -> showKeyboardReference());
		helpMenu.add(keyboardHelpItem);

		menuBar.add(helpMenu);

		setMenuBar(menuBar);
	}

	private static void initializeColorSchemes() {
		// Green on Black (Classic)
		COLOR_SCHEMES.put("Green on Black (Classic)",
				new ColorScheme(Color.BLACK, Color.GREEN, Color.WHITE, new Color[] { Color.BLACK, // 0 - Default
						Color.BLUE, // 1
						Color.RED, // 2
						Color.MAGENTA, // 3
						Color.GREEN, // 4
						Color.CYAN, // 5
						Color.YELLOW, // 6
						Color.WHITE // 7
				}));

		// White on Black
		COLOR_SCHEMES.put("White on Black", new ColorScheme(Color.BLACK, Color.WHITE, new Color(255, 255, 0), // Yellow
																												// cursor
				new Color[] { Color.BLACK, // 0 - Default
						new Color(100, 149, 237), // 1 - Cornflower Blue
						new Color(255, 99, 71), // 2 - Tomato Red
						new Color(255, 105, 180), // 3 - Hot Pink
						new Color(144, 238, 144), // 4 - Light Green
						new Color(64, 224, 208), // 5 - Turquoise
						new Color(255, 255, 0), // 6 - Yellow
						Color.WHITE // 7
				}));

		// Amber on Black (Old Terminal)
		COLOR_SCHEMES.put("Amber on Black", new ColorScheme(Color.BLACK, new Color(255, 176, 0), // Amber
				new Color(255, 200, 50), // Bright amber cursor
				new Color[] { Color.BLACK, // 0 - Default
						new Color(180, 130, 0), // 1 - Dark amber
						new Color(255, 100, 0), // 2 - Orange-red
						new Color(255, 140, 0), // 3 - Dark orange
						new Color(255, 176, 0), // 4 - Amber
						new Color(255, 200, 50), // 5 - Light amber
						new Color(255, 220, 100), // 6 - Pale amber
						new Color(255, 230, 150) // 7 - Very pale amber
				}));

		// Green on Dark Green (Phosphor)
		COLOR_SCHEMES.put("Green on Dark Green", new ColorScheme(new Color(0, 40, 0), // Very dark green
				new Color(51, 255, 51), // Bright green
				new Color(102, 255, 102), // Light green cursor
				new Color[] { new Color(0, 40, 0), // 0 - Background
						new Color(0, 128, 128), // 1 - Teal
						new Color(0, 200, 0), // 2 - Medium green
						new Color(102, 255, 102), // 3 - Light green
						new Color(51, 255, 51), // 4 - Bright green
						new Color(153, 255, 153), // 5 - Very light green
						new Color(204, 255, 204), // 6 - Pale green
						new Color(230, 255, 230) // 7 - Almost white
				}));

		// IBM 3270 Blue (Classic IBM)
		COLOR_SCHEMES.put("IBM 3270 Blue", new ColorScheme(new Color(0, 0, 64), // Dark blue
				new Color(0, 255, 0), // Bright green
				Color.WHITE, new Color[] { new Color(0, 0, 64), // 0 - Background
						new Color(85, 170, 255), // 1 - Light blue
						new Color(255, 85, 85), // 2 - Light red
						new Color(255, 85, 255), // 3 - Pink/Magenta
						new Color(0, 255, 0), // 4 - Bright green
						new Color(85, 255, 255), // 5 - Cyan
						new Color(255, 255, 85), // 6 - Yellow
						Color.WHITE // 7
				}));

		// Solarized Dark
		COLOR_SCHEMES.put("Solarized Dark", new ColorScheme(new Color(0, 43, 54), // Base03
				new Color(131, 148, 150), // Base0
				new Color(147, 161, 161), // Base1 cursor
				new Color[] { new Color(0, 43, 54), // 0 - Base03
						new Color(38, 139, 210), // 1 - Blue
						new Color(220, 50, 47), // 2 - Red
						new Color(211, 54, 130), // 3 - Magenta
						new Color(133, 153, 0), // 4 - Green
						new Color(42, 161, 152), // 5 - Cyan
						new Color(181, 137, 0), // 6 - Yellow
						new Color(238, 232, 213) // 7 - Base2
				}));
	}

// ===== EDIT MENU METHODS =====

	private void copySelection() {
		if (selectionStart < 0 || selectionEnd < 0) {
			Toolkit.getDefaultToolkit().beep();
			return;
		}

		int start = Math.min(selectionStart, selectionEnd);
		int end = Math.max(selectionStart, selectionEnd);

		StringBuilder sb = new StringBuilder();
		int startRow = start / cols;
		int endRow = end / cols;

		for (int row = startRow; row <= endRow; row++) {
			int rowStart = (row == startRow) ? start : row * cols;
			int rowEnd = (row == endRow) ? end : (row + 1) * cols - 1;

			for (int pos = rowStart; pos <= rowEnd && pos < buffer.length; pos++) {
				char c = buffer[pos];
				if (c == '\0')
					c = ' ';
				if (!isFieldStart(pos)) {
					sb.append(c);
				}
			}

			if (row < endRow) {
				sb.append('\n');
			}
		}

		String text = sb.toString();
		String[] lines = text.split("\n");
		StringBuilder cleaned = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			cleaned.append(lines[i].replaceAll("\\s+$", ""));
			if (i < lines.length - 1) {
				cleaned.append('\n');
			}
		}

		try {
			Toolkit toolkit = Toolkit.getDefaultToolkit();
			Clipboard clipboard = toolkit.getSystemClipboard();
			StringSelection selection = new StringSelection(cleaned.toString());
			clipboard.setContents(selection, null);

			statusBar.setStatus("Copied " + (end - start + 1) + " characters");
			clearSelection();

		} catch (Exception ex) {
			statusBar.setStatus("Copy failed: " + ex.getMessage());
			ex.printStackTrace();
		}
	}

	private void pasteFromClipboard() {
		if (keyboardLocked || !connected) {
			Toolkit.getDefaultToolkit().beep();
			return;
		}

		try {
			Toolkit toolkit = Toolkit.getDefaultToolkit();
			Clipboard clipboard = toolkit.getSystemClipboard();
			String text = (String) clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor);

			if (text == null || text.isEmpty()) {
				return;
			}

			for (char c : text.toCharArray()) {
				if (c == '\n' || c == '\r') {
					tabToNextField();
					continue;
				}

				if (c < 32 || c > 126) {
					continue;
				}

				if (!isProtected(cursorPos)) {
					buffer[cursorPos] = c;
					setModified(cursorPos);

					int nextPos = (cursorPos + 1) % (rows * cols);
					moveCursor(1);

					if (isFieldStart(nextPos)) {
						tabToNextField();
					}
				} else {
					tabToNextField();

					if (!isProtected(cursorPos)) {
						buffer[cursorPos] = c;
						setModified(cursorPos);
						moveCursor(1);
					}
				}
			}

			canvas.repaint();
			statusBar.setStatus("Pasted " + text.length() + " characters");

		} catch (Exception ex) {
			statusBar.setStatus("Paste failed: " + ex.getMessage());
			ex.printStackTrace();
		}
	}

	private void selectAll() {
		selectionStart = 0;
		selectionEnd = buffer.length - 1;
		canvas.repaint();
	}

	private void handleMousePress(MouseEvent e) {
		int pos = screenPositionFromMouse(e.getX(), e.getY());
		if (pos >= 0 && pos < buffer.length) {
			selecting = true;
			selectionStart = pos;
			selectionEnd = pos;
			dragStart = e.getPoint();
			canvas.repaint();
		}
	}

	private void handleMouseDrag(MouseEvent e) {
		if (selecting) {
			int pos = screenPositionFromMouse(e.getX(), e.getY());
			if (pos >= 0 && pos < buffer.length) {
				selectionEnd = pos;
				canvas.repaint();
			}
		}
	}

	private void handleMouseRelease(MouseEvent e) {
		if (selecting) {
			selecting = false;
			// Normalize selection so start < end
			if (selectionStart > selectionEnd) {
				int temp = selectionStart;
				selectionStart = selectionEnd;
				selectionEnd = temp;
			}
		}
	}

	private void selectWord(MouseEvent e) {
		int pos = screenPositionFromMouse(e.getX(), e.getY());
		if (pos >= 0 && pos < buffer.length) {
			// Find word boundaries
			int start = pos;
			int end = pos;

			// Expand left
			while (start > 0 && isWordChar(buffer[start - 1])) {
				start--;
			}

			// Expand right
			while (end < buffer.length - 1 && isWordChar(buffer[end + 1])) {
				end++;
			}

			selectionStart = start;
			selectionEnd = end;
			canvas.repaint();
		}
	}

	private void selectLine(MouseEvent e) {
		int pos = screenPositionFromMouse(e.getX(), e.getY());
		if (pos >= 0 && pos < buffer.length) {
			int row = pos / cols;
			selectionStart = row * cols;
			selectionEnd = (row + 1) * cols - 1;
			canvas.repaint();
		}
	}

	private boolean isWordChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
	}

	private int screenPositionFromMouse(int x, int y) {
		int col = (x - 5) / canvas.charWidth;
		int row = (y - 5) / canvas.charHeight;

		if (col < 0)
			col = 0;
		if (col >= cols)
			col = cols - 1;
		if (row < 0)
			row = 0;
		if (row >= rows)
			row = rows - 1;

		return row * cols + col;
	}

	private void clearSelection() {
		selectionStart = -1;
		selectionEnd = -1;
		canvas.repaint();
	}

// ===== VIEW MENU METHODS =====

	private void showFontSizeDialog() {
		Dialog dialog = new Dialog(this, "Font Size", true);
		dialog.setLayout(new GridBagLayout());

		// Add window listener
		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(10, 10, 10, 10);

		gbc.gridx = 0;
		gbc.gridy = 0;
		dialog.add(new Label("Font Size:"), gbc);

		gbc.gridx = 1;
		Choice sizeChoice = new Choice();
		for (int size = 8; size <= 24; size += 2) {
			sizeChoice.add(String.valueOf(size));
		}
		sizeChoice.select("14"); // Default
		dialog.add(sizeChoice, gbc);

		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 2;
		gbc.anchor = GridBagConstraints.CENTER;
		Panel buttonPanel = new Panel(new FlowLayout());

		Button okButton = new Button("OK");
		Button cancelButton = new Button("Cancel");

		okButton.addActionListener(e -> {
			int newSize = Integer.parseInt(sizeChoice.getSelectedItem());
			canvas.terminalFont = new Font("Monospaced", Font.PLAIN, newSize);
			canvas.setFont(canvas.terminalFont);
			FontMetrics fm = canvas.getFontMetrics(canvas.terminalFont);
			canvas.charWidth = fm.charWidth('M');
			canvas.charHeight = fm.getHeight();
			canvas.updateSize();
			canvas.repaint();
			dialog.dispose();
		});

		cancelButton.addActionListener(e -> dialog.dispose());

		buttonPanel.add(okButton);
		buttonPanel.add(cancelButton);
		dialog.add(buttonPanel, gbc);

		dialog.pack();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	private void showColorSchemeDialog() {
		Dialog dialog = new Dialog(this, "Color Scheme", true);
		dialog.setLayout(new GridBagLayout());

		// Add window listener
		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});

		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.gridwidth = 2;
		dialog.add(new Label("Select Color Scheme:"), gbc);

		gbc.gridy = 1;
		Choice schemeChoice = new Choice();
		schemeChoice.add("Green on Black (Classic)");
		schemeChoice.add("White on Black");
		schemeChoice.add("Amber on Black");
		schemeChoice.add("Green on Dark Green");
		schemeChoice.add("IBM 3270 Blue");
		schemeChoice.add("Solarized Dark");
		schemeChoice.add("Custom...");
		dialog.add(schemeChoice, gbc);

		gbc.gridy = 2;
		gbc.anchor = GridBagConstraints.CENTER;
		Panel buttonPanel = new Panel(new FlowLayout());

		Button okButton = new Button("OK");
		Button cancelButton = new Button("Cancel");

		okButton.addActionListener(e -> {
			String scheme = schemeChoice.getSelectedItem();
			applyColorScheme(scheme);
			dialog.dispose();
		});

		cancelButton.addActionListener(e -> dialog.dispose());

		buttonPanel.add(okButton);
		buttonPanel.add(cancelButton);
		dialog.add(buttonPanel, gbc);

		dialog.pack();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	private void applyColorScheme(String schemeName) {
		ColorScheme scheme = COLOR_SCHEMES.get(schemeName);

		if (scheme == null) {
			// Custom scheme - show color picker dialog
			showCustomColorDialog();
			return;
		}

		// Apply the color scheme
		screenBackground = scheme.background;
		defaultForeground = scheme.defaultFg;
		cursorColor = scheme.cursor;

		// Copy colors to the instance colors array
		System.arraycopy(scheme.colors, 0, colors, 0, Math.min(scheme.colors.length, colors.length));

		// Update canvas
		canvas.setBackground(screenBackground);
		canvas.repaint();

		statusBar.setStatus("Color scheme: " + schemeName);
	}

// ===== SETTINGS MENU METHODS =====

	private void showTerminalSettingsDialog() {
		Dialog dialog = new Dialog(this, "Terminal Settings", true);
		dialog.setLayout(new GridBagLayout());

		// Add window listener
		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		gbc.gridx = 0;
		gbc.gridy = 0;
		dialog.add(new Label("Terminal Model:"), gbc);

		gbc.gridx = 1;
		Choice modelChoice = new Choice();
		modelChoice.add("3278-2 (24x80)");
		modelChoice.add("3279-2 (24x80 Color)");
		modelChoice.add("3278-3 (32x80)");
		modelChoice.add("3279-3 (32x80 Color)");
		modelChoice.add("3278-4 (43x80)");
		modelChoice.add("3278-5 (27x132)");

		// Select current model
		for (int i = 0; i < modelChoice.getItemCount(); i++) {
			if (modelChoice.getItem(i).startsWith(model)) {
				modelChoice.select(i);
				break;
			}
		}
		dialog.add(modelChoice, gbc);

		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 2;
		Checkbox cursorBlinkCheckbox = new Checkbox("Cursor Blink", true);
		dialog.add(cursorBlinkCheckbox, gbc);

		gbc.gridy = 2;
		Checkbox soundCheckbox = new Checkbox("Enable Sound", true);
		dialog.add(soundCheckbox, gbc);

		gbc.gridy = 3;
		Checkbox autoAdvanceCheckbox = new Checkbox("Auto-advance fields", true);
		dialog.add(autoAdvanceCheckbox, gbc);

		gbc.gridy = 4;
		gbc.anchor = GridBagConstraints.CENTER;
		Panel buttonPanel = new Panel(new FlowLayout());

		Button okButton = new Button("OK");
		Button cancelButton = new Button("Cancel");

		okButton.addActionListener(e -> {
			// Apply settings
			String selectedModel = modelChoice.getSelectedItem();
			String newModel = selectedModel.substring(0, 7); // Extract "3278-2" etc.

			if (!newModel.equals(model)) {
				showMessageDialog("Terminal model change will take effect on next connection.", "Settings Saved");
				model = newModel;
			}

			if (!cursorBlinkCheckbox.getState()) {
				blinkTimer.stop();
			} else {
				blinkTimer.start();
			}

			dialog.dispose();
		});

		cancelButton.addActionListener(e -> dialog.dispose());

		buttonPanel.add(okButton);
		buttonPanel.add(cancelButton);
		dialog.add(buttonPanel, gbc);

		dialog.pack();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

// ===== HELP MENU METHODS =====

	private void showAboutDialog() {
		Dialog dialog = new Dialog(this, "About TN3270 Emulator", true);
		dialog.setLayout(new GridBagLayout());

		// Add window listener
		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(10, 20, 10, 20);
		gbc.gridx = 0;

		gbc.gridy = 0;
		Label titleLabel = new Label("TN3270 Terminal Emulator");
		titleLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
		dialog.add(titleLabel, gbc);

		gbc.gridy = 1;
		dialog.add(new Label("Version 1.0"), gbc);

		gbc.gridy = 2;
		dialog.add(new Label(" "), gbc); // Spacer

		gbc.gridy = 3;
		dialog.add(new Label("A modern TN3270/TN3270E terminal emulator"), gbc);

		gbc.gridy = 4;
		dialog.add(new Label("with support for:"), gbc);

		gbc.gridy = 5;
		gbc.insets = new Insets(2, 40, 2, 20);
		dialog.add(new Label("• Multiple terminal models"), gbc);

		gbc.gridy = 6;
		dialog.add(new Label("• TLS/SSL encryption"), gbc);

		gbc.gridy = 7;
		dialog.add(new Label("• IND$FILE file transfer"), gbc);

		gbc.gridy = 8;
		dialog.add(new Label("• Extended colors and highlighting"), gbc);

		gbc.gridy = 9;
		gbc.insets = new Insets(10, 20, 10, 20);
		dialog.add(new Label(" "), gbc); // Spacer

		gbc.gridy = 10;
		dialog.add(new Label("© 2024 - All rights reserved"), gbc);

		gbc.gridy = 11;
		gbc.anchor = GridBagConstraints.CENTER;
		Button okButton = new Button("OK");
		okButton.addActionListener(e -> dialog.dispose());
		dialog.add(okButton, gbc);

		dialog.pack();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	private void showKeyboardReference() {
		Dialog dialog = new Dialog(this, "Keyboard Reference", false);
		dialog.setLayout(new BorderLayout(10, 10));

		// Add window listener
		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});

		TextArea textArea = new TextArea("", 25, 60, TextArea.SCROLLBARS_VERTICAL_ONLY);
		textArea.setEditable(false);
		textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

		StringBuilder help = new StringBuilder();
		help.append("TN3270 KEYBOARD REFERENCE\n");
		help.append("========================\n\n");

		help.append("FUNCTION KEYS:\n");
		help.append("  F1-F12         PF1-PF12 keys\n");
		help.append("  Enter          Send data to host\n");
		help.append("  Escape         Clear screen\n");
		help.append("  Insert         Toggle Insert/Replace mode\n\n");

		help.append("NAVIGATION:\n");
		help.append("  Arrow Keys     Move cursor\n");
		help.append("  Tab            Next unprotected field\n");
		help.append("  Shift+Tab      Previous unprotected field\n");
		help.append("  Home           Move to top-left\n");
		help.append("  Backspace      Delete previous character\n\n");

		help.append("EDITING:\n");
		help.append("  Ctrl+C         Copy selection\n");
		help.append("  Ctrl+V         Paste text\n");
		help.append("  Ctrl+A         Select all\n\n");

		help.append("SELECTION:\n");
		help.append("  Click+Drag     Select text\n");
		help.append("  Double-Click   Select word\n");
		help.append("  Triple-Click   Select line\n\n");

		help.append("FILE TRANSFER:\n");
		help.append("  Ctrl+U         Upload to host\n");
		help.append("  Ctrl+D         Download from host\n\n");

		help.append("OTHER:\n");
		help.append("  Ctrl+N         New connection\n");
		help.append("  Ctrl+Q         Quit\n");
		help.append("  F1             This help screen\n\n");

		help.append("ATTENTION KEYS:\n");
		help.append("  Use keyboard panel buttons for:\n");
		help.append("    PA1, PA2, PA3\n");
		help.append("    CLEAR\n");
		help.append("    RESET\n");
		help.append("    ERASE EOF, ERASE EOL\n");

		textArea.setText(help.toString());

		dialog.add(textArea, BorderLayout.CENTER);

		Panel buttonPanel = new Panel(new FlowLayout());
		Button closeButton = new Button("Close");
		closeButton.addActionListener(e -> dialog.dispose());
		buttonPanel.add(closeButton);
		dialog.add(buttonPanel, BorderLayout.SOUTH);

		dialog.pack();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

// ===== CONNECTION DIALOG =====
	private static void showConnectionDialog() {
		Dialog dialog = new Dialog((Frame) null, "Connect to Host", true);
		dialog.setLayout(new BorderLayout(10, 10));

		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});

		// Top panel - Profile selection
		Panel topPanel = new Panel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		gbc.gridx = 0;
		gbc.gridy = 0;
		topPanel.add(new Label("Saved Profiles:"), gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		Choice profileChoice = new Choice();
		profileChoice.add("(New Connection)");
		for (String profileName : savedProfiles.keySet()) {
			profileChoice.add(profileName);
		}
		topPanel.add(profileChoice, gbc);

		gbc.gridx = 2;
		gbc.weightx = 0;
		Button manageButton = new Button("Manage...");
		topPanel.add(manageButton, gbc);

		dialog.add(topPanel, BorderLayout.NORTH);

		// Center panel - Connection details
		Panel centerPanel = new Panel(new GridBagLayout());
		gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		// Hostname
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 0;
		centerPanel.add(new Label("Hostname:"), gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;
		gbc.gridwidth = 2;
		TextField hostnameField = new TextField("localhost", 30);
		centerPanel.add(hostnameField, gbc);

		// Port
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.weightx = 0;
		gbc.gridwidth = 1;
		centerPanel.add(new Label("Port:"), gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;
		TextField portField = new TextField("23", 10);
		centerPanel.add(portField, gbc);

		// Model
		gbc.gridx = 0;
		gbc.gridy = 2;
		gbc.weightx = 0;
		centerPanel.add(new Label("Terminal Model:"), gbc);
		gbc.gridx = 1;
		gbc.gridwidth = 2;
		gbc.weightx = 1.0;
		Choice modelChoice = new Choice();
		modelChoice.add("3278-2 (24x80)");
		modelChoice.add("3279-2 (24x80 Color)");
		modelChoice.add("3278-3 (32x80)");
		modelChoice.add("3279-3 (32x80 Color)");
		modelChoice.add("3278-4 (43x80)");
		modelChoice.add("3278-5 (27x132)");
		modelChoice.select(3);
		centerPanel.add(modelChoice, gbc);

		// Options
		gbc.gridx = 0;
		gbc.gridy = 3;
		gbc.gridwidth = 3;
		Checkbox tlsCheckbox = new Checkbox("Use TLS/SSL encryption", false);
		centerPanel.add(tlsCheckbox, gbc);

		dialog.add(centerPanel, BorderLayout.CENTER);

		// Bottom panel - Buttons
		Panel bottomPanel = new Panel(new FlowLayout(FlowLayout.RIGHT));

		Button saveProfileButton = new Button("Save Profile...");
		Button connectButton = new Button("Connect");
		Button cancelButton = new Button("Cancel");

		bottomPanel.add(saveProfileButton);
		bottomPanel.add(connectButton);
		bottomPanel.add(cancelButton);

		dialog.add(bottomPanel, BorderLayout.SOUTH);

		// Profile selection handler
		profileChoice.addItemListener(e -> {
			String selected = profileChoice.getSelectedItem();
			if (selected.equals("(New Connection)")) {
				hostnameField.setText("localhost");
				portField.setText("23");
				modelChoice.select(3);
				tlsCheckbox.setState(false);
			} else {
				ConnectionProfile profile = savedProfiles.get(selected);
				if (profile != null) {
					hostnameField.setText(profile.hostname);
					portField.setText(String.valueOf(profile.port));
					tlsCheckbox.setState(profile.useTLS);

					// Select the right model
					for (int i = 0; i < modelChoice.getItemCount(); i++) {
						if (modelChoice.getItem(i).startsWith(profile.model)) {
							modelChoice.select(i);
							break;
						}
					}
				}
			}
		});

		// Manage profiles button
		manageButton.addActionListener(e -> {
			dialog.setVisible(false);
			showManageProfilesDialog();
			dialog.setVisible(true);

			// Refresh profile list
			String currentSelection = profileChoice.getSelectedItem();
			profileChoice.removeAll();
			profileChoice.add("(New Connection)");
			for (String profileName : savedProfiles.keySet()) {
				profileChoice.add(profileName);
			}
			profileChoice.select(currentSelection);
		});

		// Save profile button
		saveProfileButton.addActionListener(e -> {
			String name = showInputDialog(dialog, "Enter profile name:", "Save Profile");
			if (name != null && !name.trim().isEmpty()) {
				String hostname = hostnameField.getText().trim();
				int port = Integer.parseInt(portField.getText().trim());
				String modelStr = modelChoice.getSelectedItem();
				String model = modelStr.substring(0, 7).trim();
				boolean useTLS = tlsCheckbox.getState();

				savedProfiles.put(name, new ConnectionProfile(name, hostname, port, model, useTLS));
				saveProfiles();

				// Refresh profile list
				profileChoice.add(name);
				profileChoice.select(name);

				showStaticMessageDialog("Profile saved successfully", "Saved");
			}
		});

		// Connect action
		Runnable connectAction = () -> {
			String hostname = hostnameField.getText().trim();
			if (hostname.isEmpty()) {
				hostname = "localhost";
			}

			int port;
			try {
				port = Integer.parseInt(portField.getText().trim());
			} catch (NumberFormatException ex) {
				port = 23;
			}

			String modelStr = modelChoice.getSelectedItem();
			String model = modelStr.substring(0, 7);

			dialog.dispose();

			TN3270Emulator emulator = new TN3270Emulator(model);
			emulator.setUseTLS(tlsCheckbox.getState());
			emulator.connect(hostname, port);
		};

		connectButton.addActionListener(e -> connectAction.run());
		cancelButton.addActionListener(e -> dialog.dispose());

		// Enter key on fields
		ActionListener enterAction = e -> connectAction.run();
		hostnameField.addActionListener(enterAction);
		portField.addActionListener(enterAction);

		dialog.pack();
		dialog.setLocationRelativeTo(null);
		dialog.setVisible(true);
	}

	private static void showManageProfilesDialog() {
		Dialog dialog = new Dialog((Frame) null, "Manage Profiles", true);
		dialog.setLayout(new BorderLayout(10, 10));

		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});

		// List of profiles
		java.awt.List profileList = new java.awt.List(10, false);
		for (String profileName : savedProfiles.keySet()) {
			profileList.add(profileName);
		}
		dialog.add(profileList, BorderLayout.CENTER);

		// Buttons
		Panel buttonPanel = new Panel(new FlowLayout());
		Button deleteButton = new Button("Delete");
		Button closeButton = new Button("Close");

		deleteButton.addActionListener(e -> {
			String selected = profileList.getSelectedItem();
			if (selected != null) {
				int confirm = showConfirmDialog(dialog, "Delete profile '" + selected + "'?", "Confirm Delete");
				if (confirm == 0) { // Yes
					savedProfiles.remove(selected);
					saveProfiles();
					profileList.remove(selected);
				}
			}
		});

		closeButton.addActionListener(e -> dialog.dispose());

		buttonPanel.add(deleteButton);
		buttonPanel.add(closeButton);
		dialog.add(buttonPanel, BorderLayout.SOUTH);

		dialog.pack();
		dialog.setLocationRelativeTo(null);
		dialog.setVisible(true);
	}

// Helper method for input dialog
	private static String showInputDialog(Dialog parent, String message, String title) {
		Dialog dialog = new Dialog(parent, title, true);
		dialog.setLayout(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(10, 10, 10, 10);

		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.gridwidth = 2;
		dialog.add(new Label(message), gbc);

		gbc.gridy = 1;
		TextField textField = new TextField(20);
		dialog.add(textField, gbc);

		gbc.gridy = 2;
		gbc.gridwidth = 1;
		Panel buttonPanel = new Panel(new FlowLayout());
		Button okButton = new Button("OK");
		Button cancelButton = new Button("Cancel");

		final String[] result = { null };

		okButton.addActionListener(e -> {
			result[0] = textField.getText();
			dialog.dispose();
		});

		cancelButton.addActionListener(e -> dialog.dispose());

		textField.addActionListener(e -> {
			result[0] = textField.getText();
			dialog.dispose();
		});

		buttonPanel.add(okButton);
		buttonPanel.add(cancelButton);
		dialog.add(buttonPanel, gbc);

		dialog.pack();
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);

		return result[0];
	}

// Helper method for confirm dialog
	private static int showConfirmDialog(Dialog parent, String message, String title) {
		Dialog dialog = new Dialog(parent, title, true);
		dialog.setLayout(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(10, 10, 10, 10);

		gbc.gridx = 0;
		gbc.gridy = 0;
		dialog.add(new Label(message), gbc);

		gbc.gridy = 1;
		Panel buttonPanel = new Panel(new FlowLayout());
		Button yesButton = new Button("Yes");
		Button noButton = new Button("No");

		final int[] result = { 1 }; // Default to No

		yesButton.addActionListener(e -> {
			result[0] = 0;
			dialog.dispose();
		});

		noButton.addActionListener(e -> {
			result[0] = 1;
			dialog.dispose();
		});

		buttonPanel.add(yesButton);
		buttonPanel.add(noButton);
		dialog.add(buttonPanel, gbc);

		dialog.pack();
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);

		return result[0];
	}

// ===== RECONNECT METHOD =====

	private void reconnect() {
		if (socket != null && !socket.isClosed()) {
			try {
				String hostname = socket.getInetAddress().getHostName();
				int port = socket.getPort();
				disconnect();

				// Brief delay to ensure clean disconnect
				Thread.sleep(500);

				connect(hostname, port);
			} catch (Exception e) {
				statusBar.setStatus("Reconnect failed: " + e.getMessage());
			}
		} else {
			showMessageDialog("No previous connection to reconnect to", "Reconnect");
		}
	}

	private void showCustomColorDialog() {
		Dialog dialog = new Dialog(this, "Custom Color Scheme", true);
		dialog.setLayout(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});

		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.gridwidth = 2;
		dialog.add(new Label("Customize Colors:"), gbc);

		gbc.gridy = 1;
		gbc.gridwidth = 1;
		dialog.add(new Label("Background:"), gbc);
		gbc.gridx = 1;
		Button bgButton = new Button("Choose...");
		final Color[] selectedBg = { screenBackground };
		bgButton.addActionListener(e -> {
			Color c = showColorChooser(selectedBg[0]);
			if (c != null)
				selectedBg[0] = c;
		});
		dialog.add(bgButton, gbc);

		gbc.gridx = 0;
		gbc.gridy = 2;
		dialog.add(new Label("Default Text:"), gbc);
		gbc.gridx = 1;
		Button fgButton = new Button("Choose...");
		final Color[] selectedFg = { defaultForeground };
		fgButton.addActionListener(e -> {
			Color c = showColorChooser(selectedFg[0]);
			if (c != null)
				selectedFg[0] = c;
		});
		dialog.add(fgButton, gbc);

		gbc.gridx = 0;
		gbc.gridy = 3;
		dialog.add(new Label("Cursor:"), gbc);
		gbc.gridx = 1;
		Button cursorButton = new Button("Choose...");
		final Color[] selectedCursor = { cursorColor };
		cursorButton.addActionListener(e -> {
			Color c = showColorChooser(selectedCursor[0]);
			if (c != null)
				selectedCursor[0] = c;
		});
		dialog.add(cursorButton, gbc);

		gbc.gridx = 0;
		gbc.gridy = 4;
		gbc.gridwidth = 2;
		gbc.anchor = GridBagConstraints.CENTER;
		Panel buttonPanel = new Panel(new FlowLayout());

		Button okButton = new Button("Apply");
		Button cancelButton = new Button("Cancel");

		okButton.addActionListener(e -> {
			screenBackground = selectedBg[0];
			defaultForeground = selectedFg[0];
			cursorColor = selectedCursor[0];
			canvas.setBackground(screenBackground);
			canvas.repaint();
			statusBar.setStatus("Custom color scheme applied");
			dialog.dispose();
		});

		cancelButton.addActionListener(e -> dialog.dispose());

		buttonPanel.add(okButton);
		buttonPanel.add(cancelButton);
		dialog.add(buttonPanel, gbc);

		dialog.pack();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	private Color showColorChooser(Color initialColor) {
		// Simple color chooser using RGB sliders
		Dialog dialog = new Dialog(this, "Choose Color", true);
		dialog.setLayout(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});

		final Color[] selectedColor = { initialColor };

		// Red slider
		gbc.gridx = 0;
		gbc.gridy = 0;
		dialog.add(new Label("Red:"), gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;
		Scrollbar redBar = new Scrollbar(Scrollbar.HORIZONTAL, initialColor.getRed(), 1, 0, 256);
		dialog.add(redBar, gbc);
		gbc.gridx = 2;
		gbc.weightx = 0;
		Label redLabel = new Label(String.valueOf(initialColor.getRed()) + "  ");
		dialog.add(redLabel, gbc);

		// Green slider
		gbc.gridx = 0;
		gbc.gridy = 1;
		dialog.add(new Label("Green:"), gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;
		Scrollbar greenBar = new Scrollbar(Scrollbar.HORIZONTAL, initialColor.getGreen(), 1, 0, 256);
		dialog.add(greenBar, gbc);
		gbc.gridx = 2;
		gbc.weightx = 0;
		Label greenLabel = new Label(String.valueOf(initialColor.getGreen()) + "  ");
		dialog.add(greenLabel, gbc);

		// Blue slider
		gbc.gridx = 0;
		gbc.gridy = 2;
		dialog.add(new Label("Blue:"), gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;
		Scrollbar blueBar = new Scrollbar(Scrollbar.HORIZONTAL, initialColor.getBlue(), 1, 0, 256);
		dialog.add(blueBar, gbc);
		gbc.gridx = 2;
		gbc.weightx = 0;
		Label blueLabel = new Label(String.valueOf(initialColor.getBlue()) + "  ");
		dialog.add(blueLabel, gbc);

		// Color preview
		gbc.gridx = 0;
		gbc.gridy = 3;
		gbc.gridwidth = 3;
		Canvas preview = new Canvas() {
			public void paint(Graphics g) {
				g.setColor(selectedColor[0]);
				g.fillRect(0, 0, getWidth(), getHeight());
			}
		};
		preview.setPreferredSize(new Dimension(200, 50));
		dialog.add(preview, gbc);

		// Update listeners
		AdjustmentListener updateListener = e -> {
			selectedColor[0] = new Color(redBar.getValue(), greenBar.getValue(), blueBar.getValue());
			redLabel.setText(String.valueOf(redBar.getValue()) + "  ");
			greenLabel.setText(String.valueOf(greenBar.getValue()) + "  ");
			blueLabel.setText(String.valueOf(blueBar.getValue()) + "  ");
			preview.repaint();
		};

		redBar.addAdjustmentListener(updateListener);
		greenBar.addAdjustmentListener(updateListener);
		blueBar.addAdjustmentListener(updateListener);

		// Buttons
		gbc.gridy = 4;
		gbc.anchor = GridBagConstraints.CENTER;
		Panel buttonPanel = new Panel(new FlowLayout());

		Button okButton = new Button("OK");
		Button cancelButton = new Button("Cancel");

		final Color[] result = { null };

		okButton.addActionListener(e -> {
			result[0] = selectedColor[0];
			dialog.dispose();
		});

		cancelButton.addActionListener(e -> dialog.dispose());

		buttonPanel.add(okButton);
		buttonPanel.add(cancelButton);
		dialog.add(buttonPanel, gbc);

		dialog.pack();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);

		return result[0];
	}

	// Update indicators
	private void updateStatusIndicators() {
		if (sessionIndicator != null) {
			if (connected) {
				sessionIndicator.setLabel("● Session: Connected");
			} else {
				sessionIndicator.setLabel("○ Session: Disconnected");
			}
		}

		if (uploadIndicator != null) {
			if (ftDirection == FileTransferDirection.UPLOAD && ftState == FileTransferState.TRANSFER_IN_PROGRESS) {
				uploadIndicator.setLabel("▲ Upload: Active");
			} else {
				uploadIndicator.setLabel("  Upload: Idle");
			}
		}

		if (downloadIndicator != null) {
			if (ftDirection == FileTransferDirection.DOWNLOAD && ftState == FileTransferState.TRANSFER_IN_PROGRESS) {
				downloadIndicator.setLabel("▼ Download: Active");
			} else {
				downloadIndicator.setLabel("  Download: Idle");
			}
		}
	}

	private void addMappingRow(Panel panel, String keyName, int keyCode) {
		Panel row = new Panel(new FlowLayout(FlowLayout.LEFT));

		row.add(new Label(keyName + ":"));

		TextField charField = new TextField(5);
		KeyMapping current = keyMap.get(keyCode);
		if (current != null && current.aid == null) {
			charField.setText(String.valueOf(current.character));
		}
		row.add(charField);

		Button setCharButton = new Button("Set Character");
		setCharButton.addActionListener(e -> {
			String text = charField.getText();
			if (text.length() > 0) {
				keyMap.put(keyCode, new KeyMapping(text.charAt(0), keyName));
				showMessageDialog("Mapping saved: " + keyName + " -> " + text.charAt(0), "Saved");
			}
		});
		row.add(setCharButton);

		Choice aidChoice = new Choice();
		aidChoice.add("(None)");
		aidChoice.add("ENTER");
		aidChoice.add("CLEAR");
		aidChoice.add("PA1");
		aidChoice.add("PA2");
		aidChoice.add("PA3");
		for (int i = 1; i <= 12; i++) {
			aidChoice.add("PF" + i);
		}
		row.add(aidChoice);

		Button setAidButton = new Button("Set AID");
		setAidButton.addActionListener(e -> {
			String selected = aidChoice.getSelectedItem();
			if (selected.equals("(None)")) {
				keyMap.remove(keyCode);
			} else {
				byte aid = getAidForName(selected);
				keyMap.put(keyCode, new KeyMapping(aid, keyName + " -> " + selected));
				showMessageDialog("Mapping saved: " + keyName + " -> " + selected, "Saved");
			}
		});
		row.add(setAidButton);

		panel.add(row);
	}

	private byte getAidForName(String name) {
		switch (name) {
		case "ENTER":
			return AID_ENTER;
		case "CLEAR":
			return AID_CLEAR;
		case "PA1":
			return AID_PA1;
		case "PA2":
			return AID_PA2;
		case "PA3":
			return AID_PA3;
		case "PF1":
			return AID_PF1;
		case "PF2":
			return AID_PF2;
		case "PF3":
			return AID_PF3;
		case "PF4":
			return AID_PF4;
		case "PF5":
			return AID_PF5;
		case "PF6":
			return AID_PF6;
		case "PF7":
			return AID_PF7;
		case "PF8":
			return AID_PF8;
		case "PF9":
			return AID_PF9;
		case "PF10":
			return AID_PF10;
		case "PF11":
			return AID_PF11;
		case "PF12":
			return AID_PF12;
		case "PF13":
			return AID_PF13;
		case "PF14":
			return AID_PF14;
		case "PF15":
			return AID_PF15;
		case "PF16":
			return AID_PF16;
		case "PF17":
			return AID_PF17;
		case "PF18":
			return AID_PF18;
		case "PF19":
			return AID_PF19;
		case "PF20":
			return AID_PF20;
		case "PF21":
			return AID_PF21;
		case "PF22":
			return AID_PF22;
		case "PF23":
			return AID_PF23;
		case "PF24":
			return AID_PF24;
		default:
			return AID_ENTER;
		}
	}

	// File Transfer Dialog
	//
	// CMS Send parameters: - must be preceded by a "("
	// - ASCII CRLF APPEND RECFM x LRECL n
	// - ASCII
	// - CRLF
	// - APPEND
	// - RECFM x ("F", "V", or "U")
	// - LRECL n
	//
	// CMS Receive parameters: - must be preceded by a "("
	// - APPEND
	// - ASCII
	// - CRLF
	//
	// TSO Send parameters:
	// - ASCII
	// - CRLF
	// - APPEND
	// - RECFM(x)
	// - LRECL(n)
	// - BLKSIZE(n)
	// - SPACE(n,n)
	//
	// TSO Receive parameters:
	// - ASCII CRLF RECFM(F) LRECL(80) BLKSIZE(6160)
	//
	private void showFileTransferDialog(boolean isDownload) {
		if (!connected) {
			showMessageDialog("Not connected to host", "Connection Required", true);
			return;
		}

		Dialog dialog = new Dialog(this, isDownload ? "Download from Host" : "Upload to Host", true);
		dialog.setLayout(new GridBagLayout());

		// Add window listener
		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		// Host Type
		gbc.gridx = 0;
		gbc.gridy = 0;
		dialog.add(new Label("Host Type:"), gbc);

		gbc.gridx = 1;
		gbc.gridwidth = 2;
		Choice hostTypeChoice = new Choice();
		hostTypeChoice.add("TSO (z/OS)");
		hostTypeChoice.add("CMS (z/VM)");
		hostTypeChoice.select(hostType == HostType.TSO ? 0 : 1);
		dialog.add(hostTypeChoice, gbc);

		// Local file
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 1;
		dialog.add(new Label("Local File:"), gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		TextField localFileField = new TextField(40);
		dialog.add(localFileField, gbc);

		gbc.gridx = 2;
		gbc.weightx = 0;
		Button browseButton = new Button("Browse...");
		dialog.add(browseButton, gbc);

		// Host dataset/file
		gbc.gridx = 0;
		gbc.gridy = 2;
		gbc.weightx = 0;
		Label datasetLabel = new Label("Host Dataset:");
		dialog.add(datasetLabel, gbc);

		gbc.gridx = 1;
		gbc.gridwidth = 2;
		gbc.weightx = 1.0;
		TextField hostDatasetField = new TextField(40);
		hostDatasetField.setText("USER.TEST.DATA");
		dialog.add(hostDatasetField, gbc);

		// Transfer mode
		gbc.gridx = 0;
		gbc.gridy = 3;
		gbc.gridwidth = 1;
		gbc.weightx = 0;
		dialog.add(new Label("Transfer Mode:"), gbc);

		gbc.gridx = 1;
		gbc.gridwidth = 2;
		Choice modeChoice = new Choice();
		modeChoice.add("ASCII (Text)");
		modeChoice.add("BINARY");
		dialog.add(modeChoice, gbc);

		// CRLF option (only for ASCII)
		gbc.gridx = 0;
		gbc.gridy = 4;
		gbc.gridwidth = 3;
		Checkbox crlfCheckbox = new Checkbox("Add CRLF line endings (ASCII only)", true);
		dialog.add(crlfCheckbox, gbc);

		// APPEND option
		gbc.gridy = 5;
		Checkbox appendCheckbox = new Checkbox("Append to existing file", false);
		dialog.add(appendCheckbox, gbc);

		// Record format options (only for upload/send)
		Panel recfmPanel = new Panel(new FlowLayout(FlowLayout.LEFT));
		recfmPanel.add(new Label("RECFM:"));
		Choice recfmChoice = new Choice();
		recfmChoice.add("F");
		recfmChoice.add("V");
		recfmChoice.add("U");
		recfmPanel.add(recfmChoice);

		recfmPanel.add(new Label("  LRECL:"));
		TextField lreclField = new TextField("80", 6);
		recfmPanel.add(lreclField);

		recfmPanel.add(new Label("  BLKSIZE:"));
		TextField blksizeField = new TextField("6160", 6);
		recfmPanel.add(blksizeField);

		Label blksizeLabel = new Label("  BLKSIZE:");
		TextField spaceField = new TextField("1,1", 10);
		Label spaceLabel = new Label("  SPACE:");

		if (!isDownload) {
			gbc.gridx = 0;
			gbc.gridy = 6;
			gbc.gridwidth = 3;
			dialog.add(recfmPanel, gbc);

			// TSO-only: SPACE parameter
			Panel spacePanel = new Panel(new FlowLayout(FlowLayout.LEFT));
			spacePanel.add(spaceLabel);
			spacePanel.add(spaceField);
			spacePanel.add(new Label("(TSO only - Primary,Secondary)"));

			gbc.gridy = 7;
			dialog.add(spacePanel, gbc);
		}

		// Buttons
		gbc.gridy = isDownload ? 6 : 8;
		gbc.gridx = 0;
		gbc.gridwidth = 3;
		gbc.anchor = GridBagConstraints.CENTER;
		Panel buttonPanel = new Panel(new FlowLayout());

		Button okButton = new Button("Start Transfer");
		Button cancelButton = new Button("Cancel");

		buttonPanel.add(okButton);
		buttonPanel.add(cancelButton);
		dialog.add(buttonPanel, gbc);

		// Update labels based on host type
		hostTypeChoice.addItemListener(e -> {
			if (hostTypeChoice.getSelectedIndex() == 0) {
				// TSO
				datasetLabel.setText("Host Dataset:");
				hostDatasetField.setText("USER.TEST.DATA");
			} else {
				// CMS
				datasetLabel.setText("Host File:");
				hostDatasetField.setText("TEST DATA A");
			}
		});

		// Browse button action
		browseButton.addActionListener(e -> {
			FileDialog fileDialog = new FileDialog(this,
					isDownload ? "Select destination file" : "Select file to upload",
					isDownload ? FileDialog.SAVE : FileDialog.LOAD);
			fileDialog.setVisible(true);

			String dir = fileDialog.getDirectory();
			String file = fileDialog.getFile();

			if (dir != null && file != null) {
				localFileField.setText(dir + file);
			}
		});

		// Mode choice action - enable/disable CRLF checkbox
		modeChoice.addItemListener(e -> {
			crlfCheckbox.setEnabled(modeChoice.getSelectedIndex() == 0);
		});

		// OK button action
		okButton.addActionListener(e -> {
			String localFile = localFileField.getText().trim();
			String hostDataset = hostDatasetField.getText().trim();

			if (localFile.isEmpty()) {
				showMessageDialog("Please specify a local file", "Validation Error", true);
				return;
			}

			if (hostDataset.isEmpty()) {
				showMessageDialog("Please specify a host dataset/file name", "Validation Error", true);
				return;
			}

			// Validate file exists for upload
			if (!isDownload) {
				File f = new File(localFile);
				if (!f.exists()) {
					showMessageDialog("Local file does not exist: " + localFile, "File Not Found", true);
					return;
				}
			}

			// Determine host type
			hostType = hostTypeChoice.getSelectedIndex() == 0 ? HostType.TSO : HostType.CMS;

			// Build IND$FILE command based on host type
			String cmd = buildIndFileCommand(isDownload, hostType, hostDataset, modeChoice.getSelectedIndex() == 0, // isAscii
					crlfCheckbox.getState(), appendCheckbox.getState(), recfmChoice.getSelectedItem(),
					lreclField.getText().trim(), blksizeField.getText().trim(), spaceField.getText().trim());

			dialog.dispose();

			// Initiate the transfer
			initiateFileTransfer(localFile, cmd, isDownload);
		});

		// Cancel button action
		cancelButton.addActionListener(e -> dialog.dispose());

		// Enter key on text fields
		// OK button action
		Runnable startTransfer = () -> {
			String localFile = localFileField.getText().trim();
			String hostDataset = hostDatasetField.getText().trim();

			if (localFile.isEmpty()) {
				showMessageDialog("Please specify a local file", "Validation Error", true);
				return;
			}

			if (hostDataset.isEmpty()) {
				showMessageDialog("Please specify a host dataset/file name", "Validation Error", true);
				return;
			}

			// Validate file exists for upload
			if (!isDownload) {
				File f = new File(localFile);
				if (!f.exists()) {
					showMessageDialog("Local file does not exist: " + localFile, "File Not Found", true);
					return;
				}
			}

			// Determine host type
			hostType = hostTypeChoice.getSelectedIndex() == 0 ? HostType.TSO : HostType.CMS;

			// Build IND$FILE command based on host type
			String cmd = buildIndFileCommand(isDownload, hostType, hostDataset, modeChoice.getSelectedIndex() == 0, // isAscii
					crlfCheckbox.getState(), appendCheckbox.getState(), recfmChoice.getSelectedItem(),
					lreclField.getText().trim(), blksizeField.getText().trim(), spaceField.getText().trim());

			dialog.dispose();

			// Initiate the transfer
			initiateFileTransfer(localFile, cmd, isDownload);
		};

		okButton.addActionListener(e -> startTransfer.run());

		// Cancel button action
		cancelButton.addActionListener(e -> dialog.dispose());

		// Enter key on text fields - now calls the same logic
		ActionListener enterAction = e -> startTransfer.run();
		localFileField.addActionListener(enterAction);
		hostDatasetField.addActionListener(enterAction);

		dialog.pack();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	// Build IND$FILE command with proper syntax for TSO or CMS
	private String buildIndFileCommand(boolean isDownload, HostType hostType, String hostDataset, boolean isAscii,
			boolean useCrlf, boolean append, String recfm, String lrecl, String blksize, String space) {
		StringBuilder cmd = new StringBuilder();
		cmd.append("IND$FILE ");

		// Command verb
		if (isDownload) {
			cmd.append("GET ");
		} else {
			cmd.append("PUT ");
		}

		// Dataset/filename
		cmd.append(hostDataset);

		if (hostType == HostType.CMS) {
			// CMS format - parameters in parentheses
			StringBuilder params = new StringBuilder();

			if (isAscii) {
				params.append(" ASCII");
			}
			if (useCrlf && isAscii) {
				params.append(" CRLF");
			}
			if (append) {
				params.append(" APPEND");
			}

			// For PUT (upload), add RECFM and LRECL
			if (!isDownload && !recfm.isEmpty()) {
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
			if (isAscii) {
				cmd.append(" ASCII");
			}
			if (useCrlf && isAscii) {
				cmd.append(" CRLF");
			}
			if (append) {
				cmd.append(" APPEND");
			}

			// For PUT (upload), add RECFM, LRECL, BLKSIZE, SPACE
			if (!isDownload) {
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
				// For GET (download), only add format parameters if specified
				if (isAscii && !recfm.isEmpty()) {
					cmd.append(" RECFM(").append(recfm).append(")");
				}
				if (isAscii && !lrecl.isEmpty()) {
					cmd.append(" LRECL(").append(lrecl).append(")");
				}
				if (!blksize.isEmpty()) {
					cmd.append(" BLKSIZE(").append(blksize).append(")");
				}
			}
		}

		return cmd.toString();
	}

	// Simple message dialog using AWT
	private static void showStaticMessageDialog(String message, String title) {
		Dialog dialog = new Dialog((Frame) null, title, true);
		dialog.setLayout(new BorderLayout(10, 10));

		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});

		Panel messagePanel = new Panel();
		messagePanel.add(new Label(message));
		dialog.add(messagePanel, BorderLayout.CENTER);

		Panel buttonPanel = new Panel();
		Button okButton = new Button("OK");
		okButton.addActionListener(e -> dialog.dispose());
		buttonPanel.add(okButton);
		dialog.add(buttonPanel, BorderLayout.SOUTH);

		dialog.pack();
		dialog.setLocationRelativeTo(null);
		dialog.setVisible(true);
	}

	private void showMessageDialog(String message, String title, boolean isError) {
		Dialog dialog = new Dialog(this, title, true);
		dialog.setLayout(new BorderLayout(10, 10));

		Panel messagePanel = new Panel();
		messagePanel.add(new Label(message));
		dialog.add(messagePanel, BorderLayout.CENTER);

		Panel buttonPanel = new Panel();
		Button okButton = new Button("OK");
		okButton.addActionListener(e -> dialog.dispose());
		buttonPanel.add(okButton);
		dialog.add(buttonPanel, BorderLayout.SOUTH);

		dialog.pack();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	// Replace all JOptionPane calls with this AWT version
	private void showMessageDialog(String message, String title) {
		showMessageDialog(message, title, false);
	}

	// Multi-line message dialog
	private void showMultilineMessageDialog(String message, String title) {
		Dialog dialog = new Dialog(this, title, true);
		dialog.setLayout(new BorderLayout(10, 10));

		TextArea textArea = new TextArea(message, 5, 40, TextArea.SCROLLBARS_VERTICAL_ONLY);
		textArea.setEditable(false);
		dialog.add(textArea, BorderLayout.CENTER);

		Panel buttonPanel = new Panel();
		Button okButton = new Button("OK");
		okButton.addActionListener(e -> dialog.dispose());
		buttonPanel.add(okButton);
		dialog.add(buttonPanel, BorderLayout.SOUTH);

		dialog.pack();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	private void showProgressDialog(String operation) {
		if (progressDialog != null) {
			progressDialog.dispose();
		}

		progressDialog = new Dialog(this, "File Transfer", false);
		progressDialog.setLayout(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(10, 10, 10, 10);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		gbc.gridx = 0;
		gbc.gridy = 0;
		progressDialog.add(new Label(operation), gbc);

		gbc.gridy = 1;
		progressLabel = new Label("Initializing...");
		progressDialog.add(progressLabel, gbc);

		gbc.gridy = 2;
		statusLabel = new Label(" ");
		progressDialog.add(statusLabel, gbc);

		gbc.gridy = 3;
		gbc.anchor = GridBagConstraints.CENTER;
		cancelTransferButton = new Button("Cancel");
		cancelTransferButton.addActionListener(e -> {
			// TODO: Implement cancel logic
			closeProgressDialog();
			statusBar.setStatus("Transfer cancelled");
		});
		progressDialog.add(cancelTransferButton, gbc);

		progressDialog.pack();
		progressDialog.setLocationRelativeTo(this);
		progressDialog.setVisible(true);
	}

	private void updateProgressDialog(String progress, String status) {
		if (progressLabel != null) {
			progressLabel.setText(progress);
		}
		if (statusLabel != null && status != null) {
			statusLabel.setText(status);
		}
		if (progressDialog != null) {
			progressDialog.pack();
		}
	}

	private void closeProgressDialog() {
		if (progressDialog != null) {
			progressDialog.dispose();
			progressDialog = null;
			progressLabel = null;
			statusLabel = null;
			cancelTransferButton = null;
		}
	}

	private SSLSocketFactory createTrustAllSSLSocketFactory() {
		try {
			// Create a trust manager that does not validate certificate chains
			TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
				public java.security.cert.X509Certificate[] getAcceptedIssuers() {
					return null;
				}

				public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
				}

				public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
				}
			} };

			// Install the all-trusting trust manager
			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, trustAllCerts, new java.security.SecureRandom());
			return sc.getSocketFactory();

		} catch (Exception e) {
			e.printStackTrace();
			return (SSLSocketFactory) SSLSocketFactory.getDefault();
		}
	}

	public void connect(String hostname, int port) {
		try {
			if (useTLS) {
				SSLSocketFactory factory = createTrustAllSSLSocketFactory();
				socket = factory.createSocket(hostname, port);

				// Also disable hostname verification
				if (socket instanceof SSLSocket) {
					SSLSocket sslSocket = (SSLSocket) socket;
					sslSocket.setEnabledProtocols(new String[] { "TLSv1.2", "TLSv1.3" });
					// Start handshake immediately
					sslSocket.startHandshake();
				}

				statusBar.setStatus("TLS connected to " + hostname + ":" + port);
			} else {
				socket = new Socket(hostname, port);
				statusBar.setStatus("Connected to " + hostname + ":" + port);
			}

			input = socket.getInputStream();
			output = socket.getOutputStream();
			connected = true;

			updateStatusIndicators();

			readerThread = new Thread(this::readLoop);
			readerThread.start();

		} catch (IOException e) {
			statusBar.setStatus("Connection failed: " + e.getMessage());
			e.printStackTrace();
		}
	}

	public void disconnect() {
		connected = false;

		updateStatusIndicators();

		try {
			if (socket != null)
				socket.close();
		} catch (IOException e) {
			// Ignore
		}
	}

	private void readLoop() {
		byte[] buf = new byte[8192];
		ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
		boolean inTelnetCommand = false;
		boolean inSubnegotiation = false;
		ByteArrayOutputStream subnegBuffer = new ByteArrayOutputStream();

		try {
			while (connected) {
				int n = input.read(buf);
				if (n <= 0)
					break;

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
						if (b == SE && subnegBuffer.size() > 1) {
							byte[] subnegData = subnegBuffer.toByteArray();
							if (subnegData[subnegData.length - 2] == IAC) {
								byte[] cleanData = new byte[subnegData.length - 2];
								System.arraycopy(subnegData, 0, cleanData, 0, cleanData.length);
								handleSubnegotiation(cleanData);
								subnegBuffer.reset();
								inSubnegotiation = false;
							}
						}

						continue;
					}

					// Detect start of telnet command
					if (b == IAC && !inTelnetCommand) {
						inTelnetCommand = true;
						continue;
					}

					// Process telnet command
					if (inTelnetCommand) {
						if (b == IAC) {
							// IAC IAC = escaped literal 0xFF byte in data
							dataStream.write((byte) 0xFF);
						} else if (b == SB) {
							// Start subnegotiation
							inSubnegotiation = true;
							subnegBuffer.reset();
						} else if (b == DO || b == DONT || b == WILL || b == WONT) {
							// Two-byte telnet command
							if (i + 1 < n) {
								handleTelnetCommand(b, buf[++i]);
							}
						} else if (b == (byte) 0xEF) {
							// EOR - End of Record, process accumulated 3270 data
							if (dataStream.size() > 0) {
								process3270Data(dataStream.toByteArray());
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
				statusBar.setStatus("Connection lost: " + e.getMessage());
			}
		}
		connected = false;
	}

	private void handleTelnetCommand(byte command, byte option) throws IOException {
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

	private void handleSubnegotiation(byte[] data) throws IOException {
		if (data.length < 2)
			return;

		byte option = data[0];
		if (option == OPT_TERMINAL_TYPE && data.length > 1 && data[1] == 1) {
			sendTerminalType();
		} else if (option == OPT_TN3270E) {
			handleTN3270ESubneg(data);
		}
	}

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
				/*
				 * ByteArrayOutputStream baos2 = new ByteArrayOutputStream(); baos2.write(IAC);
				 * baos2.write(SB); baos2.write(OPT_TN3270E); baos2.write(0x07); // INFO
				 * baos2.write("IBM-".getBytes()); baos2.write(model.getBytes());
				 * baos2.write("-E".getBytes()); baos2.write(IAC); baos2.write(SE);
				 * output.write(baos2.toByteArray()); output.flush();
				 * System.out.println("Sent DEVICE-TYPE INFO (in response to 0x08 0x02): IBM-" +
				 * model + "-E");
				 */

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

	private void handleTN3270ESubnegotiation(byte[] data) throws IOException {
		// data[0] should be 0x28 (TN3270E)
		if (data.length < 3)
			return;
		int func = data[1] & 0xFF;

		switch (func) {
		case 0x08: // FUNCTIONS IS
			System.out.println("TN3270E subnegotiation: FUNCTIONS IS received");
			sendTN3270ERequestFunctions();
			break;

		case 0x06: // CONNECT
			System.out.println("TN3270E subnegotiation: CONNECT received");
			sendTN3270EConnectReply();
			break;

		default:
			System.out.printf("TN3270E subnegotiation: unhandled function 0x%02X\n", func);
			break;
		}
	}

	private void sendTN3270EConnect(String model) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(IAC);
		baos.write(SB);
		baos.write(OPT_TN3270E);
		baos.write(6); // CONNECT
		baos.write("IBM-".getBytes());
		baos.write(model.getBytes());
		baos.write(IAC);
		baos.write(SE);
		output.write(baos.toByteArray());
		output.flush();
	}

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

	private void sendTN3270EDeviceType(String model) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(IAC);
		baos.write(SB);
		baos.write(OPT_TN3270E);
		baos.write(4); // DEVICE-TYPE IS
		baos.write(("IBM-" + model + "-E").getBytes());
		baos.write(IAC);
		baos.write(SE);
		output.write(baos.toByteArray());
		output.flush();
		System.out.println("Sent DEVICE-TYPE IS: IBM-" + model + "-E");
	}

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

	private void sendTN3270ESendFunctionsRequest() throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(IAC);
		baos.write(SB);
		baos.write(OPT_TN3270E);
		baos.write(3); // SEND FUNCTIONS
		baos.write(IAC);
		baos.write(SE);
		output.write(baos.toByteArray());
		output.flush();
		System.out.println("Sent TN3270E SEND FUNCTIONS request");
	}

	private void sendTN3270ERequestFunctions() throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		// Start TN3270E subnegotiation
		baos.write(IAC);
		baos.write(SB);
		baos.write(OPT_TN3270E);

		// Function code: REQUEST-FUNCTIONS (0x09)
		baos.write(0x09);

		// Functions list we support: BIND-IMAGE(0x00), DATA-STREAM-CTL(0x01),
		// RESPONSES(0x02), SYSREQ(0x03)
		baos.write(0x00);
		baos.write(0x01);
		baos.write(0x02);
		baos.write(0x03);

		// End subnegotiation
		baos.write(IAC);
		baos.write(SE);

		output.write(baos.toByteArray());
		output.flush();

		System.out.println("Sent TN3270E REQUEST FUNCTIONS (00,01,02,03)");
	}

	private void fallBackToTN3270() {
		if (tn3270eFailed)
			return; // Already fallen back

		try {
			System.out.println("=== FALLING BACK TO TN3270 ===");
			tn3270eFailed = true;
			tn3270eMode = false;
			tn3270eNegotiationComplete = false;

			// Send WONT TN3270E to cancel
			sendTelnet(WONT, OPT_TN3270E);
			System.out.println("Sent WONT TN3270E");

			statusBar.setStatus("Using TN3270 mode (TN3270E not supported)");

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

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

	private void sendTelnet(byte command, byte option) throws IOException {
		output.write(new byte[] { IAC, command, option });
		output.flush();
	}

	private void sendTerminalType() throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(IAC);
		baos.write(SB);
		baos.write(OPT_TERMINAL_TYPE);
		baos.write(0); // IS
		baos.write("IBM-".getBytes());
		baos.write(model.getBytes());
		baos.write("-E".getBytes());
		baos.write(IAC);
		baos.write(SE);
		output.write(baos.toByteArray());
		output.flush();
	}

	private void process3270Data(byte[] data) {
		if (data.length < 1)
			return;
		// System.out.println(">>> RECEIVED 3270 DATA: " + data.length + " bytes");
		// if (data.length < 10) {
		// System.out.print("Raw bytes: ");
		// for (byte b : data) {
		// System.out.print(String.format("%02X ", b));
		// }
		// System.out.println();
		// }

		int offset = 0;

		if (tn3270eMode && data.length >= 5) {
			byte dataType = data[0];
			offset = 5;
			System.out.println("TN3270E Data type: " + String.format("%02X", dataType));

			if (dataType != TN3270E_DT_3270_DATA) {
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
		case CMD_ERASE_WRITE_05:
		case CMD_ERASE_WRITE_F5:
			// Erase Write - use primary size
			if (useAlternateSize) {
				useAlternateSize = false;
				rows = primaryRows;
				cols = primaryCols;
				canvas.updateSize();
				System.out.println("Switched to primary size: " + cols + "x" + rows);
			}
			currentCharSet = EBCDIC_TO_ASCII; // Reset to default
			clearScreen();
			// Fall through to normal WRITE processing
		case CMD_WRITE_01:
		case CMD_WRITE_F1:
			if (offset < data.length) {
				byte wcc = data[offset++];
				processWCC(wcc);
				processOrders(data, offset);
			}
			keyboardLocked = false;
			break;

		case CMD_ERASE_WRITE_ALTERNATE_0D:
		case CMD_ERASE_WRITE_ALTERNATE_7E:
			// Erase Write Alternate - switch to alternate size
			if (!useAlternateSize) {
				useAlternateSize = true;
				rows = alternateRows;
				cols = alternateCols;
				canvas.updateSize();
				System.out.println("Switched to alternate size: " + cols + "x" + rows);
			}
			currentCharSet = EBCDIC_TO_ASCII; // Reset to default
			clearScreen();
			if (offset < data.length) {
				byte wcc = data[offset++];
				processWCC(wcc);
				processOrders(data, offset);
			}
			keyboardLocked = false;
			break;

		case CMD_READ_BUFFER_02:
		case CMD_READ_BUFFER_F2:
			sendReadBuffer();
			break;

		case CMD_READ_MODIFIED_06:
		case CMD_READ_MODIFIED_F6:
			// System.out.println("Calling SendAID from CCW processing...");
			sendAID(lastAID);
			break;

		case CMD_WSF_11:
		case CMD_WSF_F3:
			processWSF(data, offset);
			break;

		case CMD_ERASE_ALL_UNPROTECTED_0F:
		case CMD_ERASE_ALL_UNPROTECTED_6F:
			eraseAllUnprotected();
			break;
		}

		canvas.repaint();
		statusBar.update();
		System.out.println("Finished processing command 0x" + String.format("%02X", command));
	}

	private void eraseAllUnprotected() {
		// Erase all unprotected fields and reset MDT
		for (int i = 0; i < buffer.length; i++) {
			if (isFieldStart(i)) {
				// Reset MDT bit
				attributes[i] &= ~0x01;
			} else if (!isProtected(i)) {
				// Clear unprotected field data
				buffer[i] = '\0';
			}
		}

		clearUnprotectedModifiedFlags(); // reset MDT for unprotected fields only

		keyboardLocked = false;

		canvas.repaint();
		statusBar.update();
	}

	private void processWCC(byte wcc) {
		if ((wcc & WCC_RESET) != 0) {
			keyboardLocked = false;

			// Reset Reply Mode if WCC_RESET
			resetReplyModeToDefault();
		}
		if ((wcc & WCC_RESET_MDT) != 0) {
			resetMDT();
		}
		if ((wcc & WCC_ALARM) != 0) {
			Toolkit.getDefaultToolkit().beep();
		}
		statusBar.update();
	}

	private void processOrders(byte[] data, int offset) {
		int pos = 0;
		int i = offset;
		int currentFieldStart = -1;
		byte currentColor = 0;
		byte currentHighlight = 0;
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
			case ORDER_RA:
				if (i + 2 < data.length) {
					int endPos = decode3270Address(data[i], data[i + 1]);
					byte ch = data[i + 2];
					i += 3;

					if (ch == ORDER_GE) { // Is char a GE order?
						if (i + 1 < data.length) {
							ch = data[i + 1]; // Yes, grab next char (APL)
							i++; // Account for GE order
							c = EBCDIC_TO_APL[ch & 0xFF]; // Translate to APL char
						} else {
							break; // or safely ignore truncated GE
						}
					} else {
						c = EBCDIC_TO_ASCII[ch & 0xFF];
					}

					while (pos != endPos) {
						buffer[pos] = c;
						extendedColors[pos] = currentColor;
						highlighting[pos] = currentHighlight;
						pos = (pos + 1) % (rows * cols);
					}
				}
				break;
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
			/*
			 * case ORDER_GE: // GE - Graphics Escape if (i < data.length) { b = data[i++];
			 * // byte charSet = data[i++]; if (charSet == (byte)0xF1) { currentCharSet =
			 * EBCDIC_TO_APL; // Switch to APL
			 * //System.out.println("Switched to APL character set: " +
			 * String.format("%02X", charSet)); } else { currentCharSet = EBCDIC_TO_ASCII;
			 * // Switch back to default
			 * //System.out.println("Switched to default character set: " +
			 * String.format("%02X", charSet)); } // graphicEscape = true; } break;
			 */
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
					// System.out.println("GE: " + c + "\n");
				} else
					c = EBCDIC_TO_ASCII[b & 0xFF];

				// c = currentCharSet[b & 0xFF]; // Use current character set
				// char c = EBCDIC_TO_ASCII[b & 0xFF];
				
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

		// System.out.println("=== SCREEN CONTENT AFTER PROCESSING ===");
		// for (int row = 0; row < Math.min(5, rows); row++) {
		// StringBuilder line = new StringBuilder();
		// for (int col = 0; col < cols; col++) {
		// c = buffer[row * cols + col];
		// line.append(c == '\0' ? '.' : c);
		// }
		// System.out.println("Row " + row + ": " + line.toString());
		// }
		// System.out.println();
	}

	// Enhance processWSF method
	private void processWSF(byte[] data, int offset) {
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
			} else if (sfid == SF_ID_SET_REPLY_MODE) {
        		handleSetReplyModeSF(data, i, length);
			//} else if (sfid == SF_ID_READ_PARTITION_QUERY_LIST) {
        		//handleReadPartitionQueryList(data, i, length);
			} else {
				System.out.println("WSF: Unknown/unhandled SFID");
			}

			// Move to next structured field
			i += length;
		}
	}

	/**
 * Handle Set Reply Mode structured field (SF type 0x09).
 *
 * sfBuf[offset .. offset+len-1] contains the SF payload (subfields).
 *
 * The spec allows several subfields; commonly a subfield carries reply-mode flags.
 * We collect up to 4 bytes of payload and map to a high-level ReplyMode.
 */
private void handleSetReplyModeSF(byte[] sfBuf, int offset, int len) {
    if (sfBuf == null || len <= 0 || offset < 0 || offset + len > sfBuf.length) {
        System.err.println("handleSetReplyModeSF: invalid params");
        return;
    }
    System.out.printf("handleSetReplyModeSF: offset=%d len=%d%n", offset, len);

    int p = offset;
    int end = offset + len;
    int flags = 0;

    // parse generic subfield triplets: [tag][len][payload...]
    while (p + 1 < end) {
        int tag = sfBuf[p++] & 0xFF;
        int slen = sfBuf[p++] & 0xFF;
        if (p + slen > end) {
            System.err.println("SetReplyMode SF truncated subfield; bailing");
            break;
        }

        // If this subfield is the reply-mode flags subfield (common implementations),
        // we aggregate up to 4 bytes into an int.
        // The exact tag value for the reply-mode flags subfield depends on implementation;
        // many hosts use the primary subfield carrying the flags immediately.
        // To be forgiving we aggregate all payload bytes into the flags field (up to 4 bytes).
        for (int i = 0; i < Math.min(4, slen); i++) {
            flags = (flags << 8) | (sfBuf[p + i] & 0xFF);
        }
        p += slen;
    }

    // store raw flags for diagnostics
    this.replyModeFlags = flags;

    // Map raw flags -> high-level modes.
    // NOTE: the GA23-0059 spec describes reply-mode bits/subfields precisely. In practice,
    // hosts use simple 0/1/2 codes or small bit-masks. We implement the common mapping:
    //
    //   0x00 / no bits set    -> FIELD (default)
    //   value == 0x01         -> EXTENDED_FIELD
    //   value == 0x02         -> CHARACTER
    //
    // Many hosts set specific bits to indicate supported modes; if your host uses bitfields
    // instead of single value, replace the masks below with the canonical ones from GA23-0059.
    final int MASK_EXTENDED_FIELD = 0x01;   // replace with exact mask from manual if needed
    final int MASK_CHARACTER = 0x02;        // replace with exact mask from manual if needed

    if ((flags & MASK_CHARACTER) != 0) {
        currentReplyMode = ReplyMode.CHARACTER;
    } else if ((flags & MASK_EXTENDED_FIELD) != 0) {
        currentReplyMode = ReplyMode.EXTENDED_FIELD;
    } else {
        currentReplyMode = ReplyMode.FIELD;
    }

    System.out.printf("handleSetReplyModeSF -> rawFlags=0x%X mappedMode=%s%n", flags, currentReplyMode);
}

	private void handleSetReplyModeOld(byte[] sf, int idx) {
    	// parse subfields carefully
    	// Typical layout: [SF-ID][subfield-tag][subfield-len][payload]...
    	int p = idx;
    	while (p + 1 < sf.length) {
        	int subTag = sf[p++] & 0xFF;
        	int subLen = sf[p++] & 0xFF;
        	if (p + subLen > sf.length) {
            	System.err.println("SetReplyMode: truncated subfield");
            	break;
        	}
        	byte[] payload = Arrays.copyOfRange(sf, p, p + subLen);
        	p += subLen;

        	// Example subTag values — check spec for real tags
        	if (subTag == 0x11) {
            	// Reply-mode flags
            	parseAndStoreReplyModeFlags(payload);
        	} else {
            	System.err.printf("SetReplyMode: unknown subTag 0x%02X%n", subTag);
        	}
    	}
		// After Set Reply Mode, the host may follow with queries — be ready.
    	System.out.println("Set Reply Mode processed. Reply flags: " + replyModeFlagsToString());
	}

	private void parseAndStoreReplyModeFlags(byte[] payload) {
    // existing: store raw flags for debugging
    int flags = 0;
    for (int i = 0; i < Math.min(4, payload.length); i++) {
        flags = (flags << 8) | (payload[i] & 0xFF);
    }
    replyModeFlags = flags;

    // Map flags to our higher-level ReplyMode
    // NOTE: this mapping depends on the exact bit definitions in the manuals.
    // Common interpretation:
    // - if a "character mode" bit is set -> CHARACTER
    // - else if "extended field mode" bit set -> EXTENDED_FIELD
    // - otherwise default -> FIELD

    if (isCharacterModeRequested(flags)) {
        currentReplyMode = ReplyMode.CHARACTER;
    } else if (isExtendedFieldModeRequested(flags)) {
        currentReplyMode = ReplyMode.EXTENDED_FIELD;
    } else {
        currentReplyMode = ReplyMode.FIELD;
    }

    System.out.printf("Reply mode flags=0x%X -> mode=%s%n", flags, currentReplyMode);
}

/**
 * Build a Read Modified response payload according to currentReplyMode.
 * Returns an array of bytes ready to send (AID + cursor address + payload).
 *
 * The caller should choose the AID to send (e.g., AID_ENTER or other) and then write
 * the returned bytes in one atomic socket write.
 */
/* 
private byte[] buildReadModifiedResponse(byte aidToSend) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();

    // 1) AID
    bos.write(aidToSend & 0xFF);

    // 2) Cursor address (use your current host-visible cursor position)
    int cursorPos = currentCursorAddress(); // implement to return your pos encoding input to encode3270Address
    byte[] cursorBytes = encode3270Address(cursorPos);
    bos.write(cursorBytes);

    // 3) Payload per reply mode
    if (currentReplyMode == ReplyMode.FIELD || currentReplyMode == ReplyMode.EXTENDED_FIELD) {
        // Field mode: for each field with MDT set, emit SBA + field data
        for (Field f : enumerateFieldsInBufferOrder()) {
            if (!f.hasMDT()) continue;
            // SBA (set buffer address) to the field data start
            bos.write(ORDER_SBA & 0xFF);
            bos.write(encode3270Address(f.dataStart));

            // Append the field data bytes (EBCDIC as your display bytes)
            for (int p = f.dataStart; p <= f.dataEnd; p++) {
                bos.write(displayByteAt(p) & 0xFF);
            }
        }
    } else {         // Character
        // Character mode: emit SA orders whenever attribute type changes, and data in sequence.
        // We only include positions part of MDT-marked fields (i.e. fields with MDT set).
        int max = rows * cols;
        int lastAttrType = -1;
        boolean inIncludedRegion = false;

        for (int p = 0; p < max; p++) {
            if (isFieldStart(p)) {
                // Determine if this field is MDT-marked (we include its data)
                Field f = findFieldByStart(p);
                inIncludedRegion = (f != null && f.hasMDT());
                lastAttrType = (f != null) ? f.getAttributeType() : -1;
                continue;
            }
            if (!inIncludedRegion) continue;

            // find attribute type for this position's field
            int attrType = getAttributeTypeForPosition(p);
            if (attrType != lastAttrType) {
                // insert SA order (type + value)
                bos.write(ORDER_SA & 0xFF);
                bos.write((byte) attrType); // type
                bos.write((byte) getAttributeValueForPosition(p)); // value
                lastAttrType = attrType;
            }
            bos.write(displayByteAt(p) & 0xFF);
        }
    }

    return bos.toByteArray();
}

private byte[] buildReadBufferResponse(byte aidToSend) throws IOException {
    // For now we implement same as Read Modified but for all fields (no MDT filtering)
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    bos.write(aidToSend & 0xFF);
    byte[] cursor = encode3270Address(currentCursorAddress());
    bos.write(cursor);

    if (currentReplyMode == ReplyMode.CHARACTER) {
        // Walk entire buffer and insert SA whenever attribute type changes
        int max = rows * cols;
        int lastAttrType = -1;
        for (int p = 0; p < max; p++) {
            if (isFieldStart(p)) {
                lastAttrType = getAttributeTypeForPosition(p);
                continue;
            }
            int attr = getAttributeTypeForPosition(p);
            if (attr != lastAttrType) {
                bos.write(ORDER_SA & 0xFF);
                bos.write((byte) attr);
                bos.write((byte) getAttributeValueForPosition(p));
                lastAttrType = attr;
            }
            bos.write(displayByteAt(p) & 0xFF);
        }
    } else {
        // FIELD/EXTENDED_FIELD: for each field emit SBA + all data
        for (Field f : enumerateFieldsInBufferOrder()) {
            bos.write(ORDER_SBA & 0xFF);
            bos.write(encode3270Address(f.dataStart));
            for (int p = f.dataStart; p <= f.dataEnd; p++) bos.write(displayByteAt(p) & 0xFF);
        }
    }
    return bos.toByteArray();
}
*/

// Example bit-check helpers (replace masks with spec values)
private boolean isCharacterModeRequested(int flags) {
    // placeholder: true if the specific bit(s) for Character Mode are set
    final int CHARACTER_MODE_BITMASK = 0x0001; // <-- replace with correct mask
    return (flags & CHARACTER_MODE_BITMASK) != 0;
}
private boolean isExtendedFieldModeRequested(int flags) {
    final int EXTENDED_FIELD_BITMASK = 0x0002; // <-- replace with correct mask
    return (flags & EXTENDED_FIELD_BITMASK) != 0;
}

// Convenience: expose a reset helper
private void resetReplyModeToDefault() {
    currentReplyMode = ReplyMode.FIELD;
    replyModeFlags = 0;
    System.out.println("Reply Mode reset to FIELD (default).");
}

	private void parseAndStoreReplyModeFlagsOld(byte[] payload) {
    	// Parse per spec — for now, read up to 4 bytes safely and store as int
    	int flags = 0;
    	for (int i = 0; i < Math.min(4, payload.length); i++) {
        	flags = (flags << 8) | (payload[i] & 0xFF);
    	}
    	replyModeFlags = flags;
    	System.out.printf("Saved replyModeFlags=0x%X%n", flags);
	}

	private String replyModeFlagsToString() {
    	return String.format("0x%08X", replyModeFlags);
	}

	// Handle Data Chain (IND$FILE) operations
	private void handleDataChain(byte[] data, int offset, int length) {
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

	// Handle DC_OPEN
	public void initiateFileTransfer(String localFilePath, String command, boolean isDownload) {
		try {
			currentFile = new File(localFilePath);
			ftDirection = isDownload ? FileTransferDirection.DOWNLOAD : FileTransferDirection.UPLOAD;

			// Determine if text or binary based on command
			ftIsText = command.toUpperCase().contains("ASCII") || command.toUpperCase().contains("CRLF");

			// Reset message flag at start of transfer ***
			ftIsMessage = false;

			ftHadSuccessfulTransfer = false;

			System.out.println("Initiating file transfer:");
			System.out.println("  Local file: " + localFilePath);
			System.out.println("  Direction: " + (isDownload ? "Download (Host->PC)" : "Upload (PC->Host)"));
			System.out.println("  Mode: " + (ftIsText ? "TEXT" : "BINARY"));
			System.out.println("  Command: " + command);

			// Show progress dialog
			showProgressDialog(isDownload ? "Downloading from Host" : "Uploading to Host");
			updateProgressDialog("Sending command to host...", currentFile.getName());

			// Type the command into the current field and send it
			for (char c : command.toCharArray()) {
				if (!isProtected(cursorPos)) {
					buffer[cursorPos] = c;
					setModified(cursorPos);
					cursorPos = (cursorPos + 1) % (rows * cols);
				}
			}

			canvas.repaint();

			// Send the command with ENTER
			// System.out.println("Calling SendAID to send IND$FILE command...");
			sendAID(AID_ENTER);

		} catch (Exception e) {
			e.printStackTrace();
			statusBar.setStatus("File transfer error: " + e.getMessage());
		}
	}

	// Handle DC_OPEN
	private void handleDCOpen(byte[] data, int offset, int length) {
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

	// Handle DC_CLOSE
	private void handleDCClose(byte[] data, int offset, int length) {
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

	// Handle DC_SET_CURSOR
	private void handleDCSetCursor(byte[] data, int offset, int length) {
		System.out.println("=== DC_SET_CURSOR received ===");
		// This is typically followed by DC_GET in the same WSF
		// The Set Cursor command just positions the cursor, no response needed here
		// The response will be sent with the DC_GET
	}

	// Handle DC_GET (for upload from PC to Host)
	private void handleDCGet(byte[] data, int offset, int length) {
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
							//foundLine = true;
							//break;
						} else //{
							// Bare CR followed by something else - reset to keep the next char
							//lineBuffer.write(ch);
							if (next != -1) {
								uploadStream.reset();
							}
						//}
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

	// Handle DC_INSERT (for download from Host to PC)
	private void handleDCInsert(byte[] data, int offset, int length) {
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

				// Check transfer success
				/* 
				boolean hadSuccess = false;
				long fileSize = 0;
				File completedFile = currentFile;  // ← Save reference

				if (completedFile != null && completedFile.exists()) {
					fileSize = completedFile.length();
					hadSuccess = (fileSize > 0);
				}
				*/
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
				boolean isError = message.contains("Error") || message.contains("TRANS13") || message.contains("TRANS14");

				if (isSuccess && !isError) {
    				// Success - show completion message
    				String successMsg = "Transfer complete!\n\n" + "Host message: " + message;
    				if (currentFile != null) {
        				successMsg = "Transfer complete!\n\nFile: " + currentFile.getName() + "\n\nHost message: " + message;
    				}
    				showMessageDialog(successMsg, "Transfer Complete");
    				statusBar.setStatus("Transfer complete" + (currentFile != null ? ": " + currentFile.getName() : ""));
				} else if (isError) {
    				// Failure - show error
    				showMessageDialog("Transfer failed:\n\n" + message, "Transfer Error");
    				statusBar.setStatus("Transfer failed");
				} else {
    				// Unknown status - show message as-is
    				showMessageDialog("Transfer status:\n\n" + message, "Transfer Status");
    				statusBar.setStatus("Transfer completed");
				}
				// Show appropriate message
				/* 
				if (hadSuccess && completedFile != null) {
					String successMsg = "Transfer complete!\n\n" + "File: " + completedFile.getName() + "\n" + "Size: "
							+ fileSize + " bytes\n\n" + "Host message: " + message;
					System.out.println("=== SHOWING SUCCESS DIALOG ===");
					System.out.println(successMsg);
					showMessageDialog(successMsg, "Transfer Complete");
					statusBar.setStatus("Transfer complete: " + currentFile.getName());
				} else {
					showMessageDialog("Transfer failed:\n\n" + message, "Transfer Error");
					statusBar.setStatus("Transfer failed");
				}
				*/
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

	// Send DC_OPEN response
	private void sendDCOpenResponse(boolean success, int errorCode) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();

			if (success) {
				// Positive response
				baos.write(0x00); // Length MSB
				baos.write(0x05); // Length LSB
				baos.write(SFID_DATA_CHAIN);
				baos.write(DC_OPEN);
				baos.write(RESP_POSITIVE);
			} else {
				// Negative response
				baos.write(0x00); // Length MSB
				baos.write(0x09); // Length LSB
				baos.write(SFID_DATA_CHAIN);
				baos.write(DC_OPEN);
				baos.write(RESP_NEGATIVE);
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

	// Send DC_CLOSE response
	private void sendDCCloseResponse(boolean success, int errorCode) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();

			if (success) {
				baos.write(0x00);
				baos.write(0x05);
				baos.write(SFID_DATA_CHAIN);
				baos.write(DC_CLOSE);
				baos.write(RESP_POSITIVE);
			} else {
				baos.write(0x00);
				baos.write(0x09);
				baos.write(SFID_DATA_CHAIN);
				baos.write(DC_CLOSE);
				baos.write(RESP_NEGATIVE);
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

	// Send DC_GET response
	private void sendDCGetResponse(boolean success, int errorCode, byte[] data, int dataLen) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();

			if (success && data != null && dataLen > 0) {
				// Calculate lengths
				int dataLenField = dataLen + 5; // Data + overhead (C0 80 61 dd dd)
				int responseLen = 2 + 1 + 1 + 1 + 1 + 1 + 4 + 1 + 1 + 1 + 2 + dataLen;
				// That's: length(2) + SFID(1) + op(1) + header(3) + seq(4) + flags(3) +
				// datalen(2) + data

				baos.write((byte) ((responseLen >> 8) & 0xFF));
				baos.write((byte) (responseLen & 0xFF));
				baos.write(SFID_DATA_CHAIN);
				baos.write(DC_GET);
				baos.write(0x05);
				baos.write(0x63);
				baos.write(0x06);
				baos.write((byte) ((blockSequence >> 24) & 0xFF));
				baos.write((byte) ((blockSequence >> 16) & 0xFF));
				baos.write((byte) ((blockSequence >> 8) & 0xFF));
				baos.write((byte) (blockSequence & 0xFF));
				baos.write((byte) 0xC0);
				baos.write((byte) 0x80);
				baos.write((byte) 0x61);
				baos.write((byte) ((dataLenField >> 8) & 0xFF));
				baos.write((byte) (dataLenField & 0xFF));
				baos.write(data, 0, dataLen);
			} else {
				baos.write(0x00);
				baos.write(0x09);
				baos.write(SFID_DATA_CHAIN);
				baos.write(DC_GET);
				baos.write(RESP_NEGATIVE);
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

	// Send DC_INSERT response
	private void sendDCInsertResponse(boolean success, int errorCode) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();

			if (success) {
				baos.write(0x00);
				baos.write(0x0B);
				baos.write(SFID_DATA_CHAIN);
				baos.write(DC_INSERT);
				baos.write(0x05);
				baos.write(0x63);
				baos.write(0x06);
				// Block sequence number (4 bytes)
				baos.write((byte) ((blockSequence >> 24) & 0xFF));
				baos.write((byte) ((blockSequence >> 16) & 0xFF));
				baos.write((byte) ((blockSequence >> 8) & 0xFF));
				baos.write((byte) (blockSequence & 0xFF));
			} else {
				baos.write(0x00);
				baos.write(0x09);
				baos.write(SFID_DATA_CHAIN);
				baos.write(DC_INSERT);
				baos.write(RESP_NEGATIVE);
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

	// Send structured field response with AID 0x88
	private void sendStructuredFieldResponse(byte[] sfData) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(AID_STRUCTURED_FIELD);
		baos.write(sfData);

		byte[] response = baos.toByteArray();

		System.out.println("=== SENDING STRUCTURED FIELD RESPONSE ===");
		for (int j = 0; j < response.length && j < 100; j++) {
			System.out.print(String.format("%02X ", response[j]));
			if ((j + 1) % 16 == 0)
				System.out.println();
		}
		System.out.println();

		sendData(response);

		// CRITICAL: Ensure the response is actually sent
		output.flush(); // Add this if not already in sendData
	}

	private void sendQueryResponse() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();

			// AID and cursor address
			// baos.write(AID_ENTER);
			// byte[] cursorAddr = encode3270Address(cursorPos);
			// baos.write(cursorAddr[0]);
			// baos.write(cursorAddr[1]);

			int reportRows = alternateRows; // 32 for 3279-3
			int reportCols = alternateCols; // 80
			int reportBufsize = reportRows * reportCols; // 2560

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

			/*
			 * // Device characteristics baos.write((byte) 0x00); baos.write((byte) 0x1B);
			 * baos.write((byte) 0x81); baos.write((byte) 0x40); baos.write((byte) 0x00);
			 * baos.write((byte) 0x00); baos.write((byte) 0x00); baos.write((byte) 0x00);
			 * baos.write((byte) 0x00); baos.write((byte) 0x00); baos.write((byte) 0x00);
			 * baos.write((byte) 0x00); baos.write((byte) 0x00); baos.write((byte) 0x18);
			 * baos.write((byte) 0x00); baos.write((byte)(reportCols & 0xFF)); // Width MSB
			 * (0x50 = 80) baos.write((byte)((reportCols >> 8) & 0xFF)); // Width LSB (0x00)
			 * baos.write((byte) 0x2B); baos.write((byte) 0x00); baos.write((byte) 0x84);
			 * baos.write((byte) 0x07); baos.write((byte) 0x80); baos.write((byte) 0x81);
			 * baos.write((byte) 0x00); baos.write((byte) 0x02); baos.write((byte) 0x00);
			 * baos.write((byte) 0x00);
			 */
			// ===== QUERY REPLY (DEVICE CHARACTERISTICS 0x84) =====
			// PCOMM: Data='0008 8184 001E0004'x 
			baos.write(0x00);
			baos.write(0x08);        // Length = 8 bytes
			baos.write(0x81);        // Query Reply
			baos.write(0x84);        // Device Characteristics QRID
			baos.write(0x00);        // Byte 4: Device type/features
			baos.write(0x0D);        // Byte 5: Model (0x0D = Model 4, 0x70 for others)
			baos.write(0x70);        // Byte 6: Extended features
			baos.write(0x00);        // Byte 7: Reserved

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
			baos.write(0x07);        // Length = 7 bytes
			baos.write(0x81);        // Query Reply
			baos.write(0x8C);        // Format Presentation QRID
			baos.write(0x00);        // Reserved
			baos.write(0x00);        // Supports Format Presentation (was 0x01)
			baos.write(0x00);        // Reserved

			// 00 0c 81 95 00 00 40 00 40 00 01 01
			// ===== QUERY REPLY (DDM) =====
			// PCOMM:  Data='000C 8195 000009C409C40101'x
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
			baos.write(0x06);        // Length = 6 bytes
			baos.write(0x81);        // Query Reply
			baos.write(0x99);        // Storage Pools QRID
			baos.write(0x00);        // Reserved
			baos.write(0x00);        // Reserved

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
			baos.write(0x06);        // Length = 6 bytes
			baos.write(0x81);        // Query Reply
			baos.write(0xA8);        // DDM QRID
			baos.write(0x00);        // Flags
			baos.write(0x01);        // DDM subset identifier

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
baos.write(0x04);        // Length = 4 bytes
baos.write(0x81);        // Query Reply
baos.write(0xB0);        // Begin/End of File QRID

// ===== QUERY REPLY (0xB1 - Data Chaining) =====
baos.write(0x00);
baos.write(0x06);        // Length = 6 bytes
baos.write(0x81);        // Query Reply
baos.write(0xB1);        // Data Chaining QRID
baos.write(0x00);        // Maximum number of requests
baos.write(0x00);        // Reserved

// ===== QUERY REPLY (0xB2 - Destination/Origin) =====
baos.write(0x00);
baos.write(0x04);        // Length = 4 bytes
baos.write(0x81);        // Query Reply
baos.write(0xB2);        // Destination/Origin QRID

// ===== QUERY REPLY (0xB3 - Object Control) =====
baos.write(0x00);
baos.write(0x04);        // Length = 4 bytes
baos.write(0x81);        // Query Reply
baos.write(0xB3);        // Object Control QRID

// ===== QUERY REPLY (0xB4 - Object Picture) =====
baos.write(0x00);
baos.write(0x04);        // Length = 4 bytes
baos.write(0x81);        // Query Reply
baos.write(0xB4);        // Object Picture QRID

// ===== QUERY REPLY (0xB6 - Save/Restore Format) =====
baos.write(0x00);
baos.write(0x04);        // Length = 4 bytes
baos.write(0x81);        // Query Reply
baos.write(0xB6);        // Save/Restore Format QRID

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

	private int decode3270AddressNew(byte b1, byte b2) {

    	// Check for 14-bit extended addressing (rare)
    	if ((b1 & 0xC0) != 0x00 || (b2 & 0xC0) != 0x00) {
        	int addr14 = ((b1 & 0x3F) << 8) | (b2 & 0xFF);
        	return addr14 % (rows * cols);
    	}

    	// Standard 12-bit addressing
    	int addr12 = ((b1 & 0x3F) << 6) | (b2 & 0x3F);
    	return addr12 % (rows * cols);
	}

	private int decode3270Address(byte b1, byte b2) {
		int addr;
		int b1val = b1 & 0xFF;
		int b2val = b2 & 0xFF;

		// Check for 14-bit addressing: if high byte >= 0x40, it's 12-bit
		// If high byte < 0x40, it's 14-bit
		if ((b1val & 0xC0) == 0) {
			// 14-bit addressing: straight binary
			addr = (b1val << 8) | b2val;
		} else {
			// 12-bit addressing: remove 0x40 offset and combine 6+6 bits
			addr = ((b1val & 0x3F) << 6) | (b2val & 0x3F);
		}

		return addr % (rows * cols);
	}

	// Encode address into 2 bytes for insertion into a 3270 stream.
	// Returns a two-element byte[] {b1, b2}.
	private byte[] encode3270AddressNew(int addr) {
    	int max = rows * cols;
    	int a = ((addr % max) + max) % max; // normalize positive index

    	if (a <= 0x0FFF) {
        	// 12-bit encoding: two 6-bit pieces, ordinarily stored with the high 2 bits zero.
        	byte b1 = (byte) (((a >> 6) & 0x3F) | 0x40); // often a 0x40 bias used by some hosts
        	//byte b2 = (byte) ((a & 0x3F) | 0x40);
			byte b2 = (byte) (a & 0x3F);
        	// Note: some specs use 0x40 bias, some expect raw 6-bit; adapt if necessary.
        	return new byte[] { b1, b2 };
    	} else {
        	// 14-bit encoding: put low 8 bits in b2, top 6 bits in b1
        	byte b1 = (byte) (((a >> 8) & 0x3F) | 0xC0); // mark as extended (implementation-dependent)
        	byte b2 = (byte) (a & 0xFF);
        	return new byte[] { b1, b2 };
    	}
	}

	private byte[] encode3270Address(int addr) {
		byte[] result = new byte[2];

		// Ensure address is within 14-bit range
		addr = addr & 0x3FFF;

		// IBM standard: addresses >= 4096 require 14-bit addressing
		if (addr >= 0x1000) {
			// 14-bit addressing: straight binary
			result[0] = (byte) ((addr >> 8) & 0xFF);
			result[1] = (byte) (addr & 0xFF);
		} else {
			// 12-bit addressing: use EBCDIC address translation
			int high6 = (addr >> 6) & 0x3F;
			int low6 = addr & 0x3F;
			result[0] = ADDRESS_TABLE[high6];
			result[1] = ADDRESS_TABLE[low6];
		}

		return result;
	}

	// --- Helper: safeConsume ---
// Returns true if there are at least `need` bytes available from data at index idx (inclusive).
private static boolean safeConsume(byte[] data, int idx, int need) {
    return idx + need - 1 < data.length;
}

// --- Helper: fetchDisplayChar ---
// Consumes 1 or 2 stream bytes (GE escape + operand) and returns the translated display char.
// idxRef is a single-element int[] containing the current index in data. This method
// will advance idxRef[0] by the number of bytes it consumes.
private char fetchDisplayChar(byte[] data, int[] idxRef) {
    int i = idxRef[0];
    if (!safeConsume(data, i, 1)) {
        // truncated - return space and do not advance
        return ' ';
    }
    byte b = data[i];
    if (b == ORDER_GE) {
        // GE escape: require one more byte (the graphic operand)
        if (!safeConsume(data, i + 1, 1)) {
            // truncated GE -> treat as space, advance past GE byte to avoid loop
            idxRef[0] = i + 1;
            return ' ';
        }
        byte operand = data[i + 1];
        idxRef[0] = i + 2; // consumed GE + operand
        // Translate operand from EBCDIC->APL (table must exist in your code)
        return EBCDIC_TO_APL[operand & 0xFF];
    } else {
        // Normal single-byte display char (EBCDIC -> ASCII)
        idxRef[0] = i + 1; // consumed one byte
        return EBCDIC_TO_ASCII[b & 0xFF];
    }
}

	private void sendReadBuffer() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			// baos.write(AID_ENTER);
			baos.write(lastAID); // Changed from AID_ENTER
			baos.write(encode3270Address(cursorPos)[0]);
			baos.write(encode3270Address(cursorPos)[1]);

			for (int i = 0; i < buffer.length; i++) {
				if (isFieldStart(i)) {
					baos.write(ORDER_SF);
					baos.write(attributes[i]);
				} else {
					char c = buffer[i];
					if (c == '\0') {
						baos.write(0x00);
					} else if (c < 256 && ASCII_TO_EBCDIC[c] != 0) {
						baos.write(ASCII_TO_EBCDIC[c]);
					} else {
						baos.write(0x40);
					}
				}
			}

			sendData(baos.toByteArray());
			keyboardLocked = true;
			statusBar.update();

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void sendAID(byte aid) {
		lastAID = aid;
		System.out.println("Sending AID: 0x" + String.format("%02X", aid));
		System.out.println("Cursor position (decimal): " + cursorPos);
		System.out.println("Cursor row: " + (cursorPos / cols) + ", col: " + (cursorPos % cols));
		byte[] encodedCursor = encode3270Address(cursorPos);
		System.out.println("Encoded cursor: " + String.format("%02X %02X", encodedCursor[0], encodedCursor[1]));
		keyboardLocked = true;
		statusBar.update();
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write(aid);
			baos.write(encode3270Address(cursorPos)[0]);
			baos.write(encode3270Address(cursorPos)[1]);

			// Reset Reply Mode if CLEAR key is pressed
			if (aid == AID_CLEAR) resetReplyModeToDefault();

			// For read-modified operations, include modified fields
			if (aid == AID_ENTER || (aid >= AID_PF1 && aid <= AID_PF9) || aid == AID_PF10 || aid == AID_PF11
					|| aid == AID_PF12) {

				int screenSize = rows * cols;
				for (int i = 0; i < screenSize; i++) {
					if (isFieldStart(i) && (attributes[i] & 0x01) != 0) {
						// Found a modified field
						int fieldStart = i;
						int end = findNextField(i);

						// Find first non-null character in field
						int dataStart = fieldStart + 1;
						while (dataStart < end && buffer[dataStart] == '\0') {
							dataStart++;
						}

						// Find last non-null character in field
						int dataEnd = end - 1;
						while (dataEnd > fieldStart && (buffer[dataEnd] == '\0' || buffer[dataEnd] == ' ')) {
							dataEnd--;
						}

						// Only send if there's actual data
						if (dataStart <= dataEnd) {
							baos.write(ORDER_SBA);
							byte[] addr = encode3270Address(dataStart);
							baos.write(addr[0]);
							baos.write(addr[1]);

							System.out.println("Sending modified field: start=" + dataStart + " end=" + dataEnd
									+ " fieldAttr=" + fieldStart);

							for (int j = dataStart; j <= dataEnd; j++) {
								if (!isFieldStart(j)) {
									char c = buffer[j];
									if (c != '\0') {
										if (c < 256 && ASCII_TO_EBCDIC[c] != 0) {
											baos.write(ASCII_TO_EBCDIC[c]);
										} else {
											baos.write(0x40);
										}
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
		canvas.repaint();
	}

	private void sendData(byte[] data) throws IOException {
		ByteArrayOutputStream fullPacket = new ByteArrayOutputStream();

		if (tn3270eMode) {
			fullPacket.write(TN3270E_DT_3270_DATA);
			fullPacket.write(0);
			fullPacket.write(0);
			fullPacket.write(0);
			fullPacket.write(0);
		}

		fullPacket.write(data);

		// Add EOR marker to the SAME packet
		fullPacket.write(IAC);
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

		// Write everything in ONE operation
		output.write(completePacket);
		output.flush();
	}

	private boolean isFieldStart(int pos) {
		return attributes[pos] != 0;
	}

	private boolean isProtected(int pos) {
		int fieldStart = findFieldStart(pos);
		return (attributes[fieldStart] & 0x20) != 0;
	}

	private void clearUnprotectedModifiedFlags() {
		for (int i = 0; i < rows * cols; i++) {
			if (isFieldStart(i) && !isProtected(i)) {
				// clearModified(i);
				attributes[i] &= 0xFE;
			}
		}
	}

	private boolean isNonDisplay(int pos) {
		int fieldStart = findFieldStart(pos);
		return (attributes[fieldStart] & 0x0C) == 0x0C;
	}

	private boolean isModified(int pos) {
		int fieldStart = findFieldStart(pos);
		return (attributes[fieldStart] & 0x01) != 0;
		// return (attributes[pos] & 0x01) != 0;
	}

	private void clearModified(int pos) {
		int fieldStart = findFieldStart(pos);
		attributes[fieldStart] &= 0xFE; // clear MDT
	}

	private int findFieldStart(int pos) {
		int start = pos;
		while (!isFieldStart(start)) {
			start = (start - 1 + buffer.length) % buffer.length;
			if (start == pos)
				break;
		}
		return start;
	}

	private int findNextField(int pos) {
		int next = (pos + 1) % buffer.length;
		int count = 0;
		int maxSize = rows * cols;
		while (!isFieldStart(next) && next != pos && count < maxSize) {
			next = (next + 1) % buffer.length;
			count++;
		}
		return next;
	}

	private void resetMDT() {
		for (int i = 0; i < attributes.length; i++) {
			attributes[i] &= ~0x01;
		}
	}

	private void clearScreen() {
		int size = rows * cols; // Keep the loop-based version
		for (int i = 0; i < size; i++) {
			buffer[i] = ' ';
			attributes[i] = 0;
			extendedColors[i] = 0;
			highlighting[i] = 0;
		}
		cursorPos = 0;
	}

	public void keyPressed(KeyEvent e) {
		// Handle copy/paste shortcuts
		if (e.isControlDown()) {
			if (e.getKeyCode() == KeyEvent.VK_C) {
				copySelection();
				return;
			} else if (e.getKeyCode() == KeyEvent.VK_V) {
				pasteFromClipboard();
				return;
			} else if (e.getKeyCode() == KeyEvent.VK_A) {
				selectAll();
				return;
			}
		}

		// Clear selection on any key press (except copy/paste)
		if (selectionStart >= 0 || selectionEnd >= 0) {
			clearSelection();
		}

		int keyCode = e.getKeyCode();

		// Handle navigation keys BEFORE the keyboard lock check
		switch (keyCode) {
		case KeyEvent.VK_LEFT:
			cursorPos = (cursorPos - 1 + buffer.length) % buffer.length;
			canvas.repaint();
			statusBar.update();
			return;

		case KeyEvent.VK_RIGHT:
			cursorPos = (cursorPos + 1) % buffer.length;
			canvas.repaint();
			statusBar.update();
			return;

		case KeyEvent.VK_UP:
			cursorPos = (cursorPos - cols + buffer.length) % buffer.length;
			canvas.repaint();
			statusBar.update();
			return;

		case KeyEvent.VK_DOWN:
			cursorPos = (cursorPos + cols) % buffer.length;
			canvas.repaint();
			statusBar.update();
			return;

		case KeyEvent.VK_HOME:
			cursorPos = 0;
			canvas.repaint();
			statusBar.update();
			return;
		}

		if (keyboardLocked || !connected) {
			Toolkit.getDefaultToolkit().beep();
			return;
		}

		// Check for custom key mapping first
		KeyMapping mapping = keyMap.get(keyCode);
		if (mapping != null) {
			if (mapping.aid != null) {
				// Mapped to AID function
				sendAID(mapping.aid);
				return;
			} else {
				// Mapped to character - handle in keyTyped
				// (let it fall through)
			}
		}

		// Handle function keys with explicit mapping
		if (keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12) {
			byte aid;
			switch (keyCode) {
			case KeyEvent.VK_F1:
				aid = AID_PF1;
				break;
			case KeyEvent.VK_F2:
				aid = AID_PF2;
				break;
			case KeyEvent.VK_F3:
				aid = AID_PF3;
				break;
			case KeyEvent.VK_F4:
				aid = AID_PF4;
				break;
			case KeyEvent.VK_F5:
				aid = AID_PF5;
				break;
			case KeyEvent.VK_F6:
				aid = AID_PF6;
				break;
			case KeyEvent.VK_F7:
				aid = AID_PF7;
				break;
			case KeyEvent.VK_F8:
				aid = AID_PF8;
				break;
			case KeyEvent.VK_F9:
				aid = AID_PF9;
				break;
			case KeyEvent.VK_F10:
				aid = AID_PF10;
				break;
			case KeyEvent.VK_F11:
				aid = AID_PF11;
				break;
			case KeyEvent.VK_F12:
				aid = AID_PF12;
				break;
			default:
				return;
			}
			
			sendAID(aid);
			return;
		}

		switch (keyCode) {
		case KeyEvent.VK_ESCAPE:
			clearScreen();
			sendAID(AID_CLEAR);
			canvas.repaint();
			return;

		case KeyEvent.VK_ENTER:
			sendAID(AID_ENTER);
			return;

		case KeyEvent.VK_INSERT:
			insertMode = !insertMode;
			statusBar.update();
			canvas.repaint();
			return;

		case KeyEvent.VK_TAB:
			if (e.isShiftDown()) {
				tabToPreviousField();
			} else {
				tabToNextField();
			}
			return;

		case KeyEvent.VK_BACK_SPACE:
			if (!isProtected(cursorPos)) {
				moveCursor(-1);
				if (!isProtected(cursorPos)) {
					buffer[cursorPos] = ' ';
					setModified(cursorPos);
					canvas.repaint();
				}
			}
			return;
		}
	}

	public void keyTyped(KeyEvent e) {
		// System.out.println("keyTyped: locked=" + keyboardLocked +
		// " connected=" + connected +
		// " cursorPos=" + cursorPos +
		// " isProtected=" + isProtected(cursorPos) +
		// " isFieldStart=" + isFieldStart(cursorPos) +
		// " char='" + e.getKeyChar() + "' (" + (int)e.getKeyChar() + ")" +
		// " insertMode=" + insertMode);

		if (keyboardLocked || !connected)
			return;

		char c = e.getKeyChar();

		KeyMapping mapping = keyMap.get(e.getKeyCode());
		if (mapping != null && mapping.aid == null) {
			c = mapping.character;
		}

		// System.out.println("Processing character: '" + c + "' (code " + (int)c +
		// ")");

		if (c < 32 || c > 126)
			return;

		if (!isProtected(cursorPos)) {
			if (insertMode) {
				int fieldStart = findFieldStart(cursorPos);
				int fieldEnd = findNextField(fieldStart);

				int lastPos = fieldEnd - 1;
				if (isFieldStart(lastPos))
					lastPos--;

				char lastChar = buffer[lastPos];
				if (lastChar != '\0' && lastChar != ' ') {
					Toolkit.getDefaultToolkit().beep();
					return;
				}

				// Shift characters right
				for (int i = lastPos; i > cursorPos; i--) {
					if (!isFieldStart(i) && !isFieldStart(i - 1)) {
						buffer[i] = buffer[i - 1];
					}
				}
			}

			// Common code for both insert and replace modes
			buffer[cursorPos] = c;
			setModified(cursorPos);

			// Check if we need to auto-advance BEFORE moving cursor
			int nextPos = (cursorPos + 1) % (rows * cols);
			moveCursor(1);

			// Only auto-advance if the NEXT position is a field boundary
			if (isFieldStart(nextPos)) {
				tabToNextField();
			}

			canvas.repaint();
		} else {
			Toolkit.getDefaultToolkit().beep();
		}
	}

	public void keyReleased(KeyEvent e) {
	}

	private void moveCursor(int delta) {
		cursorPos = (cursorPos + delta + buffer.length) % buffer.length;
		canvas.repaint();
		statusBar.update();
	}

	private void tabToNextField() {
		int start = cursorPos;
		int count = 0;
		int maxSize = rows * cols;

		do {
			cursorPos = (cursorPos + 1) % (rows * cols);
			count++;

			if (isFieldStart(cursorPos)) {
				// We're on a field attribute, check if the field itself is protected
				boolean fieldIsProtected = (attributes[cursorPos] & 0x20) != 0;

				if (!fieldIsProtected) {
					// Move to first position after the field attribute
					cursorPos = (cursorPos + 1) % (rows * cols);
					canvas.repaint();
					statusBar.update();
					return;
				}
			}
		} while (cursorPos != start && count < maxSize);

		// No unprotected field found, stay where we are
		cursorPos = start;
		canvas.repaint();
		statusBar.update();
	}

	private void tabToPreviousField() {
		int start = cursorPos;
		int count = 0;
		int maxSize = rows * cols;

		do {
			cursorPos = (cursorPos - 1 + rows * cols) % (rows * cols);
			count++;

			if (isFieldStart(cursorPos)) {
				// We're on a field attribute, check if the field itself is protected
				boolean fieldIsProtected = (attributes[cursorPos] & 0x20) != 0;

				if (!fieldIsProtected) {
					// Move to first position after the field attribute
					cursorPos = (cursorPos + 1) % (rows * cols);
					canvas.repaint();
					statusBar.update();
					return;
				}
			}
		} while (cursorPos != start && count < maxSize);

		// No unprotected field found, stay where we are
		cursorPos = start;
		canvas.repaint();
		statusBar.update();
	}

	private void setModified(int pos) {
		int fieldStart = findFieldStart(pos);
		attributes[fieldStart] |= 0x01;
	}

	private void eraseToEndOfField() {
		if (isProtected(cursorPos)) {
			Toolkit.getDefaultToolkit().beep();
			return;
		}

		int fieldStart = findFieldStart(cursorPos);
		int fieldEnd = findNextField(fieldStart);

		for (int i = cursorPos; i < fieldEnd && !isFieldStart(i); i++) {
			buffer[i] = '\0';
		}

		setModified(cursorPos);
		canvas.repaint();
		statusBar.update();
		// Don't lock keyboard - this is a local editing operation
	}

	private void eraseToEndOfLine() {
		if (isProtected(cursorPos)) {
			Toolkit.getDefaultToolkit().beep();
			return;
		}

		int row = cursorPos / cols;
		int endOfLine = (row + 1) * cols;
		int fieldStart = findFieldStart(cursorPos);
		int fieldEnd = findNextField(fieldStart);

		for (int i = cursorPos; i < endOfLine && i < fieldEnd && !isFieldStart(i); i++) {
			buffer[i] = '\0';
		}

		setModified(cursorPos);
		canvas.repaint();
		statusBar.update();
		// Don't lock keyboard - this is a local editing operation
	}

// Add this new class for the ribbon toolbar
	class RibbonToolbar extends Panel {
		private TN3270Emulator emulator;

		public RibbonToolbar(TN3270Emulator emulator) {
			this.emulator = emulator;
			setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
			setBackground(new Color(240, 240, 240));

			// Connection group
			add(createLabel("Connection:"));
			add(createIconButton("Connect", "connect", e -> showConnectionDialog()));
			add(createIconButton("Disconnect", "disconnect", e -> emulator.disconnect()));
			add(createSeparator());

			// File Transfer group
			add(createLabel("File Transfer:"));
			add(createIconButton("Upload", "upload", e -> emulator.showFileTransferDialog(false)));
			add(createIconButton("Download", "download", e -> emulator.showFileTransferDialog(true)));
			add(createSeparator());

			// Edit group
			add(createLabel("Edit:"));
			add(createIconButton("Copy", "copy", e -> emulator.copySelection()));
			add(createIconButton("Paste", "paste", e -> emulator.pasteFromClipboard()));
			add(createSeparator());

			// Settings group
			add(createLabel("Settings:"));
			add(createIconButton("Keyboard", "keyboard", e -> emulator.showKeyboardMappingDialog()));
			add(createIconButton("Colors", "colors", e -> emulator.showColorSchemeDialog()));
			add(createIconButton("Font", "font", e -> emulator.showFontSizeDialog()));
		}

		private Label createLabel(String text) {
			Label label = new Label(text);
			label.setFont(new Font("SansSerif", Font.BOLD, 11));
			label.setForeground(new Color(80, 80, 80));
			return label;
		}

		private Component createSeparator() {
			Canvas sep = new Canvas() {
				public Dimension getPreferredSize() {
					return new Dimension(2, 32);
				}

				public void paint(Graphics g) {
					g.setColor(new Color(200, 200, 200));
					g.fillRect(0, 4, 1, 24);
				}
			};
			return sep;
		}

		private Button createIconButton(String text, String iconName, ActionListener action) {
			Button btn = new Button() {
				private boolean hover = false;
				private BufferedImage icon;

				{
					// Generate icon
					icon = createIcon(iconName);

					addMouseListener(new MouseAdapter() {
						public void mouseEntered(MouseEvent e) {
							hover = true;
							repaint();
						}

						public void mouseExited(MouseEvent e) {
							hover = false;
							repaint();
						}
					});
				}

				public Dimension getPreferredSize() {
					return new Dimension(70, 50);
				}

				public void paint(Graphics g) {
					Graphics2D g2 = (Graphics2D) g;
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

					// Background
					if (hover) {
						g2.setColor(new Color(220, 235, 250));
						g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
						g2.setColor(new Color(100, 150, 200));
						g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
					} else {
						g2.setColor(getBackground());
						g2.fillRect(0, 0, getWidth(), getHeight());
					}

					// Icon
					if (icon != null) {
						int iconX = (getWidth() - icon.getWidth()) / 2;
						g2.drawImage(icon, iconX, 4, null);
					}

					// Text
					g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
					FontMetrics fm = g2.getFontMetrics();
					int textWidth = fm.stringWidth(text);
					int textX = (getWidth() - textWidth) / 2;
					g2.setColor(Color.BLACK);
					g2.drawString(text, textX, getHeight() - 6);
				}
			};

			btn.addActionListener(action);
			return btn;
		}

		private BufferedImage createIcon(String name) {
			BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setStroke(new BasicStroke(2.0f));

			switch (name) {
			case "connect":
				// Network plug icon
				g.setColor(new Color(50, 150, 50));
				g.drawOval(4, 4, 16, 16);
				g.fillOval(10, 10, 4, 4);
				g.drawLine(12, 4, 12, 10);
				g.drawLine(12, 14, 12, 20);
				break;

			case "disconnect":
				// Disconnected plug icon
				g.setColor(new Color(200, 50, 50));
				g.drawOval(4, 4, 16, 16);
				g.drawLine(6, 6, 18, 18);
				g.drawLine(18, 6, 6, 18);
				break;

			case "upload":
				// Up arrow
				g.setColor(new Color(50, 100, 200));
				int[] xUp = { 12, 6, 12, 12, 18, 12 };
				int[] yUp = { 4, 12, 12, 20, 12, 4 };
				g.fillPolygon(xUp, yUp, 6);
				break;

			case "download":
				// Down arrow
				g.setColor(new Color(50, 100, 200));
				int[] xDown = { 12, 6, 12, 12, 18, 12 };
				int[] yDown = { 20, 12, 12, 4, 12, 20 };
				g.fillPolygon(xDown, yDown, 6);
				break;

			case "copy":
				// Two overlapping rectangles
				g.setColor(new Color(100, 100, 100));
				g.drawRect(6, 8, 10, 12);
				g.drawRect(8, 4, 10, 12);
				break;

			case "paste":
				// Clipboard icon
				g.setColor(new Color(100, 100, 100));
				g.drawRect(6, 6, 12, 14);
				g.drawRect(9, 4, 6, 3);
				g.drawLine(8, 10, 16, 10);
				g.drawLine(8, 13, 16, 13);
				g.drawLine(8, 16, 16, 16);
				break;

			case "keyboard":
				// Keyboard icon
				g.setColor(new Color(80, 80, 80));
				g.drawRoundRect(2, 8, 20, 12, 3, 3);
				for (int i = 0; i < 3; i++) {
					for (int j = 0; j < 5; j++) {
						g.fillRect(4 + j * 4, 10 + i * 3, 2, 2);
					}
				}
				break;

			case "colors":
				// Color palette icon
				g.setColor(Color.RED);
				g.fillOval(4, 4, 6, 6);
				g.setColor(Color.GREEN);
				g.fillOval(14, 4, 6, 6);
				g.setColor(Color.BLUE);
				g.fillOval(4, 14, 6, 6);
				g.setColor(Color.YELLOW);
				g.fillOval(14, 14, 6, 6);
				break;

			case "font":
				// "A" letter icon
				g.setColor(Color.BLACK);
				g.setFont(new Font("SansSerif", Font.BOLD, 18));
				g.drawString("A", 6, 18);
				break;
			}

			g.dispose();
			return img;
		}
	}

	class EnhancedRibbonToolbar extends Panel {
		private TN3270Emulator emulator;

		public EnhancedRibbonToolbar(TN3270Emulator emulator) {
			this.emulator = emulator;
			setLayout(new BorderLayout());
			setBackground(new Color(245, 245, 245));

			// Main toolbar
			Panel mainBar = new Panel(new FlowLayout(FlowLayout.LEFT, 2, 2));
			mainBar.setBackground(new Color(245, 245, 245));

			// Connection group
			//mainBar.add(createGroupLabel("Connection"));
			mainBar.add(createIconButton("New\nConnection", "new_conn", e -> TN3270Emulator.showConnectionDialog()));
			mainBar.add(createIconButton("Disconnect", "disconnect", e -> emulator.disconnect()));
			mainBar.add(createIconButton("Reconnect", "reconnect", e -> emulator.reconnect()));
			mainBar.add(createSeparator());

			// File Transfer group
			//mainBar.add(createGroupLabel("File Transfer"));
			mainBar.add(createIconButton("Upload\nto Host", "upload", e -> emulator.showFileTransferDialog(false)));
			mainBar.add(
					createIconButton("Download\nfrom Host", "download", e -> emulator.showFileTransferDialog(true)));
			mainBar.add(createSeparator());

			// Edit group
			//mainBar.add(createGroupLabel("Edit"));
			mainBar.add(createIconButton("Copy", "copy", e -> emulator.copySelection()));
			mainBar.add(createIconButton("Paste", "paste", e -> emulator.pasteFromClipboard()));
			mainBar.add(createIconButton("Select All", "select_all", e -> emulator.selectAll()));
			mainBar.add(createSeparator());

			// View group
			//mainBar.add(createGroupLabel("View"));
			mainBar.add(createIconButton("Colors", "colors", e -> emulator.showColorSchemeDialog()));
			mainBar.add(createIconButton("Font", "font", e -> emulator.showFontSizeDialog()));
			mainBar.add(createSeparator());

			// Settings group
			//mainBar.add(createGroupLabel("Settings"));
			mainBar.add(createIconButton("Keyboard", "keyboard", e -> emulator.showKeyboardMappingDialog()));
			mainBar.add(createIconButton("Terminal", "terminal", e -> emulator.showTerminalSettingsDialog()));

			add(mainBar, BorderLayout.CENTER);

			// Bottom border line
			add(new Canvas() {
				public Dimension getPreferredSize() {
					return new Dimension(0, 2);
				}

				public void paint(Graphics g) {
					g.setColor(new Color(200, 200, 200));
					g.fillRect(0, 0, getWidth(), 1);
				}
			}, BorderLayout.SOUTH);
		}

		private Label createGroupLabel(String text) {
			Label label = new Label(text);
			label.setFont(new Font("SansSerif", Font.BOLD, 10));
			label.setForeground(new Color(100, 100, 100));
			return label;
		}

		private Component createSeparator() {
			return new Canvas() {
				public Dimension getPreferredSize() {
					return new Dimension(1, 55);
				}

				public void paint(Graphics g) {
					g.setColor(new Color(220, 220, 220));
					g.fillRect(0, 5, 1, 45);
				}
			};
		}

		private Button createIconButton(String text, String iconName, ActionListener action) {
			Button btn = new Button() {
				private boolean hover = false;
				private BufferedImage icon;

				{
					icon = createEnhancedIcon(iconName);

					addMouseListener(new MouseAdapter() {
						public void mouseEntered(MouseEvent e) {
							hover = true;
							repaint();
						}

						public void mouseExited(MouseEvent e) {
							hover = false;
							repaint();
						}
					});
				}

				public Dimension getPreferredSize() {
					return new Dimension(75, 55);
				}

				public void paint(Graphics g) {
					Graphics2D g2 = (Graphics2D) g;
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

					// Background
					if (hover) {
						g2.setColor(new Color(230, 240, 255));
						g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 6, 6);
						g2.setColor(new Color(150, 180, 220));
						g2.setStroke(new BasicStroke(1.5f));
						g2.drawRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 6, 6);
					}

					// Icon
					if (icon != null) {
						int iconX = (getWidth() - 32) / 2;
						g2.drawImage(icon, iconX, 5, null);
					}

					// Text (multi-line support)
					g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
					g2.setColor(Color.BLACK);
					String[] lines = text.split("\n");
					int y = getHeight() - 14;
					for (String line : lines) {
						FontMetrics fm = g2.getFontMetrics();
						int textWidth = fm.stringWidth(line);
						int textX = (getWidth() - textWidth) / 2;
						g2.drawString(line, textX, y);
						y += 10;
					}
				}
			};

			btn.addActionListener(action);
			return btn;
		}

		private BufferedImage createEnhancedIcon(String name) {
			BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

			switch (name) {
			case "new_conn":
				// Computer with plug
				g.setColor(new Color(70, 130, 180));
				g.fillRoundRect(4, 8, 24, 16, 4, 4);
				g.setColor(new Color(100, 160, 210));
				g.fillRoundRect(6, 10, 20, 12, 2, 2);
				g.setColor(new Color(50, 180, 50));
				g.fillOval(24, 4, 6, 6);
				break;

			case "disconnect":
				// Computer with X
				g.setColor(new Color(180, 70, 70));
				g.fillRoundRect(4, 8, 24, 16, 4, 4);
				g.setColor(Color.WHITE);
				g.setStroke(new BasicStroke(2.5f));
				g.drawLine(10, 14, 22, 20);
				g.drawLine(22, 14, 10, 20);
				break;

			case "reconnect":
				// Circular arrow
				g.setColor(new Color(70, 130, 180));
				g.drawArc(8, 8, 16, 16, 45, 270);
				int[] xPoints = { 24, 28, 24 };
				int[] yPoints = { 12, 16, 20 };
				g.fillPolygon(xPoints, yPoints, 3);
				break;

			case "upload":
				// Up arrow with document
				g.setColor(new Color(50, 120, 200));
				g.fillRect(10, 18, 12, 10);
				g.setColor(new Color(80, 150, 230));
				int[] xUp = { 16, 8, 16, 16, 24, 16 };
				int[] yUp = { 6, 16, 16, 26, 16, 6 };
				g.fillPolygon(xUp, yUp, 6);
				break;

			case "download":
				// Down arrow with document
				g.setColor(new Color(50, 120, 200));
				g.fillRect(10, 4, 12, 10);
				g.setColor(new Color(80, 150, 230));
				int[] xDown = { 16, 8, 16, 16, 24, 16 };
				int[] yDown = { 26, 16, 16, 6, 16, 26 };
				g.fillPolygon(xDown, yDown, 6);
				break;

			case "copy":
				// Two documents
				g.setColor(new Color(100, 100, 100));
				g.fillRoundRect(8, 10, 14, 18, 2, 2);
				g.setColor(new Color(150, 150, 150));
				g.fillRoundRect(12, 6, 14, 18, 2, 2);
				g.setColor(Color.WHITE);
				g.drawLine(14, 10, 22, 10);
				g.drawLine(14, 14, 22, 14);
				g.drawLine(14, 18, 22, 18);
				break;

			case "paste":
				// Clipboard
				g.setColor(new Color(120, 120, 120));
				g.fillRoundRect(8, 8, 16, 20, 2, 2);
				g.setColor(new Color(180, 180, 180));
				g.fillRoundRect(12, 4, 8, 6, 2, 2);
				g.setColor(Color.WHITE);
				g.fillRect(11, 12, 10, 2);
				g.fillRect(11, 16, 10, 2);
				g.fillRect(11, 20, 10, 2);
				break;

			case "select_all":
				// Selection rectangle
				g.setColor(new Color(100, 150, 255));
				g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f,
						new float[] { 4.0f }, 0.0f));
				g.drawRect(6, 6, 20, 20);
				g.setColor(new Color(100, 150, 255, 50));
				g.fillRect(7, 7, 18, 18);
				break;

			case "colors":
				// Color palette
				g.setColor(Color.RED);
				g.fillOval(6, 6, 10, 10);
				g.setColor(Color.GREEN);
				g.fillOval(18, 6, 10, 10);
				g.setColor(Color.BLUE);
				g.fillOval(6, 18, 10, 10);
				g.setColor(Color.YELLOW);
				g.fillOval(18, 18, 10, 10);
				break;

			case "font":
				// "Aa" letters
				g.setColor(Color.BLACK);
				g.setFont(new Font("SansSerif", Font.BOLD, 20));
				g.drawString("Aa", 6, 24);
				break;

			case "keyboard":
				// Keyboard
				g.setColor(new Color(80, 80, 80));
				g.fillRoundRect(4, 10, 24, 16, 3, 3);
				g.setColor(new Color(200, 200, 200));
				for (int i = 0; i < 3; i++) {
					for (int j = 0; j < 6; j++) {
						g.fillRect(6 + j * 4, 12 + i * 4, 2, 3);
					}
				}
				break;

			case "terminal":
				// Monitor/terminal
				g.setColor(new Color(60, 60, 60));
				g.fillRoundRect(4, 4, 24, 20, 4, 4);
				g.setColor(new Color(50, 200, 50));
				g.fillRect(7, 7, 18, 14);
				g.setColor(new Color(60, 60, 60));
				g.fillRect(10, 24, 12, 3);
				g.fillRect(8, 27, 16, 2);
				break;
			}

			g.dispose();
			return img;
		}
	}

	class VisualKeyboardDialog extends Dialog {
		private TN3270Emulator emulator;
		private Map<Integer, KeyMapping> tempKeyMap;
		private Map<Integer, Button> keyButtons;
		private Button selectedButton = null;
		private int selectedKeyCode = -1;
		private Label infoLabel;
		private TextField charField;
		private Choice aidChoice;
				private boolean shiftMode = false;
		private boolean ctrlMode = false;
		private Button shiftButton;
		private Button ctrlButton;

		public VisualKeyboardDialog(TN3270Emulator emulator) {
			super(emulator, "Keyboard Remapping", true);
			this.emulator = emulator;
			this.tempKeyMap = new HashMap<>();
			this.keyButtons = new HashMap<>();

			// Deep copy of current mappings
			for (Map.Entry<Integer, KeyMapping> entry : emulator.keyMap.entrySet()) {
				KeyMapping km = entry.getValue();
				if (km.aid != null) {
					tempKeyMap.put(entry.getKey(), new KeyMapping(km.aid, km.description));
				} else {
					tempKeyMap.put(entry.getKey(), new KeyMapping(km.character, km.description));
				}
			}

			setLayout(new BorderLayout(10, 10));
			setBackground(new Color(245, 245, 245));

			// Instructions
			Panel instrPanel = new Panel();
			instrPanel.setBackground(new Color(230, 240, 255));
			Label instrLabel = new Label("Click a key to remap, then choose action below");
			instrLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
			instrPanel.add(instrLabel);
			add(instrPanel, BorderLayout.NORTH);

			// Keyboard layout
			Panel keyboardPanel = createKeyboardLayout();
			add(keyboardPanel, BorderLayout.CENTER);

			// Mapping options
			Panel optionsPanel = createOptionsPanel();
			add(optionsPanel, BorderLayout.SOUTH);

			addWindowListener(new WindowAdapter() {
				public void windowClosing(WindowEvent e) {
					dispose();
				}
			});

			pack();
			setLocationRelativeTo(emulator);
		}


		private Panel createKeyboardLayout() {
			Panel main = new Panel(new GridBagLayout());
			main.setBackground(new Color(240, 240, 240));
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.insets = new Insets(2, 2, 2, 2);
			gbc.fill = GridBagConstraints.BOTH;

			// Row 0: Function keys row (Esc, F1-F12)
			gbc.gridy = 0;
			gbc.gridx = 0;
			addKey(main, gbc, "Esc", KeyEvent.VK_ESCAPE, 1.0);
			
			// Small gap
			gbc.gridx++;
			Canvas gap1 = new Canvas();
			gap1.setPreferredSize(new Dimension(10, 1));
			main.add(gap1, gbc);
			
			// F1-F12
			String[] fkeys = {"F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"};
			int[] fcodes = {KeyEvent.VK_F1, KeyEvent.VK_F2, KeyEvent.VK_F3, KeyEvent.VK_F4,
							KeyEvent.VK_F5, KeyEvent.VK_F6, KeyEvent.VK_F7, KeyEvent.VK_F8,
							KeyEvent.VK_F9, KeyEvent.VK_F10, KeyEvent.VK_F11, KeyEvent.VK_F12};
			for (int i = 0; i < fkeys.length; i++) {
				gbc.gridx++;
				addKey(main, gbc, fkeys[i], fcodes[i], 1.0);
			}

			// Row 1: Number row
			gbc.gridy = 1;
			String[] row1 = { "`", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=", "Back" };
			int[] codes1 = { KeyEvent.VK_BACK_QUOTE, KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4,
					KeyEvent.VK_5, KeyEvent.VK_6, KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9, KeyEvent.VK_0,
					KeyEvent.VK_MINUS, KeyEvent.VK_EQUALS, KeyEvent.VK_BACK_SPACE };
			double[] widths1 = { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1.5 };
			addKeyRow(main, gbc, row1, codes1, widths1, 0);

			// Row 2: QWERTY row
			gbc.gridy = 2;
			gbc.gridx = 0;
			addModifierKey(main, gbc, "Tab", KeyEvent.VK_TAB, 1.5, false);

			String[] row2 = { "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]", "\\" };
			int[] codes2 = { KeyEvent.VK_Q, KeyEvent.VK_W, KeyEvent.VK_E, KeyEvent.VK_R, KeyEvent.VK_T, KeyEvent.VK_Y,
					KeyEvent.VK_U, KeyEvent.VK_I, KeyEvent.VK_O, KeyEvent.VK_P, KeyEvent.VK_OPEN_BRACKET,
					KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_BACK_SLASH };
			double[] widths2 = { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1.5 };
			addKeyRow(main, gbc, row2, codes2, widths2, gbc.gridx);

			// Row 3: ASDF row
			gbc.gridy = 3;
			gbc.gridx = 0;
			addModifierKey(main, gbc, "Caps", KeyEvent.VK_CAPS_LOCK, 1.75, false);

			String[] row3 = { "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'" };
			int[] codes3 = { KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D, KeyEvent.VK_F, KeyEvent.VK_G, KeyEvent.VK_H,
					KeyEvent.VK_J, KeyEvent.VK_K, KeyEvent.VK_L, KeyEvent.VK_SEMICOLON, KeyEvent.VK_QUOTE };
			double[] widths3 = { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 };
			addKeyRow(main, gbc, row3, codes3, widths3, gbc.gridx);

			addKey(main, gbc, "Enter", KeyEvent.VK_ENTER, 2.25);

			// Row 4: ZXCV row
			gbc.gridy = 4;
			gbc.gridx = 0;
			shiftButton = addModifierKey(main, gbc, "Shift", KeyEvent.VK_SHIFT, 2.25, true);

			String[] row4 = { "Z", "X", "C", "V", "B", "N", "M", ",", ".", "/" };
			int[] codes4 = { KeyEvent.VK_Z, KeyEvent.VK_X, KeyEvent.VK_C, KeyEvent.VK_V, KeyEvent.VK_B, KeyEvent.VK_N,
					KeyEvent.VK_M, KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD, KeyEvent.VK_SLASH };
			double[] widths4 = { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 };
			addKeyRow(main, gbc, row4, codes4, widths4, gbc.gridx);

			// Row 5: Space bar row
			gbc.gridy = 5;
			gbc.gridx = 0;
			ctrlButton = addModifierKey(main, gbc, "Ctrl", KeyEvent.VK_CONTROL, 1.5, true);
			addModifierKey(main, gbc, "Alt", KeyEvent.VK_ALT, 1.5, false);
			addKey(main, gbc, "Space", KeyEvent.VK_SPACE, 7.0);

			return main;
		}

		// Add modifier key (Shift, Ctrl, Tab, Caps) with toggle behavior
		private Button addModifierKey(Panel panel, GridBagConstraints gbc, String label, int keyCode, double width, boolean isToggle) {
			final boolean[] toggleState = {false}; // Use array to allow modification in inner class
			
			Button key = new Button(label) {
				public void paint(Graphics g) {
					// Highlight if toggled
					if (toggleState[0]) {
						setBackground(new Color(100, 200, 100));
						setForeground(Color.BLACK);
					} else {
						setBackground(new Color(220, 220, 220));
						setForeground(Color.BLACK);
					}

					super.paint(g);
				}

				public Dimension getPreferredSize() {
					return new Dimension((int) (45 * width), 35);
				}
			};

			key.setFont(new Font("SansSerif", Font.BOLD, 10));

			if (isToggle) {
				key.addActionListener(e -> {
					toggleState[0] = !toggleState[0];
					
					if (keyCode == KeyEvent.VK_SHIFT) {
						shiftMode = toggleState[0];
						updateKeyboardDisplay();
					} else if (keyCode == KeyEvent.VK_CONTROL) {
						ctrlMode = toggleState[0];
					}
					
					key.repaint();
				});
			}

			gbc.weightx = width;
			panel.add(key, gbc);
			gbc.gridx++;
			
			return key;
		}
		
		// Update keyboard display based on shift mode
		private void updateKeyboardDisplay() {
			// Update all character key labels based on shift state
			for (Map.Entry<Integer, Button> entry : keyButtons.entrySet()) {
				int code = entry.getKey();
				Button btn = entry.getValue();
				
				// Update label for letter keys
				if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) {
					char c = (char) ('A' + (code - KeyEvent.VK_A));
					btn.setLabel(shiftMode ? String.valueOf(c) : String.valueOf(Character.toLowerCase(c)));
				}
				// Update labels for number row
				else if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) {
					if (shiftMode) {
						String[] shifted = {")", "!", "@", "#", "$", "%", "^", "&", "*", "("};
						btn.setLabel(shifted[code - KeyEvent.VK_0]);
					} else {
						btn.setLabel(String.valueOf((char) ('0' + (code - KeyEvent.VK_0))));
					}
				}
				// Update other shifted keys
				else {
					switch (code) {
						case KeyEvent.VK_BACK_QUOTE: btn.setLabel(shiftMode ? "~" : "`"); break;
						case KeyEvent.VK_MINUS: btn.setLabel(shiftMode ? "_" : "-"); break;
						case KeyEvent.VK_EQUALS: btn.setLabel(shiftMode ? "+" : "="); break;
						case KeyEvent.VK_OPEN_BRACKET: btn.setLabel(shiftMode ? "{" : "["); break;
						case KeyEvent.VK_CLOSE_BRACKET: btn.setLabel(shiftMode ? "}" : "]"); break;
						case KeyEvent.VK_BACK_SLASH: btn.setLabel(shiftMode ? "|" : "\\"); break;
						case KeyEvent.VK_SEMICOLON: btn.setLabel(shiftMode ? ":" : ";"); break;
						case KeyEvent.VK_QUOTE: btn.setLabel(shiftMode ? "\"" : "'"); break;
						case KeyEvent.VK_COMMA: btn.setLabel(shiftMode ? "<" : ","); break;
						case KeyEvent.VK_PERIOD: btn.setLabel(shiftMode ? ">" : "."); break;
						case KeyEvent.VK_SLASH: btn.setLabel(shiftMode ? "?" : "/"); break;
					}
				}
			}
		}

		private Panel createKeyboardLayoutOld() {
			Panel main = new Panel(new GridBagLayout());
			main.setBackground(new Color(240, 240, 240));
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.insets = new Insets(2, 2, 2, 2);
			gbc.fill = GridBagConstraints.BOTH;

			// Row 1: Number row
			gbc.gridy = 0;
			String[] row1 = { "`", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=", "Back" };
			int[] codes1 = { KeyEvent.VK_BACK_QUOTE, KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4,
					KeyEvent.VK_5, KeyEvent.VK_6, KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9, KeyEvent.VK_0,
					KeyEvent.VK_MINUS, KeyEvent.VK_EQUALS, KeyEvent.VK_BACK_SPACE };
			double[] widths1 = { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1.5 };
			addKeyRow(main, gbc, row1, codes1, widths1, 0);

			// Row 2: QWERTY row
			gbc.gridy = 1;
			gbc.gridx = 0;
			addKey(main, gbc, "Tab", KeyEvent.VK_TAB, 1.5);

			String[] row2 = { "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]", "\\" };
			int[] codes2 = { KeyEvent.VK_Q, KeyEvent.VK_W, KeyEvent.VK_E, KeyEvent.VK_R, KeyEvent.VK_T, KeyEvent.VK_Y,
					KeyEvent.VK_U, KeyEvent.VK_I, KeyEvent.VK_O, KeyEvent.VK_P, KeyEvent.VK_OPEN_BRACKET,
					KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_BACK_SLASH };
			double[] widths2 = { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1.5 };
			addKeyRow(main, gbc, row2, codes2, widths2, gbc.gridx);

			// Row 3: ASDF row
			gbc.gridy = 2;
			gbc.gridx = 0;
			addKey(main, gbc, "Caps", KeyEvent.VK_CAPS_LOCK, 1.75);

			String[] row3 = { "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'" };
			int[] codes3 = { KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D, KeyEvent.VK_F, KeyEvent.VK_G, KeyEvent.VK_H,
					KeyEvent.VK_J, KeyEvent.VK_K, KeyEvent.VK_L, KeyEvent.VK_SEMICOLON, KeyEvent.VK_QUOTE };
			double[] widths3 = { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 };
			addKeyRow(main, gbc, row3, codes3, widths3, gbc.gridx);

			addKey(main, gbc, "Enter", KeyEvent.VK_ENTER, 2.25);

			// Row 4: ZXCV row
			gbc.gridy = 3;
			gbc.gridx = 0;
			addKey(main, gbc, "Shift", KeyEvent.VK_SHIFT, 2.25);

			String[] row4 = { "Z", "X", "C", "V", "B", "N", "M", ",", ".", "/" };
			int[] codes4 = { KeyEvent.VK_Z, KeyEvent.VK_X, KeyEvent.VK_C, KeyEvent.VK_V, KeyEvent.VK_B, KeyEvent.VK_N,
					KeyEvent.VK_M, KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD, KeyEvent.VK_SLASH };
			double[] widths4 = { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 };
			addKeyRow(main, gbc, row4, codes4, widths4, gbc.gridx);

			// Row 5: Space bar row
			gbc.gridy = 4;
			gbc.gridx = 0;
			addKey(main, gbc, "Ctrl", KeyEvent.VK_CONTROL, 1.5);
			addKey(main, gbc, "Alt", KeyEvent.VK_ALT, 1.5);
			addKey(main, gbc, "Space", KeyEvent.VK_SPACE, 6.0);

			return main;
		}

		private void addKeyRow(Panel panel, GridBagConstraints gbc, String[] labels, int[] codes, double[] widths,
				int startX) {
			gbc.gridx = startX;
			for (int i = 0; i < labels.length; i++) {
				addKey(panel, gbc, labels[i], codes[i], widths[i]);
			}
		}

		private void addKey(Panel panel, GridBagConstraints gbc, String label, int keyCode, double width) {
			Button key = new Button(label) {
				public void paint(Graphics g) {
					boolean isSelected = (this == selectedButton);
					boolean hasMapped = tempKeyMap.containsKey(keyCode);

					// Background
					if (isSelected) {
						setBackground(new Color(100, 150, 255));
						setForeground(Color.WHITE);
					} else if (hasMapped) {
						setBackground(new Color(255, 255, 200));
						setForeground(Color.BLACK);
					} else {
						setBackground(Color.WHITE);
						setForeground(Color.BLACK);
					}

					super.paint(g);

					// Draw mapped indicator
					if (hasMapped && !isSelected) {
						g.setColor(new Color(0, 150, 0));
						g.fillOval(getWidth() - 8, 2, 5, 5);
					}
				}

				public Dimension getPreferredSize() {
					return new Dimension((int) (45 * width), 35);
				}
			};

			key.setFont(new Font("SansSerif", Font.PLAIN, 10));

			key.addActionListener(e -> {
				selectedButton = key;
				selectedKeyCode = keyCode;
				updateKeyInfo(keyCode, label);

				// Repaint all keys to update selection
				for (Button b : keyButtons.values()) {
					b.repaint();
				}
			});

			keyButtons.put(keyCode, key);
			gbc.weightx = width;
			panel.add(key, gbc);
			gbc.gridx++;
		}

		private Panel createOptionsPanel() {
			Panel panel = new Panel(new GridBagLayout());
			panel.setBackground(new Color(250, 250, 250));
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.insets = new Insets(5, 10, 5, 10);
			gbc.fill = GridBagConstraints.HORIZONTAL;

			// Current mapping display
			gbc.gridx = 0;
			gbc.gridy = 0;
			gbc.gridwidth = 3;
			infoLabel = new Label("Select a key above to remap");
			infoLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
			infoLabel.setForeground(new Color(80, 80, 80));
			panel.add(infoLabel, gbc);

			// Map to character option
			gbc.gridy = 1;
			gbc.gridwidth = 1;
			gbc.anchor = GridBagConstraints.EAST;
			panel.add(new Label("Map to character:"), gbc);

			gbc.gridx = 1;
			gbc.anchor = GridBagConstraints.WEST;
			charField = new TextField(5);
			charField.setFont(new Font("Monospaced", Font.PLAIN, 14));
			panel.add(charField, gbc);

			gbc.gridx = 2;
			Button mapCharBtn = new Button("Set Character");
			mapCharBtn.addActionListener(e -> {
				if (selectedKeyCode != -1 && charField.getText().length() > 0) {
					char c = charField.getText().charAt(0);
					tempKeyMap.put(selectedKeyCode, new KeyMapping(c, "Maps to '" + c + "'"));
					updateKeyInfo(selectedKeyCode, KeyEvent.getKeyText(selectedKeyCode));
					if (selectedButton != null) {
						selectedButton.repaint();
					}
				}
			});
			panel.add(mapCharBtn, gbc);

			// Map to AID option
			gbc.gridx = 0;
			gbc.gridy = 2;
			gbc.anchor = GridBagConstraints.EAST;
			panel.add(new Label("Map to AID:"), gbc);

			gbc.gridx = 1;
			gbc.anchor = GridBagConstraints.WEST;
			aidChoice = new Choice();
			aidChoice.add("(None)");
			aidChoice.add("ENTER");
			aidChoice.add("CLEAR");
			aidChoice.add("PA1");
			aidChoice.add("PA2");
			aidChoice.add("PA3");
			for (int i = 1; i <= 12; i++) {
				aidChoice.add("PF" + i);
			}
			panel.add(aidChoice, gbc);

			gbc.gridx = 2;
			Button mapAidBtn = new Button("Set AID");
			mapAidBtn.addActionListener(e -> {
				if (selectedKeyCode != -1) {
					String selected = aidChoice.getSelectedItem();
					if (selected.equals("(None)")) {
						tempKeyMap.remove(selectedKeyCode);
					} else {
						byte aid = getAidForName(selected);
						tempKeyMap.put(selectedKeyCode, new KeyMapping(aid, "Maps to " + selected));
					}
					updateKeyInfo(selectedKeyCode, KeyEvent.getKeyText(selectedKeyCode));
					if (selectedButton != null) {
						selectedButton.repaint();
					}
				}
			});
			panel.add(mapAidBtn, gbc);

			// Clear mapping button
			gbc.gridx = 0;
			gbc.gridy = 3;
			Button clearBtn = new Button("Clear Mapping");
			clearBtn.addActionListener(e -> {
				if (selectedKeyCode != -1) {
					tempKeyMap.remove(selectedKeyCode);
					updateKeyInfo(selectedKeyCode, KeyEvent.getKeyText(selectedKeyCode));
					if (selectedButton != null) {
						selectedButton.repaint();
					}
				}
			});
			panel.add(clearBtn, gbc);

			// Buttons
			gbc.gridx = 0;
			gbc.gridy = 4;
			gbc.gridwidth = 3;
			gbc.anchor = GridBagConstraints.CENTER;
			Panel btnPanel = new Panel(new FlowLayout(FlowLayout.CENTER, 10, 5));

			Button saveBtn = new Button("Save & Close");
			saveBtn.addActionListener(e -> {
				emulator.keyMap.clear();
				emulator.keyMap.putAll(tempKeyMap);
				emulator.saveKeyMappings();
				dispose();
			});
			btnPanel.add(saveBtn);

			Button resetBtn = new Button("Reset to Defaults");
			resetBtn.addActionListener(e -> {
				tempKeyMap.clear();
				emulator.initializeKeyMappings();
				for (Button b : keyButtons.values()) {
					b.repaint();
				}
				if (selectedKeyCode != -1) {
					updateKeyInfo(selectedKeyCode, KeyEvent.getKeyText(selectedKeyCode));
				}
			});
			btnPanel.add(resetBtn);

			Button cancelBtn = new Button("Cancel");
			cancelBtn.addActionListener(e -> dispose());
			btnPanel.add(cancelBtn);

			panel.add(btnPanel, gbc);

			return panel;
		}

		private void updateKeyInfo(int keyCode, String label) {
			KeyMapping mapping = tempKeyMap.get(keyCode);
			if (mapping != null) {
				String info = "Key '" + label + "': " + mapping.description;
				infoLabel.setText(info);

				if (mapping.aid != null) {
					aidChoice.select(getNameForAid(mapping.aid));
					charField.setText("");
				} else {
					aidChoice.select("(None)");
					charField.setText(String.valueOf(mapping.character));
				}
			} else {
				infoLabel.setText("Key '" + label + "': No mapping (default behavior)");
				aidChoice.select("(None)");
				charField.setText("");
			}
		}

		private byte getAidForName(String name) {
			switch (name) {
			case "ENTER":
				return AID_ENTER;
			case "CLEAR":
				return AID_CLEAR;
			case "PA1":
				return AID_PA1;
			case "PA2":
				return AID_PA2;
			case "PA3":
				return AID_PA3;
			case "PF1":
				return AID_PF1;
			case "PF2":
				return AID_PF2;
			case "PF3":
				return AID_PF3;
			case "PF4":
				return AID_PF4;
			case "PF5":
				return AID_PF5;
			case "PF6":
				return AID_PF6;
			case "PF7":
				return AID_PF7;
			case "PF8":
				return AID_PF8;
			case "PF9":
				return AID_PF9;
			case "PF10":
				return AID_PF10;
			case "PF11":
				return AID_PF11;
			case "PF12":
				return AID_PF12;
			default:
				return AID_ENTER;
			}
		}

		private String getNameForAid(byte aid) {
			if (aid == AID_ENTER)
				return "ENTER";
			if (aid == AID_CLEAR)
				return "CLEAR";
			if (aid == AID_PA1)
				return "PA1";
			if (aid == AID_PA2)
				return "PA2";
			if (aid == AID_PA3)
				return "PA3";
			if (aid == AID_PF1)
				return "PF1";
			if (aid == AID_PF2)
				return "PF2";
			if (aid == AID_PF3)
				return "PF3";
			if (aid == AID_PF4)
				return "PF4";
			if (aid == AID_PF5)
				return "PF5";
			if (aid == AID_PF6)
				return "PF6";
			if (aid == AID_PF7)
				return "PF7";
			if (aid == AID_PF8)
				return "PF8";
			if (aid == AID_PF9)
				return "PF9";
			if (aid == AID_PF10)
				return "PF10";
			if (aid == AID_PF11)
				return "PF11";
			if (aid == AID_PF12)
				return "PF12";
			if (aid == AID_PF13)
				return "PF13";
			if (aid == AID_PF14)
				return "PF14";
			if (aid == AID_PF15)
				return "PF15";
			if (aid == AID_PF16)
				return "PF16";
			if (aid == AID_PF17)
				return "PF17";
			if (aid == AID_PF18)
				return "PF18";
			if (aid == AID_PF19)
				return "PF19";
			if (aid == AID_PF20)
				return "PF20";
			if (aid == AID_PF21)
				return "PF21";
			if (aid == AID_PF22)
				return "PF22";
			if (aid == AID_PF23)
				return "PF23";
			if (aid == AID_PF24)
				return "PF24";
			return "(None)";
		}
	}

// Update showKeyboardMappingDialog in TN3270Emulator
	private void showKeyboardMappingDialog() {
		new VisualKeyboardDialog(this).setVisible(true);
	}

	class ModernKeyboardPanel extends Panel {
		private TN3270Emulator emulator;

		public ModernKeyboardPanel(TN3270Emulator emulator) {
    this.emulator = emulator;
    setLayout(new BorderLayout(5, 5));
    setBackground(new Color(50, 50, 55));

    // Main keyboard area with 3 rows
    Panel mainPanel = new Panel(new GridLayout(3, 1, 3, 3));
    mainPanel.setBackground(new Color(50, 50, 55));

	// Row 1: PF1-PF12
	Panel row1 = new Panel(new GridLayout(1, 12, 3, 3));
	row1.setBackground(new Color(50, 50, 55));

	for (int i = 0; i < 12; i++) {

    	// Correct table-based AID assignment
    	//final byte aid;
		final int pfNum = i + 1;
		final byte aid = PF_AID[i];
		/* 
    	if (i <= 9)
        	aid = (byte)(AID_PF1 + (i - 1));  // PF1–PF9 are contiguous
    	else if (i == 10)
        	aid = AID_PF10;
    	else if (i == 11)
        	aid = AID_PF11;
    	else
        	aid = AID_PF12;
		*/
    	row1.add(createStyledButton("F" + pfNum, new Color(70, 130, 180), e -> {
        	if (!emulator.keyboardLocked && emulator.connected) {
            	emulator.sendAID(aid);
        	}
        	emulator.canvas.requestFocus();
    	}));
	}
/* 
    // Row 1: PF1-PF12
    Panel row1 = new Panel(new GridLayout(1, 12, 3, 3));
    row1.setBackground(new Color(50, 50, 55));
    for (int i = 1; i <= 12; i++) {
        final byte aid = (byte) (AID_PF1 + (i - 1));
        row1.add(createStyledButton("F" + i, new Color(70, 130, 180), e -> {
            if (!emulator.keyboardLocked && emulator.connected) {
                emulator.sendAID(aid);
            }
            emulator.canvas.requestFocus();
        }));
    }
		*/
    mainPanel.add(row1);

    // Row 2: PF13-PF24
    Panel row2 = new Panel(new GridLayout(1, 12, 3, 3));
    row2.setBackground(new Color(50, 50, 55));
    for (int i = 12; i < 24; i++) {
        final int pfNum = i + 1;
		final byte aid = PF_AID[i];
        row2.add(createStyledButton("F" + pfNum, new Color(70, 130, 180), e -> {
            if (!emulator.keyboardLocked && emulator.connected) {
				emulator.sendAID(aid);
            }
            emulator.canvas.requestFocus();
        }));
    }
    mainPanel.add(row2);

    // Row 3: Action keys
    Panel row3 = new Panel(new FlowLayout(FlowLayout.CENTER, 5, 3));
    row3.setBackground(new Color(50, 50, 55));
    
    row3.add(createStyledButton("CLEAR", new Color(150, 50, 50), e -> {
        if (!emulator.keyboardLocked && emulator.connected) {
            emulator.clearScreen();
            emulator.sendAID(AID_CLEAR);
            emulator.canvas.repaint();
        }
        emulator.canvas.requestFocus();
    }));
    
    row3.add(createStyledButton("PA1", new Color(180, 130, 70), e -> {
        if (!emulator.keyboardLocked && emulator.connected) {
            emulator.sendAID(AID_PA1);
        }
        emulator.canvas.requestFocus();
    }));
    
    row3.add(createStyledButton("PA2", new Color(180, 130, 70), e -> {
        if (!emulator.keyboardLocked && emulator.connected) {
            emulator.sendAID(AID_PA2);
        }
        emulator.canvas.requestFocus();
    }));
    
    row3.add(createStyledButton("PA3", new Color(180, 130, 70), e -> {
        if (!emulator.keyboardLocked && emulator.connected) {
            emulator.sendAID(AID_PA3);
        }
        emulator.canvas.requestFocus();
    }));
    
    row3.add(createStyledButton("RESET", new Color(100, 100, 100), e -> {
        emulator.keyboardLocked = false;
        emulator.statusBar.update();
        emulator.canvas.repaint();
        emulator.canvas.requestFocus();
    }));
    
    row3.add(createStyledButton("INSERT", new Color(100, 100, 150), e -> {
        emulator.insertMode = !emulator.insertMode;
        emulator.statusBar.update();
        emulator.canvas.repaint();
        emulator.canvas.requestFocus();
    }));
    
    row3.add(createStyledButton("ERASE EOL", new Color(120, 100, 100), e -> {
        if (!emulator.keyboardLocked && emulator.connected) {
            emulator.eraseToEndOfLine();
        }
        emulator.canvas.requestFocus();
    }, 85));
    
    row3.add(createStyledButton("NEWLINE", new Color(100, 120, 100), e -> {
        if (!emulator.keyboardLocked && emulator.connected) {
            emulator.tabToNextField();
        }
        emulator.canvas.requestFocus();
    }, 85));
    
    row3.add(createStyledButton("ENTER", new Color(50, 150, 50), e -> {
        if (!emulator.keyboardLocked && emulator.connected) {
            emulator.sendAID(AID_ENTER);
        }
        emulator.canvas.requestFocus();
    }, 80));
    
    mainPanel.add(row3);

    add(mainPanel, BorderLayout.CENTER);

    // Right side - Status indicators
    Panel rightPanel = createStatusPanel();
    add(rightPanel, BorderLayout.EAST);
}
/* 
		public ModernKeyboardPanelOld(TN3270Emulator emulator) {
			this.emulator = emulator;
			setLayout(new BorderLayout(5, 5));
			setBackground(new Color(50, 50, 55));

			// Left side - Function keys
			Panel leftPanel = createFunctionKeyPanel();
			add(leftPanel, BorderLayout.WEST);

			// Center - Main action keys
			Panel centerPanel = createActionKeyPanel();
			add(centerPanel, BorderLayout.CENTER);

			// Right side - Status indicators
			Panel rightPanel = createStatusPanel();
			add(rightPanel, BorderLayout.EAST);
		}
*/
		private Panel createFunctionKeyPanel() {
			Panel panel = new Panel(new GridLayout(2, 6, 3, 3));
			panel.setBackground(new Color(50, 50, 55));

			// PF1-PF12
			for (int i = 1; i <= 12; i++) {
				final byte aid;     // must be assigned once *in this iteration*

	    		if (i <= 9)
        			aid = (byte)(AID_PF1 + (i - 1));
    			else if (i == 10)
        			aid = AID_PF10;
    			else if (i == 11)
        			aid = AID_PF11;
    			else
        			aid = AID_PF12;

				panel.add(createStyledButton("F" + i, new Color(70, 130, 180), e -> {
					if (!emulator.keyboardLocked && emulator.connected) {
						emulator.sendAID(aid);
					}
					emulator.canvas.requestFocus();
				}));
			}

			return panel;
		}

		private Panel createActionKeyPanel() {
    Panel panel = new Panel(new FlowLayout(FlowLayout.CENTER, 8, 5));
    panel.setBackground(new Color(50, 50, 55));

    // ENTER key
    panel.add(createStyledButton("ENTER", new Color(50, 150, 50), e -> {
        if (!emulator.keyboardLocked && emulator.connected) {
            emulator.sendAID(AID_ENTER);
        }
        emulator.canvas.requestFocus();
    }, 100));

    // PA keys
    panel.add(createStyledButton("PA1", new Color(180, 130, 70), e -> {
        if (!emulator.keyboardLocked && emulator.connected) {
            emulator.sendAID(AID_PA1);
        }
        emulator.canvas.requestFocus();
    }));

    panel.add(createStyledButton("PA2", new Color(180, 130, 70), e -> {
        if (!emulator.keyboardLocked && emulator.connected) {
            emulator.sendAID(AID_PA2);
        }
        emulator.canvas.requestFocus();
    }));

    panel.add(createStyledButton("PA3", new Color(180, 130, 70), e -> {
        if (!emulator.keyboardLocked && emulator.connected) {
            emulator.sendAID(AID_PA3);
        }
        emulator.canvas.requestFocus();
    }));

    // CLEAR key
    panel.add(createStyledButton("CLEAR", new Color(150, 50, 50), e -> {
        if (!emulator.keyboardLocked && emulator.connected) {
            emulator.clearScreen();
            emulator.sendAID(AID_CLEAR);
            emulator.canvas.repaint();
        }
        emulator.canvas.requestFocus();
    }));

    // RESET key
    panel.add(createStyledButton("RESET", new Color(100, 100, 100), e -> {
        emulator.keyboardLocked = false;
        emulator.statusBar.update();
        emulator.canvas.repaint();
        emulator.canvas.requestFocus();
    }));

    // INSERT toggle
    panel.add(createStyledButton("INSERT", new Color(100, 100, 150), e -> {
        emulator.insertMode = !emulator.insertMode;
        emulator.statusBar.update();
        emulator.canvas.repaint();
        emulator.canvas.requestFocus();
    }));

    // ERASE EOL
    panel.add(createStyledButton("ERASE EOL", new Color(120, 100, 100), e -> {
        if (!emulator.keyboardLocked && emulator.connected) {
            emulator.eraseToEndOfLine();
        }
        emulator.canvas.requestFocus();
    }));

    // NEW LINE (like Tab to next field)
    panel.add(createStyledButton("NEWLINE", new Color(100, 120, 100), e -> {
        if (!emulator.keyboardLocked && emulator.connected) {
            emulator.tabToNextField();
        }
        emulator.canvas.requestFocus();
    }));

    return panel;
}

		private Panel createActionKeyPanelOld() {
			Panel panel = new Panel(new FlowLayout(FlowLayout.CENTER, 8, 5));
			panel.setBackground(new Color(50, 50, 55));

			// ENTER key
			panel.add(createStyledButton("ENTER", new Color(50, 150, 50), e -> {
				if (!emulator.keyboardLocked && emulator.connected) {
					emulator.sendAID(AID_ENTER);
				}
				emulator.canvas.requestFocus();
			}, 100));

			// PA keys
			panel.add(createStyledButton("PA1", new Color(180, 130, 70), e -> {
				if (!emulator.keyboardLocked && emulator.connected) {
					emulator.sendAID(AID_PA1);
				}
				emulator.canvas.requestFocus();
			}));

			panel.add(createStyledButton("PA2", new Color(180, 130, 70), e -> {
				if (!emulator.keyboardLocked && emulator.connected) {
					emulator.sendAID(AID_PA2);
				}
				emulator.canvas.requestFocus();
			}));

			panel.add(createStyledButton("PA3", new Color(180, 130, 70), e -> {
				if (!emulator.keyboardLocked && emulator.connected) {
					emulator.sendAID(AID_PA3);
				}
				emulator.canvas.requestFocus();
			}));

			// CLEAR key
			panel.add(createStyledButton("CLEAR", new Color(150, 50, 50), e -> {
				if (!emulator.keyboardLocked && emulator.connected) {
					emulator.clearScreen();
					emulator.sendAID(AID_CLEAR);
					emulator.canvas.repaint();
				}
				emulator.canvas.requestFocus();
			}));

			// RESET key
			panel.add(createStyledButton("RESET", new Color(100, 100, 100), e -> {
				emulator.keyboardLocked = false;
				emulator.statusBar.update();
				emulator.canvas.repaint();
				emulator.canvas.requestFocus();
			}));

			// INSERT toggle
			panel.add(createStyledButton("INSERT", new Color(100, 100, 150), e -> {
				emulator.insertMode = !emulator.insertMode;
				emulator.statusBar.update();
				emulator.canvas.repaint();
				emulator.canvas.requestFocus();
			}));

			// ERASE EOF
			panel.add(createStyledButton("ERASE EOF", new Color(120, 100, 100), e -> {
				if (!emulator.keyboardLocked && emulator.connected) {
					emulator.eraseToEndOfField();
				}
				emulator.canvas.requestFocus();
			}));

			return panel;
		}

		private Panel createStatusPanel() {
			Panel panel = new Panel(new GridLayout(3, 1, 2, 2));
			panel.setBackground(new Color(50, 50, 55));

			// Connection status
			Canvas connStatus = new Canvas() {
				public Dimension getPreferredSize() {
					return new Dimension(100, 20);
				}

				public void paint(Graphics g) {
					g.setFont(new Font("SansSerif", Font.BOLD, 10));
					if (emulator.connected) {
						g.setColor(new Color(50, 200, 50));
						g.fillOval(2, 5, 10, 10);
						g.setColor(Color.WHITE);
						g.drawString("CONNECTED", 16, 14);
					} else {
						g.setColor(new Color(200, 50, 50));
						g.fillOval(2, 5, 10, 10);
						g.setColor(Color.LIGHT_GRAY);
						g.drawString("OFFLINE", 16, 14);
					}
				}
			};
			panel.add(connStatus);

			// Keyboard status
			Canvas kbStatus = new Canvas() {
				public Dimension getPreferredSize() {
					return new Dimension(100, 20);
				}

				public void paint(Graphics g) {
					g.setFont(new Font("SansSerif", Font.BOLD, 10));
					if (emulator.keyboardLocked) {
						g.setColor(new Color(255, 200, 0));
						g.fillRect(2, 5, 10, 10);
						g.setColor(Color.WHITE);
						g.drawString("LOCKED", 16, 14);
					} else {
						g.setColor(new Color(100, 100, 100));
						g.fillRect(2, 5, 10, 10);
						g.setColor(Color.LIGHT_GRAY);
						g.drawString("UNLOCKED", 16, 14);
					}
				}
			};
			panel.add(kbStatus);

			// Insert mode status
			Canvas insStatus = new Canvas() {
				public Dimension getPreferredSize() {
					return new Dimension(100, 20);
				}

				public void paint(Graphics g) {
					g.setFont(new Font("SansSerif", Font.BOLD, 10));
					g.setColor(emulator.insertMode ? Color.CYAN : Color.GRAY);
					g.drawString(emulator.insertMode ? "INS" : "OVR", 2, 14);
				}
			};
			panel.add(insStatus);

			// Timer to update status
			Timer statusTimer = new Timer(250, e -> {
				connStatus.repaint();
				kbStatus.repaint();
				insStatus.repaint();
			});
			statusTimer.start();

			return panel;
		}

		private Button createStyledButton(String text, Color color, ActionListener action) {
			return createStyledButton(text, color, action, 60);
		}

		private Button createStyledButton(String text, Color color, ActionListener action, int width) {
			Button btn = new Button(text) {
				private boolean pressed = false;
				private boolean hover = false;

				{
					addMouseListener(new MouseAdapter() {
						public void mouseEntered(MouseEvent e) {
							hover = true;
							repaint();
						}

						public void mouseExited(MouseEvent e) {
							hover = false;
							repaint();
						}

						public void mousePressed(MouseEvent e) {
							pressed = true;
							repaint();
						}

						public void mouseReleased(MouseEvent e) {
							pressed = false;
							repaint();
						}
					});
				}

				public Dimension getPreferredSize() {
					return new Dimension(width, 28);
				}

				public void paint(Graphics g) {
					Graphics2D g2 = (Graphics2D) g;
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

					// Button background with gradient
					Color baseColor = color;
					if (pressed) {
						baseColor = baseColor.darker();
					} else if (hover) {
						baseColor = baseColor.brighter();
					}

					GradientPaint gradient = new GradientPaint(0, 0, baseColor.brighter(), 0, getHeight(),
							baseColor.darker());
					g2.setPaint(gradient);
					g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);

					// Border
					g2.setColor(pressed ? baseColor.darker().darker() : baseColor.darker());
					g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);

					// Text
					g2.setColor(Color.WHITE);
					g2.setFont(new Font("SansSerif", Font.BOLD, 10));
					FontMetrics fm = g2.getFontMetrics();
					int textWidth = fm.stringWidth(text);
					int textX = (getWidth() - textWidth) / 2;
					int textY = (getHeight() + fm.getAscent()) / 2 - 2;

					// Text shadow
					g2.setColor(new Color(0, 0, 0, 100));
					g2.drawString(text, textX + 1, textY + 1);

					// Text
					g2.setColor(Color.WHITE);
					g2.drawString(text, textX, textY);
				}
			};

			btn.addActionListener(action);
			return btn;
		}
	}

	class TerminalCanvas extends Canvas {
		private Font terminalFont;
		private int charWidth;
		private int charHeight;
		private Image offscreenImage;
		private Graphics offscreenGraphics;

		public TerminalCanvas() {
			terminalFont = new Font("Monospaced", Font.PLAIN, 14);
			setFont(terminalFont);
			FontMetrics fm = getFontMetrics(terminalFont);
			charWidth = fm.charWidth('M');
			charHeight = fm.getHeight();

			setPreferredSize(new Dimension(cols * charWidth + 10, rows * charHeight + 10));
			setBackground(Color.BLACK);
			setFocusTraversalKeysEnabled(false);

			// Add mouse listeners for selection
			addMouseListener(new MouseAdapter() {
				public void mousePressed(MouseEvent e) {
					handleMousePress(e);
				}

				public void mouseReleased(MouseEvent e) {
					handleMouseRelease(e);
				}

				public void mouseClicked(MouseEvent e) {
					if (e.getClickCount() == 2) {
						selectWord(e);
					} else if (e.getClickCount() == 3) {
						selectLine(e);
					}
				}
			});

			addMouseMotionListener(new MouseMotionAdapter() {
				public void mouseDragged(MouseEvent e) {
					handleMouseDrag(e);
				}
			});

			System.out.println("TerminalCanvas: cols=" + cols + ", rows=" + rows + ", charWidth=" + charWidth
					+ ", charHeight=" + charHeight);
		}

		public boolean isFocusable() {
			return true;
		}

		public void update(Graphics g) {
			paint(g);
		}

		public void paint(Graphics g) {
			if (offscreenImage == null) {
				offscreenImage = createImage(getWidth(), getHeight());
				if (offscreenImage == null) {
					paintScreen(g);
					return;
				}
				offscreenGraphics = offscreenImage.getGraphics();
			}

			paintScreen(offscreenGraphics);
			g.drawImage(offscreenImage, 0, 0, this);
		}

		private void paintScreen(Graphics g) {
			// g.setColor(Color.BLACK);
			g.setColor(screenBackground);
			g.fillRect(0, 0, getWidth(), getHeight());

			g.setFont(terminalFont);

			boolean blinkVisible = (System.currentTimeMillis() / 500) % 2 == 0;

			for (int row = 0; row < rows; row++) {
				for (int col = 0; col < cols; col++) {
					int pos = row * cols + col;
					char c = buffer[pos];

					if (isNonDisplay(pos) && !isFieldStart(pos)) {
						continue;
					}

					if (c == '\0')
						c = ' ';

					// Color fg = Color.GREEN;
					// Color bg = Color.BLACK;
					Color fg = defaultForeground; // Use the color scheme's default
					Color bg = screenBackground; // Use the color scheme's background
					boolean reverseVideo = false;
					boolean blink = false;

					// Check if this position is selected
					boolean isSelected = false;
					if (selectionStart >= 0 && selectionEnd >= 0) {
						int start = Math.min(selectionStart, selectionEnd);
						int end = Math.max(selectionStart, selectionEnd);
						isSelected = (pos >= start && pos <= end);
					}

					byte highlight = highlighting[pos];
					if (highlight == (byte) 0xF2) {
						reverseVideo = true;
					} else if (highlight == (byte) 0xF1) {
						blink = true;
					}

					if (extendedColors[pos] > 0 && extendedColors[pos] < colors.length) {
						fg = colors[extendedColors[pos]];
						// if (extendedColors[pos] > 0 && extendedColors[pos] < COLORS.length) {
						// fg = COLORS[extendedColors[pos]];
					} else {
						int fieldStart = findFieldStart(pos);
						int colorCode = (attributes[fieldStart] >> 4) & 0x07;
						if (colorCode > 0 && colorCode < colors.length) {
							fg = colors[colorCode];
							// if (colorCode > 0 && colorCode < COLORS.length) {
							// fg = COLORS[colorCode];
						}

						if (isProtected(pos) && isFieldStart(pos)) {
							fg = Color.CYAN;
						}
					}

					int x = 5 + col * charWidth;
					int y = 5 + row * charHeight + charHeight - 3;

					// if (reverseVideo) {
					// Color temp = fg;
					// fg = bg;
					// bg = temp;
					// }

					// Handle selection highlighting
					if (isSelected) {
						// Swap colors for selection
						Color temp = fg;
						fg = new Color(255, 255, 255); // White text
						bg = new Color(0, 120, 215); // Blue selection background
					} else if (reverseVideo) {
						Color temp = fg;
						fg = bg;
						bg = temp;
					}

					if (!bg.equals(Color.BLACK)) {
						g.setColor(bg);
						g.fillRect(x, y - charHeight + 3, charWidth, charHeight);
					}

					if (blink && !blinkVisible) {
						continue;
					}

					g.setColor(fg);
					g.drawString(String.valueOf(c), x, y);
				}
			}

			if (!keyboardLocked) {
				int row = cursorPos / cols;
				int col = cursorPos % cols;
				int x = 5 + col * charWidth;
				int y = 5 + row * charHeight;

				// g.setColor(Color.WHITE);
				g.setColor(cursorColor); // Use the color scheme's cursor color
				g.fillRect(x, y + charHeight - 3, charWidth, 2);
			}
		}

		public void updateSize() {
			setPreferredSize(new Dimension(cols * charWidth + 10, rows * charHeight + 10));
			if (offscreenImage != null) {
				offscreenImage = null; // Force recreation with new size
			}
			revalidate();
			TN3270Emulator.this.pack();
		}
	}

	class StatusBar extends Panel {
		private Label statusLabel;
		private Label positionLabel;
		private Label modeLabel;

		public StatusBar() {
			setLayout(new FlowLayout(FlowLayout.LEFT));
			setBackground(Color.DARK_GRAY);

			statusLabel = new Label("Not connected");
			statusLabel.setForeground(Color.WHITE);
			add(statusLabel);

			add(new Label("  "));

			positionLabel = new Label("Pos: 001/001");
			positionLabel.setForeground(Color.WHITE);
			add(positionLabel);

			add(new Label("  "));

			modeLabel = new Label("Mode: ");
			modeLabel.setForeground(Color.WHITE);
			add(modeLabel);
		}

		public void setStatus(String status) {
			statusLabel.setText(status);
		}

		public void update() {
			int row = cursorPos / cols + 1;
			int col = cursorPos % cols + 1;
			positionLabel.setText(String.format("Pos: %03d/%03d", row, col));

			String mode = insertMode ? "Insert" : "Replace";
			if (keyboardLocked)
				mode += " [LOCKED]";
			modeLabel.setText("Mode: " + mode);
		}
	}

	class KeyboardPanel extends Panel {
		public KeyboardPanel() {
			setLayout(new GridLayout(2, 1, 5, 5)); // 2 rows, 1 column
			setBackground(Color.LIGHT_GRAY);

			// Row 1: Control keys, PA keys, and editing functions
			Panel row1 = new Panel(new FlowLayout(FlowLayout.CENTER, 5, 5));
			addButtonToPanel(row1, "CLEAR", AID_CLEAR);
			addButtonToPanel(row1, "PA1", AID_PA1);
			addButtonToPanel(row1, "PA2", AID_PA2);
			addButtonToPanel(row1, "PA3", AID_PA3);

			Button resetBtn = new Button("RESET");
			resetBtn.addActionListener(e -> {
				keyboardLocked = false;
				statusBar.update();
				canvas.repaint();
				canvas.requestFocus(); // Return focus to canvas
			});
			row1.add(resetBtn);

			Button enterBtn = new Button("ENTER");
			enterBtn.addActionListener(e -> {
				if (!keyboardLocked && connected) {
					// System.out.println("Calling SendAID from ENTER button...");
					sendAID(AID_ENTER);
				}
				canvas.requestFocus(); // Return focus to canvas
			});
			row1.add(enterBtn);

			Button insertBtn = new Button("INSERT");
			insertBtn.addActionListener(e -> {
				insertMode = !insertMode;
				// System.out.println("INSERT button clicked - insertMode now: " + insertMode);
				statusBar.update();
				canvas.repaint();
				canvas.requestFocus(); // Return focus to canvas
			});
			row1.add(insertBtn);

			Button eraseEOFBtn = new Button("ERASE EOF");
			eraseEOFBtn.addActionListener(e -> {
				if (!keyboardLocked && connected) {
					eraseToEndOfField();
				}
				canvas.requestFocus(); // Return focus to canvas
			});
			row1.add(eraseEOFBtn);

			Button eraseEOLBtn = new Button("ERASE EOL");
			eraseEOLBtn.addActionListener(e -> {
				if (!keyboardLocked && connected) {
					eraseToEndOfLine();
				}
				canvas.requestFocus(); // Return focus to canvas
			});
			row1.add(eraseEOLBtn);

			add(row1);

			// Row 2: PF1-PF12
			Panel row2 = new Panel(new FlowLayout(FlowLayout.CENTER, 5, 5));
			for (int i = 1; i <= 12; i++) {
				byte aid = (byte) (AID_PF1 + (i - 1));
				if (i == 10)
					aid = AID_PF10;
				else if (i == 11)
					aid = AID_PF11;
				else if (i == 12)
					aid = AID_PF12;
				addButtonToPanel(row2, "F" + i, aid);
			}
			add(row2);
		}

		private void addButtonToPanel(Panel panel, String label, byte aid) {
			Button btn = new Button(label);
			btn.addActionListener(e -> {
				if (!keyboardLocked && connected) {
					// System.out.println("Calling SendAID from addButtonToPanel...");
					sendAID(aid);
				}
				canvas.requestFocus(); // Return focus to canvas
			});
			panel.add(btn);
		}
	}

	public static void main(String[] args) {
		// Set modern look and feel
		try {
			// Try to use system look and feel for native appearance
			// UIManager.setSystemLookAndFeel();
			UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
		} catch (Exception e) {
			System.err.println("Could not set look and feel: " + e.getMessage());
		}

		if (args.length < 1) {
			// Show connection dialog instead of command-line only
			showConnectionDialog();
			return;
		}

		/*
		 * if (args.length < 1) { System.out.
		 * println("Usage: java TN3270Emulator <hostname> [port] [model] [options]");
		 * System.out.
		 * println("Models: 3278-2 (24x80), 3278-3 (32x80), 3278-4 (43x80), 3278-5 (27x132)"
		 * ); System.out.println("Options:");
		 * System.out.println("  --tls        Use TLS/SSL encryption");
		 * System.out.println("  --tn3270e    Prefer TN3270E mode"); System.exit(1); }
		 */

		String hostname = args[0];
		int port = 23;
		String model = "3279-3";
		boolean useTLS = false;

		for (int i = 1; i < args.length; i++) {
			if (args[i].equals("--tls")) {
				useTLS = true;
			} else if (args[i].equals("--tn3270e")) {
				// TN3270E negotiation happens automatically
			} else if (args[i].startsWith("3278-") || args[i].startsWith("3279-")) {
				model = args[i].trim();
			} else {
				try {
					port = Integer.parseInt(args[i]);
				} catch (NumberFormatException e) {
					// Ignore
				}
			}
		}

		TN3270Emulator emulator = new TN3270Emulator(model);
		emulator.setUseTLS(useTLS);
		emulator.connect(hostname, port);
	}
}
