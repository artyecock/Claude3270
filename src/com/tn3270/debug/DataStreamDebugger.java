package com.tn3270.debug;

import static com.tn3270.constants.ProtocolConstants.*;

import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Logger;

/**
 * Data Stream Debugger for TN3270 Emulator
 * 
 * Features:
 * - Maintains circular buffer of screen pages (configurable via MAX_SCREEN_PAGES)
 * - Each page = 1 Erase Write + all subsequent Write commands until next Erase Write
 * - Captures all Write/Read data streams with timestamps
 * - Supports future record/playback functionality
 * - Provides scroll-back capability
 */
public class DataStreamDebugger {
    private static final Logger logger = Logger.getLogger(DataStreamDebugger.class.getName());
    private static final int MAX_SCREEN_PAGES = 42;  // Maximum number of screen-clearing commands to keep
    
    private boolean debugMode = false;
    private final LinkedList<DataStreamEntry> dataStreamHistory;
    
    /**
     * Create a new data stream debugger.
     */
    public DataStreamDebugger() {
        this.dataStreamHistory = new LinkedList<>();
    }
    
    /**
     * Enable or disable debug mode.
     * When disabled, no data streams are captured.
     * 
     * @param enabled true to enable debug mode
     */
    public void setDebugMode(boolean enabled) {
        // Only log if the state is actually changing
        if (this.debugMode != enabled) {
            this.debugMode = enabled;
            if (enabled) {
                logger.fine("Data Stream Debugger ENABLED");
            } else {
                logger.fine("Data Stream Debugger DISABLED");
            }
        }
    }
    
    /**
     * Check if debug mode is currently enabled.
     * 
     * @return true if debug mode is enabled
     */
    public boolean isDebugMode() {
        return debugMode;
    }
    
    /**
     * Capture a data stream for debugging.
     * Call this from TN3270Session.processInbound() before processing.
     * 
     * @param data The complete data stream
     * @param offset The offset where the command byte is located
     */
    public void captureDataStream(byte[] data, int offset) {
        if (!debugMode || data == null || offset >= data.length) {
            return;
        }
        
        // Determine command type
        int cmd = data[offset] & 0xFF;
        DataStreamEntry.CommandType cmdType = classifyCommand(cmd);
        
        // Only capture relevant commands
        if (cmdType == DataStreamEntry.CommandType.OTHER) {
            return;
        }
        
        // Create entry with full data stream
        DataStreamEntry entry = new DataStreamEntry(data, cmdType, cmd);
        
        // Add to history
        synchronized (dataStreamHistory) {
            dataStreamHistory.addLast(entry);
            
            // Maintain page limit: remove oldest entries when we exceed MAX_SCREEN_PAGES screen-clearing commands
            if (entry.isScreenClearingCommand()) {
                // Count screen-clearing commands
                int screenPageCount = 0;
                for (DataStreamEntry e : dataStreamHistory) {
                    if (e.isScreenClearingCommand()) {
                        screenPageCount++;
                    }
                }
                
                // If we have too many pages, remove the oldest page (ERASE_WRITE + all subsequent WRITEs)
                while (screenPageCount > MAX_SCREEN_PAGES) {
                    // Find and remove the first (oldest) ERASE_WRITE and all entries before the next one
                    boolean foundFirst = false;
                    boolean foundSecond = false;
                    
                    Iterator<DataStreamEntry> iterator = dataStreamHistory.iterator();
                    while (iterator.hasNext() && !foundSecond) {
                        DataStreamEntry e = iterator.next();
                        
                        if (e.isScreenClearingCommand()) {
                            if (!foundFirst) {
                                foundFirst = true;
                                iterator.remove();  // Remove the first ERASE_WRITE
                                screenPageCount--;
                            } else {
                                foundSecond = true;  // Stop at the second ERASE_WRITE
                            }
                        } else if (foundFirst && !foundSecond) {
                            iterator.remove();  // Remove WRITEs between first and second ERASE_WRITE
                        }
                    }
                }
            }
        }
        
        logger.fine("Captured: " + entry.toDebugString());
    }
    
    /**
     * Classify a command byte into a CommandType.
     * 
     * @param cmd The command byte value (0-255)
     * @return The classified CommandType
     */
    private DataStreamEntry.CommandType classifyCommand(int cmd) {
        if (cmd == CMD_ERASE_WRITE_05 || cmd == CMD_ERASE_WRITE_F5) {
            return DataStreamEntry.CommandType.ERASE_WRITE;
        } else if (cmd == CMD_ERASE_WRITE_ALTERNATE_0D || cmd == CMD_ERASE_WRITE_ALTERNATE_7E) {
            return DataStreamEntry.CommandType.ERASE_WRITE_ALTERNATE;
        } else if (cmd == CMD_WRITE_01 || cmd == CMD_WRITE_F1) {
            return DataStreamEntry.CommandType.WRITE;
        } else if (cmd == CMD_WSF_11 || cmd == CMD_WSF_F3) {
            return DataStreamEntry.CommandType.WRITE_STRUCTURED_FIELD;
        } else if (cmd == CMD_READ_MODIFIED_06 || cmd == CMD_READ_MODIFIED_F6) {
            return DataStreamEntry.CommandType.READ_MODIFIED;
        } else if (cmd == CMD_READ_BUFFER_02 || cmd == CMD_READ_BUFFER_F2) {
            return DataStreamEntry.CommandType.READ_BUFFER;
        }
        return DataStreamEntry.CommandType.OTHER;
    }
    
