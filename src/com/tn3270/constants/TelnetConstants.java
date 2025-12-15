package com.tn3270.constants;

public class TelnetConstants {
    // Telnet commands
    public static final byte IAC = (byte) 0xFF;
    public static final byte DO = (byte) 0xFD;
    public static final byte DONT = (byte) 0xFE;
    public static final byte WILL = (byte) 0xFB;
    public static final byte WONT = (byte) 0xFC;
    public static final byte SB = (byte) 0xFA;
    public static final byte SE = (byte) 0xF0;

    // Telnet options
    public static final byte OPT_BINARY = (byte) 0x00;
    public static final byte OPT_TERMINAL_TYPE = (byte) 0x18;
    public static final byte OPT_EOR = (byte) 0x19;
    public static final byte OPT_TN3270E = (byte) 0x28;

    // TN3270E header types
    public static final byte TN3270E_DT_3270_DATA = 0x00;
    public static final byte TN3270E_DT_SCS_DATA = 0x01;
    public static final byte TN3270E_DT_RESPONSE = 0x02;
    public static final byte TN3270E_DT_BIND_IMAGE = 0x03;
    public static final byte TN3270E_DT_UNBIND = 0x04;

    // TN3270E Opcodes (RFC 2355)
    public static final byte TN3270E_OP_ASSOCIATE = 0x00;
    public static final byte TN3270E_OP_CONNECT = 0x01;
    public static final byte TN3270E_OP_DEVICE_TYPE = 0x02;
    public static final byte TN3270E_OP_FUNCTIONS = 0x03;
    public static final byte TN3270E_OP_IS = 0x04;
    public static final byte TN3270E_OP_REASON = 0x05;
    public static final byte TN3270E_OP_REJECT = 0x06;
    public static final byte TN3270E_OP_REQUEST = 0x07;
    public static final byte TN3270E_OP_SEND = 0x08;
}
