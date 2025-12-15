package ui.dialogs;

import config.ConnectionProfile;

import java.awt.*;
import java.awt.event.*;
import java.util.*;

/**
 * Dialog for managing saved connection profiles.
 * Allows viewing, editing, and deleting profiles.
 * 
 * Extracted from TN3270Emulator-Monolithic.java lines 1242-1285
 */
public class ManageProfilesDialog extends Dialog {
    
    private java.awt.List profileList;
    private Map<String, ConnectionProfile> profiles;
    
    /**
     * Creates a new manage profiles dialog.
     * 
     * @param parent Parent frame
     */
    public ManageProfilesDialog(Frame parent) {
        super(parent, "Manage Profiles", true);
        setLayout(new BorderLayout(10, 10));
        
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                dispose();
            }
        });
        
        // Load profiles
        profiles = ConnectionProfile.loadProfiles();
        
        // Profile list
        profileList = new java.awt.List(10, false);
        for (String profileName : profiles.keySet()) {
            profileList.add(profileName);
        }
        add(profileList, BorderLayout.CENTER);
        
        // Button panel
        Panel buttonPanel = new Panel(new FlowLayout());
        
        Button viewButton = new Button("View");
        viewButton.addActionListener(e -> viewProfile());
        buttonPanel.add(viewButton);
        
        Button deleteButton = new Button("Delete");
        deleteButton.addActionListener(e -> deleteProfile());
        buttonPanel.add(deleteButton);
        
        Button closeButton = new Button("Close");
        closeButton.addActionListener(e -> dispose());
        buttonPanel.add(closeButton);
        
        add(buttonPanel, BorderLayout.SOUTH);
        
        pack();
        setLocationRelativeTo(parent);
    }
    
    /**
     * View selected profile details.
     */
    private void viewProfile() {
        String selected = profileList.getSelectedItem();
        if (selected == null) {
            MessageDialog.showMessage(
                (Frame) getParent(),
                "No Selection",
                "Please select a profile to view"
            );
            return;
        }
        
        ConnectionProfile profile = profiles.get(selected);
        if (profile != null) {
            String details = String.format(
                "Profile: %s\n\n" +
                "Hostname: %s\n" +
                "Port: %d\n" +
                "Model: %s\n" +
                "TLS: %s",
                //profile.name,
                //profile.hostname,
                //profile.port,
                //profile.model,
                //profile.useTLS ? "Yes" : "No"
                profile.getName(),
                profile.getHostname(),
                profile.getPort(),
                profile.getModel(),
                profile.useTLS()
            );
            
            MessageDialog.showMultilineMessage(
                (Frame) getParent(),
                "Profile Details",
                details
            );
        }
    }
    
    /**
     * Delete selected profile.
     */
    private void deleteProfile() {
        String selected = profileList.getSelectedItem();
        if (selected == null) {
            MessageDialog.showMessage(
                (Frame) getParent(),
                "No Selection",
                "Please select a profile to delete"
            );
            return;
        }
        
        int confirm = MessageDialog.showConfirm(
            (Frame) getParent(),
            "Confirm Delete",
            "Delete profile '" + selected + "'?"
        );
        
        if (confirm == 0) { // Yes
            profiles.remove(selected);
            ConnectionProfile.saveProfiles(profiles);
            profileList.remove(selected);
            
            MessageDialog.showMessage(
                (Frame) getParent(),
                "Deleted",
                "Profile deleted successfully"
            );
        }
    }
    
    /**
     * Show the dialog.
     */
    public void showDialog() {
        setVisible(true);
    }
}