    /**
     * Get all captured data stream entries.
     * 
     * @return List of all data stream entries (newest last)
     */
    public List<DataStreamEntry> getHistory() {
        synchronized (dataStreamHistory) {
            return new ArrayList<>(dataStreamHistory);
        }
    }
    
    /**
     * Get only screen-clearing commands (Erase Write variants).
     * These are the "pages" for scroll-back.
     * 
     * @return List of screen-clearing commands (newest last)
     */
    public List<DataStreamEntry> getScreenClearingCommands() {
        synchronized (dataStreamHistory) {
            List<DataStreamEntry> result = new ArrayList<>();
            for (DataStreamEntry entry : dataStreamHistory) {
                if (entry.isScreenClearingCommand()) {
                    result.add(entry);
                }
            }
            return result;
        }
    }
    
    /**
     * Get all Write commands after a specific Erase Write command.
     * Used to reconstruct a screen page.
     * 
     * @param eraseWrite The Erase Write command to start from
     * @return List of Write commands after the specified Erase Write
     */
    public List<DataStreamEntry> getWriteCommandsAfter(DataStreamEntry eraseWrite) {
        synchronized (dataStreamHistory) {
            List<DataStreamEntry> result = new ArrayList<>();
            boolean found = false;
            
            // Use timestamp for equality since eraseWrite may be from a different list
            LocalDateTime targetTime = eraseWrite.getTimestamp();
            
            for (DataStreamEntry entry : dataStreamHistory) {
                // Match by timestamp instead of object identity
                if (!found && entry.getTimestamp().equals(targetTime) && 
                    entry.isScreenClearingCommand()) {
                    found = true;
                    continue;
                }
                
                if (found) {
                    // Stop at next screen-clearing command
                    if (entry.isScreenClearingCommand()) {
                        break;
                    }
                    
                    // Collect Write commands (excluding WSF as requested)
                    if (entry.getCommandType() == DataStreamEntry.CommandType.WRITE) {
                        result.add(entry);
                    }
                }
            }
            
            return result;
        }
    }
    
    /**
     * Clear all captured history.
     */
    public void clearHistory() {
        synchronized (dataStreamHistory) {
            dataStreamHistory.clear();
        }
        logger.info("Data stream history cleared");
    }
    
