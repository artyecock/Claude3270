package config;

/**
 * TN3270 Protocol Constants
 * 
 * Contains all byte codes, command values, and fixed data structures used in
 * the TN3270 and Telnet protocols.
 */
public class Constants {

// ===== TELNET COMMANDS =====
	public static final byte IAC = (byte) 0xFF;
	public static final byte DO = (byte) 0xFD;
	public static final byte DONT = (byte) 0xFE;
	public static final byte WILL = (byte) 0xFB;
	public static final byte WONT = (byte) 0xFC;
	public static final byte SB = (byte) 0xFA;
	public static final byte SE = (byte) 0xF0;

// ===== TELNET OPTIONS =====
	public static final byte OPT_BINARY = (byte) 0x00;
	public static final byte OPT_TERMINAL_TYPE = (byte) 0x18;
	public static final byte OPT_EOR = (byte) 0x19;
	public static final byte OPT_TN3270E = (byte) 0x28;

// ===== TN3270E HEADER =====
	public static final byte TN3270E_DT_3270_DATA = 0x00;
	public static final byte TN3270E_DT_SCS_DATA = 0x01;
	public static final byte TN3270E_DT_RESPONSE = 0x02;
	public static final byte TN3270E_DT_BIND_IMAGE = 0x03;
	public static final byte TN3270E_DT_UNBIND = 0x04;

// ===== 3270 COMMANDS =====
	public static final byte CMD_WRITE_01 = (byte) 0x01;
	public static final byte CMD_WRITE_F1 = (byte) 0xF1;
	public static final byte CMD_ERASE_WRITE_05 = (byte) 0x05;
	public static final byte CMD_ERASE_WRITE_F5 = (byte) 0xF5;
	public static final byte CMD_ERASE_WRITE_ALTERNATE_0D = (byte) 0x0D;
	public static final byte CMD_ERASE_WRITE_ALTERNATE_7E = (byte) 0x7E;
	public static final byte CMD_READ_BUFFER_02 = (byte) 0x02;
	public static final byte CMD_READ_BUFFER_F2 = (byte) 0xF2;
	public static final byte CMD_READ_MODIFIED_06 = (byte) 0x06;
	public static final byte CMD_READ_MODIFIED_F6 = (byte) 0xF6;
	public static final byte CMD_READ_MODIFIED_ALL_0E = (byte) 0x0E;
	public static final byte CMD_READ_MODIFIED_ALL_6E = (byte) 0x6E;
	public static final byte CMD_WSF_11 = (byte) 0x11;
	public static final byte CMD_WSF_F3 = (byte) 0xF3;
	public static final byte CMD_ERASE_ALL_UNPROTECTED_0F = (byte) 0x0F;
	public static final byte CMD_ERASE_ALL_UNPROTECTED_6F = (byte) 0x6F;

// ===== 3270 ORDERS =====
	public static final byte ORDER_SF = (byte) 0x1D;
	public static final byte ORDER_SFE = (byte) 0x29;
	public static final byte ORDER_SA = (byte) 0x28;
	public static final byte ORDER_SBA = (byte) 0x11;
	public static final byte ORDER_IC = (byte) 0x13;
	public static final byte ORDER_RA = (byte) 0x3C;
	public static final byte ORDER_MF = (byte) 0x2C;
	public static final byte ORDER_GE = (byte) 0x08;
	public static final byte ORDER_EUA = (byte) 0x12;
	public static final byte ORDER_FM = (byte) 0x0E;

// ===== ATTRIBUTE TYPES =====
	public static final byte ATTR_FIELD = (byte) 0xC0;
	public static final byte ATTR_HIGHLIGHTING = (byte) 0x41;
	public static final byte ATTR_FOREGROUND = (byte) 0x42;
	public static final byte ATTR_BACKGROUND = (byte) 0x45;

// ===== 3270 AIDs =====
	public static final byte AID_ENTER = (byte) 0x7D;
	public static final byte AID_CLEAR = (byte) 0x6D;
	public static final byte AID_PA1 = (byte) 0x6C;
	public static final byte AID_PA2 = (byte) 0x6E;
	public static final byte AID_PA3 = (byte) 0x6B;
	public static final byte AID_PF1 = (byte) 0xF1;
	public static final byte AID_PF2 = (byte) 0xF2;
	public static final byte AID_PF3 = (byte) 0xF3;
	public static final byte AID_PF4 = (byte) 0xF4;
	public static final byte AID_PF5 = (byte) 0xF5;
	public static final byte AID_PF6 = (byte) 0xF6;
	public static final byte AID_PF7 = (byte) 0xF7;
	public static final byte AID_PF8 = (byte) 0xF8;
	public static final byte AID_PF9 = (byte) 0xF9;
	public static final byte AID_PF10 = (byte) 0x7A;
	public static final byte AID_PF11 = (byte) 0x7B;
	public static final byte AID_PF12 = (byte) 0x7C;
	public static final byte AID_PF13 = (byte) 0xC1;
	public static final byte AID_PF14 = (byte) 0xC2;
	public static final byte AID_PF15 = (byte) 0xC3;
	public static final byte AID_PF16 = (byte) 0xC4;
	public static final byte AID_PF17 = (byte) 0xC5;
	public static final byte AID_PF18 = (byte) 0xC6;
	public static final byte AID_PF19 = (byte) 0xC7;
	public static final byte AID_PF20 = (byte) 0xC8;
	public static final byte AID_PF21 = (byte) 0xC9;
	public static final byte AID_PF22 = (byte) 0x4A;
	public static final byte AID_PF23 = (byte) 0x4B;
	public static final byte AID_PF24 = (byte) 0x4C;
	public static final byte AID_STRUCTURED_FIELD = (byte) 0x88;

// ===== WCC BITS =====
	public static final byte WCC_RESET = (byte) 0x40;
	public static final byte WCC_ALARM = (byte) 0x04;
	public static final byte WCC_RESET_MDT = (byte) 0x01;

// ===== FILE TRANSFER =====
	public static final byte SFID_DATA_CHAIN = (byte) 0xD0;
	public static final byte SFID_INBOUND_3270DS = (byte) 0x61;

