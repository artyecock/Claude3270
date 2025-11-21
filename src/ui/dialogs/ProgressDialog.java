import java.awt.*;
import java.awt.event.*;


/**
 * 
 * Progress dialog for file transfer operations.
 */
public class ProgressDialog extends Dialog {
	private Label progressLabel;
	private Label statusLabel;
	private Button cancelButton;
	private boolean cancelled = false;

	/**
	 * 
	 * Create a progress dialog.
	 */
	public ProgressDialog(Frame parent, String operation) {
		super(parent, "File Transfer", false);
		setLayout(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(10, 10, 10, 10);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				cancelled = true;
				dispose();
			}
		});
		gbc.gridx = 0;
		gbc.gridy = 0;
		add(new Label(operation), gbc);
		gbc.gridy = 1;
		progressLabel = new Label("Initializing...");
		add(progressLabel, gbc);
		gbc.gridy = 2;
		statusLabel = new Label(" ");
		add(statusLabel, gbc);
		gbc.gridy = 3;
		gbc.anchor = GridBagConstraints.CENTER;
		cancelButton = new Button("Cancel");
		cancelButton.addActionListener(e -> {
			cancelled = true;
			closeDialog();
		});
		add(cancelButton, gbc);
		pack();
		setLocationRelativeTo(parent);
	}

	/**
	 * 
	 * Update the progress message.
	 */
	public void updateProgress(String progress, String status) {
		if (progressLabel != null) {
			progressLabel.setText(progress);
		}
		if (statusLabel != null && status != null) {
			statusLabel.setText(status);
		}
		pack();
	}

	/**
	 * 
	 * Check if transfer was cancelled.
	 */
	public boolean wasCancelled() {
		return cancelled;
	}

	/**
	 * 
	 * Close the dialog.
	 */
	public void closeDialog() {
		dispose();
	}
}
