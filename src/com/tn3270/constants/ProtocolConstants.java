package com.tn3270.constants;

public class ProtocolConstants {
	// -----------------------------------------------------------------------
	// 3270 Commands
	// defined as int to avoid Java signed byte issues (e.g., 0xF5 != -11)
	// -----------------------------------------------------------------------
	public static final int CMD_WRITE_01 = 0x01;
	public static final int CMD_WRITE_F1 = 0xF1;
	public static final int CMD_ERASE_WRITE_05 = 0x05;
	public static final int CMD_ERASE_WRITE_F5 = 0xF5;
	public static final int CMD_ERASE_WRITE_ALTERNATE_0D = 0x0D;
	public static final int CMD_ERASE_WRITE_ALTERNATE_7E = 0x7E;
	public static final int CMD_READ_BUFFER_02 = 0x02;
	public static final int CMD_READ_BUFFER_F2 = 0xF2;
	public static final int CMD_READ_MODIFIED_06 = 0x06;
	public static final int CMD_READ_MODIFIED_F6 = 0xF6;
	public static final int CMD_READ_MODIFIED_ALL_0E = 0x0E;
	public static final int CMD_READ_MODIFIED_ALL_6E = 0x6E;
	public static final int CMD_WSF_11 = 0x11;
	public static final int CMD_WSF_F3 = 0xF3;
	public static final int CMD_ERASE_ALL_UNPROTECTED_0F = 0x0F;
	public static final int CMD_ERASE_ALL_UNPROTECTED_6F = 0x6F;

	// -----------------------------------------------------------------------
	// 3270 Orders
	// -----------------------------------------------------------------------
	public static final int ORDER_SF = 0x1D;
	public static final int ORDER_SFE = 0x29;
	public static final int ORDER_SA = 0x28;
	public static final int ORDER_SBA = 0x11;
	public static final int ORDER_IC = 0x13;
	public static final int ORDER_RA = 0x3C;
	public static final int ORDER_MF = 0x2C;
	public static final int ORDER_GE = 0x08;
	public static final int ORDER_EUA = 0x12;
	public static final int ORDER_FM = 0x0E; // or 0x1E
	public static final int ORDER_PT = 0x05;

	// -----------------------------------------------------------------------
	// Attribute types for SA/SFE
	// -----------------------------------------------------------------------
	public static final int ATTR_FIELD = 0xC0;
	public static final int ATTR_HIGHLIGHTING = 0x41;
	public static final int ATTR_FOREGROUND = 0x42;
	public static final int ATTR_BACKGROUND = 0x45;
	public static final int ATTR_CHAR_SET = 0x43;
	public static final byte CHARSET_APL = (byte) 0xF1;

	// -----------------------------------------------------------------------
	// 3270 AIDs (Attention IDs)
	// -----------------------------------------------------------------------
	public static final int AID_ENTER = 0x7D;
	public static final int AID_CLEAR = 0x6D;
	public static final int AID_SYSREQ = 0xF0;
	public static final int AID_ATTN = 0x6A;
	public static final int AID_CURSOR_SELECT = 0x7E;
	public static final int AID_PA1 = 0x6C;
	public static final int AID_PA2 = 0x6E;
	public static final int AID_PA3 = 0x6B;

	// PF Keys
	public static final int AID_PF1 = 0xF1;
	public static final int AID_PF2 = 0xF2;
	public static final int AID_PF3 = 0xF3;
	public static final int AID_PF4 = 0xF4;
	public static final int AID_PF5 = 0xF5;
	public static final int AID_PF6 = 0xF6;
	public static final int AID_PF7 = 0xF7;
	public static final int AID_PF8 = 0xF8;
	public static final int AID_PF9 = 0xF9;
	public static final int AID_PF10 = 0x7A;
	public static final int AID_PF11 = 0x7B;
	public static final int AID_PF12 = 0x7C;
	public static final int AID_PF13 = 0xC1;
	public static final int AID_PF14 = 0xC2;
	public static final int AID_PF15 = 0xC3;
	public static final int AID_PF16 = 0xC4;
	public static final int AID_PF17 = 0xC5;
	public static final int AID_PF18 = 0xC6;
	public static final int AID_PF19 = 0xC7;
	public static final int AID_PF20 = 0xC8;
	public static final int AID_PF21 = 0xC9;
	public static final int AID_PF22 = 0x4A;
	public static final int AID_PF23 = 0x4B;
	public static final int AID_PF24 = 0x4C;

	// -----------------------------------------------------------------------
	// File Transfer AIDs/Responses
	// -----------------------------------------------------------------------
	public static final int AID_STRUCTURED_FIELD = 0x88;
	public static final int RESP_POSITIVE = 0x09;
	public static final int RESP_NEGATIVE = 0x08;

	// PF1–PF24 Array (Updated to int[])
	public static final int[] PF_AID = new int[] { AID_PF1, AID_PF2, AID_PF3, AID_PF4, AID_PF5, AID_PF6, AID_PF7,
			AID_PF8, AID_PF9, AID_PF10, AID_PF11, AID_PF12, AID_PF13, AID_PF14, AID_PF15, AID_PF16, AID_PF17, AID_PF18,
			AID_PF19, AID_PF20, AID_PF21, AID_PF22, AID_PF23, AID_PF24 };

	// -----------------------------------------------------------------------
	// WCC bits
	// -----------------------------------------------------------------------
	public static final int WCC_RESET = 0x40;
	public static final int WCC_ALARM = 0x04;
	public static final int WCC_RESET_MDT = 0x01;

	// -----------------------------------------------------------------------
	// File Transfer SFIDs
	// -----------------------------------------------------------------------
	public static final int SFID_DATA_CHAIN = 0xD0;
	public static final int SFID_INBOUND_3270DS = 0x61;

	// -----------------------------------------------------------------------
	// Data Chain Opcodes
	// -----------------------------------------------------------------------
	public static final int DC_OPEN = 0x00;
	public static final int DC_CLOSE = 0x41;
	public static final int DC_SET_CURSOR = 0x45;
	public static final int DC_GET = 0x46;
	public static final int DC_INSERT = 0x47;

	// -----------------------------------------------------------------------
	// Structured Field IDs
	// -----------------------------------------------------------------------
	public static final int SF_ID_SET_REPLY_MODE = 0x09;
	public static final int SF_ID_READ_PARTITION_QUERY_LIST = 0xA1;
}
