import java.awt.*;
import java.awt.event.*;

/**
 * 
 * Dialog for creating and managing connection profiles.
 */
public class ConnectionDialog extends Dialog {
	private TextField hostnameField;
	private TextField portField;
	private Choice modelChoice;
	private Choice profileChoice;
	private Checkbox tlsCheckbox;
	private ConnectionProfile selectedProfile = null;
	private boolean cancelled = true;

	/**
	 * 
	 * Create a connection dialog.
	 */
	public ConnectionDialog(Frame parent) {
		super(parent, "Connect to Host", true);
		setLayout(new BorderLayout(10, 10));
		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dispose();
			}
		});
		// Top panel - Profile selection
		Panel topPanel = new Panel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.gridx = 0;
		gbc.gridy = 0;
		topPanel.add(new Label("Saved Profiles:"), gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;
		profileChoice = new Choice();
		profileChoice.add("(New Connection)");
		for (String profileName : ConnectionProfile.getProfileNames()) {
			profileChoice.add(profileName);
		}
		profileChoice.addItemListener(e -> onProfileSelected(profileChoice.getSelectedItem()));
		topPanel.add(profileChoice, gbc);
		gbc.gridx = 2;
		gbc.weightx = 0;
		Button manageButton = new Button("Manage...");
		manageButton.addActionListener(e -> onManageProfiles());
		topPanel.add(manageButton, gbc);
		add(topPanel, BorderLayout.NORTH);
		// Center panel - Connection details
		Panel centerPanel = new Panel(new GridBagLayout());
		gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		// Hostname
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 0;
		centerPanel.add(new Label("Hostname:"), gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;
		gbc.gridwidth = 2;
		hostnameField = new TextField("localhost", 30);
		centerPanel.add(hostnameField, gbc);
		// Port
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.weightx = 0;
		gbc.gridwidth = 1;
		centerPanel.add(new Label("Port:"), gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;
		portField = new TextField("23", 10);
		centerPanel.add(portField, gbc);
		// Model
		gbc.gridx = 0;
		gbc.gridy = 2;
		gbc.weightx = 0;
		centerPanel.add(new Label("Terminal Model:"), gbc);
		gbc.gridx = 1;
		gbc.gridwidth = 2;
		gbc.weightx = 1.0;
		modelChoice = new Choice();
		modelChoice.add("3278-2 (24x80)");
		modelChoice.add("3279-2 (24x80 Color)");
		modelChoice.add("3278-3 (32x80)");
		modelChoice.add("3279-3 (32x80 Color)");
		modelChoice.add("3278-4 (43x80)");
		modelChoice.add("3278-5 (27x132)");
		modelChoice.select(3); // 3279-3 default
		centerPanel.add(modelChoice, gbc);
		// TLS option
		gbc.gridx = 0;
		gbc.gridy = 3;
		gbc.gridwidth = 3;
		tlsCheckbox = new Checkbox("Use TLS/SSL encryption", false);
		centerPanel.add(tlsCheckbox, gbc);
		add(centerPanel, BorderLayout.CENTER);
		// Bottom panel - Buttons
		Panel bottomPanel = new Panel(new FlowLayout(FlowLayout.RIGHT));
		Button saveProfileButton = new Button("Save Profile...");
		saveProfileButton.addActionListener(e -> onSaveProfile());
		Button connectButton = new Button("Connect");
		connectButton.addActionListener(e -> onConnect());
		Button cancelButton = new Button("Cancel");
		cancelButton.addActionListener(e -> dispose());
		bottomPanel.add(saveProfileButton);
		bottomPanel.add(connectButton);
		bottomPanel.add(cancelButton);
		add(bottomPanel, BorderLayout.SOUTH);
		// Enter key on fields
		ActionListener enterAction = e -> onConnect();
		hostnameField.addActionListener(enterAction);
		portField.addActionListener(enterAction);
		pack();
		setLocationRelativeTo(parent);
	}

	/**
	 * 
	 * Show the dialog and return the selected/created profile. Returns null if
	 * cancelled.
	 */
	public ConnectionProfile showDialog() {
		setVisible(true);
		return cancelled ? null : selectedProfile;
	}

	/**
	 * 
	 * Handle profile selection change.
	 */
	private void onProfileSelected(String profileName) {
		if (profileName.equals("(New Connection)")) {
			hostnameField.setText("localhost");
			portField.setText("23");
			modelChoice.select(3);
			tlsCheckbox.setState(false);
		} else {
			ConnectionProfile profile = ConnectionProfile.getProfile(profileName);
			if (profile != null) {
				hostnameField.setText(profile.hostname);
				portField.setText(String.valueOf(profile.port));
				tlsCheckbox.setState(profile.useTLS);
				//Select the right model
				for (int i = 0; i < modelChoice.getItemCount(); i++) {
					if (modelChoice.getItem(i).startsWith(profile.model)) {
						modelChoice.select(i);
						break;
					}
				}
			}
		}
	}

	/**
	 * 
	 * Handle save profile button.
	 */
	private void onSaveProfile() {
		String name = MessageDialog.showInput(this, "Enter profile name:", "Save Profile");
		if (name != null && !name.trim().isEmpty()) {
			ConnectionProfile profile = buildProfileFromFields();
			if (profile != null) {
				ConnectionProfile.putProfile(name, profile);
				//Refresh profile list
				profileChoice.add(name);
				profileChoice.select(name);

				MessageDialog.showMessage(this, "Profile saved successfully", "Saved");
			}
		}
	}

	/**
	 * 
	 * Handle manage profiles button.
	 */
	private void onManageProfiles() {
		setVisible(false);
		ManageProfilesDialog manageDialog = new ManageProfilesDialog(
				getOwner() instanceof Frame ? (Frame) getOwner() : null);
		manageDialog.showDialog();
		// Refresh profile list
		String currentSelection = profileChoice.getSelectedItem();
		profileChoice.removeAll();
		profileChoice.add("(New Connection)");
		for (String profileName : ConnectionProfile.getProfileNames()) {
			profileChoice.add(profileName);
		}
		profileChoice.select(currentSelection);
		setVisible(true);
	}

	/**
	 * 
	 * Handle connect button.
	 */
	private void onConnect() {
		selectedProfile = buildProfileFromFields();
		if (selectedProfile != null) {
			cancelled = false;
			dispose();
		}
	}

	/**
	 * 
	 * Validate and build connection profile from form fields.
	 */
	private ConnectionProfile buildProfileFromFields() {
		String hostname = hostnameField.getText().trim();
		if (hostname.isEmpty()) {
			MessageDialog.showMessage(this, "Please enter a hostname", "Validation Error");
			return null;
		}
		int port;
		try {
			port = Integer.parseInt(portField.getText().trim());
		} catch (NumberFormatException ex) {
			MessageDialog.showMessage(this, "Invalid port number", "Validation Error");
			return null;
		}
		String modelStr = modelChoice.getSelectedItem();
		String model = modelStr.substring(0, 7).trim(); // Extract "3279-3" etc.
		boolean useTLS = tlsCheckbox.getState();
		return new ConnectionProfile("temp", hostname, port, model, useTLS);
	}
}
