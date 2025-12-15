import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;


/**
 * 
 * Terminal display canvas with rendering and mouse selection support.
 */
public class TerminalCanvas extends Canvas {
	private Font terminalFont;
	private int charWidth;
	private int charHeight;
	private Image offscreenImage;
	private Graphics offscreenGraphics;
	// References to emulator state
	private ScreenBuffer screenBuffer;
	private ColorScheme colorScheme;
	// Selection state
	private int selectionStart = -1;
	private int selectionEnd = -1;
	// Dimensions
	private int cols;
	private int rows;

	/**
	 * 
	 * Create a new terminal canvas.
	 */
	public TerminalCanvas(int cols, int rows) {
		this.cols = cols;
		this.rows = rows;
		terminalFont = new Font("Monospaced", Font.PLAIN, 14);
		setFont(terminalFont);
		FontMetrics fm = getFontMetrics(terminalFont);
		charWidth = fm.charWidth('M');
		charHeight = fm.getHeight();
		setPreferredSize(new Dimension(cols * charWidth + 10, rows * charHeight + 10));
		setBackground(Color.BLACK);
		setFocusTraversalKeysEnabled(false);
		System.out.println("TerminalCanvas: cols=" + cols + ", rows=" + rows + ", charWidth=" + charWidth
				+ ", charHeight=" + charHeight);
	}

	/**
	 * 
	 * Set the screen buffer to display.
	 */
	public void setScreenBuffer(ScreenBuffer buffer) {
		this.screenBuffer = buffer;
	}

	/**
	 * 
	 * Set the color scheme for rendering.
	 */
	public void setColorScheme(ColorScheme scheme) {
		this.colorScheme = scheme;
		if (scheme != null) {
			setBackground(scheme.background);
		}
	}

	/**
	 * 
	 * Update canvas size based on current dimensions.
	 */
	public void updateSize(int cols, int rows) {
		this.cols = cols;
		this.rows = rows;
		setPreferredSize(new Dimension(cols * charWidth + 10, rows * charHeight + 10));
		if (offscreenImage != null) {
			offscreenImage = null; // Force recreation
		}
		revalidate();
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
				paintScreen(g, 0, false);
				return;
			}
			offscreenGraphics = offscreenImage.getGraphics();
		}
		paintScreen(offscreenGraphics, 0, false);
		g.drawImage(offscreenImage, 0, 0, this);
	}

	/**
	 * 
	 * Render the terminal screen to the given graphics context. Must be called with
	 * current cursor position and keyboard lock state.
	 */
	public void paintScreen(Graphics g, int cursorPos, boolean keyboardLocked) {
		if (screenBuffer == null || colorScheme == null) {
			return;
		}
		// Background
		g.setColor(colorScheme.background);
		g.fillRect(0, 0, getWidth(), getHeight());
		g.setFont(terminalFont);
		boolean blinkVisible = (System.currentTimeMillis() / 500) % 2 == 0;
		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				int pos = row * cols + col;
				if (pos >= screenBuffer.getBufferSize())
					break;

				char c = screenBuffer.getChar(pos);

				if (screenBuffer.isNonDisplay(pos) && !screenBuffer.isFieldStart(pos)) {
					continue;
				}

				if (c == '\0')
					c = ' ';

				Color fg = colorScheme.defaultFg;
				Color bg = colorScheme.background;
				boolean reverseVideo = false;
				boolean blink = false;

				// Check if this position is selected
				boolean isSelected = false;
				if (selectionStart >= 0 && selectionEnd >= 0) {
					int start = Math.min(selectionStart, selectionEnd);
					int end = Math.max(selectionStart, selectionEnd);
					isSelected = (pos >= start && pos <= end);
				}

				byte highlight = screenBuffer.getHighlighting(pos);
				if (highlight == (byte) 0xF2) {
					reverseVideo = true;
				} else if (highlight == (byte) 0xF1) {
					blink = true;
				}

				byte extColor = screenBuffer.getExtendedColor(pos);
				if (extColor > 0 && extColor < colorScheme.colors.length) {
					fg = colorScheme.colors[extColor];
				} else {
					int fieldStart = screenBuffer.findFieldStart(pos);
					byte attr = screenBuffer.getAttribute(fieldStart);
					int colorCode = (attr >> 4) & 0x07;
					if (colorCode > 0 && colorCode < colorScheme.colors.length) {
						fg = colorScheme.colors[colorCode];
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
					bg = new Color(0, 120, 215); // Blue selection background
				} else if (reverseVideo) {
					Color temp = fg;
					fg = bg;
					bg = temp;
				}

				if (!bg.equals(colorScheme.background)) {
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
		//Draw cursor
		if (!keyboardLocked && cursorPos >= 0 && cursorPos < screenBuffer.getBufferSize()) {
			int row = cursorPos / cols;
			int col = cursorPos % cols;
			int x = 5 + col * charWidth;
			int y = 5 + row * charHeight;
			g.setColor(colorScheme.cursor);
			g.fillRect(x, y + charHeight - 3, charWidth, 2);
		}
	}

	/**
	 * 
	 * Get the character width in pixels.
	 */
	public int getCharWidth() {
		return charWidth;
	}

	/**
	 * 
	 * Get the character height in pixels.
	 */
	public int getCharHeight() {
		return charHeight;
	}

	/**
	 * 
	 * Set the terminal font.
	 */
	public void setTerminalFont(Font font) {
		this.terminalFont = font;
		setFont(font);
		FontMetrics fm = getFontMetrics(font);
		charWidth = fm.charWidth('M');
		charHeight = fm.getHeight();
	}

	/**
	 * 
	 * Get selection start position.
	 */
	public int getSelectionStart() {
		return selectionStart;
	}

	/**
	 * 
	 * Get selection end position.
	 */
	public int getSelectionEnd() {
		return selectionEnd;
	}

	/**
	 * 
	 * Set selection range.
	 */
	public void setSelection(int start, int end) {
		this.selectionStart = start;
		this.selectionEnd = end;
		repaint();
	}

	/**
	 * 
	 * Clear selection.
	 */
	public void clearSelection() {
		this.selectionStart = -1;
		this.selectionEnd = -1;
		repaint();
	}
}
