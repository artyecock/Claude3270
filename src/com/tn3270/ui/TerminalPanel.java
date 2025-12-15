package com.tn3270.ui;

import com.tn3270.model.ScreenModel;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

public class TerminalPanel extends JPanel implements Scrollable {

    private final ScreenModel screenModel;
    private Font terminalFont;
    private int charWidth;
    private int charHeight;
    private int charAscent;
    private boolean isUpdatingSize = false;
    private boolean showCrosshair = false;
    
    // Selection State
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private boolean selecting = false;

    public enum CursorStyle { BLOCK, UNDERSCORE, I_BEAM }
    private CursorStyle cursorStyle = CursorStyle.BLOCK;

    public TerminalPanel(ScreenModel model) {
        this.screenModel = model;
        
        // Smart Default Font Size
        int fontSize = 14;
        int cols = model.getCols();
        if (cols >= 160) fontSize = 9;
        else if (cols >= 132) fontSize = 11;
        
        this.terminalFont = new Font("Monospaced", Font.PLAIN, fontSize);
        
        setBackground(Color.BLACK);
        setForeground(Color.GREEN);
        setDoubleBuffered(true);
        setFocusable(true);
        
        // FIX: Disable Swing's TAB traversal so we can use TAB for 3270 navigation
        setFocusTraversalKeysEnabled(false);
        
        updateSize();
        
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (SwingUtilities.isLeftMouseButton(e)) {
                    int pos = screenPositionFromMouse(e.getX(), e.getY());
                    if (pos >= 0) {
                        selecting = true;
                        selectionStart = pos;
                        selectionEnd = pos;
                        repaint();
                    }
                }
            }
            @Override public void mouseReleased(MouseEvent e) { selecting = false; }
        });
        
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (selecting) {
                    int pos = screenPositionFromMouse(e.getX(), e.getY());
                    if (pos >= 0) { selectionEnd = pos; repaint(); }
                }
            }
        });
    }

    // --- SCROLLABLE IMPLEMENTATION ---
    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return charHeight; }
    @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return charHeight * 10; }
    
    /**
     * Determines if the panel should force itself to match the Viewport width.
     * 
     * REGRESSION NOTE: SCROLLBARS VS BACKGROUND
     * 
     * The Fix: We return 'true' (track width) only if the viewport is wider than our content.
     * This forces the JPanel to stretch and paint its black background over the entire 
     * JScrollPane area, preventing gray/white gaps from the ScrollPane peering through.
     * 
     * However, we must return 'false' if our content is WIDER than the viewport. 
     * If we returned true always, the JScrollPane would never show horizontal scrollbars 
     * because it would think the content fits perfectly.
     */
    @Override 
    public boolean getScrollableTracksViewportWidth() { 
        // Only track width (stretch/center) if content fits. If content is huge, return false to allow scrollbars.
        return getParent() instanceof JViewport && getParent().getWidth() >= getPreferredSize().width; 
    }
    
    @Override 
    public boolean getScrollableTracksViewportHeight() { 
        return getParent() instanceof JViewport && getParent().getHeight() >= getPreferredSize().height; 
    }

    // --- AUTO-FIT LOGIC ---
    public void fitToSize(int availableWidth, int availableHeight) {
        if (availableWidth <= 0 || availableHeight <= 0) return;
        
        int bestSize = 6;
        // Check down from a large size to find the first one that fits
        for (int s = 72; s >= 6; s--) {
            Font testFont = new Font("Monospaced", Font.PLAIN, s);
            FontMetrics fm = getFontMetrics(testFont);
            int totalW = fm.charWidth('M') * screenModel.getCols();
            int totalH = fm.getHeight() * screenModel.getRows();
            
            // Allow slight overflow for padding tolerance
            if (totalW <= availableWidth && totalH <= availableHeight) {
                bestSize = s;
                break;
            }
        }
        setFont(new Font("Monospaced", Font.PLAIN, bestSize));
    }

    public void updateSize() {
        if (isUpdatingSize || screenModel == null) return;
        isUpdatingSize = true;
        try {
            Font f = (terminalFont != null) ? terminalFont : getFont();
            if (f == null) return;
            FontMetrics fm = getFontMetrics(f);
            this.charWidth = fm.charWidth('M');
            this.charHeight = fm.getHeight();
            this.charAscent = fm.getAscent();
            
            int cols = screenModel.getCols();
            int rows = screenModel.getRows();
            
            // STRICT calculation for PreferredSize
            setPreferredSize(new Dimension(cols * charWidth, rows * charHeight));
            revalidate();
            repaint();
        } finally {
            isUpdatingSize = false;
        }
    }

    @Override
    public void setFont(Font f) {
        super.setFont(f);
        this.terminalFont = f;
        updateSize();
    }
    
    public void setShowCrosshair(boolean b) {
        this.showCrosshair = b;
        repaint();
    }
    
    public boolean isShowCrosshair() { 
    	return showCrosshair; 
    }

    /**
     * Paints the terminal characters.
     * 
     * REGRESSION NOTE: SMART SCALING / BLACK BANDS
     * This method includes logic to eliminate the "Black Bands" (unused viewport background)
     * caused by integer math (e.g., Window Width 1000px / Char Width 9px = Remainder 10px).
     * 
     * Strategy:
     * 1. Calculate the exact pixel width of the character grid (cols * charWidth).
     * 2. Compare it to the Panel width.
     * 3. 'closeEnoughWidth': If the difference is small (< 2 chars), we assume the intent 
     *    is "Auto-Fit". We use Graphics2D.scale() to stretch the grid to the edges.
     * 4. If the difference is large, we assume "Manual Mode" (User wants a small font).
     *    We DISABLE scaling and CENTER the grid using calculated margins.
     */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (screenModel == null) return;
        
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int cols = screenModel.getCols();
        int rows = screenModel.getRows();
        int gridWidth = cols * charWidth;
        int gridHeight = rows * charHeight;
        
        double scaleX = 1.0;
        double scaleY = 1.0;
        int marginLeft = 0;
        int marginTop = 0;
        
        int w = getWidth();
        int h = getHeight();

        // SMART SCALING:
        // If content is roughly the same size as viewport (Auto-Fit), stretch to kill bands.
        // If content is significantly smaller/larger (Manual), Center or Clip.
        boolean closeEnoughWidth = Math.abs(w - gridWidth) < (charWidth * 2);
        boolean closeEnoughHeight = Math.abs(h - gridHeight) < (charHeight * 2);

        if (gridWidth > 0 && gridHeight > 0 && closeEnoughWidth && closeEnoughHeight) {
            scaleX = (double) w / (double) gridWidth;
            scaleY = (double) h / (double) gridHeight;
        } else {
            // Manual Mode: Center content if smaller than viewport
            if (w > gridWidth) marginLeft = (w - gridWidth) / 2;
            if (h > gridHeight) marginTop = (h - gridHeight) / 2;
        }

        java.awt.geom.AffineTransform oldTransform = g2d.getTransform();
        if (scaleX != 1.0 || scaleY != 1.0) {
            g2d.scale(scaleX, scaleY);
        }

        g2d.setFont(terminalFont);

        char[] buffer = screenModel.getBuffer();
        byte[] attr = screenModel.getAttributes();
        byte[] extColors = screenModel.getExtendedColors();
        byte[] highlight = screenModel.getHighlight();
        Color[] palette = screenModel.getPalette();
        
        boolean blinkVisible = (System.currentTimeMillis() / 500) % 2 == 0;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int pos = row * cols + col;
                if (pos >= buffer.length) break;

                char c = buffer[pos];
                if (screenModel.isFieldStart(pos)) c = ' ';
                if (c == '\0') c = ' ';

                Color fg = getForeground();
                Color bg = getBackground();
                boolean reverse = false;
                boolean underscore = false;
                boolean isHidden = false;

                int fieldStart = screenModel.findFieldStart(pos);
                byte a = (fieldStart >= 0) ? attr[fieldStart] : 0;
                
                if ((a & 0x0C) == 0x0C) { isHidden = true; c = ' '; }

                if (!isHidden) {
                    if (extColors[pos] > 0 && extColors[pos] < palette.length) {
                        fg = palette[extColors[pos]];
                    } else if (fieldStart >= 0) {
                        byte fieldColor = extColors[fieldStart];
                        if (fieldColor > 0 && fieldColor < palette.length) fg = palette[fieldColor];
                        else {
                            boolean high = (a & 0x08) != 0;
                            boolean prot = (a & 0x20) != 0;
                            if (prot && high) fg = palette[7];
                            else if (prot)    fg = palette[5];
                            else if (high)    fg = palette[2];
                            else              fg = palette[4];
                        }
                    }
                    byte hl = highlight[pos];
                    if (hl == 0 && fieldStart >= 0) hl = highlight[fieldStart];
                    if (hl == 0xF1 && !blinkVisible) c = ' ';
                    if (hl == 0xF2) reverse = true;
                    if (hl == 0xF4) underscore = true;
                }

                if (isPosSelected(pos)) {
                    fg = Color.WHITE;
                    bg = new Color(0, 120, 215);
                    reverse = false;
                }

                int x = marginLeft + (col * charWidth);
                int y = marginTop + (row * charHeight);

                if (reverse) { Color tmp = fg; fg = bg; bg = tmp; }

                if (!bg.equals(getBackground())) {
                    g2d.setColor(bg);
                    g2d.fillRect(x, y, charWidth, charHeight);
                }

                g2d.setColor(fg);
                g2d.drawString(String.valueOf(c), x, y + charAscent);

                if (underscore && !isHidden) {
                    g2d.drawLine(x, y + charAscent + 2, x + charWidth, y + charAscent + 2);
                }
            }
        }
        /*
        // Draw Cursor
        if (!screenModel.isKeyboardLocked() && hasFocus()) {
            int cPos = screenModel.getCursorPos();
            if (cPos >= 0 && cPos < buffer.length) {
                int cRow = cPos / cols;
                int cCol = cPos % cols;
                int cx = marginLeft + (cCol * charWidth);
                int cy = marginTop + (cRow * charHeight);

                g2d.setColor(screenModel.getCursorColor());
                if (cursorStyle == CursorStyle.BLOCK) {
                    g2d.setXORMode(getBackground());
                    g2d.fillRect(cx, cy, charWidth, charHeight);
                    g2d.setPaintMode();
                } else if (cursorStyle == CursorStyle.UNDERSCORE) {
                    g2d.fillRect(cx, cy + charHeight - 2, charWidth, 2);
                } else {
                    g2d.fillRect(cx, cy, 2, charHeight);
                }
            }
        }
        */
        // 5. DRAW CURSOR & CROSSHAIR
        if (!screenModel.isKeyboardLocked() && hasFocus()) {
            int cPos = screenModel.getCursorPos();
            if (cPos >= 0 && cPos < buffer.length) {
                int cRow = cPos / cols;
                int cCol = cPos % cols;
                int cx = marginLeft + (cCol * charWidth);
                int cy = marginTop + (cRow * charHeight);

                // --- NEW: CROSSHAIR LOGIC ---
                if (showCrosshair) {
                    Color cc = screenModel.getCursorColor();
                    // Create an "Ultra-Light" version (15% Opacity)
                    g2d.setColor(new Color(cc.getRed(), cc.getGreen(), cc.getBlue(), 40));
                    
                    // Calculate center of the character cell
                    int midX = cx + (charWidth / 2);
                    int midY = cy + (charHeight / 2);

                    // Draw Horizontal Line (Full Width)
                    g2d.drawLine(0, midY, getWidth(), midY); // Note: getWidth() works because we are in scaled context
                    
                    // Draw Vertical Line (Full Height)
                    g2d.drawLine(midX, 0, midX, getHeight());
                }
                // -----------------------------

                g2d.setColor(screenModel.getCursorColor());
                
                if (cursorStyle == CursorStyle.BLOCK) {
                    g2d.setXORMode(getBackground());
                    g2d.fillRect(cx, cy, charWidth, charHeight);
                    g2d.setPaintMode();
                } else if (cursorStyle == CursorStyle.UNDERSCORE) {
                    g2d.fillRect(cx, cy + charHeight - 2, charWidth, 2);
                } else {
                    g2d.fillRect(cx, cy, 2, charHeight);
                }
            }
        }
        
        g2d.setTransform(oldTransform);
    }
    
    // --- MOUSE COORDINATE MAPPING ---
    private int screenPositionFromMouse(int x, int y) {
        if (screenModel == null) return -1;
        int cols = screenModel.getCols();
        int rows = screenModel.getRows();
        int gridWidth = cols * charWidth;
        int gridHeight = rows * charHeight;
        if (gridWidth == 0 || gridHeight == 0) return -1;

        int w = getWidth();
        int h = getHeight();
        
        // Match paintComponent logic
        boolean closeEnoughWidth = Math.abs(w - gridWidth) < (charWidth * 2);
        boolean closeEnoughHeight = Math.abs(h - gridHeight) < (charHeight * 2);
        
        double scaleX = 1.0, scaleY = 1.0;
        int marginLeft = 0, marginTop = 0;

        if (closeEnoughWidth && closeEnoughHeight) {
            scaleX = (double) w / (double) gridWidth;
            scaleY = (double) h / (double) gridHeight;
        } else {
            if (w > gridWidth) marginLeft = (w - gridWidth) / 2;
            if (h > gridHeight) marginTop = (h - gridHeight) / 2;
        }

        // Un-map coordinates
        int logicalX = (int) ((x / scaleX) - marginLeft);
        int logicalY = (int) ((y / scaleY) - marginTop);

        int col = logicalX / charWidth;
        int row = logicalY / charHeight;

        if (col >= 0 && col < cols && row >= 0 && row < rows) {
            return row * cols + col;
        }
        return -1;
    }
    
    // THE MISSING METHOD
    private boolean isPosSelected(int pos) {
        if (selectionStart < 0 || selectionEnd < 0) return false;
        int s = Math.min(selectionStart, selectionEnd);
        int e = Math.max(selectionStart, selectionEnd);
        return pos >= s && pos <= e;
    }

    public void setCursorStyle(CursorStyle style) { this.cursorStyle = style; repaint(); }
    public Font getTerminalFont() { return terminalFont; }
    public void clearSelection() { selectionStart = -1; selectionEnd = -1; repaint(); }
    public void selectAll() { selectionStart = 0; selectionEnd = screenModel.getSize() - 1; repaint(); }
    public String getSelectedText() {
        if (selectionStart < 0 || selectionEnd < 0) return "";
        int s = Math.min(selectionStart, selectionEnd);
        int e = Math.max(selectionStart, selectionEnd);
        StringBuilder sb = new StringBuilder();
        int cols = screenModel.getCols();
        for (int i = s; i <= e; i++) {
            char c = screenModel.getChar(i);
            sb.append(c == '\0' ? ' ' : c);
            if ((i + 1) % cols == 0) sb.append('\n');
        }
        return sb.toString().trim();
    }
    public boolean hasSelection() { return selectionStart >= 0 && selectionEnd >= 0; }
}
