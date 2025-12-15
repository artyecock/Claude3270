package terminal;

import terminal.ScreenBuffer;

import java.awt.Toolkit;

/**
 * Manages cursor position and field navigation.
 * Handles cursor movement, tab navigation, and field-related operations.
 */
public class CursorManager {
    private final ScreenBuffer screenBuffer;
    private int cursorPos = 0;
    
    public CursorManager(ScreenBuffer screenBuffer) {
        this.screenBuffer = screenBuffer;
    }
    
    /**
     * Get current cursor position.
     */
    public int getCursorPos() {
    	System.out.println("CursorManager reporting cursor at " + cursorPos);
        return cursorPos;
    }
    
    /**
     * Set cursor position.
     * @param pos New cursor position
     */
    public void setCursorPos(int pos) {
        int bufferSize = screenBuffer.getBufferSize();
        this.cursorPos = ((pos % bufferSize) + bufferSize) % bufferSize;
        System.out.println("CursorManager setting cursor to " + pos + " (" + cursorPos + ")");
    }
    
    /**
     * Move cursor by a relative amount.
     * @param delta Amount to move (positive or negative)
     */
    public void moveCursor(int delta) {
        int bufferSize = screenBuffer.getBufferSize();
        cursorPos = ((cursorPos + delta) % bufferSize + bufferSize) % bufferSize;
    }
    
    /**
     * Move cursor to next unprotected field.
     * Searches forward for the first unprotected field attribute.
     */
    public void tabToNextField() {
        int start = cursorPos;
        int count = 0;
        int bufferSize = screenBuffer.getBufferSize();
        
        do {
            cursorPos = (cursorPos + 1) % bufferSize;
            count++;
            
            if (screenBuffer.isFieldStart(cursorPos)) {
                // We're on a field attribute, check if the field itself is protected
                boolean fieldIsProtected = screenBuffer.isProtected(cursorPos);
                
                if (!fieldIsProtected) {
                    // Move to first position after the field attribute
                    cursorPos = (cursorPos + 1) % bufferSize;
                    return;
                }
            }
        } while (cursorPos != start && count < bufferSize);
        
        // No unprotected field found, stay where we are
        cursorPos = start;
    }
    
    /**
     * Move cursor to previous unprotected field.
     * Searches backward for the first unprotected field attribute.
     */
    public void tabToPreviousField() {
        int start = cursorPos;
        int count = 0;
        int bufferSize = screenBuffer.getBufferSize();
        
        do {
            cursorPos = (cursorPos - 1 + bufferSize) % bufferSize;
            count++;
            
            if (screenBuffer.isFieldStart(cursorPos)) {
                // We're on a field attribute, check if the field itself is protected
                boolean fieldIsProtected = screenBuffer.isProtected(cursorPos);
                
                if (!fieldIsProtected) {
                    // Move to first position after the field attribute
                    cursorPos = (cursorPos + 1) % bufferSize;
                    return;
                }
            }
        } while (cursorPos != start && count < bufferSize);
        
        // No unprotected field found, stay where we are
        cursorPos = start;
    }
    
    /**
     * Erase from cursor to end of field.
     * Only works if cursor is in an unprotected field.
     * @return True if operation succeeded
     */
    public boolean eraseToEndOfField() {
        if (screenBuffer.isProtected(cursorPos)) {
            Toolkit.getDefaultToolkit().beep();
            return false;
        }
        
        int fieldStart = screenBuffer.findFieldStart(cursorPos);
        int fieldEnd = screenBuffer.findNextField(fieldStart);
        
        for (int i = cursorPos; i < fieldEnd && !screenBuffer.isFieldStart(i); i++) {
            screenBuffer.setChar(i, '\0');
        }
        
        screenBuffer.setModified(cursorPos);
        return true;
    }
    
    /**
     * Erase from cursor to end of line.
     * Only erases within the current field boundaries.
     * @return True if operation succeeded
     */
    public boolean eraseToEndOfLine() {
        if (screenBuffer.isProtected(cursorPos)) {
            Toolkit.getDefaultToolkit().beep();
            return false;
        }
        
        int cols = screenBuffer.getCols();
        int row = cursorPos / cols;
        int endOfLine = (row + 1) * cols;
        int fieldStart = screenBuffer.findFieldStart(cursorPos);
        int fieldEnd = screenBuffer.findNextField(fieldStart);
        
        for (int i = cursorPos; i < endOfLine && i < fieldEnd && !screenBuffer.isFieldStart(i); i++) {
            screenBuffer.setChar(i, '\0');
        }
        
        screenBuffer.setModified(cursorPos);
        return true;
    }
    
    /**
     * Move cursor up one row.
     */
    public void moveCursorUp() {
        int cols = screenBuffer.getCols();
        int bufferSize = screenBuffer.getBufferSize();
        cursorPos = (cursorPos - cols + bufferSize) % bufferSize;
    }
    
    /**
     * Move cursor down one row.
     */
    public void moveCursorDown() {
        int cols = screenBuffer.getCols();
        int bufferSize = screenBuffer.getBufferSize();
        cursorPos = (cursorPos + cols) % bufferSize;
    }
    
    /**
     * Move cursor left one position.
     */
    public void moveCursorLeft() {
        int bufferSize = screenBuffer.getBufferSize();
        cursorPos = (cursorPos - 1 + bufferSize) % bufferSize;
    }
    
    /**
     * Move cursor right one position.
     */
    public void moveCursorRight() {
        int bufferSize = screenBuffer.getBufferSize();
        cursorPos = (cursorPos + 1) % bufferSize;
    }
    
    /**
     * Move cursor to home position (0,0).
     */
    public void moveCursorHome() {
        cursorPos = 0;
    }
    
    /**
     * Check if auto-advance should occur after character entry.
     * Auto-advances when the next position is a field boundary.
     * @return True if should auto-advance to next field
     */
    public boolean shouldAutoAdvance() {
        int bufferSize = screenBuffer.getBufferSize();
        int nextPos = (cursorPos + 1) % bufferSize;
        return screenBuffer.isFieldStart(nextPos);
    }
}
