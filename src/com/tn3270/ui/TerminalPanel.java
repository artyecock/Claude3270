package com.tn3270.ui;

import static com.tn3270.constants.ProtocolConstants.CHARSET_APL;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;

import com.tn3270.model.ScreenModel;

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

	public enum CursorStyle {
		BLOCK, UNDERSCORE, I_BEAM
	}

	private CursorStyle cursorStyle = CursorStyle.BLOCK;

	// NEW: Explicit Blink State
	private boolean blinkState = true;

	// Optimization flags
	private boolean hasBlinkingText = false;
	private boolean cursorBlinkEnabled = true;

	private boolean paintingEnabled = true;

	public TerminalPanel(ScreenModel model) {
		this.screenModel = model;

		// Smart Default Font Size
		int fontSize = 14;
		int cols = model.getCols();
		if (cols >= 160)
			fontSize = 9;
		else if (cols >= 132)
			fontSize = 11;

		this.terminalFont = new Font("Monospaced", Font.PLAIN, fontSize);

		setBackground(Color.BLACK);
		setForeground(Color.GREEN);
		setDoubleBuffered(true);
		setFocusable(true);

		// FIX: Disable Swing's TAB traversal so we can use TAB for 3270 navigation
		setFocusTraversalKeysEnabled(false);

		updateSize();

		addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
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

			@Override
			public void mouseReleased(MouseEvent e) {
				selecting = false;
			}
		});

		addMouseMotionListener(new MouseMotionAdapter() {
			@Override
			public void mouseDragged(MouseEvent e) {
				if (selecting) {
					int pos = screenPositionFromMouse(e.getX(), e.getY());
					if (pos >= 0) {
						selectionEnd = pos;
						repaint();
					}
				}
			}
		});
	}

	// --- SCROLLABLE IMPLEMENTATION ---
	@Override
	public Dimension getPreferredScrollableViewportSize() {
		return getPreferredSize();
	}

	@Override
	public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
		return charHeight;
	}

	@Override
	public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
		return charHeight * 10;
	}

	/**
	 * Determines if the panel should force itself to match the Viewport width.
	 * 
	 * REGRESSION NOTE: SCROLLBARS VS BACKGROUND
	 * 
	 * The Fix: We return 'true' (track width) only if the viewport is wider than
	 * our content. This forces the JPanel to stretch and paint its black background
	 * over the entire JScrollPane area, preventing gray/white gaps from the
	 * ScrollPane peering through.
	 * 
	 * However, we must return 'false' if our content is WIDER than the viewport. If
	 * we returned true always, the JScrollPane would never show horizontal
	 * scrollbars because it would think the content fits perfectly.
	 */
	@Override
	public boolean getScrollableTracksViewportWidth() {
		// Only track width (stretch/center) if content fits. If content is huge, return
		// false to allow scrollbars.
		return getParent() instanceof JViewport && getParent().getWidth() >= getPreferredSize().width;
	}

	@Override
	public boolean getScrollableTracksViewportHeight() {
		return getParent() instanceof JViewport && getParent().getHeight() >= getPreferredSize().height;
	}

	// --- AUTO-FIT LOGIC ---
	public void fitToSize(int availableWidth, int availableHeight) {
		if (availableWidth <= 0 || availableHeight <= 0)
			return;

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
		if (isUpdatingSize || screenModel == null)
			return;
		isUpdatingSize = true;
		try {
			Font f = (terminalFont != null) ? terminalFont : getFont();
			if (f == null)
				return;
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

	public void setPaintingEnabled(boolean b) {
		this.paintingEnabled = b;
		if (b)
			repaint(); // Force a refresh when re-enabling
	}

	public void setHasBlinkingText(boolean hasBlink) {
		this.hasBlinkingText = hasBlink;
	}

	public void setCursorBlinkEnabled(boolean enabled) {
		this.cursorBlinkEnabled = enabled;
	}

	public boolean isShowCrosshair() {
		return showCrosshair;
	}

	// NEW: Method called by the Timer
	/*
	 * public void toggleBlink() { this.blinkState = !this.blinkState; repaint(); }
	 */
	public void toggleBlink() {
		this.blinkState = !this.blinkState;

		// 1. If text is blinking, we must repaint the whole screen
		// (or iterate to find specific chars, but full repaint is safer for text)
		if (hasBlinkingText) {
			repaint();
			return;
		}

		// 2. If only the cursor needs to blink:
		// We only repaint if the feature is enabled, the keyboard is unlocked,
		// and the window actually has focus.
		if (cursorBlinkEnabled && !screenModel.isKeyboardLocked() && hasFocus()) {
			repaint();
		}

		// If neither condition is met, this method finishes instantly
		// without triggering the expensive Swing painting machinery.
	}

	/**
	 * Paints the terminal characters.
	 * 
	 * REGRESSION NOTE: SMART SCALING / BLACK BANDS This method includes logic to
	 * eliminate the "Black Bands" (unused viewport background) caused by integer
	 * math (e.g., Window Width 1000px / Char Width 9px = Remainder 10px).
	 * 
	 * Strategy: 1. Calculate the exact pixel width of the character grid (cols *
	 * charWidth). 2. Compare it to the Panel width. 3. 'closeEnoughWidth': If the
	 * difference is small (< 2 chars), we assume the intent is "Auto-Fit". We use
	 * Graphics2D.scale() to stretch the grid to the edges. 4. If the difference is
	 * large, we assume "Manual Mode" (User wants a small font). We DISABLE scaling
	 * and CENTER the grid using calculated margins.
	 */
	@Override
	/**
	 * Paints the terminal characters.
	 * 
	 * REGRESSION NOTE: SMART SCALING / BLACK BANDS This method includes logic to
	 * eliminate the "Black Bands" (unused viewport background) caused by integer
	 * math (e.g., Window Width 1000px / Char Width 9px = Remainder 10px).
	 * 
	 * Strategy: 1. Calculate the exact pixel width of the character grid (cols *
	 * charWidth). 2. Compare it to the Panel width. 3. 'closeEnoughWidth': If the
	 * difference is small (< 2 chars), we assume the intent is "Auto-Fit". We use
	 * Graphics2D.scale() to stretch the grid to the edges. 4. If the difference is
	 * large, we assume "Manual Mode" (User wants a small font). We DISABLE scaling
	 * and CENTER the grid using calculated margins.
	 */
	//@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		if (screenModel == null)
			return;
		if (!paintingEnabled)
			return; // Skip painting if frozen

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
		// If content is roughly the same size as viewport (Auto-Fit), stretch to kill
		// bands.
		// If content is significantly smaller/larger (Manual), Center or Clip.
		boolean closeEnoughWidth = Math.abs(w - gridWidth) < (charWidth * 2);
		boolean closeEnoughHeight = Math.abs(h - gridHeight) < (charHeight * 2);

		if (gridWidth > 0 && gridHeight > 0 && closeEnoughWidth && closeEnoughHeight) {
			scaleX = (double) w / (double) gridWidth;
			scaleY = (double) h / (double) gridHeight;
		} else {
			// Manual Mode: Center content if smaller than viewport
			if (w > gridWidth)
				marginLeft = (w - gridWidth) / 2;
			if (h > gridHeight)
				marginTop = (h - gridHeight) / 2;
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
		byte[] charsets = screenModel.getCharsets();
		Color[] palette = screenModel.getPalette();

		boolean blinkVisible = this.blinkState;

		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				int pos = row * cols + col;
				if (pos >= buffer.length)
					break;

				char c = buffer[pos];
				boolean isAttrByte = screenModel.isFieldStart(pos);

				if (isAttrByte)
					c = ' ';
				if (c == '\0')
					c = ' ';

				byte cs = charsets[pos];
				boolean isAPL = (cs == CHARSET_APL);

				Color fg = getForeground();
				Color bg = getBackground();
				boolean reverse = false;
				boolean underscore = false;
				boolean isHidden = false;

				int fieldStart = screenModel.findFieldStart(pos);
				byte a = (fieldStart >= 0) ? attr[fieldStart] : 0;

				if ((a & 0x0C) == 0x0C) {
					isHidden = true;
					c = ' ';
				}
/*
				if (!isHidden) {
					// FIXED COLOR LOGIC: Handle signed bytes and implement PCOMM "smart blue"
					int charColor = extColors[pos] & 0xFF;
					
					if (charColor > 0 && charColor < palette.length) {
						// Character has explicit SA color - always use it (no override)
						fg = palette[charColor];
					} else if (fieldStart >= 0) {
						int fieldColor = extColors[fieldStart] & 0xFF;
						if (fieldColor > 0 && fieldColor < palette.length) {
							// Use field's explicit color
							fg = palette[fieldColor];
						} else {
							// No explicit colors - apply PCOMM-compatible defaults
							// PCOMM "smart blue" logic: protected+numeric → yellow
							boolean numeric = (a & 0x10) != 0;
							boolean prot = (a & 0x20) != 0;
							boolean high = (a & 0x08) != 0;
							
							if (prot && numeric)
								fg = palette[6];  // Yellow for protected+numeric fields
							else if (prot)
								fg = palette[5];  // Turquoise for protected only
							else if (high)
								fg = palette[2];  // Red for high intensity
							else
								fg = palette[4];  // Green for default
						}
					}
					
					// Handle highlighting
					int hl = highlight[pos] & 0xFF;
					if (hl == 0 && fieldStart >= 0)
						hl = highlight[fieldStart];
					if (hl == 0xF1 && !blinkVisible)
						c = ' ';
					if (hl == 0xF2)
						reverse = true;
					if (hl == 0xF4)
						underscore = true;

					// If this is the attribute byte itself, do not apply visuals to it
					if (isAttrByte) {
						underscore = false;
						reverse = false;
					}
				}
*/
				/*
				if (!isHidden) {
					// FIXED COLOR LOGIC: Handle signed bytes and implement PCOMM "smart blue"
					int charColor = extColors[pos] & 0xFF;
					
					if (charColor > 0 && charColor < palette.length) {
						// Character has explicit SA color - always use it (no override)
						fg = palette[charColor];
					} else if (fieldStart >= 0) {
						int fieldColor = extColors[fieldStart] & 0xFF;
						if (fieldColor > 0 && fieldColor < palette.length) {
							// Use field's explicit color
							fg = palette[fieldColor];
						} else {
							// No explicit colors - apply PCOMM-compatible defaults
							boolean numeric = (a & 0x10) != 0;
							boolean prot = (a & 0x20) != 0;
							boolean high = (a & 0x08) != 0;
							
							// PCOMM "smart blue" logic: protected+numeric → yellow
							// Protected+high (but not numeric) → white (for labels/headers)
							if (prot && numeric)
								fg = palette[6];  // Yellow for protected+numeric fields
							else if (prot && high)
								fg = palette[7];  // White for protected+high (non-numeric)
							else if (prot)
								fg = palette[5];  // Turquoise for protected only
							else if (high)
								fg = palette[2];  // Red for high intensity
							else
								fg = palette[4];  // Green for default
						}
					}
					
					// Handle highlighting
					int hl = highlight[pos] & 0xFF;
					if (hl == 0 && fieldStart >= 0)
						hl = highlight[fieldStart];
					if (hl == 0xF1 && !blinkVisible)
						c = ' ';
					if (hl == 0xF2)
						reverse = true;
					if (hl == 0xF4)
						underscore = true;

					// If this is the attribute byte itself, do not apply visuals to it
					if (isAttrByte) {
						underscore = false;
						reverse = false;
					}
					*/
				/*
				if (!isHidden) {
					// FIXED COLOR LOGIC: Handle signed bytes and implement PCOMM "smart blue"
					//int charColor = extColors[pos] & 0xFF;
					//System.out.println("Char: " + c + String.format("Attr: 0x%02X", a) + ", Row: " + row + ", Col: " + col + ", Pos: " + fieldStart + ", charColor: " + charColor);
					// C8 = Green    1100 1000
					// 60 = Blue     0110 0000
					// 40 = Green    0100 0000
					// E8 = White    1110 1000 correct (prot & high)
					// C8 = Red      1100 1000
					// F0 = Yellow?  1111 0000
					// F8, E8 was white
					// 40 was green
					// 70 was non-display
					// 60 was turquoise
					// C8 supposed to be green, displayed in red
					// F0 was yellow (should be blue?)
					
					// x20 = protected
					// x10 = numeric
					// x08 = high intensity
					// x04 = low
					// Unprotected, normal intensity Green = x04
					// Unprotected, intensified Red = x08
					// Protected, normal intensity Blue = x20 + x04     prot &&
					// Protected, intensified White = x20 + x08         prot && high
					
					Bit 	Mask 	Meaning
					8 	0x80 	Always set
					7 	0x40 	Always set
					6 	0x20 	Protected
					5 	0x10 	Numeric
					4 	0x08 	Intensity (high bit)
					3 	0x04 	Intensity (low bit)
					2 	0x02 	Always clear
					1 	0x01 	Modified 
					
					  
					   * http://www-01.ibm.com/support/knowledgecenter/SSGMGV_3.1.0/com.ibm.cics.
					   * ts31.doc/dfhp3/dfhp3at.htm%23dfhp3at
					   * 
					   * Some terminals support base color without, or in addition to, the extended 
					   * colors included in the extended attributes. There is a mode switch on the 
					   * front of such a terminal, allowing the operator to select base or default 
					   * color. Default color shows characters in green unless field attributes specify
					   * bright intensity, in which case they are white. In base color mode, the 
					   * protection and intensity bits are used in combination to select among four 
					   * colors: normally white, red, blue, and green; the protection bits retain their 
					   * protection functions as well as determining color. (If you use extended color, 
					   * rather than base color, for 3270 terminals, note that you cannot specify 
					   * "white" as a color. You need to specify "neutral", which is displayed as white 
					   * on a terminal.)
					   * 
					   *     Color color = isHighIntensity ? //
        				* isProtected ? WHITE : RED : //
        				* isProtected ? BLUE : GREEN;
					   
					//
					if (charColor > 0 && charColor < palette.length) {
						// Character has explicit SA color
						// BUT: PCOMM "smart blue" override - if it's blue (1) in a protected+numeric field, use yellow
						if (charColor == 1 && fieldStart >= 0) {
							//byte attrib = attr[fieldStart];
							boolean numeric = (a & 0x10) != 0;
							boolean prot = (a & 0x20) != 0;
							if (prot && numeric) {
								fg = palette[6];  // Override blue to yellow for protected+numeric
							} else {
								fg = palette[charColor];  // Use the SA color as-is
							}
						} else {
							fg = palette[charColor];  // Use the SA color as-is
						}
						//
						//test
					    
						boolean numeric = (a & 0x10) != 0;
						boolean prot = (a & 0x20) != 0;
						boolean high = (a & 0x08) != 0;
						
						Color newFg = high ? prot ? palette[7] : palette[2] : prot ? palette[5] : palette[4];
						//commented:
						if (prot && high)
						    fg = palette[7];  // White for protected+high (takes precedence)
						else if (prot && numeric && !high)  
						    fg = palette[6];  // Yellow for protected+numeric (without high)
						else if (prot && !high)
						    fg = palette[5];  // Turquoise for protected only
						else if (!prot && high)
						    fg = palette[2];  // Red for high intensity
						else
						    fg = palette[4];  // Green for default
						//
						//if (fg != newFg) System.out.println("1: fg: " + fg + ", newfg: " + newFg + ", CharColor: " + charColor);
						fg = newFg;
						//test
					} else if (fieldStart >= 0) {
						int fieldColor = extColors[fieldStart] & 0xFF;
				
						if (fieldColor > 0 && fieldColor < palette.length) {
							// Field has explicit color
						    // Field has explicit color
						    //if (fieldColor == 1) {  // Blue
						    //    boolean numeric = (a & 0x10) != 0;
						    //    boolean prot = (a & 0x20) != 0;
						    //    if (prot && numeric) {
						    //        fg = palette[6];  // Override blue to yellow
						    //    } else {
						    //        fg = palette[fieldColor];
						    //    }
						    //} else {
						     //   fg = palette[fieldColor];
						    //}
						    //commented
							// Same override: blue field color in protected+numeric → yellow
							if (fieldColor == 1) {
								//byte attrib = attr[fieldStart];
								boolean numeric = (a & 0x10) != 0;
								boolean prot = (a & 0x20) != 0;
								if (prot && numeric) {
								//	fg = palette[6];  // Override blue to yellow
								} else {
									fg = palette[fieldColor];
								}
							} else {
								fg = palette[fieldColor];
							}
							//
							//test
							boolean numeric = (a & 0x10) != 0;
							boolean prot = (a & 0x20) != 0;
							boolean high = (a & 0x08) != 0;
							
							Color newFg = high ? prot ? palette[7] : palette[2] : prot ? palette[5] : palette[4];
							if (fg != newFg) System.out.println("2: fg: " + fg + ", newfg: " + newFg + "FieldColor: " + fieldColor);
							fg = newFg;
							
						    // Field has explicit color
						    if (fieldColor == 1) {  // Blue
						        //boolean numeric = (a & 0x10) != 0;
						        //boolean prot = (a & 0x20) != 0;
						        if (prot && numeric) {
						            fg = palette[6];  // Override blue to yellow
						        } else {
						            fg = palette[fieldColor];
						            fg = newFg;
						        }
						    } else {
						        fg = palette[fieldColor];
						        fg = newFg;
						    }
							//commented
							if (prot && high)
							    fg = palette[7];  // White for protected+high (takes precedence)
							else if (prot && numeric && !high)  
							    fg = palette[6];  // Yellow for protected+numeric (without high)
							else if (prot && !high)
							    fg = palette[5];  // Turquoise for protected only
							else if (high && !prot)
							    fg = palette[2];  // Red for high intensity
							else
							    fg = palette[4];  // Green for default
							    //
							//test
						} else {
							// No explicit colors - apply PCOMM-compatible defaults
							boolean numeric = (a & 0x10) != 0;
							boolean prot = (a & 0x20) != 0;
							boolean high = (a & 0x08) != 0;
							
							Color newFg = high ? prot ? palette[7] : palette[2] : prot ? palette[5] : palette[4];
							if (fg != newFg) System.out.println("3: fg: " + fg + ", newfg: " + newFg);
							fg = newFg;
							
							//commented
							if (prot && numeric)
								fg = palette[6];  // Yellow for protected+numeric fields
							else if (prot && high)
								fg = palette[7];  // White for protected+high (non-numeric)
							else if (prot)
								fg = palette[5];  // Turquoise for protected only
							else if (high)
								fg = palette[2];  // Red for high intensity
							else
								fg = palette[4];  // Green for default
							//
							//commented
							if (prot && high)
							    fg = palette[7];  // White for protected+high (takes precedence)
							else if (prot && numeric && !high)  
							    fg = palette[6];  // Yellow for protected+numeric (without high)
							else if (prot && !high)
							    fg = palette[5];  // Turquoise for protected only
							else if (high && !prot)
							    fg = palette[2];  // Red for high intensity
							else
							    fg = palette[4];  // Green for default
							//
						}
					}
					*/
					/*
					if (charColor > 0 && charColor < palette.length) {
					    // Character has explicit SA color - USE IT, no overrides based on basic attributes
					    fg = palette[charColor];
					    
					} else if (fieldStart >= 0) {
					    int fieldColor = extColors[fieldStart] & 0xFF;
					    if (fieldColor > 0 && fieldColor < palette.length) {
					        // Field has explicit color - USE IT
					        fg = palette[fieldColor];
					    } else {
					        // No explicit colors - use basic attribute to determine color
					        boolean prot = (a & 0x20) != 0;
					        boolean high = (a & 0x08) != 0;
					        
					        fg = high ? (prot ? palette[7] : palette[2]) : (prot ? palette[5] : palette[4]);
					    }
					}
					*/
					if (!isHidden) {
						/*
					    int charColor = extColors[pos] & 0xFF;
					    
					    // 1. Calculate Base Attributes
					    boolean numeric = (a & 0x10) != 0;
					    boolean prot = (a & 0x20) != 0;
					    boolean high = (a & 0x08) != 0;

					    // 2. Calculate the Standard Base Color (Your "newFg" logic)
					    // We calculate this once, but only use it if Extended Colors don't override it.
					    Color baseFg = high ? (prot ? palette[7] : palette[2]) : (prot ? palette[5] : palette[4]);

					    if (charColor > 0 && charColor < palette.length) {
					        // --- CASE A: Explicit Character Color (Overrides Base) ---
					        
					        // PCOMM "Smart Blue" Override:
					        // If Host asks for Blue (1) in a Prot/Num field, use Yellow.
					        if (charColor == 1 && prot && numeric) {
					            fg = palette[6]; 
					            //fg = palette[charColor];
					        } else {
					            fg = palette[charColor];
					        }

					    } else if (fieldStart >= 0) {
					        // --- CASE B: Inherited Field Color (Overrides Base) ---
					        int fieldColor = extColors[fieldStart] & 0xFF;

					        if (fieldColor > 0 && fieldColor < palette.length) {
					            // Field has explicit color
					            if (fieldColor == 1 && prot && numeric) {
					                fg = palette[6]; // PCOMM Smart Blue Override
					            } else {
					                fg = palette[fieldColor];
					            }
					        } else {
					            // --- CASE C: No Extended Colors ---
					            // Fallback to the Base Color we calculated above
					            fg = baseFg;
					        }
					    } else {
					         // (Rare) No attributes at all (Unformatted screen) -> Green
					         fg = palette[4];
					    }
					*/
						/*
					    int charColor = extColors[pos] & 0xFF;

					    // 1. Calculate Base Attributes (The Discriminators)
					    // The "Numeric" bit is the key differentiator between "RECALL" and the Bars.
					    boolean numeric = (a & 0x10) != 0;
					    boolean prot = (a & 0x20) != 0;
					    boolean high = (a & 0x08) != 0;

					    // 2. Calculate Base Color (Fallback for Case C)
					    Color baseFg = high ? (prot ? palette[7] : palette[2]) : (prot ? palette[5] : palette[4]);

					    // 3. Determine Final Color
					    if (charColor > 0 && charColor < palette.length) {
					        // --- CASE A: Explicit Character Color ---
					        
					        // PCOMM RULE: Protected + Numeric + Blue (1) -> Map to Yellow (6)
					        // "RECALL" is Alpha, so it fails this check and stays Blue.
					        // The Bars are Numeric, so they pass this check and become Yellow.
					        if (charColor == 1 && prot && numeric) {
					            fg = palette[6]; 
					        } else {
					            fg = palette[charColor];
					        }

					    } else if (fieldStart >= 0) {
					        // --- CASE B: Field Extended Color ---
					        int fieldColor = extColors[fieldStart] & 0xFF;

					        if (fieldColor > 0 && fieldColor < palette.length) {
					            // Apply Same PCOMM Rule to Field Colors
					            if (fieldColor == 1 && prot && numeric) {
					                fg = palette[6]; 
					            } else {
					                fg = palette[fieldColor];
					            }
					        } else {
					            // --- CASE C: Standard Attributes (No Extended Color) ---
					            fg = baseFg;
					        }
					    } else {
					         fg = baseFg; 
					    }
					    */
						/*
					    int charColor = extColors[pos] & 0xFF;

					    // 1. Calculate Base Attributes
					    boolean numeric = (a & 0x10) != 0;
					    boolean prot = (a & 0x20) != 0;
					    boolean high = (a & 0x08) != 0;

					    // 2. Calculate Base Color (Fallback for Case C)
					    Color baseFg = high ? (prot ? palette[7] : palette[2]) : (prot ? palette[5] : palette[4]);

					    // 3. Determine Final Color
					    if (charColor > 0 && charColor < palette.length) {
					        // --- CASE A: Explicit Character Color (SA Order) ---
					        // The Host explicitly set this character's color (e.g. SA Color=Blue).
					        // We MUST respect this, even if the field is Prot+Num.
					        // This ensures "RECALL" (preceded by SA Color Blue) appears Blue.
					        fg = palette[charColor];

					    } else if (fieldStart >= 0) {
					        // --- CASE B: Field Extended Color ---
					        int fieldColor = extColors[fieldStart] & 0xFF;

					        if (fieldColor > 0 && fieldColor < palette.length) {
					            // Apply PCOMM "Smart Blue" Override HERE.
					            // If the Field is defined as Blue, but is Prot+Num (typical border),
					            // remap to Yellow for visibility.
					            // This ensures the Vertical Bars (inheriting Field Blue) appear Yellow.
					            if (fieldColor == 1 && prot && numeric) {
					                fg = palette[6]; // Force Yellow
					            } else {
					                fg = palette[fieldColor];
					            }
					        } else {
					            // --- CASE C: Standard Attributes (No Extended Color) ---
					            fg = baseFg;
					        }
					    } else {
					         // Fallback for unformatted screens
					         fg = baseFg; 
					    }
					    */
						/*
					    int charColor = extColors[pos] & 0xFF;

					    // 1. Calculate Base Attributes (The Discriminators)
					    boolean numeric = (a & 0x10) != 0;
					    boolean prot = (a & 0x20) != 0;
					    boolean high = (a & 0x08) != 0;

					    // 2. Calculate Base Color (Fallback for Case C)
					    Color baseFg = high ? (prot ? palette[7] : palette[2]) : (prot ? palette[5] : palette[4]);

					    // 3. Determine Final Color
					    if (charColor > 0 && charColor < palette.length) {
					        // --- CASE A: Explicit Character Color (SA Order) ---
					        
					        // RESTORED PCOMM "Smart Blue" Override:
					        // Apply the override here too, but rely on the 'numeric' check to protect text.
					        // - Bars (Prot + Num + Blue) -> Will trigger this and become Yellow.
					        // - "RECALL" (Prot + Alpha + Blue) -> Will fail 'numeric' check and stay Blue.
					        if (charColor == 1 && prot && numeric) {
					            fg = palette[6]; // Force Yellow
					        } else {
					            fg = palette[charColor]; // Respect Host's Explicit Color
					        }

					    } else if (fieldStart >= 0) {
					        // --- CASE B: Field Extended Color ---
					        int fieldColor = extColors[fieldStart] & 0xFF;

					        if (fieldColor > 0 && fieldColor < palette.length) {
					            // Apply Same PCOMM Override to Field Colors
					            if (fieldColor == 1 && prot && numeric) {
					                fg = palette[6]; // Force Yellow
					            } else {
					                fg = palette[fieldColor];
					            }
					        } else {
					            // --- CASE C: Standard Attributes (No Extended Color) ---
					            fg = baseFg;
					        }
					    } else {
					         // Fallback for unformatted screens
					         fg = baseFg; 
					    }
					    */
					    /*
					    int charColor = extColors[pos] & 0xFF;

					    // 1. Calculate Base Attributes
					    boolean numeric = (a & 0x10) != 0;
					    boolean prot = (a & 0x20) != 0;
					    boolean high = (a & 0x08) != 0;

					    // 2. Calculate Base Color (Fallback for Case C)
					    Color baseFg = high ? (prot ? palette[7] : palette[2]) : (prot ? palette[5] : palette[4]);

					    // 3. Determine Final Color
					    if (charColor > 0 && charColor < palette.length) {
					        // --- CASE A: Explicit Character Color (SA Order) ---
					        // The Host EXPLICITLY requested this color for this character.
					        // We treat this as a "Strong" request and DO NOT apply the PCOMM override.
					        // Result: "RECALL" (Explicit SA Blue) stays Blue.
					        fg = palette[charColor];

					    } else if (fieldStart >= 0) {
					        // --- CASE B: Field Extended Color ---
					        int fieldColor = extColors[fieldStart] & 0xFF;

					        if (fieldColor > 0 && fieldColor < palette.length) {
					            // --- PCOMM "Smart Blue" Override (Weak Request) ---
					            // If the FIELD is Blue, but it is Protected + Numeric (like a border),
					            // we override it to Yellow for readability.
					            // Result: Vertical Bars (Inherited Field Blue) become Yellow.
					            if (fieldColor == 1 && prot && numeric) {
					                fg = palette[6]; // Force Yellow
					            } else {
					                fg = palette[fieldColor];
					            }
					        } else {
					            // --- CASE C: Standard Attributes (No Extended Color) ---
					            fg = baseFg;
					        }
					    } else {
					         // Fallback for unformatted screens
					         fg = baseFg; 
					    }
					    */
						/*
					    int charColor = extColors[pos] & 0xFF;

					    // 1. Calculate Base Attributes
					    boolean numeric = (a & 0x10) != 0;
					    boolean prot = (a & 0x20) != 0;
					    boolean high = (a & 0x08) != 0;

					    // 2. Calculate Base Color (Fallback)
					    Color baseFg = high ? (prot ? palette[7] : palette[2]) : (prot ? palette[5] : palette[4]);

					    // 3. Determine Final Color
					    if (charColor > 0 && charColor < palette.length) {
					        // --- CASE A: Explicit Character Color ---
					        
					        // PCOMM "SMART BLUE" REFINED:
					        // Only remap Blue->Yellow if:
					        // 1. It is Blue (1).
					        // 2. It is Protected & Numeric (0xF0).
					        // 3. It is NOT a standard letter or digit. (This protects labels like "RECALL").
					        if (charColor == 1 && prot && numeric && !Character.isLetterOrDigit(c)) {
					             fg = palette[6]; // Force Yellow (for Borders/Bars)
					        } else {
					             fg = palette[charColor]; // Respect Explicit Color (for Text)
					        }

					    } else if (fieldStart >= 0) {
					        // --- CASE B: Field Extended Color ---
					        int fieldColor = extColors[fieldStart] & 0xFF;

					        if (fieldColor > 0 && fieldColor < palette.length) {
					            // Apply same Logic to Field Colors
					            //if (fieldColor == 1 && prot && numeric && !Character.isLetterOrDigit(c)) {
					        	if (fieldColor == 1 && prot && numeric) {	
					                fg = palette[6]; // Force Yellow
					            } else {
					                fg = palette[fieldColor];
					            }
					        } else {
					            // --- CASE C: Standard Attributes ---
					            fg = baseFg;
					        }
					    } else {
					         fg = baseFg; 
					    }
					    */
					 /*
					    int charColor = extColors[pos] & 0xFF;

					    // 1. Calculate Base Attributes
					    boolean numeric = (a & 0x10) != 0;
					    boolean prot = (a & 0x20) != 0;
					    boolean high = (a & 0x08) != 0;

					    // 2. Calculate Base Color (Fallback)
					    Color baseFg = high ? (prot ? palette[7] : palette[2]) : (prot ? palette[5] : palette[4]);

					    // 3. Define the PCOMM "Smart Blue" Target
					    // We only override if it is a Vertical Bar.
					    // We check both '¦' (Broken Bar) and '|' (Pipe) to account for different code page mappings.
					    boolean isVerticalBar = (c == '¦' || c == '|');

					    // 4. Determine Final Color
					    if (charColor > 0 && charColor < palette.length) {
					        // --- CASE A: Explicit Character Color ---
					        
					        // PCOMM Override: Only map Blue->Yellow if it is specifically a Vertical Bar.
					        // - "RECALL" (Blue) -> Not a bar -> Stays Blue.
					        // - "*" or "@" (Blue) -> Not a bar -> Stays Blue.
					        // - "¦" (Blue + Prot + Num) -> Is a bar -> Becomes Yellow.
					        if (charColor == 1 && prot && numeric && isVerticalBar) {
					             fg = palette[6]; // Force Yellow
					        } else {
					             fg = palette[charColor];
					        }

					    } else if (fieldStart >= 0) {
					        // --- CASE B: Field Extended Color ---
					        int fieldColor = extColors[fieldStart] & 0xFF;

					        if (fieldColor > 0 && fieldColor < palette.length) {
					            // Apply Same Logic to Field Colors
					            if (fieldColor == 1 && prot && numeric && isVerticalBar) {
					                fg = palette[6]; // Force Yellow
					            } else {
					                fg = palette[fieldColor];
					            }
					        } else {
					            // --- CASE C: Standard Attributes ---
					            fg = baseFg;
					        }
					    } else {
					         fg = baseFg; 
					    }
					    */
						// x20 = protected
						// x10 = numeric
						// x08 = high intensity
						// x04 = low
						// Unprotected, normal intensity Green = x04
						// Unprotected, intensified Red = x08
						// Protected, normal intensity Blue = x20 + x04     prot &&
						// Protected, intensified White = x20 + x08         prot && high
					    int charColor = extColors[pos] & 0xFF;
					    
					    // 1. Calculate Base Attributes
					    boolean numeric = (a & 0x10) != 0;
					    boolean prot = (a & 0x20) != 0;
					    boolean high = (a & 0x08) != 0;
					    
					    boolean isVerticalBar = (c == '¦' || c == '|');

					    // 2. Base Color
					    Color baseFg = high ? (prot ? palette[7] : palette[2]) : (prot ? palette[5] : palette[4]);

					    // 3. Final Decision
					    if (charColor > 0 && charColor < palette.length) {
					        // --- CASE A: Explicit Character Color (SA Order) ---
					        // "RECALL" hits this. Host set explicit Blue. We trust it.
					        fg = palette[charColor];

					    } else if (fieldStart >= 0) {
					        // --- CASE B: Inherited Field Color ---
					        int fieldColor = extColors[fieldStart] & 0xFF;

					        if (fieldColor > 0 && fieldColor < palette.length) {
					            // Bars hit this. They inherit Blue.
					            // Since they are Inherited + Prot + Num, we apply the override.
					            if (fieldColor == 1 && prot && numeric && isVerticalBar) {
					            //if (fieldColor == 1 && prot && numeric && high) {
					                fg = palette[6]; // Force Yellow
					                System.out.println(String.format("Forcing char '%c' at pos=%d num=%s prot=%s high=%s", c, pos, numeric, prot, high));
					            } else {
					                fg = palette[fieldColor];
					            }
					        } else {
					            // --- CASE C: Standard Attributes ---
					            fg = baseFg;
					        }
					    } else {
					         fg = baseFg; 
					    }
					    //System.out.println("Pos: " + pos + ", Color attribute: " + charColor + ", Rendered color: " + fg + " (Base color " + baseFg +")");
					    //System.out.println(String.format("Writing char '%c' at pos=%d Color attribute: %s Rendered color: %s (Base color: %s)", c, pos, charColor, fg, baseFg));
					// Handle highlighting
					int hl = highlight[pos] & 0xFF;
					if (hl == 0 && fieldStart >= 0)
						hl = highlight[fieldStart];
					if (hl == 0xF1 && !blinkVisible)
						c = ' ';
					if (hl == 0xF2)
						reverse = true;
					if (hl == 0xF4)
						underscore = true;

					// If this is the attribute byte itself, do not apply visuals to it
					if (isAttrByte) {
						underscore = false;
						reverse = false;
					}
				
					// DEBUG: Log vertical bar colors
					/*
					if (c == '¦' && row >= 5 && row <= 12) {
					    System.out.println(String.format(
					        "Bar at pos %d (row %d, col %d): charColor=%d fieldStart=%d fieldColor=%d prot=%b numeric=%b high=%b finalColor=%s",
					        pos, row, col, charColor, fieldStart,
					        (fieldStart >= 0 ? extColors[fieldStart] & 0xFF : -1),
					        (a & 0x20) != 0, (a & 0x10) != 0, (a & 0x08) != 0,
					        fg.toString()
					    ));
					}
					*/
				}
				
				
				if (isPosSelected(pos)) {
					fg = Color.WHITE;
					bg = new Color(0, 120, 215);
					reverse = false;
				}

				int x = marginLeft + (col * charWidth);
				int y = marginTop + (row * charHeight);

				if (reverse) {
					Color tmp = fg;
					fg = bg;
					bg = tmp;
				}

				if (!bg.equals(getBackground())) {
					g2d.setColor(bg);
					g2d.fillRect(x, y, charWidth, charHeight);
				}

				g2d.setColor(fg);

				// Display vertical bars as tall lines
				if (c == '|') {
					int centerX = x + (charWidth / 2);
					g2d.drawLine(centerX, y, centerX, y + charHeight);
					continue; // Skip standard string drawing
				}

				// Draw the character
				g2d.drawString(String.valueOf(c), x, y + charAscent);
				
				//System.out.println(String.format("Writing char '%c' at pos=%d Color attribute: %s Rendered color: %s(Base color: %s)", c, pos, charColor, fg. baseFg));
				
				if (underscore && !isHidden) {
					g2d.fillRect(x, y + charHeight - 1, charWidth, 1);
				}
			}
		}

		// 5. DRAW CURSOR & CROSSHAIR
		if (!screenModel.isKeyboardLocked() && hasFocus()) {
			int cPos = screenModel.getCursorPos();
			if (cPos >= 0 && cPos < buffer.length) {
				int cRow = cPos / cols;
				int cCol = cPos % cols;
				int cx = marginLeft + (cCol * charWidth);
				int cy = marginTop + (cRow * charHeight);

				// --- CROSSHAIR LOGIC ---
				if (showCrosshair) {
					Color cc = screenModel.getCursorColor();
					g2d.setColor(new Color(cc.getRed(), cc.getGreen(), cc.getBlue(), 40));

					int midX = cx + (charWidth / 2);
					int midY = cy + (charHeight / 2);

					g2d.drawLine(0, midY, getWidth(), midY);
					g2d.drawLine(midX, 0, midX, getHeight());
				}

				// --- CURSOR DRAWING LOGIC ---
				if (!cursorBlinkEnabled || blinkState) {
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
		}

		g2d.setTransform(oldTransform);
	}
	
	protected void paintComponentWorks(Graphics g) {
		super.paintComponent(g);
		if (screenModel == null)
			return;
		if (!paintingEnabled)
			return; // Skip painting if frozen

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
		// If content is roughly the same size as viewport (Auto-Fit), stretch to kill
		// bands.
		// If content is significantly smaller/larger (Manual), Center or Clip.
		boolean closeEnoughWidth = Math.abs(w - gridWidth) < (charWidth * 2);
		boolean closeEnoughHeight = Math.abs(h - gridHeight) < (charHeight * 2);

		if (gridWidth > 0 && gridHeight > 0 && closeEnoughWidth && closeEnoughHeight) {
			scaleX = (double) w / (double) gridWidth;
			scaleY = (double) h / (double) gridHeight;
		} else {
			// Manual Mode: Center content if smaller than viewport
			if (w > gridWidth)
				marginLeft = (w - gridWidth) / 2;
			if (h > gridHeight)
				marginTop = (h - gridHeight) / 2;
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
		byte[] charsets = screenModel.getCharsets(); // NEW: Get Charset Array
		Color[] palette = screenModel.getPalette();

		// boolean blinkVisible = (System.currentTimeMillis() / 500) % 2 == 0;
		boolean blinkVisible = this.blinkState;

		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				int pos = row * cols + col;
				if (pos >= buffer.length)
					break;

				char c = buffer[pos];
				// byte charset = screenModel.getCharset(pos); // Get the context
				// byte charset = charsets[pos];
				// if (screenModel.isFieldStart(pos)) c = ' ';
				boolean isAttrByte = screenModel.isFieldStart(pos);

				if (isAttrByte)
					c = ' ';
				if (c == '\0')
					c = ' ';

				// --- NEW: APL/Charset Mapping ---
				// If the charset for this position indicates APL (0xF1),
				// we assume 'c' already holds the correct mapped char from processOrders
				// (e.g. \u2502). We use this flag for custom rendering logic.
				byte cs = charsets[pos];
				boolean isAPL = (cs == CHARSET_APL);

				Color fg = getForeground();
				Color bg = getBackground();
				boolean reverse = false;
				boolean underscore = false;
				boolean isHidden = false;

				int fieldStart = screenModel.findFieldStart(pos);
				byte a = (fieldStart >= 0) ? attr[fieldStart] : 0;

				if ((a & 0x0C) == 0x0C) {
					isHidden = true;
					c = ' ';
				}

				if (!isHidden) {
				    // Handle signed bytes properly by masking with 0xFF
				    int charColor = extColors[pos] & 0xFF;
				    if (charColor > 0 && charColor < palette.length) {
						fg = palette[extColors[pos]];
					} else if (fieldStart >= 0) {
						int fieldColor = extColors[fieldStart] & 0xFF;
						if (fieldColor > 0 && fieldColor < palette.length)
							fg = palette[fieldColor];
						else {
							boolean high = (a & 0x08) != 0;
							boolean prot = (a & 0x20) != 0;
							if (prot && high)
							    fg = palette[7];
							else if (prot)
								fg = palette[5];
							else if (high)
								fg = palette[2];
							else
								fg = palette[4];
						}
					}
					
					// FIX: HANDLE SIGNED BYTES
					// Java bytes are signed (-128 to 127). 0xF1 becomes -15.
					// We must mask with 0xFF to compare correctly with integer literals like 0xF1.
					int hl = highlight[pos] & 0xFF;
					if (hl == 0 && fieldStart >= 0)
						hl = highlight[fieldStart];
					
					// FIX: Use the class field blinkVisible
					if (hl == 0xF1 && !blinkVisible)
						c = ' ';
					if (hl == 0xF2)
						reverse = true;
					if (hl == 0xF4)
						underscore = true;

					// If this is the attribute byte itself, do not apply visuals to it
					if (isAttrByte) {
						underscore = false;
						reverse = false;
						// Note: We don't disable blink here because 'c' is already ' ',
						// blinking a space is invisible anyway.
					}
				}

				if (isPosSelected(pos)) {
					fg = Color.WHITE;
					bg = new Color(0, 120, 215);
					reverse = false;
				}

				int x = marginLeft + (col * charWidth);
				int y = marginTop + (row * charHeight);

				if (reverse) {
					Color tmp = fg;
					fg = bg;
					bg = tmp;
				}

				if (!bg.equals(getBackground())) {
					g2d.setColor(bg);
					g2d.fillRect(x, y, charWidth, charHeight);
				}
				
				// -----------------------------------------------------------------------------
				// IBM PCOMM COMPATIBILITY FIX: "SMART BLUE" REMAPPING
				// -----------------------------------------------------------------------------
				// Problem: The 3270 "Blue" color (0xF1) is often illegible on black backgrounds.
				// Host applications (like CMS/ISPF) often define "Protected" fields (labels)
				// with "Blue" extended color, but also set the "High Intensity" basic attribute.
				//
				// Architecture vs. Reality:
				// - 3270 Arch (GA23-0059): Extended Color (Blue) takes precedence. Result: Dark Blue.
				// - IBM PCOMM Behavior: Detects "Protected + Blue" (and often High Intensity)
				//   and dynamically remaps it to Yellow (or White) to ensure the text is readable.
				//   This is effectively a legacy fallback to 3278 (Monochrome) logic where
				//   "Protected High Intensity" = White/Yellow.
				//
				// Logic:
				// If (Extended Color == Blue) AND (Basic Attribute == Protected):
				//     Force Color = Yellow (Palette Index 6)
				//
				// Reference:
				// IBM Personal Communications User's Guide > Changing Colors > Attribute Colors
				// https://www.ibm.com/docs/en/pcomm/14.0?topic=colors-changing
				// -----------------------------------------------------------------------------
				
				// 
				// PCOMM "SMART BLUE" LOGIC:
			    // We map Extended Blue (F1) to Yellow ONLY if the field is:
			    // 1. Protected (0x20)
			    // 2. Numeric   (0x10)  <-- The critical differentiator
			    //
			    // Evidence:
			    // - FILELIST headers (0x60 = Prot + Alpha) -> Display BLUE (Standard)
			    // - ISPF/CMS Labels  (0xF0 = Prot + Num)   -> Display YELLOW (Remapped)
			    //
				
				// 1. Get the color stored for this character
				byte colorCode = extColors[pos];

				// 2. Resolve default RGB from palette
				//Color fgColor = screenModel.getPalette()[colorCode & 0x07];

				// 3. PCOMM COMPATIBILITY FIX
				// Only affects BLUE (F1) text. (0xF1 must be normalized to 0x01)
				//if (colorCode == (byte)0xF1) {
				if (colorCode == (byte)0x01) {
				    // Find the field attribute to see if it is Protected
				    int fieldStartPos = -1;
				    int size = screenModel.getSize();
				    
				    // Scan backwards to find the attribute byte
				    for (int offset = 0; offset < size; offset++) {
				        int probe = (pos - offset + size) % size;
				        if (screenModel.isFieldStart(probe)) {
				            fieldStartPos = probe;
				            break;
				        }
				    }

				    if (fieldStartPos != -1) {
				        byte fieldAttribute = attr[fieldStartPos];
				        
				        // PCOMM LOGIC REFINED:
				        // Only remap to Yellow if it is PROTECTED (0x20) AND HIGH INTENSITY (0x08).
				        // 0x60 (Prot, Normal) -> Stays Blue (FILELIST header)
				        // 0xF0 (Prot, High)   -> Becomes Yellow (RECALL label)
				        
				        boolean isProtected = (fieldAttribute & 0x20) != 0;
				        boolean isNumeric   = (fieldAttribute & 0x10) != 0;

				        // Map "Protected Numeric Blue" to Yellow
				        if (isProtected && isNumeric) {
				            fg = screenModel.getPalette()[6]; // Yellow
				        }
				    }
				}

				g2d.setColor(fg);
				// g2d.drawString(String.valueOf(c), x, y + charAscent);

				// new
				// If it's a pipe AND we are in APL context, make it tall
				//if (c == '|' && isAPL) {
				if (c == '|') {      // Just display vertical bars as "tall", no matter what
					/*
					// OPTION A: Swap to Unicode Box Drawing char
					// (Simplest, assumes font supports U+2502)
					// c = '\u2502';

					// OPTION B: Manual Drawing (If you want "Continuous" lines)
					// If you want lines to touch exactly, fonts often leave gaps.
					// You can draw a line manually:

					// int cx = col * charWidth;
					// int cy = row * charHeight;
					g2d.setColor(fg); // Use current foreground

					// FIX: Use 'x' and 'y' (which include margins), not raw col/row calcs
					int centerX = x + (charWidth / 2);

					// Draw line down the exact center, full height
					g2d.drawLine(centerX, y, centerX, y + charHeight);

					// Draw line down the exact center
					// g2d.drawLine(cx + charWidth/2, cy, cx + charWidth/2, cy + charHeight);
					continue; // Skip standard string drawing
					*/
					g2d.setColor(fg);
					
					int centerX = x + (charWidth / 2);
					
					// Define thickness (2 pixels is usually perfect for a solid look)
					// Note: Since we are using g2d.scale() earlier, this 2 will 
					// automatically scale up on High-DPI screens.
					int barWidth = 2; 
					
					// Calculate start X to center the bar
					int barX = centerX - (barWidth / 2);
					
					// Draw full height rectangle
					g2d.fillRect(barX, y, barWidth, charHeight);
					
					continue; // Skip standard string drawing
				}

				// Draw the character
				g2d.drawString(String.valueOf(c), x, y + charAscent);
				// new
				// --- NEW: Custom Rendering for APL Vertical Bar ---
				// If it's the Box Drawings Vertical (\u2502), draw a manual line
				// to ensure it connects vertically without font gaps.
				/*
				 * if (c == '\u2502' || c == '|') { // Calculate center of cell int centerX = x
				 * + (charWidth / 2); // Draw full height (y to y+height) g2d.drawLine(centerX,
				 * y, centerX, y + charHeight); } else { g2d.drawString(String.valueOf(c), x, y
				 * + charAscent); }
				 */
				if (underscore && !isHidden) {
					// g2d.drawLine(x, y + charAscent + 2, x + charWidth, y + charAscent + 2);

					// FIX: Draw line at the absolute bottom of the cell (height - 1).
					// Previous logic (charAscent + 2) pushed the line into the next row,
					// causing it to be overwritten by the next row's background fill.
					// int lineY = y + charHeight - 1;
					// g2d.drawLine(x, lineY, x + charWidth, lineY);
					// FIX 3: USE FILLRECT FOR UNDERSCORE
					// drawLine can be inconsistent with sub-pixel scaling.
					// fillRect guarantees a 1px (or scaled equivalent) line.
					// Anchored at the very bottom of the cell.

					// At some point, we may decide NOT to underscore NULL characters
					// which means saving the raw character so that we can test it here:
					// FIX: Don't underline Nulls, even if they render as spaces.
					// Only underline if it's a real character or a real space (0x40).
					// if (rawChar != '\0' && !screenModel.isFieldStart(pos)) {
					g2d.fillRect(x, y + charHeight - 1, charWidth, 1);
				}
			}
		}

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

				// --- CURSOR DRAWING LOGIC ---
				// START CHANGE: Only draw if Blink is DISABLED or we are in the VISIBLE phase
				if (!cursorBlinkEnabled || blinkState) {
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
		}

		g2d.setTransform(oldTransform);
	}

	// --- MOUSE COORDINATE MAPPING ---
	private int screenPositionFromMouse(int x, int y) {
		if (screenModel == null)
			return -1;
		int cols = screenModel.getCols();
		int rows = screenModel.getRows();
		int gridWidth = cols * charWidth;
		int gridHeight = rows * charHeight;
		if (gridWidth == 0 || gridHeight == 0)
			return -1;

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
			if (w > gridWidth)
				marginLeft = (w - gridWidth) / 2;
			if (h > gridHeight)
				marginTop = (h - gridHeight) / 2;
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
		if (selectionStart < 0 || selectionEnd < 0)
			return false;
		int s = Math.min(selectionStart, selectionEnd);
		int e = Math.max(selectionStart, selectionEnd);
		return pos >= s && pos <= e;
	}

	public void setCursorStyle(CursorStyle style) {
		this.cursorStyle = style;
		repaint();
	}

	public Font getTerminalFont() {
		return terminalFont;
	}

	public void clearSelection() {
		selectionStart = -1;
		selectionEnd = -1;
		repaint();
	}

	public void selectAll() {
		selectionStart = 0;
		selectionEnd = screenModel.getSize() - 1;
		repaint();
	}

	public String getSelectedText() {
		if (selectionStart < 0 || selectionEnd < 0)
			return "";
		int s = Math.min(selectionStart, selectionEnd);
		int e = Math.max(selectionStart, selectionEnd);
		StringBuilder sb = new StringBuilder();
		int cols = screenModel.getCols();
		for (int i = s; i <= e; i++) {
			char c = screenModel.getChar(i);
			sb.append(c == '\0' ? ' ' : c);
			if ((i + 1) % cols == 0)
				sb.append('\n');
		}
		return sb.toString().trim();
	}

	public boolean hasSelection() {
		return selectionStart >= 0 && selectionEnd >= 0;
	}
}
