package com.tn3270.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

public class KeyCaptureDialog extends JDialog {
	public int capturedKeyCode = -1;
	public char capturedChar = 0;
	public boolean cleared = false;
	private final boolean charMode;

	public KeyCaptureDialog(Window owner, String prompt, boolean charMode) {
		super(owner, "Press Key", ModalityType.APPLICATION_MODAL);
		this.charMode = charMode;

		setLayout(new BorderLayout());
		setSize(400, 180);
		setLocationRelativeTo(owner);

		JLabel lbl = new JLabel(
				"<html><center>" + prompt + "<br><small>"
						+ (charMode ? "(Type the character)" : "(Press the physical key)") + "</small></center></html>",
				SwingConstants.CENTER);
		lbl.setFont(new Font("SansSerif", Font.BOLD, 14));
		add(lbl, BorderLayout.CENTER);

		JPanel bottom = new JPanel(new FlowLayout());
		JLabel sub = new JLabel("(Press Esc to cancel)   ", SwingConstants.CENTER);
		sub.setForeground(Color.GRAY);
		bottom.add(sub);

		if (!charMode) {
			JButton clearBtn = new JButton("Clear Assignment");
			clearBtn.setForeground(Color.RED);
			clearBtn.addActionListener(e -> {
				cleared = true;
				dispose();
			});
			bottom.add(clearBtn);
		}

		add(bottom, BorderLayout.SOUTH);

		addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				int code = e.getKeyCode();
				if (code == KeyEvent.VK_ESCAPE) {
					capturedKeyCode = -1;
					capturedChar = 0;
					dispose();
					return;
				}

				if (charMode)
					return;

				if (code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT
						|| code == KeyEvent.VK_META) {
					return;
				}

				capturedKeyCode = code;
			}

			@Override
			public void keyTyped(KeyEvent e) {
				if (e.getKeyChar() != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(e.getKeyChar())
						&& e.getKeyChar() != 27) {

					capturedChar = e.getKeyChar();
					if (charMode)
						dispose();
				}
			}

			@Override
			public void keyReleased(KeyEvent e) {
				if (charMode)
					return;
				if (capturedKeyCode != -1) {
					dispose();
				}
			}
		});

		setFocusable(true);
	}
}
