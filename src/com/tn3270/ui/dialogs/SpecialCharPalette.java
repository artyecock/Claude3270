package com.tn3270.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

public class SpecialCharPalette extends JDialog {
	private String selectedChar = null;

	// Common 3270 / EBCDIC symbols (31 items)
	private final String[] SYMBOLS = { "¬", "¢", "¦", "|", "\\", "[", "]", "{", "}", "μ", "ß", "!", "@", "#", "$", "%",
			"^", "&", "*", "(", ")", "_", "+", "-", "=", "<", ">", "?", "/", "~", "`" };

	private final String[] DESCRIPTIONS = { "Not Sign", "Cent", "Broken Bar", "Solid Pipe", "Backslash", "Left Bracket",
			"Right Bracket", "Left Brace", "Right Brace", "Mu", "Eszett", "Exclamation", "At", "Hash", "Dollar",
			"Percent", "Caret", "Ampersand", "Star", "L-Paren", "R-Paren", "Underscore", "Plus", "Minus", "Equals",
			"Less", "Greater", "Question", "Slash", "Tilde", "Backtick" };

	public SpecialCharPalette(Window owner) {
		super(owner, "Select Character", ModalityType.APPLICATION_MODAL);
		setLayout(new BorderLayout());
		setSize(550, 400);
		setLocationRelativeTo(owner);

		JLabel lbl = new JLabel("Select a symbol or enter a Hex code:", SwingConstants.CENTER);
		lbl.setBorder(new EmptyBorder(10, 10, 10, 10));
		add(lbl, BorderLayout.NORTH);

		// 4 Columns x 8 Rows = 32 Slots.
		JPanel grid = new JPanel(new GridLayout(0, 4, 5, 5));
		grid.setBorder(new EmptyBorder(10, 10, 10, 10));

		// 1. Add Standard Symbols
		for (int i = 0; i < SYMBOLS.length; i++) {
			final String s = SYMBOLS[i];
			final String d = DESCRIPTIONS[i];
			JButton btn = new JButton(s);
			btn.setFont(new Font("Monospaced", Font.BOLD, 16));
			btn.setToolTipText(d);
			btn.addActionListener(e -> {
				selectedChar = s;
				dispose();
			});
			grid.add(btn);
		}

		// 2. Add Hex Input Button (The 32nd Slot)
		JButton hexBtn = new JButton(
				"<html><center><font size='3'>Hex</font><br><font size='2'>Code...</font></center></html>");
		hexBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
		hexBtn.setBackground(new Color(230, 240, 255));
		hexBtn.setToolTipText("Enter a specific Unicode character by Hex value (e.g. 00A5)");

		hexBtn.addActionListener(e -> {
			String input = JOptionPane.showInputDialog(this, "Enter 4-digit Hex Code (e.g. 00AC for ¬):", "Hex Input",
					JOptionPane.PLAIN_MESSAGE);

			if (input != null && !input.trim().isEmpty()) {
				try {
					// Clean input (handle 0x, U+, etc)
					String clean = input.trim().replace("0x", "").replace("U+", "").replace("u+", "");
					int codePoint = Integer.parseInt(clean, 16);
					selectedChar = String.valueOf((char) codePoint);
					dispose();
				} catch (NumberFormatException ex) {
					JOptionPane.showMessageDialog(this,
							"Invalid Hex Code. Please enter hexadecimal numbers (0-9, A-F).", "Error",
							JOptionPane.ERROR_MESSAGE);
				}
			}
		});
		grid.add(hexBtn);

		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> dispose());

		add(new JScrollPane(grid), BorderLayout.CENTER);
		add(cancel, BorderLayout.SOUTH);
	}

	public String getSelected() {
		return selectedChar;
	}
}
