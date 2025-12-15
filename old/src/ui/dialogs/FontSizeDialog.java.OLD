import java.awt.*;
import java.awt.event.*;


/**
 * 
 * Dialog for selecting terminal font size.
 */
public class FontSizeDialog extends Dialog {
	private Choice sizeChoice;
	private int selectedSize = 14;
	private boolean cancelled = true;

	/**
	 * 
	 * Create a font size dialog.
	 */
	public FontSizeDialog(Frame parent, int currentSize) {
		super(parent, "Font Size", true);
		setLayout(new GridBagLayout());
		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dispose();
			}
		});
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(10, 10, 10, 10);
		gbc.gridx = 0;
		gbc.gridy = 0;
		add(new Label("Font Size:"), gbc);
		gbc.gridx = 1;
		sizeChoice = new Choice();
		for (int size = 8; size <= 24; size += 2) {
			sizeChoice.add(String.valueOf(size));
		}
		sizeChoice.select(String.valueOf(currentSize));
		add(sizeChoice, gbc);
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 2;
		gbc.anchor = GridBagConstraints.CENTER;
		Panel buttonPanel = new Panel(new FlowLayout());
		Button okButton = new Button("OK");
		Button cancelButton = new Button("Cancel");
		okButton.addActionListener(e -> {
			selectedSize = Integer.parseInt(sizeChoice.getSelectedItem());
			cancelled = false;
			dispose();
		});
		cancelButton.addActionListener(e -> dispose());
		buttonPanel.add(okButton);
		buttonPanel.add(cancelButton);
		add(buttonPanel, gbc);
		pack();
		setLocationRelativeTo(parent);
	}

	/**
	 * 
	 * Show the dialog and return selected font size. Returns -1 if cancelled.
	 */
	public int showDialog() {
		setVisible(true);
		return cancelled ? -1 : selectedSize;
	}
}
