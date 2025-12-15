package ui.dialogs;

import config.ConnectionProfile;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;

/**
 * Connection dialog for selecting and managing connection profiles.
 * Allows users to configure hostname, port, terminal model, and TLS settings.
 * 
 * Extracted from TN3270Emulator-Monolithic.java lines 1070-1240
 */
public class ConnectionDialog extends Dialog {
    
    private TextField hostnameField;
    private TextField portField;
    private Choice modelChoice;
    private Checkbox tlsCheckbox;
    private Choice profileChoice;
    
    private ConnectionProfile selectedProfile = null;
    private boolean cancelled = true;
    
    /**
     * Creates a new connection dialog.
     * 
     * @param parent Parent frame
     */
    public ConnectionDialog(Frame parent) {
        super(parent, "Connect to Host", true);
        setLayout(new BorderLayout(10, 10));
        
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                cancelled = true;
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
        
        // Load saved profiles
        Map<String, ConnectionProfile> profiles = ConnectionProfile.loadProfiles();
        for (String profileName : profiles.keySet()) {
            profileChoice.add(profileName);
        }
        
        profileChoice.addItemListener(e -> onProfileSelected());
        topPanel.add(profileChoice, gbc);
        
        gbc.gridx = 2;
        gbc.weightx = 0;
        Button manageButton = new Button("Manage...");
        manageButton.addActionListener(e -> showManageProfilesDialog());
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
        modelChoice.select(3); // Default to 3279-3
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
        saveProfileButton.addActionListener(e -> saveProfile());
        bottomPanel.add(saveProfileButton);
        
        Button connectButton = new Button("Connect");
        connectButton.addActionListener(e -> onConnect());
        bottomPanel.add(connectButton);
        
        Button cancelButton = new Button("Cancel");
        cancelButton.addActionListener(e -> {
            cancelled = true;
            dispose();
        });
        bottomPanel.add(cancelButton);
        
        add(bottomPanel, BorderLayout.SOUTH);
        
        // Enter key on fields triggers connect
        ActionListener connectAction = e -> onConnect();
        hostnameField.addActionListener(connectAction);
        portField.addActionListener(connectAction);
        
        pack();
        setLocationRelativeTo(parent);
    }
    
    /**
     * Handle profile selection change.
     */
    private void onProfileSelected() {
        String selected = profileChoice.getSelectedItem();
        if (selected.equals("(New Connection)")) {
            hostnameField.setText("localhost");
            portField.setText("23");
            modelChoice.select(3);
            tlsCheckbox.setState(false);
        } else {
            Map<String, ConnectionProfile> profiles = ConnectionProfile.loadProfiles();
            ConnectionProfile profile = profiles.get(selected);
            if (profile != null) {
                //hostnameField.setText(profile.hostname);
                hostnameField.setText(profile.getHostname());
                //portField.setText(String.valueOf(profile.port));
                portField.setText(String.valueOf(profile.getPort()));
                //tlsCheckbox.setState(profile.useTLS);
                tlsCheckbox.setState(profile.useTLS());
                
                // Select the right model
                for (int i = 0; i < modelChoice.getItemCount(); i++) {
                    //if (modelChoice.getItem(i).startsWith(profile.model)) {
                    if (modelChoice.getItem(i).startsWith(profile.getModel())) {
                        modelChoice.select(i);
                        break;
                    }
                }
            }
        }
    }
    
    /**
     * Handle connect button click.
     */
    private void onConnect() {
        String hostname = hostnameField.getText().trim();
        if (hostname.isEmpty()) {
            hostname = "localhost";
        }
        
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            port = 23;
        }
        
        String modelStr = modelChoice.getSelectedItem();
        String model = modelStr.substring(0, 7).trim();
        boolean useTLS = tlsCheckbox.getState();
        
        selectedProfile = new ConnectionProfile(
            "Current Connection",
            hostname,
            port,
            model,
            useTLS
        );
        
        cancelled = false;
        dispose();
    }
    
    /**
     * Save current settings as a profile.
     */
    private void saveProfile() {
        String name = MessageDialog.showInput(
            (Frame) getParent(),
            "Save Profile",
            "Enter profile name:"
        );
        
        if (name != null && !name.trim().isEmpty()) {
            String hostname = hostnameField.getText().trim();
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ex) {
                port = 23;
            }
            
            String modelStr = modelChoice.getSelectedItem();
            String model = modelStr.substring(0, 7).trim();
            boolean useTLS = tlsCheckbox.getState();
            
            ConnectionProfile profile = new ConnectionProfile(name, hostname, port, model, useTLS);
            
            Map<String, ConnectionProfile> profiles = ConnectionProfile.loadProfiles();
            profiles.put(name, profile);
            ConnectionProfile.saveProfiles(profiles);
            
            // Refresh profile list
            String currentSelection = profileChoice.getSelectedItem();
            profileChoice.removeAll();
            profileChoice.add("(New Connection)");
            for (String profileName : profiles.keySet()) {
                profileChoice.add(profileName);
            }
            profileChoice.select(name);
            
            MessageDialog.showMessage((Frame) getParent(), "Saved", "Profile saved successfully");
        }
    }
    
    /**
     * Show manage profiles dialog.
     */
    private void showManageProfilesDialog() {
        setVisible(false);
        ManageProfilesDialog manageDialog = new ManageProfilesDialog((Frame) getParent());
        manageDialog.showDialog();
        
        // Refresh profile list
        String currentSelection = profileChoice.getSelectedItem();
        profileChoice.removeAll();
        profileChoice.add("(New Connection)");
        
        Map<String, ConnectionProfile> profiles = ConnectionProfile.loadProfiles();
        for (String profileName : profiles.keySet()) {
            profileChoice.add(profileName);
        }
        
        profileChoice.select(currentSelection);
        setVisible(true);
    }
    
    /**
     * Show the dialog and return the selected profile.
     * 
     * @return Selected profile, or null if cancelled
     */
    public ConnectionProfile showDialog() {
        setVisible(true);
        return cancelled ? null : selectedProfile;
    }
    
    /**
     * Check if the dialog was cancelled.
     * 
     * @return true if cancelled
     */
    public boolean wasCancelled() {
        return cancelled;
    }
}
