import java.awt.event.*;
import java.awt.Toolkit;
import java.awt.datatransfer.*;
import java.util.Map;

/**
 * 
 * Handles keyboard input and text entry.
 */
public class InputHandler implements KeyListener {
	/**
	 * 
	 * Interface for input handler callbacks.
	 */
	public interface InputCallback {
		void onAidKey(byte aid);

		void onCursorMove(int delta);

		void onCharacterTyped(char c);

		void onBackspace();

		void onTab(boolean reverse);

		void onInsertToggle();

		void onEraseEOF();

		void onEraseEOL();

		void onClearScreen();

		void requestRepaint();

		boolean isKeyboardLocked();

		boolean isConnected();

		boolean isInsertMode();
	}

	private InputCallback callback;
	private ScreenBuffer screenBuffer;
	private CursorManager cursorManager;
	private Map<Integer, KeyMapping> keyMap;
	private boolean insertMode = false;

	/**
	 * 
	 * Create a new input handler.
	 */
	public InputHandler(InputCallback callback, ScreenBuffer screenBuffer, CursorManager cursorManager,
			Map<Integer, KeyMapping> keyMap) {
		this.callback = callback;
		this.screenBuffer = screenBuffer;
		this.cursorManager = cursorManager;
		this.keyMap = keyMap;
	}

	@Override
	public void keyPressed(KeyEvent e) {
		// CRITICAL: Complex keyboard handling logic
// IMPORTANT: Replace direct method calls with callback.on*() calls
// IMPORTANT: Replace canvas.repaint() with callback.requestRepaint()
// IMPORTANT: Replace direct cursorPos access with cursorManager.getCursorPos()
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
			cursorPos = (cursorPos - 1 + buffer.length) % buffer.length;
			canvas.repaint();
			statusBar.update();
			return;

		case KeyEvent.VK_RIGHT:
			cursorPos = (cursorPos + 1) % buffer.length;
			canvas.repaint();
			statusBar.update();
			return;

		case KeyEvent.VK_UP:
			cursorPos = (cursorPos - cols + buffer.length) % buffer.length;
			canvas.repaint();
			statusBar.update();
			return;

		case KeyEvent.VK_DOWN:
			cursorPos = (cursorPos + cols) % buffer.length;
			canvas.repaint();
			statusBar.update();
			return;

		case KeyEvent.VK_HOME:
			cursorPos = 0;
			canvas.repaint();
			statusBar.update();
			return;
		}

		if (keyboardLocked || !connected) {
			Toolkit.getDefaultToolkit().beep();
			return;
		}

		// Check for custom key mapping first
		KeyMapping mapping = keyMap.get(keyCode);
		if (mapping != null) {
			if (mapping.aid != null) {
				// Mapped to AID function
				sendAID(mapping.aid);
				return;
			} else {
				// Mapped to character - handle in keyTyped
				// (let it fall through)
			}
		}

		// Handle function keys with explicit mapping
		if (keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12) {
			byte aid;
			switch (keyCode) {
			case KeyEvent.VK_F1:
				aid = AID_PF1;
				break;
			case KeyEvent.VK_F2:
				aid = AID_PF2;
				break;
			case KeyEvent.VK_F3:
				aid = AID_PF3;
				break;
			case KeyEvent.VK_F4:
				aid = AID_PF4;
				break;
			case KeyEvent.VK_F5:
				aid = AID_PF5;
				break;
			case KeyEvent.VK_F6:
				aid = AID_PF6;
				break;
			case KeyEvent.VK_F7:
				aid = AID_PF7;
				break;
			case KeyEvent.VK_F8:
				aid = AID_PF8;
				break;
			case KeyEvent.VK_F9:
				aid = AID_PF9;
				break;
			case KeyEvent.VK_F10:
				aid = AID_PF10;
				break;
			case KeyEvent.VK_F11:
				aid = AID_PF11;
				break;
			case KeyEvent.VK_F12:
				aid = AID_PF12;
				break;
			default:
				return;
			}

			sendAID(aid);
			return;
		}

