package terminal;

import config.Constants;
import config.KeyMapping;
import callbacks.InputCallback;

import java.awt.Toolkit;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.util.Map;

/**
 * Handles keyboard input and translates it to 3270 operations.
 * Processes key presses, character input, and clipboard operations.
 */
public class InputHandler implements KeyListener {
    private final ScreenBuffer screenBuffer;
    private final CursorManager cursorManager;
    private final Map<Integer, KeyMapping> keyMap;
    private final InputCallback callback;
    
    private boolean insertMode = false;
    
    // Selection state
    private int selectionStart = -1;
    private int selectionEnd = -1;
    
    public InputHandler(ScreenBuffer screenBuffer, 
                       CursorManager cursorManager,
                       Map<Integer, KeyMapping> keyMap,
                       InputCallback callback) {
        this.screenBuffer = screenBuffer;
        this.cursorManager = cursorManager;
        this.keyMap = keyMap;
        this.callback = callback;
    }
    
    @Override
    public void keyPressed(KeyEvent e) {
        // Handle copy/paste shortcuts
        if (e.isControlDown()) {
            if (e.getKeyCode() == KeyEvent.VK_C) {
                copySelection();
                return;
            } else if (e.getKeyCode() == KeyEvent.VK_V) {
                pasteFromClipboard();
                return;
            } else if (e.getKeyCode() == KeyEvent.VK_A) {
                selectAll();
                return;
            }
        }
        
        // Clear selection on any key press (except copy/paste)
        if (selectionStart >= 0 || selectionEnd >= 0) {
            clearSelection();
        }
        
        int keyCode = e.getKeyCode();
        
        // Handle navigation keys BEFORE the keyboard lock check
        switch (keyCode) {
            case KeyEvent.VK_LEFT:
                cursorManager.moveCursorLeft();
                callback.onRepaintRequested();
                callback.onStatusUpdate();
                return;
                
            case KeyEvent.VK_RIGHT:
                cursorManager.moveCursorRight();
                callback.onRepaintRequested();
                callback.onStatusUpdate();
                return;
                
            case KeyEvent.VK_UP:
                cursorManager.moveCursorUp();
                callback.onRepaintRequested();
                callback.onStatusUpdate();
                return;
                
            case KeyEvent.VK_DOWN:
                cursorManager.moveCursorDown();
                callback.onRepaintRequested();
                callback.onStatusUpdate();
                return;
                
            case KeyEvent.VK_HOME:
                cursorManager.moveCursorHome();
                callback.onRepaintRequested();
                callback.onStatusUpdate();
                return;
        }
        
        if (callback.isKeyboardLocked() || !callback.isConnected()) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        
        // Check for custom key mapping first
        KeyMapping mapping = keyMap.get(keyCode);
        if (mapping != null && mapping.isAidMapping()) {
            // Mapped to AID function
            callback.onAIDKey(mapping.getAid());
            return;
        }
        
        // Handle function keys with explicit mapping
        if (keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12) {
            byte aid = getFunctionKeyAID(keyCode);
            callback.onAIDKey(aid);
            return;
        }
        
        switch (keyCode) {
            case KeyEvent.VK_ESCAPE:
                callback.onClearScreen();
                callback.onAIDKey(Constants.AID_CLEAR);
                callback.onRepaintRequested();
                return;
                
            case KeyEvent.VK_ENTER:
                callback.onAIDKey(Constants.AID_ENTER);
                return;
                
            case KeyEvent.VK_INSERT:
                insertMode = !insertMode;
                callback.onInsertModeChanged(insertMode);
                callback.onStatusUpdate();
                callback.onRepaintRequested();
                return;
                
            case KeyEvent.VK_TAB:
                if (e.isShiftDown()) {
                    cursorManager.tabToPreviousField();
                } else {
                    cursorManager.tabToNextField();
                }
                callback.onRepaintRequested();
                callback.onStatusUpdate();
                return;
                
            case KeyEvent.VK_BACK_SPACE:
                handleBackspace();
                return;
        }
    }
    
