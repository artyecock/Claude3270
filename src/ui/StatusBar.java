import java.awt.*;

/**
 * 
 * Status bar component showing connection status, cursor position, and mode.
 */
public class StatusBar extends Panel {
	private Label statusLabel;
	private Label positionLabel;
	private Label modeLabel;

	/**
	 * 
	 * Create a new status bar.
	 */
	public StatusBar() {
		setLayout(new FlowLayout(FlowLayout.LEFT));
		setBackground(Color.DARK_GRAY);
		statusLabel = new Label("Not connected");
		statusLabel.setForeground(Color.WHITE);
		add(statusLabel);
		add(new Label("  "));
		positionLabel = new Label("Pos: 001/001");
		positionLabel.setForeground(Color.WHITE);
		add(positionLabel);
		add(new Label("  "));
		modeLabel = new Label("Mode: ");
		modeLabel.setForeground(Color.WHITE);
		add(modeLabel);
	}

	/**
	 * 
	 * Set the status message.
	 */
	public void setStatus(String status) {
		statusLabel.setText(status);
	}

	/**
	 * 
	 * Update position and mode displays. Called with current emulator state.
	 */
	public void update(int cursorPos, int cols, boolean insertMode, boolean keyboardLocked) {
		int row = cursorPos / cols + 1;
		int col = cursorPos % cols + 1;
		positionLabel.setText(String.format("Pos: %03d/%03d", row, col));
		String mode = insertMode ? "Insert" : "Replace";
		if (keyboardLocked) {
			mode += " [LOCKED]";
		}
		modeLabel.setText("Mode: " + mode);
	}
}
