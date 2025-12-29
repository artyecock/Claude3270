package com.tn3270.util;

import java.util.Arrays;

public class EBCDIC {
    public static final char[] EBCDIC_TO_ASCII = new char[256];
    public static final byte[] ASCII_TO_EBCDIC = new byte[256];
    public static final char[] EBCDIC_TO_APL = new char[256];
    
    // NEW: Reverse mapping for APL (Sparse array using char as index)
    // 64KB size is negligible in modern Java and allows O(1) lookup.
    // (Nope, it's not negligible)
    //public static final byte[] APL_TO_EBCDIC = new byte[65536]; 

    // Address decoding table
    public static final byte[] ADDRESS_TABLE = { 
        (byte) 0x40, (byte) 0xC1, (byte) 0xC2, (byte) 0xC3, (byte) 0xC4, (byte) 0xC5, (byte) 0xC6, (byte) 0xC7, // 0x00-0x07
        (byte) 0xC8, (byte) 0xC9, (byte) 0x4A, (byte) 0x4B, (byte) 0x4C, (byte) 0x4D, (byte) 0x4E, (byte) 0x4F, // 0x08-0x0F
        (byte) 0x50, (byte) 0xD1, (byte) 0xD2, (byte) 0xD3, (byte) 0xD4, (byte) 0xD5, (byte) 0xD6, (byte) 0xD7, // 0x10-0x17
        (byte) 0xD8, (byte) 0xD9, (byte) 0x5A, (byte) 0x5B, (byte) 0x5C, (byte) 0x5D, (byte) 0x5E, (byte) 0x5F, // 0x18-0x1F
        (byte) 0x60, (byte) 0x61, (byte) 0xE2, (byte) 0xE3, (byte) 0xE4, (byte) 0xE5, (byte) 0xE6, (byte) 0xE7, // 0x20-0x27
        (byte) 0xE8, (byte) 0xE9, (byte) 0x6A, (byte) 0x6B, (byte) 0x6C, (byte) 0x6D, (byte) 0x6E, (byte) 0x6F, // 0x28-0x2F
        (byte) 0xF0, (byte) 0xF1, (byte) 0xF2, (byte) 0xF3, (byte) 0xF4, (byte) 0xF5, (byte) 0xF6, (byte) 0xF7, // 0x30-0x37
        (byte) 0xF8, (byte) 0xF9, (byte) 0x7A, (byte) 0x7B, (byte) 0x7C, (byte) 0x7D, (byte) 0x7E, (byte) 0x7F // 0x38-0x3F
    };

