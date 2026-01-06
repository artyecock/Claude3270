package com.tn3270.ai;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseWheelListener;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

public class AIMessageBubble extends JPanel {
	public final String who;
	private final JTextArea textArea;
	private final JScrollPane scrollPane;
	private final Color bubbleColor;
	private final Color borderColor;

	public AIMessageBubble(String who, String text, Font font, Color fg, Color bg, Consumer<String> onSaveAction) {
		this.who = who;
		this.setLayout(new BorderLayout());
		this.setOpaque(false);

		this.bubbleColor = bg;
		this.borderColor = new Color(Math.max(0, bg.getRed() - 30), Math.max(0, bg.getGreen() - 30),
				Math.max(0, bg.getBlue() - 30));

		textArea = new JTextArea(text);
		textArea.setFont(font);
		textArea.setForeground(fg);
		textArea.setBackground(bubbleColor);
		textArea.setEditable(false);

		textArea.setLineWrap(true);
		textArea.setWrapStyleWord(true);
		textArea.setBorder(new EmptyBorder(5, 5, 5, 5));
		textArea.setSelectionColor(new Color(50, 100, 255));
		textArea.setSelectedTextColor(Color.WHITE);

		scrollPane = new JScrollPane(textArea);
		scrollPane.setBorder(null);
		scrollPane.setOpaque(false);
		scrollPane.getViewport().setOpaque(false);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

		JPanel bubblePanel = new JPanel(new BorderLayout()) {
			@Override
			protected void paintComponent(Graphics g) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(bubbleColor);
				g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
				g2.setColor(borderColor);
				g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
				g2.dispose();
			}

			@Override
			public Dimension getPreferredSize() {
				Dimension d = textArea.getPreferredSize();
				Container parent = getParent();
				if (parent != null && parent.getParent() != null) {
					int scrollWidth = parent.getParent().getWidth();
					if (scrollWidth > 0) {
						int maxW = (int) (scrollWidth * 0.90);
						if (textArea.getLineWrap()) {
							textArea.setSize(new Dimension(maxW, Short.MAX_VALUE));
							Dimension pref = textArea.getPreferredSize();
							int targetW = Math.min(pref.width + 25, maxW);
							return new Dimension(targetW, pref.height + 25);
						} else {
							int targetW = Math.min(d.width + 25, maxW);
							int scrollBarHeight = (d.width > maxW) ? 20 : 0;
							return new Dimension(targetW, d.height + 25 + scrollBarHeight);
						}
					}
				}
				return new Dimension(d.width + 25, d.height + 25);
			}
		};

		bubblePanel.setOpaque(false);
		bubblePanel.setBorder(new EmptyBorder(4, 4, 4, 4));
		bubblePanel.add(scrollPane, BorderLayout.CENTER);

		// --- Context Menu ---
		JPopupMenu popup = new JPopupMenu();
		JMenuItem copyItem = new JMenuItem("Copy Message");
		copyItem.addActionListener(e -> {
			textArea.selectAll();
			textArea.copy();
			textArea.setCaretPosition(0);
		});

		JCheckBoxMenuItem wrapItem = new JCheckBoxMenuItem("Word Wrap", true);
		wrapItem.addActionListener(e -> {
			boolean wrap = wrapItem.isSelected();
			textArea.setLineWrap(wrap);
			textArea.setWrapStyleWord(wrap);
			bubblePanel.revalidate();
			if (getParent() != null)
				getParent().revalidate();
			repaint();
		});

		popup.add(copyItem);
		popup.addSeparator();
		popup.add(wrapItem);

		textArea.setComponentPopupMenu(popup);

		JPanel alignPanel = new JPanel(new FlowLayout("user".equals(who) ? FlowLayout.RIGHT : FlowLayout.LEFT));
		alignPanel.setOpaque(false);
		alignPanel.add(bubblePanel);

		add(alignPanel, BorderLayout.CENTER);

		MouseWheelListener forwarder = e -> {
			if (!scrollPane.getVerticalScrollBar().isVisible()) {
				JScrollPane parentScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class,
						getParent());
				if (parentScroll != null) {
					parentScroll.dispatchEvent(SwingUtilities.convertMouseEvent(e.getComponent(), e, parentScroll));
				}
			}
		};
		textArea.addMouseWheelListener(forwarder);
		scrollPane.addMouseWheelListener(forwarder);

		// --- ACTION BAR (For AI Responses) ---
		if ("assistant".equalsIgnoreCase(who)) {
			JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
			actionsPanel.setOpaque(false);

			// 1. Copy Button
			JButton copyBtn = new JButton("Copy");
			copyBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
			copyBtn.setMargin(new Insets(1, 4, 1, 4));
			copyBtn.addActionListener(e -> {
				try {
					String currentContent = textArea.getText();
					Toolkit.getDefaultToolkit().getSystemClipboard()
							.setContents(new java.awt.datatransfer.StringSelection(currentContent), null);
				} catch (Exception ex) {
				}
			});
			actionsPanel.add(copyBtn);

			// 2. Save to Host Button
			if (onSaveAction != null) {
				JButton saveHostBtn = new JButton("Save to Host");
				saveHostBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
				saveHostBtn.setMargin(new Insets(1, 4, 1, 4));

				saveHostBtn.addActionListener(e -> {
					// --- FIX: Get text from textArea, NOT the constructor argument ---
					String currentContent = textArea.getText();
					onSaveAction.accept(currentContent);
				});
				actionsPanel.add(saveHostBtn);
			}

			add(actionsPanel, BorderLayout.SOUTH);
		}
	}

	public void appendText(String s) {
		textArea.append(s);
		// Keep caret at end to auto-scroll during streaming
		textArea.setCaretPosition(textArea.getDocument().getLength());
		revalidate();
		repaint();
	}

	// Optional: Public getter if needed by other components
	public String getText() {
		return textArea.getText();
	}
}
