package com.tn3270.debug;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * Represents a captured 3270 data stream with timestamp and metadata.
 * Supports future record/playback functionality through Serializable.
 */
public class DataStreamEntry implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public enum CommandType {
        ERASE_WRITE,           // 0x05, 0xF5
        ERASE_WRITE_ALTERNATE, // 0x0D, 0x7E
        WRITE,                 // 0x01, 0xF1
        WRITE_STRUCTURED_FIELD,// 0x11, 0xF3
        READ_MODIFIED,         // 0x06, 0xF6
        READ_BUFFER,           // 0x02, 0xF2
        OTHER
    }
    
    private final byte[] dataStream;
    private final LocalDateTime timestamp;
    private final CommandType commandType;
    private final int commandByte;
    private final boolean isScreenClearingCommand;
    
    /**
     * Create a new data stream entry.
     * 
     * @param dataStream The complete data stream bytes
     * @param commandType The classified command type
     * @param commandByte The actual command byte value
     */
    public DataStreamEntry(byte[] dataStream, CommandType commandType, int commandByte) {
        // Deep copy to prevent external modification
        this.dataStream = Arrays.copyOf(dataStream, dataStream.length);
        this.timestamp = LocalDateTime.now();
        this.commandType = commandType;
        this.commandByte = commandByte;
        this.isScreenClearingCommand = (commandType == CommandType.ERASE_WRITE || 
                                         commandType == CommandType.ERASE_WRITE_ALTERNATE);
    }
    
    /**
     * Get a copy of the data stream bytes.
     * 
     * @return Copy of the data stream
     */
    public byte[] getDataStream() {
        return Arrays.copyOf(dataStream, dataStream.length);
    }
    
    /**
     * Get the timestamp when this data stream was captured.
     * 
     * @return LocalDateTime of capture
     */
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
    
    /**
     * Get the classified command type.
     * 
     * @return CommandType enum value
     */
    public CommandType getCommandType() {
        return commandType;
    }
    
    /**
     * Get the raw command byte value.
     * 
     * @return Command byte (0-255)
     */
    public int getCommandByte() {
        return commandByte;
    }
    
    /**
     * Check if this command clears the screen.
     * 
     * @return true if ERASE_WRITE or ERASE_WRITE_ALTERNATE
     */
    public boolean isScreenClearingCommand() {
        return isScreenClearingCommand;
    }
    
    /**
     * Get formatted timestamp string.
     * 
     * @return Formatted timestamp (yyyy-MM-dd HH:mm:ss.SSS)
     */
    public String getFormattedTimestamp() {
        return timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
    }
    
    /**
     * Get debug string representation.
     * 
     * @return Debug string with timestamp, type, and size
     */
    public String toDebugString() {
        return String.format("[%s] %s (0x%02X) - %d bytes",
            getFormattedTimestamp(),
            commandType,
            commandByte,
            dataStream.length);
    }
    
    @Override
    public String toString() {
        return toDebugString();
    }
}
