package com.tn3270.constants;

public class TelnetConstants {
    // -----------------------------------------------------------------------
    // Telnet commands
    // Changed to int to prevent signed byte comparison errors (0xFF vs -1)
    // -----------------------------------------------------------------------
    public static final int IAC = 0xFF;
    public static final int DO = 0xFD;
    public static final int DONT = 0xFE;
    public static final int WILL = 0xFB;
    public static final int WONT = 0xFC;
    public static final int SB = 0xFA;
    public static final int SE = 0xF0;
    public static final int EOR = 0xEF;

    // -----------------------------------------------------------------------
    // Telnet options
    // -----------------------------------------------------------------------
    public static final int OPT_BINARY = 0x00;
    public static final int OPT_TERMINAL_TYPE = 0x18;
    public static final int OPT_EOR = 0x19;
    public static final int OPT_TN3270E = 0x28;

    // -----------------------------------------------------------------------
    // TN3270E header types
    // -----------------------------------------------------------------------
    public static final int TN3270E_DT_3270_DATA = 0x00;
    public static final int TN3270E_DT_SCS_DATA = 0x01;
    public static final int TN3270E_DT_RESPONSE = 0x02;
    public static final int TN3270E_DT_BIND_IMAGE = 0x03;
    public static final int TN3270E_DT_UNBIND = 0x04;

    // -----------------------------------------------------------------------
    // TN3270E Opcodes (RFC 2355)
    // -----------------------------------------------------------------------
    public static final int TN3270E_OP_ASSOCIATE = 0x00;
    public static final int TN3270E_OP_CONNECT = 0x01;
    public static final int TN3270E_OP_DEVICE_TYPE = 0x02;
    public static final int TN3270E_OP_FUNCTIONS = 0x03;
    public static final int TN3270E_OP_IS = 0x04;
    public static final int TN3270E_OP_REASON = 0x05;
    public static final int TN3270E_OP_REJECT = 0x06;
    public static final int TN3270E_OP_REQUEST = 0x07;
    public static final int TN3270E_OP_SEND = 0x08;
}