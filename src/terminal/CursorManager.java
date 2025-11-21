import java.awt.Toolkit;

/**
 * 
 * Manages cursor movement and field navigation.
 */
public class CursorManager {
	private ScreenBuffer screenBuffer;
	private int cursorPos = 0;

	/**
	 * 
	 * Create a new cursor manager.
	 */
	public CursorManager(ScreenBuffer screenBuffer) {
		this.screenBuffer = screenBuffer;
	}

	/**
	 * 
	 * Move cursor by delta positions.
	 */
	public void moveCursor(int delta) {
		int bufferSize = screenBuffer.getBufferSize();
		cursorPos = (cursorPos + delta + bufferSize) % bufferSize;
	}

	/**
	 * 
	 * Tab to next unprotected field.
	 */
	public void tabToNextField() {
		int start = cursorPos;
		int count = 0;
		int maxSize = screenBuffer.getBufferSize();
		do {
			cursorPos = (cursorPos + 1) % screenBuffer.getBufferSize();
			count++;
			if (screenBuffer.isFieldStart(cursorPos)) {
				// We're on a field attribute, check if the field itself is protected
				boolean fieldIsProtected = screenBuffer.isProtected(cursorPos);

				if (!fieldIsProtected) {
					// Move to first position after the field attribute
					cursorPos = (cursorPos + 1) % screenBuffer.getBufferSize();
					return;
				}
			}
		} while (cursorPos != start && count < maxSize);
		// No unprotected field found, stay where we are
		cursorPos = start;
	}

	/**
	 * 
	 * Tab to previous unprotected field.
	 */
	public void tabToPreviousField() {
		int start = cursorPos;
		int count = 0;
		int maxSize = screenBuffer.getBufferSize();
		do {
			cursorPos = (cursorPos - 1 + screenBuffer.getBufferSize()) % screenBuffer.getBufferSize();
			count++;
			if (screenBuffer.isFieldStart(cursorPos)) {
				// We're on a field attribute, check if the field itself is protected
				boolean fieldIsProtected = screenBuffer.isProtected(cursorPos);

				if (!fieldIsProtected) {
					// Move to first position after the field attribute
					cursorPos = (cursorPos + 1) % screenBuffer.getBufferSize();
					return;
				}
			}
		} while (cursorPos != start && count < maxSize);
		// No unprotected field found, stay where we are
		cursorPos = start;
	}

	/**
	 * 
	 * Erase to end of current field.
	 */
	public void eraseToEndOfField() {
		if (screenBuffer.isProtected(cursorPos)) {
			Toolkit.getDefaultToolkit().beep();
			return;
		}
		int fieldStart = screenBuffer.findFieldStart(cursorPos);
		int fieldEnd = screenBuffer.findNextField(fieldStart);
		for (int i = cursorPos; i < fieldEnd && !screenBuffer.isFieldStart(i); i++) {
			screenBuffer.setChar(i, '\0');
		}
		screenBuffer.setModified(cursorPos);
	}

	/**
	 * 
	 * Erase to end of current line within field.
	 */
	public void eraseToEndOfLine() {
		if (screenBuffer.isProtected(cursorPos)) {
			Toolkit.getDefaultToolkit().beep();
			return;
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
	}

	/**
	 * 
	 * Get current cursor position.
	 */
	public int getCursorPos() {
		return cursorPos;
	}

	/**
	 * 
	 * Set cursor position.
	 */
	public void setCursorPos(int pos) {
		if (pos >= 0 && pos < screenBuffer.getBufferSize()) {
			this.cursorPos = pos;
		}
	}

	/**
	 * 
	 * Set the screen buffer (for when buffer is resized).
	 */
	public void setScreenBuffer(ScreenBuffer buffer) {
		this.screenBuffer = buffer;
		// Ensure cursor is still in valid range
		if (cursorPos >= buffer.getBufferSize()) {
			cursorPos = 0;
		}
	}
}
