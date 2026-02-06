package com.tn3270.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

public class StatusBar extends JPanel {
	private JLabel statusLabel;
	private JLabel ipLabel;
	private JLabel positionLabel;

	// Scroll-back mode indicator
	private boolean scrollMode = false;
	private JLabel scrollModeLabel;

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

		// Create scroll mode indicator label
		scrollModeLabel = new JLabel();
		scrollModeLabel.setForeground(Color.RED);
		scrollModeLabel.setFont(getFont().deriveFont(Font.BOLD));
		scrollModeLabel.setVisible(false); // Hidden by default
		/*
		 * scrollModeLabel.setText(" SCROLL-BACK MODE ");
		 * scrollModeLabel.setForeground(Color.WHITE);
		 * scrollModeLabel.setBackground(Color.RED); scrollModeLabel.setOpaque(true);
		 * scrollModeLabel.setFont(getFont().deriveFont(Font.BOLD));
		 * scrollModeLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
		 */
		scrollModeLabel.setText("◄◄ SCROLL-BACK ►►");
		scrollModeLabel.setForeground(new Color(255, 100, 100)); // Lighter red
		scrollModeLabel.setFont(getFont().deriveFont(Font.BOLD, 12f));

		// Add to status bar layout
		// Position depends on your existing layout - add it in a visible location
		// Example: if using BoxLayout or FlowLayout
		// add(Box.createHorizontalStrut(10)); // Add some spacing
		add(scrollModeLabel);
	}

	/**
	 * Set scroll-back mode with page information.
	 * 
	 * @param scrollMode  true if in scroll-back mode
	 * @param currentPage current page index (0-based)
	 * @param totalPages  total number of pages available
	 */
	public void setScrollMode(boolean scrollMode, int currentPage, int totalPages) {
		this.scrollMode = scrollMode;

		if (scrollMode) {
			String pageInfo = String.format("◄ SCROLL-BACK: Page %d of %d ►", currentPage, totalPages);
			scrollModeLabel.setText(pageInfo);
			scrollModeLabel.setVisible(true);
		} else {
			scrollModeLabel.setVisible(false);
		}

		revalidate();
		repaint();
	}

	// Keep the simple version as well for backward compatibility
	public void setScrollMode(boolean scrollMode) {
		setScrollMode(scrollMode, 0, 0);
	}

	/**
	 * Set scroll-back mode state and update the indicator.
	 * 
	 * @param scrollMode true if in scroll-back mode, false if in live mode
	 */
	/*
	 * public void setScrollMode(boolean scrollMode) { this.scrollMode = scrollMode;
	 * 
	 * if (scrollMode) { scrollModeLabel.setText("◄◄ SCROLL-BACK MODE ►►");
	 * scrollModeLabel.setVisible(true); } else { scrollModeLabel.setVisible(false);
	 * }
	 * 
	 * // Force repaint to update display revalidate(); repaint(); }
	 */
	/**
	 * Check if currently in scroll-back mode.
	 * 
	 * @return true if in scroll-back mode
	 */
	public boolean isScrollMode() {
		return scrollMode;
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