		switch (keyCode) {
		case KeyEvent.VK_ESCAPE:
			clearScreen();
			sendAID(AID_CLEAR);
			canvas.repaint();
			return;

		case KeyEvent.VK_ENTER:
			sendAID(AID_ENTER);
			return;

		case KeyEvent.VK_INSERT:
			insertMode = !insertMode;
			statusBar.update();
			canvas.repaint();
			return;

		case KeyEvent.VK_TAB:
			if (e.isShiftDown()) {
				tabToPreviousField();
			} else {
				tabToNextField();
			}
			return;

		case KeyEvent.VK_BACK_SPACE:
			if (!isProtected(cursorPos)) {
				moveCursor(-1);
				if (!isProtected(cursorPos)) {
					buffer[cursorPos] = ' ';
					setModified(cursorPos);
					canvas.repaint();
				}
			}
			return;
		}
	}

	@Override
	public void keyTyped(KeyEvent e) {
// CRITICAL: Character insertion/replacement logic with insert mode handling
// IMPORTANT: Use callback.onCharacterTyped() for field modification
// IMPORTANT: Replace buffer[pos] with screenBuffer.setChar()
		if (keyboardLocked || !connected)
			return;

		char c = e.getKeyChar();

		KeyMapping mapping = keyMap.get(e.getKeyCode());
		if (mapping != null && mapping.aid == null) {
			c = mapping.character;
		}

		if (c < 32 || c > 126)
			return;

		if (!isProtected(cursorPos)) {
			if (insertMode) {
				int fieldStart = findFieldStart(cursorPos);
				int fieldEnd = findNextField(fieldStart);

				int lastPos = fieldEnd - 1;
				if (isFieldStart(lastPos))
					lastPos--;

				char lastChar = buffer[lastPos];
				if (lastChar != '\0' && lastChar != ' ') {
					Toolkit.getDefaultToolkit().beep();
					return;
				}

				// Shift characters right
				for (int i = lastPos; i > cursorPos; i--) {
					if (!isFieldStart(i) && !isFieldStart(i - 1)) {
						buffer[i] = buffer[i - 1];
					}
				}
			}

			// Common code for both insert and replace modes
			buffer[cursorPos] = c;
			setModified(cursorPos);

			// Check if we need to auto-advance BEFORE moving cursor
			int nextPos = (cursorPos + 1) % (rows * cols);
			moveCursor(1);

			// Only auto-advance if the NEXT position is a field boundary
			if (isFieldStart(nextPos)) {
				tabToNextField();
			}

			canvas.repaint();
		} else {
			Toolkit.getDefaultToolkit().beep();
		}
	}

	@Override
	public void keyReleased(KeyEvent e) {
		// Empty - not used
	}

	/**
	 * 
	 * Handle paste from clipboard.
	 */
	public void pasteFromClipboard() {
// IMPORTANT: Replace direct field access with callback methods
		if (keyboardLocked || !connected) {
			Toolkit.getDefaultToolkit().beep();
			return;
		}

		try {
			Toolkit toolkit = Toolkit.getDefaultToolkit();
			Clipboard clipboard = toolkit.getSystemClipboard();
			String text = (String) clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor);

			if (text == null || text.isEmpty()) {
				return;
			}

			for (char c : text.toCharArray()) {
				if (c == '\n' || c == '\r') {
					tabToNextField();
					continue;
				}

				if (c < 32 || c > 126) {
					continue;
				}

				if (!isProtected(cursorPos)) {
					buffer[cursorPos] = c;
					setModified(cursorPos);

					int nextPos = (cursorPos + 1) % (rows * cols);
					moveCursor(1);

					if (isFieldStart(nextPos)) {
						tabToNextField();
					}
				} else {
					tabToNextField();

					if (!isProtected(cursorPos)) {
						buffer[cursorPos] = c;
						setModified(cursorPos);
						moveCursor(1);
					}
				}
			}

			canvas.repaint();
			statusBar.setStatus("Pasted " + text.length() + " characters");

		} catch (Exception ex) {
			statusBar.setStatus("Paste failed: " + ex.getMessage());
			ex.printStackTrace();
		}
	}

	/**
	 * 
	 * Copy selection to clipboard.
	 */
	public void copySelection(int selectionStart, int selectionEnd) {
// IMPORTANT: Use screenBuffer for buffer access
// IMPORTANT: Use screenBuffer.getCols() for column calculations
		if (selectionStart < 0 || selectionEnd < 0) {
			Toolkit.getDefaultToolkit().beep();
			return;
		}

		int start = Math.min(selectionStart, selectionEnd);
		int end = Math.max(selectionStart, selectionEnd);

		StringBuilder sb = new StringBuilder();
		int startRow = start / cols;
		int endRow = end / cols;

		for (int row = startRow; row <= endRow; row++) {
			int rowStart = (row == startRow) ? start : row * cols;
			int rowEnd = (row == endRow) ? end : (row + 1) * cols - 1;

			for (int pos = rowStart; pos <= rowEnd && pos < buffer.length; pos++) {
				char c = buffer[pos];
				if (c == '\0')
					c = ' ';
				if (!isFieldStart(pos)) {
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

			statusBar.setStatus("Copied " + (end - start + 1) + " characters");
			clearSelection();

		} catch (Exception ex) {
			statusBar.setStatus("Copy failed: " + ex.getMessage());
			ex.printStackTrace();
		}
	}

	/**
	 * 
	 * Select all text.
	 */
	public int[] selectAll() {
		return new int[] { 0, screenBuffer.getBufferSize() - 1 };
	}

	// Setters for state
	public void setInsertMode(boolean insertMode) {
		this.insertMode = insertMode;
	}

	public boolean isInsertMode() {
		return insertMode;
	}
}