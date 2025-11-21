/**
 * Terminal screen buffer management.
 * Manages character buffer, field attributes, colors, and highlighting.
 */
public class ScreenBuffer {
	// Buffer dimensions
	private int rows;
	private int cols;

	// Buffer arrays
	private char[] buffer;
	private byte[] attributes;
	private byte[] extendedColors;
	private byte[] highlighting;

	/**
	 * Create a new screen buffer with specified dimensions.
	 */
	public ScreenBuffer(int rows, int cols) {
	    this.rows = rows;
	    this.cols = cols;
	    int size = rows * cols;
	    
	    buffer = new char[size];
	    attributes = new byte[size];
	    extendedColors = new byte[size];
	    highlighting = new byte[size];
	    
	    clearScreen();
	}

	/**
	 * Resize the buffer to new dimensions.
	 * Preserves existing content where possible.
	 */
	public void resize(int newRows, int newCols) {
	    int newSize = newRows * newCols;
	    
	    // Create new arrays
	    char[] newBuffer = new char[newSize];
	    byte[] newAttributes = new byte[newSize];
	    byte[] newExtendedColors = new byte[newSize];
	    byte[] newHighlighting = new byte[newSize];
	    
	    // Copy data from old buffer (as much as fits)
	    int copyRows = Math.min(rows, newRows);
	    int copyCols = Math.min(cols, newCols);
	    
	    for (int row = 0; row < copyRows; row++) {
	        int oldOffset = row * cols;
	        int newOffset = row * newCols;
	        int copyLength = copyCols;
	        
	        System.arraycopy(buffer, oldOffset, newBuffer, newOffset, copyLength);
	        System.arraycopy(attributes, oldOffset, newAttributes, newOffset, copyLength);
	        System.arraycopy(extendedColors, oldOffset, newExtendedColors, newOffset, copyLength);
	        System.arraycopy(highlighting, oldOffset, newHighlighting, newOffset, copyLength);
	    }
	    
	    // Replace arrays
	    this.rows = newRows;
	    this.cols = newCols;
	    this.buffer = newBuffer;
	    this.attributes = newAttributes;
	    this.extendedColors = newExtendedColors;
	    this.highlighting = newHighlighting;
	}

	/**
	 * Clear the entire screen buffer.
	 * Sets all positions to spaces with no attributes.
	 */
	public void clearScreen() {
	    int size = rows * cols;
	    for (int i = 0; i < size; i++) {
	        buffer[i] = ' ';
	        attributes[i] = 0;
	        extendedColors[i] = 0;
	        highlighting[i] = 0;
	    }
	}

	/**
	 * Check if position is a field attribute.
	 * A field attribute is indicated by a non-zero attribute byte.
	 */
	public boolean isFieldStart(int pos) {
	    if (pos < 0 || pos >= buffer.length) {
	        return false;
	    }
	    return attributes[pos] != 0;
	}

	/**
	 * Check if position is in a protected field.
	 * Finds the controlling field attribute and checks the protected bit (0x20).
	 */
	public boolean isProtected(int pos) {
	    int fieldStart = findFieldStart(pos);
	    return (attributes[fieldStart] & 0x20) != 0;
	}

	/**
	 * Clear modified flags for all unprotected fields.
	 */
	public void clearUnprotectedModifiedFlags() {
	    for (int i = 0; i < rows * cols; i++) {
	        if (isFieldStart(i) && !isProtected(i)) {
	            attributes[i] &= 0xFE; // Clear MDT bit
	        }
	    }
	}

	/**
	 * Check if position is in a non-display field.
	 * Non-display is indicated by display bits = 0x0C in field attribute.
	 */
	public boolean isNonDisplay(int pos) {
	    int fieldStart = findFieldStart(pos);
	    return (attributes[fieldStart] & 0x0C) == 0x0C;
	}

