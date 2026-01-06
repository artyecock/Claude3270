package com.tn3270.util;

import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Simple logging utility for Claude3270.
 * Uses java.util.logging (built-in, no dependencies).
 * 
 * Usage:
 *   private static final Logger logger = LoggerSetup.getLogger(MyClass.class);
 *   logger.info("Connection established");
 *   logger.warning("Timeout occurred");
 *   logger.severe("Fatal error: " + e.getMessage());
 */
public class LoggerSetup {
    
    private static boolean initialized = false;
    
    /**
     * Get a logger for the specified class.
     * Automatically initializes logging on first use.
     */
    public static Logger getLogger(Class<?> clazz) {
        if (!initialized) {
            initializeLogging();
        }
        return Logger.getLogger(clazz.getName());
    }
    
    /**
     * Initialize logging with a simple, readable format.
     */
    private static void initializeLogging() {
        Logger rootLogger = Logger.getLogger("");
        
        // Remove default handlers
        for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
            rootLogger.removeHandler(handler);
        }
        
        // Add our custom handler with simple formatting
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.ALL);
        rootLogger.addHandler(handler);
        
        // Set default level (can be changed per-class if needed)
        rootLogger.setLevel(Level.INFO);
        
        initialized = true;
    }
    
    /**
     * Simple, readable log format:
     * [INFO] 14:23:45 TN3270Session: Connection established
     */
    private static class SimpleFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return String.format("[%s] %tT %s: %s%n",
                record.getLevel(),
                record.getMillis(),
                getSimpleClassName(record.getSourceClassName()),
                record.getMessage()
            );
        }
        
        private String getSimpleClassName(String fullClassName) {
            if (fullClassName == null) return "Unknown";
            int lastDot = fullClassName.lastIndexOf('.');
            return lastDot >= 0 ? fullClassName.substring(lastDot + 1) : fullClassName;
        }
    }
    
    /**
     * Enable debug logging (call during development/testing)
     */
    public static void enableDebugLogging() {
        Logger.getLogger("").setLevel(Level.FINE);
    }
    
    /**
     * Disable most logging (only show warnings and errors)
     */
    public static void setQuietMode() {
        Logger.getLogger("").setLevel(Level.WARNING);
    }
}