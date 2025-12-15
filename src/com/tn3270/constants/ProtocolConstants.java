package com.tn3270.constants;

public class ProtocolConstants {
    // 3270 Commands
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

    // 3270 Orders
    public static final byte ORDER_SF = (byte) 0x1D;
    public static final byte ORDER_SFE = (byte) 0x29;
    public static final byte ORDER_SA = (byte) 0x28;
    public static final byte ORDER_SBA = (byte) 0x11;
    public static final byte ORDER_IC = (byte) 0x13;
    public static final byte ORDER_RA = (byte) 0x3C;
    public static final byte ORDER_MF = (byte) 0x2C;
    public static final byte ORDER_GE = (byte) 0x08;
    public static final byte ORDER_EUA = (byte) 0x12;
    public static final byte ORDER_FM = (byte) 0x0E; // or 0x1E
    public static final byte ORDER_PT = (byte) 0x05;

    // Attribute types for SA/SFE
    public static final byte ATTR_FIELD = (byte) 0xC0;
    public static final byte ATTR_HIGHLIGHTING = (byte) 0x41;
    public static final byte ATTR_FOREGROUND = (byte) 0x42;
    public static final byte ATTR_BACKGROUND = (byte) 0x45;

    // 3270 AIDs
    public static final byte AID_ENTER = (byte) 0x7D;
    public static final byte AID_CLEAR = (byte) 0x6D;
    public static final byte AID_SYSREQ = (byte) 0xF0;
    public static final byte AID_ATTN = (byte) 0x6A;
    public static final byte AID_CURSOR_SELECT = (byte) 0x7E;
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
    
    // File Transfer AIDs/Responses
    public static final byte AID_STRUCTURED_FIELD = (byte) 0x88;
    public static final byte RESP_POSITIVE = 0x09;
    public static final byte RESP_NEGATIVE = 0x08;

    // PF1–PF24 for keyboard lookup
    public static final byte[] PF_AID = new byte[] { 
        AID_PF1, AID_PF2, AID_PF3, AID_PF4, AID_PF5, AID_PF6, AID_PF7, AID_PF8, AID_PF9, 
        AID_PF10, AID_PF11, AID_PF12, AID_PF13, AID_PF14, AID_PF15, AID_PF16, AID_PF17, 
        AID_PF18, AID_PF19, AID_PF20, AID_PF21, AID_PF22, AID_PF23, AID_PF24 
    };

    // WCC bits
    public static final byte WCC_RESET = (byte) 0x40;
    public static final byte WCC_ALARM = (byte) 0x04;
    public static final byte WCC_RESET_MDT = (byte) 0x01;
    
    // File Transfer SFIDs
    public static final byte SFID_DATA_CHAIN = (byte) 0xD0;
    public static final byte SFID_INBOUND_3270DS = (byte) 0x61;
    
    // Data Chain Opcodes
    public static final byte DC_OPEN = 0x00;
    public static final byte DC_CLOSE = 0x41;
    public static final byte DC_SET_CURSOR = 0x45;
    public static final byte DC_GET = 0x46;
    public static final byte DC_INSERT = 0x47;
    
    // Structured Field IDs
    public static final byte SF_ID_SET_REPLY_MODE = (byte) 0x09;
    public static final byte SF_ID_READ_PARTITION_QUERY_LIST = (byte) 0xA1;
}
