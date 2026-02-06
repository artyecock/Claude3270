package com.tn3270.debug;

import com.tn3270.model.ScreenModel;
import com.tn3270.TN3270Session;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages scroll-back functionality for reviewing historical screens.
 * 
 * This class handles:
 * - Entering and exiting scroll-back mode
 * - Scrolling through historical screens
 * - Reconstructing screens from data streams
 * - Managing UI state (border colors, status bar)
 */
public class ScrollBackManager {
    private static final Logger logger = Logger.getLogger(ScrollBackManager.class.getName());
    
    private final TN3270Session session;
    private final DataStreamDebugger debugger;
    
    private boolean scrollMode = false;
    private int scrollPosition = -1; // -1 = live screen, 0+ = historical page index
    private List<DataStreamEntry> screenPages = new ArrayList<>();
    private ScreenSnapshot liveScreenSnapshot = null;
    
    /**
     * Create a new scroll-back manager.
     * 
     * @param session The TN3270Session instance
     * @param debugger The DataStreamDebugger instance
     */
    public ScrollBackManager(TN3270Session session, DataStreamDebugger debugger) {
        this.session = session;
        this.debugger = debugger;
    }
    
    /**
     * Check if currently in scroll-back mode.
     * 
     * @return true if in scroll-back mode
     */
    public boolean isScrollMode() {
        return scrollMode;
    }
    
    /**
     * Get current scroll position.
     * 
     * @return -1 for live screen, 0+ for historical page index
     */
    public int getScrollPosition() {
        return scrollPosition;
    }
    
    /**
     * Get the total number of available historical pages.
     * 
     * @return Number of screen pages available for scroll-back
     */
    public int getAvailablePages() {
        return screenPages.size();
    }
    
    /**
     * Scroll backward one page.
     * Creates a snapshot of the current screen before entering scroll mode.
     */
    public void scrollBack() {
        if (!debugger.isDebugMode()) {
            logger.warning("Cannot scroll back: debug mode not enabled");
            return;
        }
        
        // Get available screen-clearing commands
        screenPages = debugger.getScreenClearingCommands();
        
        if (screenPages.isEmpty()) {
            logger.info("No historical screens available");
            return;
        }
        
        // Cache the size to ensure consistency throughout this method
        final int totalPages = screenPages.size();
        
        // Enter scroll mode if not already
        if (!scrollMode) {
            enterScrollMode();
        }
        
        // Move back one page (clamp to valid range)
        scrollPosition = Math.min(scrollPosition + 1, totalPages - 1);
        
        // Additional safety: ensure scrollPosition is never negative or out of bounds
        // (guards against race conditions or double-invocation)
        if (scrollPosition < 0) scrollPosition = 0;
        if (scrollPosition >= totalPages) scrollPosition = totalPages - 1;
        
        // Update status bar with 1-based page number BEFORE reconstructing
        // (ensures the display happens with stable values before any repaints)
        session.getStatusBar().setScrollMode(true, scrollPosition + 1, totalPages);
        
        // Reconstruct and display the historical screen
        reconstructScreen(scrollPosition);
        
        logger.fine("Scrolled back to page: " + (scrollPosition + 1) + " of " + totalPages);
    }
    
    /**
     * Scroll forward one page.
     * Returns to live screen when scrolling past the newest historical page.
     */
    public void scrollForward() {
        if (!scrollMode) {
            return;
        }
        
        // Cache the size for consistency
        final int totalPages = screenPages.size();
        
        scrollPosition--;
        
        if (scrollPosition < 0) {
            // Return to live screen
            exitScrollMode();
        } else {
            // Update status bar with 1-based page number BEFORE reconstructing
            session.getStatusBar().setScrollMode(true, scrollPosition + 1, totalPages);
            
            // Reconstruct and display the historical screen
            reconstructScreen(scrollPosition);
            
            logger.fine("Scrolled forward to page: " + (scrollPosition + 1) + " of " + totalPages);
        }
    }
    
    /**
     * Enter scroll-back mode.
     * Saves the current live screen and updates UI.
     */
    private void enterScrollMode() {
        scrollMode = true;
        scrollPosition = -1;
        
        // Save current live screen
        liveScreenSnapshot = new ScreenSnapshot(session.getScreenModel());
        
        // Update UI
        session.setScrollMode(true);
        session.getTerminalPanel().setBorderColor(java.awt.Color.RED);
        session.getTerminalPanel().setShowBorder(true);  // Ensure border is visible
        // Note: StatusBar page info is set by scrollBack() / scrollForward()
        // after reconstructScreen(), once scrollPosition is final.
        
        logger.info("Entered scroll-back mode");
    }
    
    /**
     * Exit scroll-back mode and return to live screen.
     * Restores the saved live screen and updates UI.
     */
    private void exitScrollMode() {
        scrollMode = false;
        scrollPosition = -1;
        
        // Restore live screen
        if (liveScreenSnapshot != null) {
            liveScreenSnapshot.restoreToModel(session.getScreenModel());
        }
        
        // Update UI
        session.setScrollMode(false);
        session.getTerminalPanel().setBorderColor(java.awt.Color.GREEN);
        session.getStatusBar().setScrollMode(false, 0, 0);
        session.getTerminalPanel().repaint();
        session.updateStatusBar();
        
        logger.info("Exited scroll-back mode - returned to live screen");
    }
    
    /**
     * Reconstruct a historical screen by replaying data streams.
     * 
     * @param pageIndex The page index to reconstruct (0 = oldest, size-1 = newest)
     */
    private void reconstructScreen(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= screenPages.size()) {
            return;
        }
        
        // Get the Erase Write command (reverse order - newest first for user)
        DataStreamEntry eraseWriteCommand = screenPages.get(
            screenPages.size() - 1 - pageIndex);
        
        // Replay the Erase Write command first
        session.replayDataStream(eraseWriteCommand.getDataStream());
        
        // Then replay all subsequent Write commands (excluding WSF)
        List<DataStreamEntry> writes = debugger.getWriteCommandsAfter(eraseWriteCommand);
        for (DataStreamEntry write : writes) {
            session.replayDataStream(write.getDataStream());
        }
        
        // Update display
        session.getTerminalPanel().repaint();
        // Note: updateStatusBar() is NOT called here because the caller
        // (scrollBack/scrollForward) updates the status bar with page info
        
        logger.fine("Reconstructed screen page " + (pageIndex + 1) + " of " + screenPages.size());
    }
    
    /**
     * Force exit from scroll mode.
     * Used when connection closes or other events require immediate return to normal mode.
     */
    public void forceExit() {
        if (scrollMode) {
            exitScrollMode();
        }
    }
}