    /**
     * Save debug session to file for playback.
     * 
     * @param file The file to save to
     * @throws IOException if save fails
     */
    public void saveSession(File file) throws IOException {
        synchronized (dataStreamHistory) {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
                oos.writeObject(new ArrayList<>(dataStreamHistory));
            }
        }
        logger.info("Debug session saved to: " + file.getAbsolutePath());
    }
    
    /**
     * Load debug session from file for playback.
     * 
     * @param file The file to load from
     * @throws IOException if load fails
     * @throws ClassNotFoundException if deserialization fails
     */
    @SuppressWarnings("unchecked")
    public void loadSession(File file) throws IOException, ClassNotFoundException {
        synchronized (dataStreamHistory) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                List<DataStreamEntry> loaded = (List<DataStreamEntry>) ois.readObject();
                dataStreamHistory.clear();
                dataStreamHistory.addAll(loaded);
            }
        }
        logger.info("Debug session loaded from: " + file.getAbsolutePath());
    }
    
    /**
     * Export history to human-readable text file.
     * 
     * @param file The file to export to
     * @throws IOException if export fails
     */
    public void exportToText(File file) throws IOException {
        synchronized (dataStreamHistory) {
            try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                writer.println("TN3270 Data Stream Debug Log");
                writer.println("Generated: " + new Date());
                writer.println("Total Entries: " + dataStreamHistory.size());
                writer.println("=".repeat(80));
                writer.println();
                
                for (int i = 0; i < dataStreamHistory.size(); i++) {
                    DataStreamEntry entry = dataStreamHistory.get(i);
                    writer.println(String.format("[%d] %s", i + 1, entry.toDebugString()));
                    
                    // Include hex dump of first 64 bytes
                    byte[] data = entry.getDataStream();
                    writer.println("  Hex dump (first 64 bytes):");
                    int dumpLength = Math.min(64, data.length);
                    for (int j = 0; j < dumpLength; j += 16) {
                        writer.print("    ");
                        for (int k = j; k < j + 16 && k < dumpLength; k++) {
                            writer.print(String.format("%02X ", data[k]));
                        }
                        writer.println();
                    }
                    writer.println();
                }
            }
        }
        logger.info("Debug log exported to: " + file.getAbsolutePath());
    }
    
    /**
     * Format all captured data streams as annotated text suitable for
     * copying to clipboard and pasting into a conversation or text editor.
     *
     * Each entry includes:
     *   - 1-based sequence number, timestamp, command name, total byte count
     *   - Complete hex dump (no truncation), 16 bytes per line with
     *     an ASCII sidebar showing printable characters
     *   - Inline annotations for recognised 3270 orders and commands
     *
     * @return The formatted text block, or a single-line message if history is empty
     */
    public String formatForClipboard() {
        synchronized (dataStreamHistory) {
            if (dataStreamHistory.isEmpty()) {
                return "(no data streams captured)";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== TN3270 Data Streams ===\n");
            sb.append("Captured: ").append(new Date()).append('\n');
            sb.append("Entries:  ").append(dataStreamHistory.size()).append('\n');
            sb.append("=".repeat(72)).append('\n');

            for (int i = 0; i < dataStreamHistory.size(); i++) {
                DataStreamEntry entry = dataStreamHistory.get(i);
                byte[] data = entry.getDataStream();

                // --- header line ---
                sb.append('\n');
                sb.append(String.format("[%d] %s  %s  (%d bytes)\n",
                    i + 1,
                    entry.getFormattedTimestamp(),
                    entry.getCommandType(),
                    data.length));
                sb.append("-".repeat(72)).append('\n');

                // --- annotated hex dump ---
                for (int offset = 0; offset < data.length; offset += 16) {
                    int lineEnd = Math.min(offset + 16, data.length);

                    // offset label
                    sb.append(String.format("%04X  ", offset));

                    // hex bytes
                    StringBuilder hexPart  = new StringBuilder();
                    StringBuilder ascPart  = new StringBuilder();
                    StringBuilder annot    = new StringBuilder();

                    for (int b = offset; b < lineEnd; b++) {
                        int val = data[b] & 0xFF;
                        hexPart.append(String.format("%02X ", val));

                        // ASCII sidebar: printable range only
                        ascPart.append((val >= 0x20 && val <= 0x7E) ? (char) val : '.');

                        // Inline annotation — only on the first byte of a recognised token
                        String tag = annotate(data, b);
                        if (tag != null) {
                            if (annot.length() > 0) annot.append(", ");
                            annot.append(String.format("%04X:%s", b, tag));
                        }
                    }

                    // pad hex field to fixed width so ASCII column lines up
                    sb.append(String.format("%-48s", hexPart));
                    sb.append(" |").append(ascPart).append("|\n");

                    // annotation line (only printed when there is something to say)
                    if (annot.length() > 0) {
                        sb.append("      ").append(annot).append('\n');
                    }
                }
            }

            sb.append('\n').append("=".repeat(72)).append('\n');
            return sb.toString();
        }
    }

    /**
     * Return a short label if the byte at {@code pos} is the start of a
     * recognised 3270 command or order, or null if it isn't.
     * Only called once per byte; the caller steps through the stream linearly,
     * so we do not try to skip ahead past multi-byte tokens — we simply
     * label the first byte of each one we recognise.
     */
    private String annotate(byte[] data, int pos) {
        int val = data[pos] & 0xFF;

        // --- commands (only meaningful at offset 0 of the stream) ---
        if (pos == 0) {
            switch (val) {
                case 0x01: case 0xF1: return "Write";
                case 0x05: case 0xF5: return "Erase/Write";
                case 0x0D: case 0x7E: return "Erase/Write Alternate";
                case 0x11: case 0xF3: return "Write Structured Field";
                case 0x02: case 0xF2: return "Read Buffer";
                case 0x06: case 0xF6: return "Read Modified";
                case 0x0E: case 0x6E: return "Read Modified All";
                case 0x0F: case 0x6F: return "Erase All Unprotected";
            }
        }

        // --- orders (valid anywhere after the command byte) ---
        if (pos > 0) {
            switch (val) {
                case 0x1D: return "SF (Start Field)";
                case 0x29: return "SFE (Start Field Extended)";
                case 0x28: return "SA (Set Attribute)";
                case 0x11: return "SBA (Set Buffer Address)";
                case 0x13: return "IC (Insert Cursor)";
                case 0x3C: return "RA (Repeat to Address)";
                case 0x2C: return "MF (Modify Field)";
                case 0x08: return "GE (Graphic Escape)";
                case 0x12: return "EUA (Erase Unprotected to Address)";
                case 0x05: return "PT (Program Tab)";
            }
        }

        return null;   // nothing to say about this byte
    }
}