    static {
        // Initialize with nulls
        Arrays.fill(EBCDIC_TO_ASCII, '\0');
        Arrays.fill(ASCII_TO_EBCDIC, (byte) 0x00);
        //Arrays.fill(APL_TO_EBCDIC, (byte) 0x00); // Initialize new table

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
        //EBCDIC_TO_ASCII[0x6A] = '|';
        // MODIFIED: Map 0x6A to Broken Bar '¦' instead of Pipe '|' to reduce ambiguity,
        // though we will still force the reverse mapping below to be safe.
        EBCDIC_TO_ASCII[0x6A] = '¦'; 
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
        EBCDIC_TO_ASCII[0x81] = 'a'; EBCDIC_TO_ASCII[0x82] = 'b'; EBCDIC_TO_ASCII[0x83] = 'c'; EBCDIC_TO_ASCII[0x84] = 'd';
        EBCDIC_TO_ASCII[0x85] = 'e'; EBCDIC_TO_ASCII[0x86] = 'f'; EBCDIC_TO_ASCII[0x87] = 'g'; EBCDIC_TO_ASCII[0x88] = 'h';
        EBCDIC_TO_ASCII[0x89] = 'i'; EBCDIC_TO_ASCII[0x91] = 'j'; EBCDIC_TO_ASCII[0x92] = 'k'; EBCDIC_TO_ASCII[0x93] = 'l';
        EBCDIC_TO_ASCII[0x94] = 'm'; EBCDIC_TO_ASCII[0x95] = 'n'; EBCDIC_TO_ASCII[0x96] = 'o'; EBCDIC_TO_ASCII[0x97] = 'p';
        EBCDIC_TO_ASCII[0x98] = 'q'; EBCDIC_TO_ASCII[0x99] = 'r'; EBCDIC_TO_ASCII[0xA1] = '~'; EBCDIC_TO_ASCII[0xA2] = 's';
        EBCDIC_TO_ASCII[0xA3] = 't'; EBCDIC_TO_ASCII[0xA4] = 'u'; EBCDIC_TO_ASCII[0xA5] = 'v'; EBCDIC_TO_ASCII[0xA6] = 'w';
        EBCDIC_TO_ASCII[0xA7] = 'x'; EBCDIC_TO_ASCII[0xA8] = 'y'; EBCDIC_TO_ASCII[0xA9] = 'z';

        // Uppercase letters
        EBCDIC_TO_ASCII[0xC1] = 'A'; EBCDIC_TO_ASCII[0xC2] = 'B'; EBCDIC_TO_ASCII[0xC3] = 'C'; EBCDIC_TO_ASCII[0xC4] = 'D';
        EBCDIC_TO_ASCII[0xC5] = 'E'; EBCDIC_TO_ASCII[0xC6] = 'F'; EBCDIC_TO_ASCII[0xC7] = 'G'; EBCDIC_TO_ASCII[0xC8] = 'H';
        EBCDIC_TO_ASCII[0xC9] = 'I'; EBCDIC_TO_ASCII[0xD1] = 'J'; EBCDIC_TO_ASCII[0xD2] = 'K'; EBCDIC_TO_ASCII[0xD3] = 'L';
        EBCDIC_TO_ASCII[0xD4] = 'M'; EBCDIC_TO_ASCII[0xD5] = 'N'; EBCDIC_TO_ASCII[0xD6] = 'O'; EBCDIC_TO_ASCII[0xD7] = 'P';
        EBCDIC_TO_ASCII[0xD8] = 'Q'; EBCDIC_TO_ASCII[0xD9] = 'R'; EBCDIC_TO_ASCII[0xE2] = 'S'; EBCDIC_TO_ASCII[0xE3] = 'T';
        EBCDIC_TO_ASCII[0xE4] = 'U'; EBCDIC_TO_ASCII[0xE5] = 'V'; EBCDIC_TO_ASCII[0xE6] = 'W'; EBCDIC_TO_ASCII[0xE7] = 'X';
        EBCDIC_TO_ASCII[0xE8] = 'Y'; EBCDIC_TO_ASCII[0xE9] = 'Z';

        // Numbers
        EBCDIC_TO_ASCII[0xF0] = '0'; EBCDIC_TO_ASCII[0xF1] = '1'; EBCDIC_TO_ASCII[0xF2] = '2'; EBCDIC_TO_ASCII[0xF3] = '3';
        EBCDIC_TO_ASCII[0xF4] = '4'; EBCDIC_TO_ASCII[0xF5] = '5'; EBCDIC_TO_ASCII[0xF6] = '6'; EBCDIC_TO_ASCII[0xF7] = '7';
        EBCDIC_TO_ASCII[0xF8] = '8'; EBCDIC_TO_ASCII[0xF9] = '9';

        // Additional characters
        EBCDIC_TO_ASCII[0xBA] = '['; EBCDIC_TO_ASCII[0xBB] = ']';
        EBCDIC_TO_ASCII[0xC0] = '{'; EBCDIC_TO_ASCII[0xD0] = '}'; EBCDIC_TO_ASCII[0xE0] = '\\';

        // Build reverse mapping
        ASCII_TO_EBCDIC[' '] = 0x40;
        ASCII_TO_EBCDIC['\0'] = 0x00;
        for (int i = 0; i < 256; i++) {
            char c = EBCDIC_TO_ASCII[i];
            if (c > 0 && c < 256 && c != ' ' && c != '\0') {
                ASCII_TO_EBCDIC[c] = (byte) i;
            }
        }
        
        // CRITICAL FIX: Force Pipe '|' to map to Solid Vertical Bar (0x4F).
        // Without this, the loop above maps '|' to 0x6A (Broken Bar) if 0x6A was mapped to '|',
        // which breaks shell pipes on Linux (expects 0x4F).
        ASCII_TO_EBCDIC['|'] = (byte) 0x4F;
        ASCII_TO_EBCDIC['¦'] = (byte) 0x6A; 

        // Initialize APL character set
        System.arraycopy(EBCDIC_TO_ASCII, 0, EBCDIC_TO_APL, 0, 256);
        
        EBCDIC_TO_APL[0xAD] = '┌'; 
        EBCDIC_TO_APL[0xC5] = '┌'; 
        EBCDIC_TO_APL[0xAE] = '┐'; 
        EBCDIC_TO_APL[0xD5] = '┐'; 
        EBCDIC_TO_APL[0xBD] = '└'; 
        EBCDIC_TO_APL[0xC4] = '└'; 
        EBCDIC_TO_APL[0xBE] = '┘'; 
        EBCDIC_TO_APL[0xD4] = '┘'; 
        EBCDIC_TO_APL[0xC6] = '├'; 
        EBCDIC_TO_APL[0xD6] = '┤'; 
        EBCDIC_TO_APL[0xD7] = '┬'; 
        EBCDIC_TO_APL[0xC7] = '┴'; 
        EBCDIC_TO_APL[0xD3] = '┼'; 
        EBCDIC_TO_APL[0xA2] = '\u2500'; 
        // FIX: Use 'Left 1/8 Block' (\u258F) instead of 'Box Light Vertical' (\u2502).
        // Blocks fill the entire line height (including leading), ensuring a 
        // continuous vertical line without gaps on all displays.
        //EBCDIC_TO_APL[0x85] = '\u2502'; 
        //EBCDIC_TO_APL[0x85] = '\u258F'; 
        // REVERT: Use Standard 'Box Drawings Light Vertical' (\u2502) for alignment.
        // We will fix the "Gap/Shortness" issue via custom rendering in TerminalPanel.
        EBCDIC_TO_APL[0x85] = '\u2502';
        EBCDIC_TO_APL[0xA3] = '\u25cf'; 
        
        // NEW: Populate Reverse APL Table
        // This iterates through the populated EBCDIC_TO_APL to build the reverse map
        //for (int i = 0; i < 256; i++) {
        //    char c = EBCDIC_TO_APL[i];
        //    if (c != '\0') {
        //        APL_TO_EBCDIC[c] = (byte) i;
        //    }
        //}
    }
}
