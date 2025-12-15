package ui;

import terminal.ScreenBuffer;
import terminal.CursorManager;
import config.ColorScheme;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;

/**
 * Enhanced terminal canvas with color support, highlighting, and selection.
 * Provides double-buffered rendering for smooth display.
 */
public class TerminalCanvas extends Canvas {
    private final ScreenBuffer screenBuffer;
    private final CursorManager cursorManager;
    private ColorScheme colorScheme;
    
    private Font terminalFont;
    private int charWidth;
    private int charHeight;
    
    private Image offscreenImage;
    private Graphics offscreenGraphics;
    
    // Selection state
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private boolean selecting = false;
    private Point dragStart = null;
    
    // Mouse listener for selection
    private SelectionListener selectionListener;
    
    public interface SelectionListener {
        void onSelectionChanged(int start, int end);
    }
    
    public TerminalCanvas(ScreenBuffer screenBuffer, CursorManager cursorManager, 
                         ColorScheme colorScheme) {
        this.screenBuffer = screenBuffer;
        this.cursorManager = cursorManager;
        this.colorScheme = colorScheme;
        
        terminalFont = new Font("Monospaced", Font.PLAIN, 14);
        setFont(terminalFont);
        updateFontMetrics();
        
        setBackground(colorScheme.getBackground());
        setFocusTraversalKeysEnabled(false);
        
        setupMouseListeners();
    }
    
    /**
     * Update font metrics when font changes.
     */
    private void updateFontMetrics() {
        FontMetrics fm = getFontMetrics(terminalFont);
        charWidth = fm.charWidth('M');
        charHeight = fm.getHeight();
    }
    
    /**
     * Set up mouse listeners for text selection.
     */
    private void setupMouseListeners() {
        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                handleMousePress(e);
            }
            
