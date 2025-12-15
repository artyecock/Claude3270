import java.awt.*;
import java.awt.event.*;

/**
 * 
 * Dialog for managing saved connection profiles.
 */
public class ManageProfilesDialog extends Dialog {
	private java.awt.List profileList;

	/**
	 * 
	 * Create a manage profiles dialog.
	 */
	public ManageProfilesDialog(Frame parent) {
		super(parent, "Manage Profiles", true);
		setLayout(new BorderLayout(10, 10));
		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dispose();
			}
		});
		// List of profiles
		profileList = new java.awt.List(10, false);
		refreshProfileList();
		add(profileList, BorderLayout.CENTER);
		// Buttons
		Panel buttonPanel = new Panel(new FlowLayout());
		Button deleteButton = new Button("Delete");
		Button closeButton = new Button("Close");
		deleteButton.addActionListener(e -> onDelete());
		closeButton.addActionListener(e -> dispose());
		buttonPanel.add(deleteButton);
		buttonPanel.add(closeButton);
		add(buttonPanel, BorderLayout.SOUTH);
		pack();
		setLocationRelativeTo(parent);
	}

	/**
	 * 
	 * Show the dialog.
	 */
	public void showDialog() {
		setVisible(true);
	}

	/**
	 * 
	 * Refresh the profile list.
	 */
	private void refreshProfileList() {
		profileList.removeAll();
		for (String profileName : ConnectionProfile.getProfileNames()) {
			profileList.add(profileName);
		}
	}

	/**
	 * 
	 * Handle delete button.
	 */
	private void onDelete() {
		String selected = profileList.getSelectedItem();
		if (selected != null) {
			int confirm = MessageDialog.showConfirm(this, "Delete profile '" + selected + "'?", "Confirm Delete");
			if (confirm == 0) { // Yes
				ConnectionProfile.removeProfile(selected);
				refreshProfileList();
			}
		}
	}
}
