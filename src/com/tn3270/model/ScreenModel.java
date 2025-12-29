package com.tn3270.model;

import java.awt.Color;
import java.awt.Dimension;
import java.util.Arrays;
import java.util.Map;

public class ScreenModel {
	private String modelName;
	private int rows;
	private int cols;
	private int primaryRows;
	private int primaryCols;
	private int alternateRows;
	private int alternateCols;
	private boolean useAlternateSize = true;

	private char[] buffer;
	private byte[] attributes;
	private byte[] extendedColors;
	private byte[] highlighting;
	private byte[] charsets; // NEW: Stores Character Set ID (e.g. 0xF1 for APL)

	private int cursorPos = 0;

	// Defaults for standard 3270 colors
	private Color[] palette = { Color.BLACK, Color.BLUE, Color.RED, Color.MAGENTA, Color.GREEN, Color.CYAN,
			Color.YELLOW, Color.WHITE };

	private Color screenBackground = Color.BLACK;
	private Color defaultForeground = Color.GREEN;
	private Color cursorColor = Color.WHITE;

	private boolean keyboardLocked = false;
	private boolean insertMode = false;

	// Temp holders for current processing context
	private byte currentColor = 0;
	private byte currentHighlight = 0;
	private byte currentCharset = 0; 

	public ScreenModel(String modelName, Map<String, Dimension> models) {
		this.modelName = modelName;
		Dimension dim = models.get(modelName);
		if (dim == null)
			dim = new Dimension(80, 24);

		this.primaryCols = 80;
		this.primaryRows = 24;
		this.alternateCols = dim.width;
		this.alternateRows = dim.height;

		this.rows = alternateRows;
		this.cols = alternateCols;

		int size = Math.max(4000, rows * cols);
		buffer = new char[size];
		attributes = new byte[size];
		extendedColors = new byte[size];
		highlighting = new byte[size];
		charsets = new byte[size]; 

		clearScreen();
	}

	public void clearScreen() {
		Arrays.fill(buffer, '\0');
		Arrays.fill(attributes, (byte) 0);
		Arrays.fill(extendedColors, (byte) 0);
		Arrays.fill(highlighting, (byte) 0);
		Arrays.fill(charsets, (byte) 0); 
		cursorPos = 0;
	}

	public void resetMDT() {
		for (int i = 0; i < attributes.length; i++)
			attributes[i] &= ~0x01;
	}

	// --- Data Accessors ---
	public int getSize() {
		return buffer.length;
	}

	public char getChar(int i) {
		return (i >= 0 && i < buffer.length) ? buffer[i] : '\0';
	}

	public void setChar(int i, char c) {
		if (i >= 0 && i < buffer.length)
			buffer[i] = c;
	}

	public byte getAttr(int i) {
		return (i >= 0 && i < attributes.length) ? attributes[i] : 0;
	}

	public void setAttr(int i, byte b) {
		if (i >= 0 && i < attributes.length)
			attributes[i] = b;
	}

	public void setExtendedColor(int i, byte b) {
		if (i >= 0 && i < extendedColors.length)
			extendedColors[i] = b;
	}

	public void setHighlight(int i, byte b) {
		if (i >= 0 && i < highlighting.length)
			highlighting[i] = b;
	}

	public void setCharset(int i, byte b) {
		if (i >= 0 && i < charsets.length)
			charsets[i] = b;
	}

	public byte getCharset(int i) {
		return (i >= 0 && i < charsets.length) ? charsets[i] : 0;
	}

	public char[] getBuffer() {
		return buffer;
	}

	public byte[] getAttributes() {
		return attributes;
	}

	public byte[] getExtendedColors() {
		return extendedColors;
	}

	public byte[] getHighlight() {
		return highlighting;
	}

	public byte[] getCharsets() {
		return charsets;
	} 

	// --- State Accessors ---
	public int getRows() {
		return rows;
	}

	public int getCols() {
		return cols;
	}

	public int getAlternateRows() {
		return alternateRows;
	}

	public int getAlternateCols() {
		return alternateCols;
	}

	public int getCursorPos() {
		return cursorPos;
	}

	public void setCursorPos(int p) {
		this.cursorPos = p;
	}

	public boolean isAlternateSize() {
		return useAlternateSize;
	}

	public void setUseAlternateSize(boolean b) {
		this.useAlternateSize = b;
		if (b) {
			rows = alternateRows;
			cols = alternateCols;
		} else {
			rows = primaryRows;
			cols = primaryCols;
		}
	}

	// --- Palette & Colors ---
	public void setPalette(Color[] colors) {
		if (colors != null && colors.length >= 8) {
			this.palette = colors;
		}
	}

	public Color[] getPalette() {
		return palette;
	}

	public void setScreenBackground(Color c) {
		this.screenBackground = c;
	}

	public Color getScreenBackground() {
		return screenBackground;
	}

	public void setDefaultForeground(Color c) {
		this.defaultForeground = c;
	}

	public Color getDefaultForeground() {
		return defaultForeground;
	}

	public void setCursorColor(Color c) {
		this.cursorColor = c;
	}

	public Color getCursorColor() {
		return cursorColor;
	}

	// --- Field Logic Helpers ---
	public boolean isFieldStart(int pos) {
		return attributes[pos] != 0;
	}

	public boolean isProtected(int pos) {
		int start = findFieldStart(pos);
		if (start == -1)
			return false;
		return (attributes[start] & 0x20) != 0;
	}

	public int findFieldStart(int pos) {
		for (int i = 0; i < buffer.length; i++) {
			int p = (pos - i + buffer.length) % buffer.length;
			if (attributes[p] != 0)
				return p;
		}
		return -1;
	}

	public int findNextField(int pos) {
		int p = (pos + 1) % buffer.length;
		int count = 0;
		while (!isFieldStart(p) && count < buffer.length) {
			p = (p + 1) % buffer.length;
			count++;
		}
		return p;
	}

	public void setModified(int pos) {
		int start = findFieldStart(pos);
		if (start != -1)
			attributes[start] |= 0x01;
	}

	public String getString(int start, int length) {
		if (start < 0 || length <= 0 || start >= buffer.length)
			return "";
		int end = Math.min(start + length, buffer.length);
		return new String(buffer, start, end - start);
	}

	public boolean isKeyboardLocked() {
		return keyboardLocked;
	}

	public void setKeyboardLocked(boolean b) {
		this.keyboardLocked = b;
	}

	// --- Context State (used by Protocol Parser) ---
	public void setCurrentColor(byte c) {
		this.currentColor = c;
	}

	public byte getCurrentColor() {
		return currentColor;
	}

	public void setCurrentHighlight(byte h) {
		this.currentHighlight = h;
	}

	public byte getCurrentHighlight() {
		return currentHighlight;
	}

	// NEW: Context state for Charset
	public void setCurrentCharset(byte c) {
		this.currentCharset = c;
	}

	public byte getCurrentCharset() {
		return currentCharset;
	}
}