	public static final byte DC_OPEN = 0x00;
	public static final byte DC_CLOSE = 0x41;
	public static final byte DC_SET_CURSOR = 0x45;
	public static final byte DC_GET = 0x46;
	public static final byte DC_INSERT = 0x47;

	public static final byte RESP_POSITIVE = 0x09;
	public static final byte RESP_NEGATIVE = 0x08;

// ===== STRUCTURED FIELD TYPES =====
	public static final byte SF_ID_READ_PARTITION = (byte) 0x01;
	public static final byte SF_ID_SET_REPLY_MODE = (byte) 0x09;
	public static final byte SF_ID_QUERY_REPLY = (byte) 0x81;

// Set Reply Mode subtypes
	public static final byte REPLY_MODE_FIELD = 0x00;
	public static final byte REPLY_MODE_EXTENDED_FIELD = 0x01;
	public static final byte REPLY_MODE_CHARACTER = 0x02;

// Query Reply types
	public static final byte QREPLY_SUMMARY = (byte) 0x80;
	public static final byte QREPLY_USABLE_AREA = (byte) 0x81;
	public static final byte QREPLY_ALPHANUMERIC = (byte) 0x84;
	public static final byte QREPLY_CHARACTER_SETS = (byte) 0x85;
	public static final byte QREPLY_COLOR = (byte) 0x86;
	public static final byte QREPLY_HIGHLIGHTING = (byte) 0x87;
	public static final byte QREPLY_REPLY_MODES = (byte) 0x88;

// ===== 3270 ADDRESS TRANSLATION TABLE =====
	public static final byte[] ADDRESS_TABLE = { (byte) 0x40, (byte) 0xC1, (byte) 0xC2, (byte) 0xC3, (byte) 0xC4,
			(byte) 0xC5, (byte) 0xC6, (byte) 0xC7, // 0x00-0x07
			(byte) 0xC8, (byte) 0xC9, (byte) 0x4A, (byte) 0x4B, (byte) 0x4C, (byte) 0x4D, (byte) 0x4E, (byte) 0x4F, // 0x08-0x0F
			(byte) 0x50, (byte) 0xD1, (byte) 0xD2, (byte) 0xD3, (byte) 0xD4, (byte) 0xD5, (byte) 0xD6, (byte) 0xD7, // 0x10-0x17
			(byte) 0xD8, (byte) 0xD9, (byte) 0x5A, (byte) 0x5B, (byte) 0x5C, (byte) 0x5D, (byte) 0x5E, (byte) 0x5F, // 0x18-0x1F
			(byte) 0x60, (byte) 0x61, (byte) 0xE2, (byte) 0xE3, (byte) 0xE4, (byte) 0xE5, (byte) 0xE6, (byte) 0xE7, // 0x20-0x27
			(byte) 0xE8, (byte) 0xE9, (byte) 0x6A, (byte) 0x6B, (byte) 0x6C, (byte) 0x6D, (byte) 0x6E, (byte) 0x6F, // 0x28-0x2F
			(byte) 0xF0, (byte) 0xF1, (byte) 0xF2, (byte) 0xF3, (byte) 0xF4, (byte) 0xF5, (byte) 0xF6, (byte) 0xF7, // 0x30-0x37
			(byte) 0xF8, (byte) 0xF9, (byte) 0x7A, (byte) 0x7B, (byte) 0x7C, (byte) 0x7D, (byte) 0x7E, (byte) 0x7F // 0x38-0x3F
	};
}
