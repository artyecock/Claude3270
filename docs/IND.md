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