            public void mouseReleased(MouseEvent e) {
                handleMouseRelease(e);
            }
            
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    selectWord(e);
                } else if (e.getClickCount() == 3) {
                    selectLine(e);
                }
            }
        });
        
        addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) {
                handleMouseDrag(e);
            }
        });
    }
    
    /**
     * Handle mouse press for selection start.
     */
    private void handleMousePress(MouseEvent e) {
        int pos = screenPositionFromMouse(e.getX(), e.getY());
        if (pos >= 0 && pos < screenBuffer.getBufferSize()) {
            selecting = true;
            selectionStart = pos;
            selectionEnd = pos;
            dragStart = e.getPoint();
            repaint();
        }
    }
    
    /**
     * Handle mouse drag for selection.
     */
    private void handleMouseDrag(MouseEvent e) {
        if (selecting) {
            int pos = screenPositionFromMouse(e.getX(), e.getY());
            if (pos >= 0 && pos < screenBuffer.getBufferSize()) {
                selectionEnd = pos;
                repaint();
            }
        }
    }
    
    /**
     * Handle mouse release for selection end.
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
            if (selectionListener != null && selectionStart >= 0 && selectionEnd >= 0) {
                selectionListener.onSelectionChanged(selectionStart, selectionEnd);
            }
        }
    }
    
    /**
     * Select word at mouse position.
     */
    private void selectWord(MouseEvent e) {
        int pos = screenPositionFromMouse(e.getX(), e.getY());
        if (pos >= 0 && pos < screenBuffer.getBufferSize()) {
            int start = pos;
            int end = pos;
            
            // Expand left
            while (start > 0 && isWordChar(screenBuffer.getChar(start - 1))) {
                start--;
            }
            
            // Expand right
            while (end < screenBuffer.getBufferSize() - 1 && 
                   isWordChar(screenBuffer.getChar(end + 1))) {
                end++;
            }
            
            selectionStart = start;
            selectionEnd = end;
            repaint();
            
            if (selectionListener != null) {
                selectionListener.onSelectionChanged(start, end);
            }
        }
    }
    
    /**
     * Select line at mouse position.
     */
    private void selectLine(MouseEvent e) {
        int pos = screenPositionFromMouse(e.getX(), e.getY());
        if (pos >= 0 && pos < screenBuffer.getBufferSize()) {
            int cols = screenBuffer.getCols();
            int row = pos / cols;
            selectionStart = row * cols;
            selectionEnd = (row + 1) * cols - 1;
            repaint();
            
            if (selectionListener != null) {
                selectionListener.onSelectionChanged(selectionStart, selectionEnd);
            }
        }
    }
    
    /**
     * Check if character is part of a word.
     */
    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
    }
    
    /**
     * Get screen position from mouse coordinates.
     */
    private int screenPositionFromMouse(int x, int y) {
        int col = (x - 5) / charWidth;
        int row = (y - 5) / charHeight;
        int cols = screenBuffer.getCols();
        int rows = screenBuffer.getRows();
        
        if (col < 0) col = 0;
        if (col >= cols) col = cols - 1;
        if (row < 0) row = 0;
        if (row >= rows) row = rows - 1;
        
        return row * cols + col;
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
        if (offscreenImage == null) {
            offscreenImage = createImage(getWidth(), getHeight());
            if (offscreenImage == null) {
                paintScreen(g);
                return;
            }
            offscreenGraphics = offscreenImage.getGraphics();
        }
        
        paintScreen(offscreenGraphics);
        g.drawImage(offscreenImage, 0, 0, this);
    }
    
    /**
     * Paint the terminal screen.
     */
    private void paintScreen(Graphics g) {
        g.setColor(colorScheme.getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        
        g.setFont(terminalFont);
        
        boolean blinkVisible = (System.currentTimeMillis() / 500) % 2 == 0;
        int rows = screenBuffer.getRows();
        int cols = screenBuffer.getCols();
        
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int pos = row * cols + col;
                char c = screenBuffer.getChar(pos);
                
                if (screenBuffer.isNonDisplay(pos) && !screenBuffer.isFieldStart(pos)) {
                    continue;
                }
                
                if (c == '\0') c = ' ';
                
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
                
                // Get highlighting
                byte highlight = screenBuffer.getHighlighting(pos);
                if (highlight == (byte)0xF2) {
                    reverseVideo = true;
                } else if (highlight == (byte)0xF1) {
                    blink = true;
                }
                
                // Get color
                byte extColor = screenBuffer.getExtendedColor(pos);
                if (extColor > 0 && extColor < 8) {
                    fg = colorScheme.getColor(extColor);
                } else {
                    int fieldStart = screenBuffer.findFieldStart(pos);
                    int colorCode = (screenBuffer.getAttribute(fieldStart) >> 4) & 0x07;
                    if (colorCode > 0 && colorCode < 8) {
                        fg = colorScheme.getColor(colorCode);
                    }
                    
                    if (screenBuffer.isProtected(pos) && screenBuffer.isFieldStart(pos)) {
                        fg = Color.CYAN;
                    }
                }
                
                int x = 5 + col * charWidth;
                int y = 5 + row * charHeight + charHeight - 3;
                
                // Handle selection highlighting
                if (isSelected) {
                    Color temp = fg;
                    fg = new Color(255, 255, 255); // White text
                    bg = new Color(0, 120, 215);   // Blue selection background
                } else if (reverseVideo) {
                    Color temp = fg;
                    fg = bg;
                    bg = temp;
                }
                
                if (!bg.equals(colorScheme.getBackground())) {
                    g.setColor(bg);
                    g.fillRect(x, y - charHeight + 3, charWidth, charHeight);
                }
                
                if (blink && !blinkVisible) {
                    continue;
                }
                
                g.setColor(fg);
                g.drawString(String.valueOf(c), x, y);
            }
        }
        
        // Draw cursor
        int cursorPos = cursorManager.getCursorPos();
        System.out.println("TerminalCanvas setting cursor at " + cursorPos);
        int row = cursorPos / cols;
        int col = cursorPos % cols;
        int x = 5 + col * charWidth;
        int y = 5 + row * charHeight;
        
        g.setColor(colorScheme.getCursor());
        g.fillRect(x, y + charHeight - 3, charWidth, 2);
    }
    
    @Override
    public Dimension getPreferredSize() {
        int cols = screenBuffer.getCols();
        int rows = screenBuffer.getRows();
        return new Dimension(cols * charWidth + 10, rows * charHeight + 10);
    }
    
    /**
     * Update size when screen dimensions change.
     */
    public void updateSize() {
        setPreferredSize(getPreferredSize());
        if (offscreenImage != null) {
            offscreenImage = null; // Force recreation with new size
        }
        revalidate();
    }
    
    /**
     * Set the color scheme.
     */
    public void setColorScheme(ColorScheme scheme) {
        this.colorScheme = scheme;
        setBackground(scheme.getBackground());
        repaint();
    }
    
    /**
     * Get the color scheme.
     */
    public ColorScheme getColorScheme() {
        return colorScheme;
    }
    
    /**
     * Set the terminal font.
     */
    public void setTerminalFont(Font font) {
        this.terminalFont = font;
        setFont(font);
        updateFontMetrics();
        updateSize();
        repaint();
    }
    
    /**
     * Get the terminal font.
     */
    public Font getTerminalFont() {
        return terminalFont;
    }

    /**
     * Get the character width.
     */
    public int getCharWidth() {
        return charWidth;
    }

    /**
     * Get the character height.
     */
    public int getCharHeight() {
        return charHeight;
    }
    
    /**
     * Clear selection.
     */
    public void clearSelection() {
        selectionStart = -1;
        selectionEnd = -1;
        repaint();
    }
    
    /**
     * Get current selection.
     */
    public int getSelectionStart() { return selectionStart; }
    public int getSelectionEnd() { return selectionEnd; }
    public boolean hasSelection() { return selectionStart >= 0 && selectionEnd >= 0; }
    
    /**
     * Set selection listener.
     */
    public void setSelectionListener(SelectionListener listener) {
        this.selectionListener = listener;
    }
}
