package com.tn3270.debug;

import com.tn3270.model.ScreenModel;
import java.io.Serializable;
import java.util.Arrays;

/**
 * Immutable snapshot of the complete screen state.
 * Used for scroll-back functionality to save and restore screen contents.
 */
public class ScreenSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final char[] buffer;
    private final byte[] attributes;
    private final byte[] extendedColors;
    private final byte[] highlighting;
    private final byte[] charsets;
    private final int cursorPos;
    private final int rows;
    private final int cols;
    private final boolean alternateSize;
    
    /**
     * Create a snapshot of the current screen model state.
     * 
     * @param model The screen model to snapshot
     */
    public ScreenSnapshot(ScreenModel model) {
        this.rows = model.getRows();
        this.cols = model.getCols();
        this.alternateSize = model.isAlternateSize();
        this.cursorPos = model.getCursorPos();
        
        // Deep copy all arrays
        int size = model.getSize();
        this.buffer = Arrays.copyOf(model.getBuffer(), size);
        this.attributes = Arrays.copyOf(model.getAttributes(), size);
        this.extendedColors = Arrays.copyOf(model.getExtendedColors(), size);
        this.highlighting = Arrays.copyOf(model.getHighlight(), size);
        this.charsets = Arrays.copyOf(model.getCharsets(), size);
    }
    
    /**
     * Restore this snapshot to the provided ScreenModel.
     * Does NOT clear the screen - applies snapshot data to current buffer.
     * 
     * @param model The screen model to restore to
     * @throws IllegalStateException if target model is too small
     */
    public void restoreToModel(ScreenModel model) {
        // Verify size compatibility
        if (model.getSize() < buffer.length) {
            throw new IllegalStateException("Target screen model is too small for snapshot");
        }
        
        // Copy all arrays back
        System.arraycopy(buffer, 0, model.getBuffer(), 0, buffer.length);
        System.arraycopy(attributes, 0, model.getAttributes(), 0, attributes.length);
        System.arraycopy(extendedColors, 0, model.getExtendedColors(), 0, extendedColors.length);
        System.arraycopy(highlighting, 0, model.getHighlight(), 0, highlighting.length);
        System.arraycopy(charsets, 0, model.getCharsets(), 0, charsets.length);
        
        model.setCursorPos(cursorPos);
    }
    
    /**
     * Get the number of rows in this snapshot.
     * 
     * @return Row count
     */
    public int getRows() {
        return rows;
    }
    
    /**
     * Get the number of columns in this snapshot.
     * 
     * @return Column count
     */
    public int getCols() {
        return cols;
    }
    
    /**
     * Get the cursor position in this snapshot.
     * 
     * @return Cursor position (0-based linear index)
     */
    public int getCursorPos() {
        return cursorPos;
    }
    
    /**
     * Check if this snapshot is using alternate screen size.
     * 
     * @return true if alternate size was active
     */
    public boolean isAlternateSize() {
        return alternateSize;
    }
}