    @Override
    public void keyTyped(KeyEvent e) {
        if (callback.isKeyboardLocked() || !callback.isConnected()) {
            return;
        }
        
        char c = e.getKeyChar();
        
        // Check for custom character mapping
        KeyMapping mapping = keyMap.get(e.getKeyCode());
        if (mapping != null && mapping.isCharMapping()) {
            c = mapping.getCharacter();
        }
        
        if (c < 32 || c > 126) {
            return;
        }
        
        int cursorPos = cursorManager.getCursorPos();
        
        if (!screenBuffer.isProtected(cursorPos)) {
            if (insertMode) {
                handleInsertModeCharacter(c, cursorPos);
            } else {
                handleReplaceModeCharacter(c, cursorPos);
            }
            
            callback.onRepaintRequested();
        } else {
            Toolkit.getDefaultToolkit().beep();
        }
    }
    
    @Override
    public void keyReleased(KeyEvent e) {
        // Not used
    }
    
    /**
     * Handle character entry in insert mode.
     */
    private void handleInsertModeCharacter(char c, int cursorPos) {
        int fieldStart = screenBuffer.findFieldStart(cursorPos);
        int fieldEnd = screenBuffer.findNextField(fieldStart);
        
        int lastPos = fieldEnd - 1;
        if (screenBuffer.isFieldStart(lastPos)) {
            lastPos--;
        }
        
        char lastChar = screenBuffer.getChar(lastPos);
        if (lastChar != '\0' && lastChar != ' ') {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        
        // Shift characters right
        for (int i = lastPos; i > cursorPos; i--) {
            if (!screenBuffer.isFieldStart(i) && !screenBuffer.isFieldStart(i - 1)) {
                screenBuffer.setChar(i, screenBuffer.getChar(i - 1));
            }
        }
        
        // Insert new character
        screenBuffer.setChar(cursorPos, c);
        screenBuffer.setModified(cursorPos);
        
        // Move cursor and check auto-advance
        cursorManager.moveCursor(1);
        if (cursorManager.shouldAutoAdvance()) {
            cursorManager.tabToNextField();
        }
    }
    
    /**
     * Handle character entry in replace mode.
     */
    private void handleReplaceModeCharacter(char c, int cursorPos) {
        screenBuffer.setChar(cursorPos, c);
        screenBuffer.setModified(cursorPos);
        
        // Move cursor and check auto-advance
        cursorManager.moveCursor(1);
        if (cursorManager.shouldAutoAdvance()) {
            cursorManager.tabToNextField();
        }
    }
    
    /**
     * Handle backspace key.
     */
    private void handleBackspace() {
        int cursorPos = cursorManager.getCursorPos();
        
        if (!screenBuffer.isProtected(cursorPos)) {
            cursorManager.moveCursor(-1);
            cursorPos = cursorManager.getCursorPos();
            
            if (!screenBuffer.isProtected(cursorPos)) {
                screenBuffer.setChar(cursorPos, ' ');
                screenBuffer.setModified(cursorPos);
                callback.onRepaintRequested();
            }
        }
    }
    
    /**
     * Get AID code for function key.
     */
    private byte getFunctionKeyAID(int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_F1: return Constants.AID_PF1;
            case KeyEvent.VK_F2: return Constants.AID_PF2;
            case KeyEvent.VK_F3: return Constants.AID_PF3;
            case KeyEvent.VK_F4: return Constants.AID_PF4;
            case KeyEvent.VK_F5: return Constants.AID_PF5;
            case KeyEvent.VK_F6: return Constants.AID_PF6;
            case KeyEvent.VK_F7: return Constants.AID_PF7;
            case KeyEvent.VK_F8: return Constants.AID_PF8;
            case KeyEvent.VK_F9: return Constants.AID_PF9;
            case KeyEvent.VK_F10: return Constants.AID_PF10;
            case KeyEvent.VK_F11: return Constants.AID_PF11;
            case KeyEvent.VK_F12: return Constants.AID_PF12;
            default: return Constants.AID_ENTER;
        }
    }
    
