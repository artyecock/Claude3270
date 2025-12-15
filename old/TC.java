package ui;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import terminal.ScreenBuffer;
import config.ColorScheme;

/**
 * Enhanced terminal canvas with double-buffered rendering, color support,
 * text selection, and smooth cursor blinking.
 * 
 * Extracted from TN3270Emulator-Monolithic.java lines 2855-2987
 */
public class TerminalCanvas extends Canvas {
    
    // Dependencies
    private ScreenBuffer screenBuffer;
    private ColorScheme colorScheme;
    private SelectionListener selectionListener;
    
    // Display properties
    private Font terminalFont;
    private int charWidth;
    private int charHeight;
    private int rows;
    private int cols;
    
    // Double buffering
    private Image offscreenImage;
    private Graphics offscreenGraphics;
    
    // State
    private int cursorPos = 0;
    private boolean keyboardLocked = false;
    private boolean insertMode = false;
    private boolean cursorVisible = true;
    
    // Selection state
    private boolean selecting = false;
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private Point dragStart = null;
    
    // Callback interface for selection events
    public interface SelectionListener {
        void onSelectionChanged(int start, int end);
        void onDoubleClick(int position);
        void onTripleClick(int position);
    }
    
    /**
     * Creates a new terminal canvas.
     * 
     * @param cols Number of columns
     * @param rows Number of rows
     */
    public TerminalCanvas(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        
        // Initialize font and metrics
        terminalFont = new Font("Monospaced", Font.PLAIN, 14);
        setFont(terminalFont);
        FontMetrics fm = getFontMetrics(terminalFont);
        charWidth = fm.charWidth('M');
        charHeight = fm.getHeight();
        
        // Set size
        setPreferredSize(new Dimension(cols * charWidth + 10, rows * charHeight + 10));
        setBackground(Color.BLACK);
        setFocusTraversalKeysEnabled(false);
        
        // Setup mouse listeners
        setupMouseListeners();
        
        System.out.println("TerminalCanvas: cols=" + cols + ", rows=" + rows + 
                         ", charWidth=" + charWidth + ", charHeight=" + charHeight);
    }
    
    /**
     * Sets the screen buffer to render.
     */
    public void setScreenBuffer(ScreenBuffer buffer) {
        this.screenBuffer = buffer;
        repaint();
    }
    
    /**
     * Sets the color scheme for rendering.
     */
    public void setColorScheme(ColorScheme scheme) {
        this.colorScheme = scheme;
        setBackground(scheme.getBackground());
        repaint();
    }
    
    /**
     * Sets the selection listener.
     */
    public void setSelectionListener(SelectionListener listener) {
        this.selectionListener = listener;
    }
    
    /**
     * Updates cursor position and repaints.
     */
    public void setCursorPosition(int pos) {
        this.cursorPos = pos;
        repaint();
    }
    
    /**
     * Sets keyboard lock state.
     */
    public void setKeyboardLocked(boolean locked) {
        this.keyboardLocked = locked;
        repaint();
    }
    
    /**
     * Sets insert mode state.
     */
    public void setInsertMode(boolean insert) {
        this.insertMode = insert;
    }
    
    /**
     * Sets cursor visibility (for blinking).
     */
    public void setCursorVisible(boolean visible) {
        this.cursorVisible = visible;
        repaint();
    }
    
    /**
     * Gets current selection start position.
     */
    public int getSelectionStart() {
        return selectionStart;
    }
    
    /**
     * Gets current selection end position.
     */
    public int getSelectionEnd() {
        return selectionEnd;
    }
    
    /**
     * Clears the current selection.
     */
    public void clearSelection() {
        selectionStart = -1;
        selectionEnd = -1;
        repaint();
    }
    
    /**
     * Updates the canvas size based on current dimensions.
     */
    public void updateSize(int newCols, int newRows) {
        this.cols = newCols;
        this.rows = newRows;
        setPreferredSize(new Dimension(cols * charWidth + 10, rows * charHeight + 10));
        if (offscreenImage != null) {
            offscreenImage = null; // Force recreation with new size
        }
        revalidate();
        repaint();
    }
    
    /**
     * Changes the terminal font and updates metrics.
     */
    public void setTerminalFont(Font font) {
        this.terminalFont = font;
        setFont(font);
        FontMetrics fm = getFontMetrics(font);
        charWidth = fm.charWidth('M');
        charHeight = fm.getHeight();
        setPreferredSize(new Dimension(cols * charWidth + 10, rows * charHeight + 10));
        if (offscreenImage != null) {
            offscreenImage = null;
        }
        revalidate();
        repaint();
    }
    
    @Override
    public boolean isFocusable() {
        return true;
    }
    
    @Override
    public void update(Graphics g) {
        paint(g);
    }
    
