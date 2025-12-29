package com.tn3270.ui.dialogs;

import com.tn3270.TN3270Session;
import com.tn3270.TN3270Session.KeyMapping;
// Make sure this imports the NEW ProtocolConstants
import static com.tn3270.constants.ProtocolConstants.*;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KeyboardSettingsDialog extends JDialog {
    private final TN3270Session session;
    private final Map<Integer, KeyMapping> tempKeyMap;
    private final Map<Character, Character> tempCharMap;
    private final JLabel statusLabel;

    private boolean shift3270Active = false;
    private final List<JButton> buttons3270 = new ArrayList<>();
    private JPanel panel3270;

    private boolean shiftPCActive = false;
    private final List<JButton> buttonsPC = new ArrayList<>();
    private JPanel panelPC;

    private Border defaultBorder;
    private Color defaultBg;
    private boolean stylesCaptured = false;

    private static final int KW = 60;
    private static final int KH = 50;
    private static final int GAP = 5;
    private static final int START_X = 20;
    private static final int START_Y = 20;

    // --- FIX 1: Updated Local Array to int[] to match ProtocolConstants ---
    // Note: We reference ProtocolConstants.AID_* directly now for safety
    private static final int[] PF_AID_ARRAY = new int[]{
            AID_PF1, AID_PF2, AID_PF3, AID_PF4, AID_PF5, AID_PF6,
            AID_PF7, AID_PF8, AID_PF9, AID_PF10, AID_PF11, AID_PF12,
            AID_PF13, AID_PF14, AID_PF15, AID_PF16, AID_PF17, AID_PF18,
            AID_PF19, AID_PF20, AID_PF21, AID_PF22, AID_PF23, AID_PF24
    };

    public KeyboardSettingsDialog(Frame owner, TN3270Session session) {
        super(owner, "Keyboard Remapping", true);
        this.session = session;
        this.tempKeyMap = new HashMap<>(session.getKeyMap());
        this.tempCharMap = new HashMap<>(session.getInputCharMap());

        setLayout(new BorderLayout());

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT));
        header.setBackground(new Color(45, 45, 48));
        JLabel title = new JLabel("Configure your keyboard interaction.");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 14));
        header.add(title);
        add(header, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("1. Map 3270 Functions", null, create3270Panel(), "Assign physical keys to 3270 functions");
        tabs.addTab("2. Translate PC Characters", null, createPCPanel(), "Change what specific characters type");
        add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(new EmptyBorder(10, 10, 10, 10));
        statusLabel = new JLabel("Green Border = Mapped/Translated.");
        statusLabel.setForeground(Color.DARK_GRAY);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveBtn = new JButton("Save & Close");
        JButton cancelBtn = new JButton("Cancel");
        JButton resetBtn = new JButton("Reset Defaults");

        saveBtn.addActionListener(e -> {
            session.getKeyMap().clear(); session.getKeyMap().putAll(tempKeyMap);
            session.getInputCharMap().clear(); session.getInputCharMap().putAll(tempCharMap);
            session.saveKeyMappings();
            dispose();
        });
        cancelBtn.addActionListener(e -> dispose());
        resetBtn.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Reset all mappings?", "Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                tempKeyMap.clear(); tempCharMap.clear();
                statusLabel.setText("Cleared. Re-open to load defaults.");
                refresh3270Visuals(); refreshPCVisuals();
            }
        });

        btnPanel.add(resetBtn); btnPanel.add(cancelBtn); btnPanel.add(saveBtn);
        footer.add(statusLabel, BorderLayout.WEST); footer.add(btnPanel, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        pack();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int w = Math.min(getWidth(), screen.width - 50);
        int h = Math.min(getHeight(), screen.height - 50);
        setSize(w, h);
        setLocationRelativeTo(owner);

        SwingUtilities.invokeLater(() -> { refresh3270Visuals(); refreshPCVisuals(); });
    }

    private JScrollPane create3270Panel() {
        panel3270 = new JPanel(null);
        panel3270.setPreferredSize(new Dimension(1220, 600));
        panel3270.setBackground(new Color(220, 225, 230));

        int x = START_X, y = START_Y;
        int controlX = START_X + (15 * (KW + GAP)) + 40;

        for (int i = 1; i <= 12; i++) {
            // FIX: Array is now int[]
            add3270Button(panel3270, "PF" + i, PF_AID_ARRAY[i - 1], x, y, KW, KH);
            x += KW + GAP;
            if (i % 4 == 0) x += (GAP * 2);
        }

        int paX = controlX;
        add3270Button(panel3270, "PA1", AID_PA1, paX, y, KW, KH);
        add3270Button(panel3270, "PA2", AID_PA2, paX + KW + GAP, y, KW, KH);
        add3270Button(panel3270, "PA3", AID_PA3, paX + (KW + GAP) * 2, y, KW, KH);

        x = START_X; y += KH + GAP + 5;
        for (int i = 13; i <= 24; i++) {
            add3270Button(panel3270, "PF" + i, PF_AID_ARRAY[i - 1], x, y, KW, KH);
            x += KW + GAP;
            if (i == 16 || i == 20) x += (GAP * 2);
        }
        add3270Button(panel3270, "CLEAR", AID_CLEAR, controlX, y, (KW * 3) + (GAP * 2), KH);

        x = START_X; y += KH + GAP + 30; int row3Y = y;
        String[] r1Normal = { "¬", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=" };
        String[] r1Shift = { "~", "!", "@", "#", "$", "%", "¬", "&", "*", "(", ")", "_", "+" };
        for (int i = 0; i < r1Normal.length; i++) {
            add3270CharButton(panel3270, r1Normal[i], r1Shift[i], x, y, KW, KH);
            x += KW + GAP;
        }
        // FIX: Use 0 instead of (byte)0
        add3270Button(panel3270, "Back", 0, x, y, KW * 2, KH);
        add3270Button(panel3270, "ATTN", AID_ATTN, controlX, row3Y, 80, KH); // Used constant

        y += KH + GAP; int row4Y = y; x = START_X;
        add3270Button(panel3270, "Tab", 0, x, y, (int) (KW * 1.5), KH); x += (int) (KW * 1.5) + GAP;
        String[] r2Normal = { "q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "[", "]", "\\" };
        String[] r2Shift = { "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "{", "}", "|" };
        for (int i = 0; i < r2Normal.length; i++) {
            add3270CharButton(panel3270, r2Normal[i], r2Shift[i], x, y, KW, KH); x += KW + GAP;
        }
        add3270Button(panel3270, "SYS REQ", AID_SYSREQ, controlX, row4Y, 80, KH);

        y += KH + GAP; int row5Y = y; x = START_X;
        JToggleButton ctrlVis = new JToggleButton("Ctrl"); ctrlVis.setBounds(x, y, (int) (KW * 1.8), KH); panel3270.add(ctrlVis); x += (int) (KW * 1.8) + GAP;
        String[] r3Normal = { "a", "s", "d", "f", "g", "h", "j", "k", "l", ";", "'" };
        String[] r3Shift = { "A", "S", "D", "F", "G", "H", "J", "K", "L", ":", "\"" };
        for (int i = 0; i < r3Normal.length; i++) {
            add3270CharButton(panel3270, r3Normal[i], r3Shift[i], x, y, KW, KH); x += KW + GAP;
        }
        add3270Button(panel3270, "Enter", AID_ENTER, x, y, (int) (KW * 2.2), KH);
        add3270Button(panel3270, "RESET", 0, controlX, row5Y, 80, KH);

        y += KH + GAP; int row6Y = y; x = START_X;
        JToggleButton shiftToggleL = new JToggleButton("Shift");
        shiftToggleL.setBounds(x, y, (int) (KW * 2.3), KH);
        shiftToggleL.addActionListener(e -> update3270ShiftState(shiftToggleL.isSelected()));
        panel3270.add(shiftToggleL); x += (int) (KW * 2.3) + GAP;
        String[] r4Normal = { "z", "x", "c", "v", "b", "n", "m", ",", ".", "/" };
        String[] r4Shift = { "Z", "X", "C", "V", "B", "N", "M", "<", ">", "?" };
        for (int i = 0; i < r4Normal.length; i++) {
            add3270CharButton(panel3270, r4Normal[i], r4Shift[i], x, y, KW, KH); x += KW + GAP;
        }
        add3270Button(panel3270, "R-Shift", 0, x, y, (int) (KW * 2.7), KH);
        add3270Button(panel3270, "EraseEOF", 0, controlX, row6Y, 80, KH);

        y += KH + GAP; int row7Y = y; x = START_X + (KW * 4);
        add3270CharButton(panel3270, "Space", "Space", x, y, KW * 6, KH);
        add3270Button(panel3270, "NEWLINE", AID_ENTER, controlX, row7Y, 80, KH);

        return new JScrollPane(panel3270);
    }

    private JScrollPane createPCPanel() {
        panelPC = new JPanel(null);
        panelPC.setPreferredSize(new Dimension(1100, 400));
        panelPC.setBackground(new Color(240, 240, 245));

        int x = START_X, y = START_Y + 50;
        JLabel help = new JLabel("<html><b>Visual PC Keyboard:</b> Click a character to translate it.<br>Example: Toggle Shift, Click '^', Select '¬'.</html>");
        help.setBounds(START_X, 10, 800, 30); help.setFont(new Font("SansSerif", Font.PLAIN, 12)); panelPC.add(help);

        String[] pc1Normal = { "`", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=" };
        String[] pc1Shift = { "~", "!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "_", "+" };
        for (int i = 0; i < pc1Normal.length; i++) {
            addPCCharButton(panelPC, pc1Normal[i], pc1Shift[i], x, y, KW, KH); x += KW + GAP;
        }
        addPCStubButton(panelPC, "Bksp", x, y, KW * 2, KH);

        y += KH + GAP; x = START_X;
        addPCStubButton(panelPC, "Tab", x, y, (int) (KW * 1.5), KH); x += (int) (KW * 1.5) + GAP;
        String[] pc2Normal = { "q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "[", "]", "\\" };
        String[] pc2Shift = { "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "{", "}", "|" };
        for (int i = 0; i < pc2Normal.length; i++) {
            addPCCharButton(panelPC, pc2Normal[i], pc2Shift[i], x, y, KW, KH); x += KW + GAP;
        }

        y += KH + GAP; x = START_X;
        addPCStubButton(panelPC, "Caps", x, y, (int) (KW * 1.8), KH); x += (int) (KW * 1.8) + GAP;
        String[] pc3Normal = { "a", "s", "d", "f", "g", "h", "j", "k", "l", ";", "'" };
        String[] pc3Shift = { "A", "S", "D", "F", "G", "H", "J", "K", "L", ":", "\"" };
        for (int i = 0; i < pc3Normal.length; i++) {
            addPCCharButton(panelPC, pc3Normal[i], pc3Shift[i], x, y, KW, KH); x += KW + GAP;
        }
        addPCStubButton(panelPC, "Enter", x, y, (int) (KW * 2.2), KH);

        y += KH + GAP; x = START_X;
        JToggleButton shiftPC = new JToggleButton("Shift");
        shiftPC.setBounds(x, y, (int) (KW * 2.3), KH);
        shiftPC.addActionListener(e -> updatePCShiftState(shiftPC.isSelected()));
        panelPC.add(shiftPC); x += (int) (KW * 2.3) + GAP;
        String[] pc4Normal = { "z", "x", "c", "v", "b", "n", "m", ",", ".", "/" };
        String[] pc4Shift = { "Z", "X", "C", "V", "B", "N", "M", "<", ">", "?" };
        for (int i = 0; i < pc4Normal.length; i++) {
            addPCCharButton(panelPC, pc4Normal[i], pc4Shift[i], x, y, KW, KH); x += KW + GAP;
        }

        return new JScrollPane(panelPC);
    }

    private void update3270ShiftState(boolean shifted) {
        this.shift3270Active = shifted; refresh3270Visuals(); panel3270.repaint();
    }

    private void add3270CharButton(JPanel p, String normal, String shifted, int x, int y, int w, int h) {
        JButton btn = new JButton(); setupButton(btn, p, x, y, w, h, "3270CHAR:" + normal);
        btn.putClientProperty("chars", new String[] { normal, shifted });
        btn.addActionListener(e -> {
            String currentLbl = getVisualLabel(btn, shift3270Active);
            KeyCaptureDialog cap = new KeyCaptureDialog(this, "Press desired physical key to act as '" + currentLbl + "' key", false);
            cap.setVisible(true);
            if (cap.cleared) {
                char targetC = currentLbl.equals("Space") ? ' ' : currentLbl.charAt(0);
                removeMappingsForTarget(targetC);
                statusLabel.setText("Restored default for '" + targetC + "'");
                refresh3270Visuals();
            } else if (cap.capturedKeyCode != -1) {
                String[] chars = (String[]) btn.getClientProperty("chars");
                String targetS = shift3270Active ? chars[1] : chars[0];
                char targetC = targetS.equals("Space") ? ' ' : targetS.charAt(0);
                tempKeyMap.put(cap.capturedKeyCode, new KeyMapping(targetC, "Char: " + targetC));
                refresh3270Visuals();
            }
        });
        buttons3270.add(btn);
    }

    // FIX: Changed signature to take int aidCode
    private void add3270Button(JPanel p, String label, int aidCode, int x, int y, int w, int h) {
        JButton btn = new JButton(); setupButton(btn, p, x, y, w, h, "3270FUNC:" + label);
        btn.addActionListener(e -> {
            KeyCaptureDialog cap = new KeyCaptureDialog(this, "Press physical key for " + label, false);
            cap.setVisible(true);
            if (cap.cleared) {
                removeMappingsForFunction(label, aidCode);
                refresh3270Visuals();
            } else if (cap.capturedKeyCode != -1) {
                KeyMapping newMap;
                // FIX: Use 0 instead of (byte)0
                if (label.equals("RESET")) newMap = new KeyMapping(0, "RESET");
                else if (label.equals("EraseEOF")) newMap = new KeyMapping(0, "EraseEOF");
                else if (label.equals("NEWLINE") || label.equals("R-Shift")) newMap = new KeyMapping(AID_ENTER, "NEWLINE");
                else if (label.equals("Back")) newMap = new KeyMapping(0, "BACKSPACE");
                else if (label.equals("Tab")) newMap = new KeyMapping(0, "TAB");
                else newMap = new KeyMapping(aidCode, label);
                tempKeyMap.put(cap.capturedKeyCode, newMap);
                refresh3270Visuals();
            }
        });
        buttons3270.add(btn);
    }

    private void updatePCShiftState(boolean shifted) {
        this.shiftPCActive = shifted; refreshPCVisuals(); panelPC.repaint();
    }

    private void addPCCharButton(JPanel p, String normal, String shifted, int x, int y, int w, int h) {
        JButton btn = new JButton(); setupButton(btn, p, x, y, w, h, "PCCHAR:" + normal);
        btn.putClientProperty("chars", new String[] { normal, shifted });
        btn.addActionListener(e -> {
            String src = getVisualLabel(btn, shiftPCActive);
            char inputChar = src.charAt(0);
            if (tempCharMap.containsKey(inputChar)) {
                if (JOptionPane.showConfirmDialog(this, "Clear translation for '" + inputChar + "'?", "Clear Rule", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    tempCharMap.remove(inputChar); refreshPCVisuals(); return;
                }
            }
            // RESTORED: Special Char Palette
            SpecialCharPalette palette = new SpecialCharPalette(this);
            palette.setVisible(true);
            String outStr = palette.getSelected();
            if (outStr != null && !outStr.isEmpty()) {
                char outputChar = outStr.charAt(0);
                if (inputChar != outputChar) {
                    tempCharMap.put(inputChar, outputChar);
                    refreshPCVisuals();
                }
            }
        });
        buttonsPC.add(btn);
    }

    private void addPCStubButton(JPanel p, String label, int x, int y, int w, int h) {
        JButton btn = new JButton(label); btn.setBounds(x, y, w, h); btn.setEnabled(false); p.add(btn);
    }

    private void setupButton(JButton btn, JPanel p, int x, int y, int w, int h, String id) {
        btn.setBounds(x, y, w, h); btn.setFont(new Font("SansSerif", Font.BOLD, 10));
        btn.setMargin(new Insets(0, 0, 0, 0)); btn.setFocusPainted(false);
        if (!stylesCaptured) { defaultBorder = btn.getBorder(); defaultBg = btn.getBackground(); stylesCaptured = true; }
        btn.putClientProperty("id", id); p.add(btn);
    }

    private String getVisualLabel(JButton btn, boolean shifted) {
        String[] chars = (String[]) btn.getClientProperty("chars");
        if (chars != null) return shifted ? chars[1] : chars[0];
        return "";
    }

    private void refresh3270Visuals() {
        for (JButton btn : buttons3270) {
            String id = (String) btn.getClientProperty("id");
            boolean mapped = false;
            String labelText = "";
            String mappedTo = "Default";

            if (id.startsWith("3270CHAR:")) {
                String[] chars = (String[]) btn.getClientProperty("chars");
                labelText = shift3270Active ? chars[1] : chars[0];
                char c = labelText.equals("Space") ? ' ' : labelText.charAt(0);
                for (Map.Entry<Integer, KeyMapping> entry : tempKeyMap.entrySet()) {
                    // FIX: .aid check for Integer
                    if (entry.getValue().aid == null && entry.getValue().character == c) {
                        mapped = true; mappedTo = KeyEvent.getKeyText(entry.getKey()); break;
                    }
                }
            } else {
                labelText = id.substring(9);
                String search = labelText.equals("R-Shift") ? "NEWLINE" : labelText;
                
                // FIX: getAidForName returns int now
                int aid = getAidForName(labelText);
                
                for (Map.Entry<Integer, KeyMapping> entry : tempKeyMap.entrySet()) {
                    KeyMapping km = entry.getValue();
                    // FIX: Compare integers safely
                    if ((km.description != null && km.description.contains(search)) || 
                        (km.aid != null && km.aid.intValue() == aid && aid != 0)) {
                        mapped = true; mappedTo = KeyEvent.getKeyText(entry.getKey()); break;
                    }
                }
            }
            applyButtonStyle(btn, labelText, mapped, mappedTo);
        }
    }

    private void refreshPCVisuals() {
        for (JButton btn : buttonsPC) {
            String[] chars = (String[]) btn.getClientProperty("chars");
            String srcChar = shiftPCActive ? chars[1] : chars[0];
            char c = srcChar.charAt(0);
            boolean mapped = tempCharMap.containsKey(c);
            String display = srcChar;
            String tooltip = "Default";
            if (mapped) {
                char target = tempCharMap.get(c);
                display = srcChar + " \u2192 " + target;
                tooltip = "Translates to: " + target;
            }
            applyButtonStyle(btn, display, mapped, tooltip);
        }
    }

    private void applyButtonStyle(JButton btn, String text, boolean mapped, String tooltip) {
        btn.setText("<html><center>" + text + "</center></html>");
        if (mapped) {
            btn.setBorder(BorderFactory.createLineBorder(new Color(0, 180, 0), 3));
            btn.setToolTipText("Mapped to Key: " + tooltip); 
        } else {
            btn.setBorder(defaultBorder);
            btn.setBackground(defaultBg);
            btn.setToolTipText(null);
        }
    }

    // FIX: Changed return type to int
    private int getAidForName(String name) {
        if (name.startsWith("PF")) { 
            try { 
                return PF_AID_ARRAY[Integer.parseInt(name.substring(2)) - 1]; 
            } catch (Exception e) {} 
        }
        if (name.equals("ENTER")) return AID_ENTER;
        if (name.equals("CLEAR")) return AID_CLEAR;
        if (name.equals("PA1")) return AID_PA1;
        if (name.equals("PA2")) return AID_PA2;
        if (name.equals("PA3")) return AID_PA3;
        if (name.equals("ATTN")) return AID_ATTN;
        if (name.equals("SYS REQ")) return AID_SYSREQ;
        return 0;
    }

    private void removeMappingsForTarget(char targetC) {
        tempKeyMap.entrySet().removeIf(entry -> entry.getValue().aid == null && entry.getValue().character == targetC);
    }

    // FIX: Changed signature to take int
    private void removeMappingsForFunction(String label, int aid) {
        String search = label.equals("R-Shift") ? "NEWLINE" : label;
        tempKeyMap.entrySet().removeIf(entry -> {
            KeyMapping km = entry.getValue();
            if (km.description != null && km.description.contains(search)) return true;
            // FIX: Integer comparison
            if (km.aid != null && km.aid.intValue() == aid && aid != 0) return true;
            return false;
        });
    }

    // --- Key Capture Dialog ---
    class KeyCaptureDialog extends JDialog {
        public int capturedKeyCode = -1;
        public boolean cleared = false;

        public KeyCaptureDialog(Dialog owner, String prompt, boolean charMode) {
            super(owner, "Press Key", true);
            setLayout(new BorderLayout()); setSize(400, 180); setLocationRelativeTo(owner);
            JLabel lbl = new JLabel("<html><center>" + prompt + "</center></html>", SwingConstants.CENTER);
            lbl.setFont(new Font("SansSerif", Font.BOLD, 14)); add(lbl, BorderLayout.CENTER);
            
            JPanel bottom = new JPanel(new FlowLayout());
            JButton cancel = new JButton("Cancel"); 
            cancel.addActionListener(e -> dispose());
            JButton clearBtn = new JButton("Clear Assignment");
            clearBtn.setForeground(Color.RED);
            clearBtn.addActionListener(e -> { cleared = true; dispose(); });
            
            bottom.add(cancel); bottom.add(clearBtn);
            add(bottom, BorderLayout.SOUTH);

            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    int code = e.getKeyCode();
                    if (code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT) {
                        capturedKeyCode = code;
                    } else if (code == KeyEvent.VK_ESCAPE) {
                        // Ignore Escape here so they can use Cancel button, or map Esc
                        capturedKeyCode = code;
                    } else {
                        capturedKeyCode = code;
                    }
                    if (capturedKeyCode != -1) dispose();
                }
            });
            setFocusable(true);
        }
    }

    // --- RESTORED: Special Character Palette ---
    class SpecialCharPalette extends JDialog {
        private String selectedChar = null;
        private final String[] SYMBOLS = { "¬", "¢", "¦", "|", "\\", "[", "]", "{", "}", "μ", "ß", "!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "_", "+", "-", "=", "<", ">", "?", "/", "~", "`" };
        private final String[] DESCRIPTIONS = { "Not Sign", "Cent", "Broken Bar", "Solid Pipe", "Backslash", "Left Bracket", "Right Bracket", "Left Brace", "Right Brace", "Mu", "Eszett", "Exclamation", "At", "Hash", "Dollar", "Percent", "Caret", "Ampersand", "Star", "L-Paren", "R-Paren", "Underscore", "Plus", "Minus", "Equals", "Less", "Greater", "Question", "Slash", "Tilde", "Backtick" };

        public SpecialCharPalette(Dialog owner) {
            super(owner, "Select Character", true);
            setLayout(new BorderLayout()); setSize(550, 400); setLocationRelativeTo(owner);
            JLabel lbl = new JLabel("Select a symbol or enter a Hex code:", SwingConstants.CENTER);
            lbl.setBorder(new EmptyBorder(10, 10, 10, 10)); add(lbl, BorderLayout.NORTH);

            JPanel grid = new JPanel(new GridLayout(0, 4, 5, 5));
            grid.setBorder(new EmptyBorder(10, 10, 10, 10));

            for (int i = 0; i < SYMBOLS.length; i++) {
                final String s = SYMBOLS[i]; final String d = DESCRIPTIONS[i];
                JButton btn = new JButton(s);
                btn.setFont(new Font("Monospaced", Font.BOLD, 16)); btn.setToolTipText(d);
                btn.addActionListener(e -> { selectedChar = s; dispose(); });
                grid.add(btn);
            }

            JButton hexBtn = new JButton("<html><center><font size='3'>Hex</font><br><font size='2'>Code...</font></center></html>");
            hexBtn.setFont(new Font("SansSerif", Font.PLAIN, 10)); hexBtn.setBackground(new Color(230, 240, 255));
            hexBtn.setToolTipText("Enter Hex value (e.g. 00A5)");
            hexBtn.addActionListener(e -> {
                String input = JOptionPane.showInputDialog(this, "Enter 4-digit Hex Code (e.g. 00AC):", "Hex Input", JOptionPane.PLAIN_MESSAGE);
                if (input != null && !input.trim().isEmpty()) {
                    try {
                        String clean = input.trim().replace("0x", "").replace("U+", "").replace("u+", "");
                        int codePoint = Integer.parseInt(clean, 16);
                        selectedChar = String.valueOf((char) codePoint);
                        dispose();
                    } catch (NumberFormatException ex) {
                        JOptionPane.showMessageDialog(this, "Invalid Hex Code.", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            });
            grid.add(hexBtn);

            JButton cancel = new JButton("Cancel");
            cancel.addActionListener(e -> dispose());
            add(new JScrollPane(grid), BorderLayout.CENTER); add(cancel, BorderLayout.SOUTH);
        }

        public String getSelected() { return selectedChar; }
    }
}
