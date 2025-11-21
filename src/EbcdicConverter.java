import java.util.Arrays;

/**
 * 
 * EBCDIC to ASCII character set conversion. Supports standard EBCDIC (CP037)
 * and APL character sets.
 */
public class EbcdicConverter {
	// Translation tables
	private static final char[] EBCDIC_TO_ASCII = new char[256];
	private static final byte[] ASCII_TO_EBCDIC = new byte[256];
	private static final char[] EBCDIC_TO_APL = new char[256];
	static {
		initializeTranslationTables();
	}

	/**
	 * 
	 * Initialize all EBCDIC translation tables.
	 */
	private static void initializeTranslationTables() {
		// Initialize with nulls
		Arrays.fill(EBCDIC_TO_ASCII, '\0');
		Arrays.fill(ASCII_TO_EBCDIC, (byte) 0x00);

		// This is the complete translation table
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
		// Initialize APL character set
		System.arraycopy(EBCDIC_TO_ASCII, 0, EBCDIC_TO_APL, 0, 256);

		// Override with APL/box-drawing characters
		//EBCDIC_TO_APL[0xAD] = '┌'; // Top-left 'E'
		EBCDIC_TO_APL[0xC5] = '┌'; // Top-left 'E' (corrected)
		//EBCDIC_TO_APL[0xAE] = '┐'; // Top-right 'N'
		EBCDIC_TO_APL[0xD5] = '┐'; // Top-right 'N' (corrected)
		//EBCDIC_TO_APL[0xBD] = '└'; // Bottom-left 'D'
		EBCDIC_TO_APL[0xC4] = '└'; // Bottom-left 'D' (corrected)
		//EBCDIC_TO_APL[0xBE] = '┘'; // Bottom-right 'M'
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

	/**
	 * 
	 * Convert EBCDIC byte to ASCII character.
	 */
	public static char ebcdicToAscii(byte b) {
		return EBCDIC_TO_ASCII[b & 0xFF];
	}

	/**
	 * 
	 * Convert ASCII character to EBCDIC byte.
	 */
	public static byte asciiToEbcdic(char c) {
		if (c < 256) {
			return ASCII_TO_EBCDIC[c];
		}
		return 0x40; // Space
	}

	/**
	 * 
	 * Convert EBCDIC byte to APL character.
	 */
	public static char ebcdicToApl(byte b) {
		return EBCDIC_TO_APL[b & 0xFF];
	}

	/**
	 * 
	 * Get the ASCII translation table.
	 */
	public static char[] getAsciiTable() {
		return EBCDIC_TO_ASCII;
	}

	/**
	 * 
	 * Get the APL translation table.
	 */
	public static char[] getAplTable() {
		return EBCDIC_TO_APL;
	}
}