    @Override
    public void paint(Graphics g) {
        // Create offscreen buffer if needed
        if (offscreenImage == null || 
            offscreenImage.getWidth(null) != getWidth() ||
            offscreenImage.getHeight(null) != getHeight()) {
            offscreenImage = createImage(getWidth(), getHeight());
            if (offscreenImage == null) {
                paintScreen(g);
                return;
            }
            offscreenGraphics = offscreenImage.getGraphics();
        }
        
        // Paint to offscreen buffer
        paintScreen(offscreenGraphics);
        
        // Blit to screen
        g.drawImage(offscreenImage, 0, 0, this);
    }
    
    /**
     * Paints the terminal screen content.
     * Extracted from TN3270Emulator-Monolithic.java lines 2917-2978
     */
    private void paintScreen(Graphics g) {
        if (screenBuffer == null || colorScheme == null) {
            // Paint placeholder
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Color.GREEN);
            g.setFont(terminalFont);
            g.drawString("Initializing...", 10, 20);
            return;
        }
        
        // Clear background
        g.setColor(colorScheme.getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        
        g.setFont(terminalFont);
        
        // Blink visible on 500ms cycle
        boolean blinkVisible = (System.currentTimeMillis() / 500) % 2 == 0;
        
        // Render each character
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int pos = row * cols + col;
                
                // Get character and attributes
                char c = screenBuffer.getChar(pos);
                if (c == '\0') c = ' ';
                
                // Skip non-display fields (except field attributes)
                if (screenBuffer.isNonDisplay(pos) && !screenBuffer.isFieldStart(pos)) {
                    continue;
                }
                
                // Default colors
                Color fg = colorScheme.getDefaultForeground();
                Color bg = colorScheme.getBackground();
                boolean reverseVideo = false;
                boolean blink = false;
                
                // Check if this position is selected
                boolean isSelected = false;
                if (selectionStart >= 0 && selectionEnd >= 0) {
                    int start = Math.min(selectionStart, selectionEnd);
                    int end = Math.max(selectionStart, selectionEnd);
                    isSelected = (pos >= start && pos <= end);
                }
                
                // Apply highlighting
                byte highlight = screenBuffer.getHighlighting(pos);
                if (highlight == (byte) 0xF2) {
                    reverseVideo = true;
                } else if (highlight == (byte) 0xF1) {
                    blink = true;
                }
                
                // Apply extended colors
                byte colorCode = screenBuffer.getExtendedColor(pos);
                if (colorCode > 0 && colorCode < colorScheme.getColors().length) {
                    fg = colorScheme.getColors()[colorCode];
                } else {
                    // Use field attribute color
                    int fieldStart = screenBuffer.findFieldStart(pos);
                    int fieldColor = (screenBuffer.getAttribute(fieldStart) >> 4) & 0x07;
                    if (fieldColor > 0 && fieldColor < colorScheme.getColors().length) {
                        fg = colorScheme.getColors()[fieldColor];
                    }
                    
                    // Protected field attributes show in cyan
                    if (screenBuffer.isProtected(pos) && screenBuffer.isFieldStart(pos)) {
                        fg = Color.CYAN;
                    }
                }
                
                // Calculate position
                int x = 5 + col * charWidth;
                int y = 5 + row * charHeight + charHeight - 3;
                
                // Apply selection highlighting
                if (isSelected) {
                    fg = Color.WHITE;
                    bg = new Color(0, 120, 215); // Blue selection
                } else if (reverseVideo) {
                    Color temp = fg;
                    fg = bg;
                    bg = temp;
                }
                
                // Draw background if not default
                if (!bg.equals(colorScheme.getBackground())) {
                    g.setColor(bg);
                    g.fillRect(x, y - charHeight + 3, charWidth, charHeight);
                }
                
                // Skip blinking text when blink is off
                if (blink && !blinkVisible) {
                    continue;
                }
                
                // Draw character
                g.setColor(fg);
                g.drawString(String.valueOf(c), x, y);
            }
        }
        
        // Draw cursor (if unlocked and visible)
        if (!keyboardLocked && cursorVisible) {
            int row = cursorPos / cols;
            int col = cursorPos % cols;
            int x = 5 + col * charWidth;
            int y = 5 + row * charHeight;
            
            g.setColor(colorScheme.getCursorColor());
            if (insertMode) {
                // Block cursor for insert mode
                g.fillRect(x, y, charWidth, charHeight);
                // Draw character in reverse
                char c = screenBuffer.getChar(cursorPos);
                if (c != '\0' && c != ' ') {
                    g.setColor(colorScheme.getBackground());
                    g.drawString(String.valueOf(c), x, y + charHeight - 3);
                }
            } else {
                // Underline cursor for replace mode
                g.fillRect(x, y + charHeight - 3, charWidth, 2);
            }
        }
    }
    
    /**
     * Sets up mouse listeners for text selection.
     * Extracted from TN3270Emulator-Monolithic.java lines 2863-2893
     */
    private void setupMouseListeners() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleMousePress(e);
            }
            
            @Override
            public void mouseReleased(MouseEvent e) {
                handleMouseRelease(e);
            }
            
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    selectWord(e);
                } else if (e.getClickCount() == 3) {
                    selectLine(e);
                }
            }
        });
        
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                handleMouseDrag(e);
            }
        });
    }
    
    /**
     * Handles mouse press for selection start.
     */
    private void handleMousePress(MouseEvent e) {
        int pos = screenPositionFromMouse(e.getX(), e.getY());
        if (pos >= 0 && pos < rows * cols) {
            selecting = true;
            selectionStart = pos;
            selectionEnd = pos;
            dragStart = e.getPoint();
            repaint();
        }
    }
    
    /**
     * Handles mouse drag for selection extension.
     */
    private void handleMouseDrag(MouseEvent e) {
        if (selecting) {
            int pos = screenPositionFromMouse(e.getX(), e.getY());
            if (pos >= 0 && pos < rows * cols) {
                selectionEnd = pos;
                repaint();
                if (selectionListener != null) {
                    selectionListener.onSelectionChanged(
                        Math.min(selectionStart, selectionEnd),
                        Math.max(selectionStart, selectionEnd)
                    );
                }
            }
        }
    }
    
    /**
     * Handles mouse release to finalize selection.
     */
    private void handleMouseRelease(MouseEvent e) {
        if (selecting) {
            selecting = false;
            // Normalize selection so start < end
            if (selectionStart > selectionEnd) {
                int temp = selectionStart;
                selectionStart = selectionEnd;
                selectionEnd = temp;
            }
        }
    }
    
    /**
     * Selects the word at the mouse position.
     */
    private void selectWord(MouseEvent e) {
        if (screenBuffer == null) return;
        
        int pos = screenPositionFromMouse(e.getX(), e.getY());
        if (pos >= 0 && pos < rows * cols) {
            // Find word boundaries
            int start = pos;
            int end = pos;
            
            // Expand left
            while (start > 0 && isWordChar(screenBuffer.getChar(start - 1))) {
                start--;
            }
            
            // Expand right
            while (end < rows * cols - 1 && isWordChar(screenBuffer.getChar(end + 1))) {
                end++;
            }
            
            selectionStart = start;
            selectionEnd = end;
            repaint();
            
            if (selectionListener != null) {
                selectionListener.onDoubleClick(pos);
            }
        }
    }
    
    /**
     * Selects the entire line at the mouse position.
     */
    private void selectLine(MouseEvent e) {
        int pos = screenPositionFromMouse(e.getX(), e.getY());
        if (pos >= 0 && pos < rows * cols) {
            int row = pos / cols;
            selectionStart = row * cols;
            selectionEnd = (row + 1) * cols - 1;
            repaint();
            
            if (selectionListener != null) {
                selectionListener.onTripleClick(pos);
            }
        }
    }
    
    /**
     * Determines if a character is part of a word.
     */
    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
    }
    
    /**
     * Converts mouse coordinates to screen buffer position.
     */
    private int screenPositionFromMouse(int x, int y) {
        int col = (x - 5) / charWidth;
        int row = (y - 5) / charHeight;
        
        if (col < 0) col = 0;
        if (col >= cols) col = cols - 1;
        if (row < 0) row = 0;
        if (row >= rows) row = rows - 1;
        
        return row * cols + col;
    }
    
    /**
     * Gets the selected text as a string.
     */
    public String getSelectedText() {
        if (screenBuffer == null || selectionStart < 0 || selectionEnd < 0) {
            return "";
        }
        
        int start = Math.min(selectionStart, selectionEnd);
        int end = Math.max(selectionStart, selectionEnd);
        
        StringBuilder sb = new StringBuilder();
        int startRow = start / cols;
        int endRow = end / cols;
        
        for (int row = startRow; row <= endRow; row++) {
            int rowStart = (row == startRow) ? start : row * cols;
            int rowEnd = (row == endRow) ? end : (row + 1) * cols - 1;
            
            for (int pos = rowStart; pos <= rowEnd && pos < rows * cols; pos++) {
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
        
        // Clean up trailing whitespace on each line
        String text = sb.toString();
        String[] lines = text.split("\n");
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            cleaned.append(lines[i].replaceAll("\\s+$", ""));
            if (i < lines.length - 1) {
                cleaned.append('\n');
            }
        }
        
        return cleaned.toString();
    }
}
