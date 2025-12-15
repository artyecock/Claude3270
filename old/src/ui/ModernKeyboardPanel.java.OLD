import java.awt.*;
import java.awt.event.*;

/**
 * 
 * Simple message dialog utility (replacement for JOptionPane).
 */
public class MessageDialog {
	/**
	 * 
	 * Show a simple message dialog.
	 */
	public static void showMessage(Frame parent, String message, String title) {
		Dialog dialog = new Dialog(parent, title, true);
		dialog.setLayout(new BorderLayout(10, 10));
		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});
		Panel messagePanel = new Panel();
		messagePanel.add(new Label(message));
		dialog.add(messagePanel, BorderLayout.CENTER);
		Panel buttonPanel = new Panel();
		Button okButton = new Button("OK");
		okButton.addActionListener(e -> dialog.dispose());
		buttonPanel.add(okButton);
		dialog.add(buttonPanel, BorderLayout.SOUTH);
		dialog.pack();
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);
	}

	/**
	 * 
	 * Show a multi-line message dialog.
	 */
	public static void showMultilineMessage(Frame parent, String message, String title) {
		Dialog dialog = new Dialog(parent, title, true);
		dialog.setLayout(new BorderLayout(10, 10));
		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});
		TextArea textArea = new TextArea(message, 5, 40, TextArea.SCROLLBARS_VERTICAL_ONLY);
		textArea.setEditable(false);
		dialog.add(textArea, BorderLayout.CENTER);
		Panel buttonPanel = new Panel();
		Button okButton = new Button("OK");
		okButton.addActionListener(e -> dialog.dispose());
		buttonPanel.add(okButton);
		dialog.add(buttonPanel, BorderLayout.SOUTH);
		dialog.pack();
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);
	}

	/**
	 * 
	 * Show an input dialog.
	 */
	public static String showInput(Frame parent, String message, String title) {
		Dialog dialog = new Dialog(parent, title, true);
		dialog.setLayout(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(10, 10, 10, 10);
		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.gridwidth = 2;
		dialog.add(new Label(message), gbc);
		gbc.gridy = 1;
		TextField textField = new TextField(20);
		dialog.add(textField, gbc);
		gbc.gridy = 2;
		gbc.gridwidth = 1;
		Panel buttonPanel = new Panel(new FlowLayout());
		Button okButton = new Button("OK");
		Button cancelButton = new Button("Cancel");
		final String[] result = { null };
		okButton.addActionListener(e -> {
			result[0] = textField.getText();
			dialog.dispose();
		});
		cancelButton.addActionListener(e -> dialog.dispose());
		textField.addActionListener(e -> {
			result[0] = textField.getText();
			dialog.dispose();
		});
		buttonPanel.add(okButton);
		buttonPanel.add(cancelButton);
		dialog.add(buttonPanel, gbc);
		dialog.pack();
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);
		return result[0];
	}

	/**
	 * 
	 * Show a confirmation dialog. Returns 0 for Yes, 1 for No.
	 */
	public static int showConfirm(Frame parent, String message, String title) {
		Dialog dialog = new Dialog(parent, title, true);
		dialog.setLayout(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(10, 10, 10, 10);
		dialog.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dialog.dispose();
			}
		});
		gbc.gridx = 0;
		gbc.gridy = 0;
		dialog.add(new Label(message), gbc);
		gbc.gridy = 1;
		Panel buttonPanel = new Panel(new FlowLayout());
		Button yesButton = new Button("Yes");
		Button noButton = new Button("No");
		final int[] result = { 1 }; // Default to No
		yesButton.addActionListener(e -> {
			result[0] = 0;
			dialog.dispose();
		});
		noButton.addActionListener(e -> {
			result[0] = 1;
			dialog.dispose();
		});
		buttonPanel.add(yesButton);
		buttonPanel.add(noButton);
		dialog.add(buttonPanel, gbc);
		dialog.pack();
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);
		return result[0];
	}
}
