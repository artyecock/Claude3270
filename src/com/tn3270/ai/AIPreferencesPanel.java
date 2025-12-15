package com.tn3270.ai;

import javax.swing.*;
import java.awt.*;
import java.io.*;

public class AIPreferencesPanel {
    private final Frame owner;
    private final AIConfig config;
    private final JDialog dialog;
    private final JTextArea ta;

    public AIPreferencesPanel(Frame owner, AIConfig cfg) {
        this.owner = owner;
        this.config = cfg;

        dialog = new JDialog(owner, "AI Preferences", true);
        dialog.setLayout(new BorderLayout());

        ta = new JTextArea(20, 80);
        ta.setFont(new Font("Monospaced", Font.PLAIN, 12));

        try {
            StringBuilder sb = new StringBuilder();
            File f = cfg.file();
            if (f.exists())
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String line;
                    while ((line = r.readLine()) != null)
                        sb.append(line).append('\n');
                }
            ta.setText(sb.toString());
        } catch (Exception e) {
            ta.setText("# error reading ai.conf: " + e.getMessage());
        }

        JScrollPane scroll = new JScrollPane(ta);

        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton save = new JButton("Save");
        JButton cancel = new JButton("Cancel");

        p.add(save);
        p.add(cancel);

        save.addActionListener(e -> {
            try (FileWriter fw = new FileWriter(config.file())) {
                fw.write(ta.getText());
                fw.flush();
            } catch (Exception se) {
                System.out.println("Exception saving config: " + se.getMessage());
            }
            config.load();
            AIManager.getInstance().reloadConfig();
            dialog.dispose();
        });

        cancel.addActionListener(e -> dialog.dispose());

        JPopupMenu popup = new JPopupMenu();
        JMenuItem cut = new JMenuItem("Cut");
        cut.addActionListener(e -> ta.cut());
        JMenuItem copy = new JMenuItem("Copy");
        copy.addActionListener(e -> ta.copy());
        JMenuItem paste = new JMenuItem("Paste");
        paste.addActionListener(e -> ta.paste());
        popup.add(cut);
        popup.add(copy);
        popup.add(paste);
        ta.setComponentPopupMenu(popup);

        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(p, BorderLayout.SOUTH);
        dialog.setSize(700, 500);
        dialog.setLocationRelativeTo(owner);
    }

    public void showDialog() {
        dialog.setVisible(true);
    }
}