	/**
	 * Check if field at position has been modified.
	 * Checks the MDT (Modified Data Tag) bit (0x01) in the field attribute.
	 */
	public boolean isModified(int pos) {
	    int fieldStart = findFieldStart(pos);
	    return (attributes[fieldStart] & 0x01) != 0;
	}

	/**
	 * Clear modified flag for field at position.
	 * Clears the MDT bit in the controlling field attribute.
	 */
	public void clearModified(int pos) {
	    int fieldStart = findFieldStart(pos);
	    attributes[fieldStart] &= 0xFE; // Clear MDT bit
	}

	/**
	 * Find the field attribute position for a given position.
	 * Searches backward from pos until a field attribute is found.
	 */
	public int findFieldStart(int pos) {
	    int start = pos;
	    int count = 0;
	    int maxSize = rows * cols;
	    
	    while (!isFieldStart(start) && count < maxSize) {
	        start = (start - 1 + maxSize) % maxSize;
	        count++;
	    }
	    
	    return start;
	}

	/**
	 * Find the next field attribute position.
	 * Searches forward from pos until the next field attribute is found.
	 */
	public int findNextField(int pos) {
	    int next = (pos + 1) % (rows * cols);
	    int count = 0;
	    int maxSize = rows * cols;
	    
	    while (!isFieldStart(next) && next != pos && count < maxSize) {
	        next = (next + 1) % (rows * cols);
	        count++;
	    }
	    
	    return next;
	}

	/**
	 * Reset all modified data tags.
	 * Clears the MDT bit for all field attributes.
	 */
	public void resetMDT() {
	    for (int i = 0; i < attributes.length; i++) {
	        attributes[i] &= ~0x01;
	    }
	}

	/**
	 * Mark field at position as modified.
	 * Sets the MDT bit in the controlling field attribute.
	 */
	public void setModified(int pos) {
	    int fieldStart = findFieldStart(pos);
	    attributes[fieldStart] |= 0x01;
	}

	/**
	 * Get character at position.
	 */
	public char getChar(int pos) {
	    if (pos < 0 || pos >= buffer.length) {
	        return ' ';
	    }
	    return buffer[pos];
	}

	/**
	 * Set character at position.
	 */
	public void setChar(int pos, char c) {
	    if (pos >= 0 && pos < buffer.length) {
	        buffer[pos] = c;
	    }
	}

	/**
	 * Get attribute byte at position.
	 */
	public byte getAttribute(int pos) {
	    if (pos < 0 || pos >= attributes.length) {
	        return 0;
	    }
	    return attributes[pos];
	}

	/**
	 * Set attribute byte at position.
	 */
	public void setAttribute(int pos, byte attr) {
	    if (pos >= 0 && pos < attributes.length) {
	        attributes[pos] = attr;
	    }
	}

	/**
	 * Get extended color at position.
	 */
	public byte getExtendedColor(int pos) {
	    if (pos < 0 || pos >= extendedColors.length) {
	        return 0;
	    }
	    return extendedColors[pos];
	}

	/**
	 * Set extended color at position.
	 */
	public void setExtendedColor(int pos, byte color) {
	    if (pos >= 0 && pos < extendedColors.length) {
	        extendedColors[pos] = color;
	    }
	}

	/**
	 * Get highlighting at position.
	 */
	public byte getHighlighting(int pos) {
	    if (pos < 0 || pos >= highlighting.length) {
	        return 0;
	    }
	    return highlighting[pos];
	}

	/**
	 * Set highlighting at position.
	 */
	public void setHighlighting(int pos, byte highlight) {
	    if (pos >= 0 && pos < highlighting.length) {
	        highlighting[pos] = highlight;
	    }
	}

	// Getters for buffer access
	public char[] getBuffer() { return buffer; }
	public byte[] getAttributes() { return attributes; }
	public byte[] getExtendedColors() { return extendedColors; }
	public byte[] getHighlighting() { return highlighting; }
	public int getRows() { return rows; }
	public int getCols() { return cols; }
	public int getBufferSize() { return rows * cols; }
}
    