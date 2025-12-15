package ui;

import java.awt.*;

/**
 * Status bar with multiple information fields.
 * Shows connection status, cursor position, and mode indicators.
 */
public class StatusBar extends Panel {
    private Label statusLabel;
    private Label positionLabel;
    private Label modeLabel;
    private Label connectionLabel;
    
    public StatusBar() {
        setLayout(new FlowLayout(FlowLayout.LEFT, 10, 2));
        setBackground(Color.DARK_GRAY);
        
        // Main status message
        statusLabel = new Label("Ready");
        statusLabel.setForeground(Color.WHITE);
        add(statusLabel);
        
        // Separator
        add(createSeparator());
        
        // Cursor position
        positionLabel = new Label("Pos: 001/001");
        positionLabel.setForeground(Color.WHITE);
        add(positionLabel);
        
        // Separator
        add(createSeparator());
        
        // Mode indicator
        modeLabel = new Label("Mode: Replace");
        modeLabel.setForeground(Color.WHITE);
        add(modeLabel);
        
        // Separator
        add(createSeparator());
        
        // Connection status
        connectionLabel = new Label("⚫ Disconnected");
        connectionLabel.setForeground(Color.LIGHT_GRAY);
        add(connectionLabel);
    }
    
    /**
     * Create a visual separator.
     */
    private Label createSeparator() {
        Label sep = new Label("|");
        sep.setForeground(Color.GRAY);
        return sep;
    }
    
    /**
     * Set the main status message.
     */
    public void setStatus(String status) {
        statusLabel.setText(status);
    }
    
    /**
     * Update cursor position display.
     */
    public void setPosition(int row, int col) {
        positionLabel.setText(String.format("Pos: %03d/%03d", row, col));
    }
    
    /**
     * Update mode display.
     */
    public void setMode(String mode, boolean locked) {
        String text = "Mode: " + mode;
        if (locked) {
            text += " [LOCKED]";
            modeLabel.setForeground(Color.YELLOW);
        } else {
            modeLabel.setForeground(Color.WHITE);
        }
        modeLabel.setText(text);
    }
    
    /**
     * Update connection status.
     */
    public void setConnected(boolean connected) {
        if (connected) {
            connectionLabel.setText("🟢 Connected");
            connectionLabel.setForeground(Color.GREEN);
        } else {
            connectionLabel.setText("⚫ Disconnected");
            connectionLabel.setForeground(Color.LIGHT_GRAY);
        }
    }
    
    /**
     * Update all status fields at once.
     */
    public void updateAll(String status, int row, int col, String mode, 
                         boolean locked, boolean connected) {
        setStatus(status);
        setPosition(row, col);
        setMode(mode, locked);
        setConnected(connected);
    }

    /**
     * Clean up resources.
     */
    public void dispose() {
        // Stop any timers if you have them
        // Currently StatusBar doesn't need cleanup
    }
}
