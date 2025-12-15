package ui.dialogs;

import java.awt.*;
import java.awt.event.*;

/**
 * Progress dialog for showing file transfer progress.
 * Displays operation name, progress message, and status.
 * 
 * Extracted from TN3270Emulator-Monolithic.java lines 1840-1886
 */
public class ProgressDialog extends Dialog {
    
    private Label progressLabel;
    private Label statusLabel;
    private Button cancelButton;
    private boolean cancelled = false;
    private CancelListener cancelListener;
    
    /**
     * Callback interface for cancel button.
     */
    public interface CancelListener {
        void onCancel();
    }
    
    /**
     * Creates a new progress dialog.
     * 
     * @param parent Parent frame
     * @param operation Operation name (e.g., "Uploading", "Downloading")
     */
    public ProgressDialog(Frame parent, String operation) {
        super(parent, "File Transfer", false); // Non-modal
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // Operation label
        gbc.gridx = 0;
        gbc.gridy = 0;
        Label opLabel = new Label(operation);
        opLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        add(opLabel, gbc);
        
        // Progress label
        gbc.gridy = 1;
        progressLabel = new Label("Initializing...");
        progressLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        add(progressLabel, gbc);
        
        // Status label
        gbc.gridy = 2;
        statusLabel = new Label(" ");
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        statusLabel.setForeground(new Color(100, 100, 100));
        add(statusLabel, gbc);
        
        // Cancel button
        gbc.gridy = 3;
        gbc.anchor = GridBagConstraints.CENTER;
        cancelButton = new Button("Cancel");
        cancelButton.addActionListener(e -> {
            cancelled = true;
            if (cancelListener != null) {
                cancelListener.onCancel();
            }
            dispose();
        });
        add(cancelButton, gbc);
        
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                cancelled = true;
                if (cancelListener != null) {
                    cancelListener.onCancel();
                }
                dispose();
            }
        });
        
        pack();
        setLocationRelativeTo(parent);
    }
    
    /**
     * Update progress message.
     * 
     * @param progress Progress message (e.g., "Block 5 of 10")
     */
    public void updateProgress(String progress) {
        if (progressLabel != null) {
            progressLabel.setText(progress);
        }
    }
    
    /**
     * Update status message.
     * 
     * @param status Status message (e.g., "5120 bytes transferred")
     */
    public void updateStatus(String status) {
        if (statusLabel != null) {
            statusLabel.setText(status);
        }
    }
    
    /**
     * Update both progress and status.
     * 
     * @param progress Progress message
     * @param status Status message
     */
    public void update(String progress, String status) {
        updateProgress(progress);
        updateStatus(status);
        pack();
    }
    
    /**
     * Set cancel listener.
     * 
     * @param listener Cancel listener
     */
    public void setCancelListener(CancelListener listener) {
        this.cancelListener = listener;
    }
    
    /**
     * Check if transfer was cancelled.
     * 
     * @return true if cancelled
     */
    public boolean wasCancelled() {
        return cancelled;
    }
    
    /**
     * Close the dialog.
     */
    public void close() {
        dispose();
    }
    
    /**
     * Show the dialog.
     */
    public void showDialog() {
        setVisible(true);
    }
}
