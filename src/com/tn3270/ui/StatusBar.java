package com.tn3270.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class StatusBar extends JPanel {
    private JLabel statusLabel;
    private JLabel ipLabel;
    private JLabel positionLabel;

    public StatusBar() {
        setLayout(new BorderLayout());
        setBackground(Color.DARK_GRAY);
        setBorder(new EmptyBorder(2, 5, 2, 5));

        // LEFT: Connection Status
        statusLabel = new JLabel("Not connected");
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        add(statusLabel, BorderLayout.WEST);

        // RIGHT: Container
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 0));
        rightPanel.setOpaque(false);

        // 1. IP Address
        ipLabel = new JLabel("");
        ipLabel.setForeground(new Color(200, 200, 200));
        ipLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        rightPanel.add(ipLabel);

        // 2. Cursor Position
        positionLabel = new JLabel("Row: 01 Col: 01");
        positionLabel.setForeground(Color.WHITE);
        positionLabel.setFont(new Font("Monospaced", Font.BOLD, 12));
        rightPanel.add(positionLabel);

        add(rightPanel, BorderLayout.EAST);
    }

    public void setStatus(String status) {
        statusLabel.setText(status);
    }

    public void setIP(String ip) {
        if (ip == null || ip.isEmpty()) {
            ipLabel.setText("");
            ipLabel.setToolTipText(null);
            return;
        }
        String displayIP = ip;
        if (ip.contains(":") && ip.length() > 20) {
            displayIP = ip.substring(0, 8) + "..." + ip.substring(ip.length() - 4);
            ipLabel.setToolTipText("Remote IP: " + ip);
        } else {
            ipLabel.setToolTipText(null);
        }
        ipLabel.setText("[" + displayIP + "]");
    }

    public void updatePosition(int rows, int cols, int cursorPos) {
        int row = (cursorPos / cols) + 1;
        int col = (cursorPos % cols) + 1;
        positionLabel.setText(String.format("Row: %02d Col: %02d", row, col));
    }
    
    // Convenience for session to call update without params if it manages state, 
    // but here we prefer passing data in.
}