    /**
     * Copy selected text to clipboard.
     */
    public void copySelection() {
        if (selectionStart < 0 || selectionEnd < 0) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        
        int start = Math.min(selectionStart, selectionEnd);
        int end = Math.max(selectionStart, selectionEnd);
        int cols = screenBuffer.getCols();
        
        StringBuilder sb = new StringBuilder();
        int startRow = start / cols;
        int endRow = end / cols;
        
        for (int row = startRow; row <= endRow; row++) {
            int rowStart = (row == startRow) ? start : row * cols;
            int rowEnd = (row == endRow) ? end : (row + 1) * cols - 1;
            
            for (int pos = rowStart; pos <= rowEnd && pos < screenBuffer.getBufferSize(); pos++) {
                char c = screenBuffer.getChar(pos);
                if (c == '\0') c = ' ';
                if (!screenBuffer.isFieldStart(pos)) {
                    sb.append(c);
                }
            }
            
            if (row < endRow) {
                sb.append('\n');
            }
        }
        
        String text = sb.toString();
        String[] lines = text.split("\n");
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            cleaned.append(lines[i].replaceAll("\\s+$", ""));
            if (i < lines.length - 1) {
                cleaned.append('\n');
            }
        }
        
        try {
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            Clipboard clipboard = toolkit.getSystemClipboard();
            StringSelection selection = new StringSelection(cleaned.toString());
            clipboard.setContents(selection, null);
            
            clearSelection();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    /**
     * Paste text from clipboard.
     */
    public void pasteFromClipboard() {
        if (callback.isKeyboardLocked() || !callback.isConnected()) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        
        try {
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            Clipboard clipboard = toolkit.getSystemClipboard();
            String text = (String) clipboard.getData(DataFlavor.stringFlavor);
            
            if (text == null || text.isEmpty()) {
                return;
            }
            
            int cursorPos = cursorManager.getCursorPos();
            
            for (char c : text.toCharArray()) {
                if (c == '\n' || c == '\r') {
                    cursorManager.tabToNextField();
                    continue;
                }
                
                if (c < 32 || c > 126) {
                    continue;
                }
                
                cursorPos = cursorManager.getCursorPos();
                
                if (!screenBuffer.isProtected(cursorPos)) {
                    screenBuffer.setChar(cursorPos, c);
                    screenBuffer.setModified(cursorPos);
                    
                    cursorManager.moveCursor(1);
                    
                    if (cursorManager.shouldAutoAdvance()) {
                        cursorManager.tabToNextField();
                    }
                } else {
                    cursorManager.tabToNextField();
                    cursorPos = cursorManager.getCursorPos();
                    
                    if (!screenBuffer.isProtected(cursorPos)) {
                        screenBuffer.setChar(cursorPos, c);
                        screenBuffer.setModified(cursorPos);
                        cursorManager.moveCursor(1);
                    }
                }
            }
            
            callback.onRepaintRequested();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    /**
     * Select all text.
     */
    public void selectAll() {
        selectionStart = 0;
        selectionEnd = screenBuffer.getBufferSize() - 1;
        callback.onRepaintRequested();
    }
    
    /**
     * Clear selection.
     */
    public void clearSelection() {
        selectionStart = -1;
        selectionEnd = -1;
        callback.onRepaintRequested();
    }
    
    /**
     * Toggle insert mode on/off.
     */
    public void toggleInsertMode() {
        insertMode = !insertMode;
        if (callback != null) {
            callback.onInsertModeChanged(insertMode);
        }
    }

    // Getters and setters
    public boolean isInsertMode() { return insertMode; }
    public void setInsertMode(boolean insertMode) { 
        this.insertMode = insertMode;
        callback.onInsertModeChanged(insertMode);
    }
    
    public int getSelectionStart() { return selectionStart; }
    public void setSelectionStart(int pos) { this.selectionStart = pos; }
    public int getSelectionEnd() { return selectionEnd; }
    public void setSelectionEnd(int pos) { this.selectionEnd = pos; }
    public boolean hasSelection() { return selectionStart >= 0 && selectionEnd >= 0; }
}
