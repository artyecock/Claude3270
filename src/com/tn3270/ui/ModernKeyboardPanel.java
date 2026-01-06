package com.tn3270.ui;

import static com.tn3270.constants.ProtocolConstants.AID_CLEAR;
import static com.tn3270.constants.ProtocolConstants.AID_ENTER;
import static com.tn3270.constants.ProtocolConstants.AID_PA1;
import static com.tn3270.constants.ProtocolConstants.AID_PA2;
import static com.tn3270.constants.ProtocolConstants.AID_PA3;
import static com.tn3270.constants.ProtocolConstants.AID_PF1;
import static com.tn3270.constants.ProtocolConstants.AID_PF10;
import static com.tn3270.constants.ProtocolConstants.AID_PF11;
import static com.tn3270.constants.ProtocolConstants.AID_PF12;
import static com.tn3270.constants.ProtocolConstants.AID_PF13;
import static com.tn3270.constants.ProtocolConstants.AID_PF22;
import static com.tn3270.constants.ProtocolConstants.AID_PF23;
import static com.tn3270.constants.ProtocolConstants.AID_PF24;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

import com.tn3270.TN3270Emulator;
import com.tn3270.TN3270Session;

public class ModernKeyboardPanel extends JPanel {
    private TN3270Emulator emulator;

    // Colors
    private final Color COL_FKEY     = new Color(70, 130, 180);  // Steel Blue
    private final Color COL_CLEAR    = new Color(150, 50, 50);   // Red
    private final Color COL_PA       = new Color(180, 130, 70);  // Bronze
    private final Color COL_ENTER    = new Color(50, 150, 50);   // Green
    private final Color COL_RESET    = new Color(100, 100, 100); // Dark Grey
    private final Color COL_INSERT   = new Color(100, 100, 150); // Blue-Grey
    private final Color COL_ERASE    = new Color(120, 100, 100); // Red-Grey
    private final Color COL_NEWLINE  = new Color(100, 120, 100); // Green-Grey
    private final Color COL_TEXT     = Color.WHITE;

    public ModernKeyboardPanel(TN3270Emulator emulator) {
        this.emulator = emulator;
        
        setLayout(new BorderLayout());
        setBackground(new Color(50, 50, 55));
        setBorder(new EmptyBorder(2, 2, 2, 2));
        
        // --- LEFT: KEYS ---
        JPanel keysPanel = new JPanel(new GridBagLayout());
        keysPanel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; 
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 2, 0);

        // Row 1: F1-F12
        gbc.gridy = 0;
        JPanel row1 = new JPanel(new GridLayout(1, 12, 1, 1));
        row1.setOpaque(false);
        for (int i = 1; i <= 12; i++) {
            final int aid = (i <= 9) ? (AID_PF1 + i - 1) :
                             (i == 10)? AID_PF10 : 
                             (i == 11)? AID_PF11 : AID_PF12;
            row1.add(createButton("F" + i, COL_FKEY, e -> sendAID(aid)));
        }
        keysPanel.add(row1, gbc);

        // Row 2: F13-F24
        gbc.gridy = 1;
        JPanel row2 = new JPanel(new GridLayout(1, 12, 1, 1));
        row2.setOpaque(false);
        for (int i = 13; i <= 24; i++) {
            final int shift = i - 13; 
            final byte aid;
            if (shift <= 8) aid = (byte)(AID_PF13 + shift); 
            else if (shift == 9) aid = AID_PF22;
            else if (shift == 10) aid = AID_PF23;
            else aid = AID_PF24;
            row2.add(createButton("F" + i, COL_FKEY, e -> sendAID(aid)));
        }
        keysPanel.add(row2, gbc);

        // Row 3: Action Keys (9 Buttons)
        gbc.gridy = 2;
        gbc.insets = new Insets(0, 0, 0, 0);
        JPanel row3 = new JPanel(new GridLayout(1, 9, 1, 1));
        row3.setOpaque(false);

        row3.add(createButton("Clear", COL_CLEAR, e -> {
            TN3270Session s = emulator.getCurrentSession();
            if (s != null && !s.keyboardLocked && s.isConnected()) {
                s.sendAID(AID_CLEAR);
            }
        }));
        
        row3.add(createButton("PA1", COL_PA, e -> sendAID(AID_PA1)));
        row3.add(createButton("PA2", COL_PA, e -> sendAID(AID_PA2)));
        row3.add(createButton("PA3", COL_PA, e -> sendAID(AID_PA3))); 
        
        row3.add(createButton("Reset", COL_RESET, e -> {
            TN3270Session s = emulator.getCurrentSession();
            if(s != null) {
                s.keyboardLocked = false;
                s.repaint();
                s.requestFocusInWindow(); 
            }
        }));
        
        row3.add(createButton("Insert", COL_INSERT, e -> {
             TN3270Session s = emulator.getCurrentSession();
             if(s != null) s.toggleInsertMode();
        }));
        
        row3.add(createButton("EraseEOF", COL_ERASE, e -> {
            TN3270Session s = emulator.getCurrentSession();
            if(s != null && !s.keyboardLocked && s.isConnected()) {
                s.eraseToEndOfField();
                s.requestFocusInWindow();
            }
        }));
        
        row3.add(createButton("Newline", COL_NEWLINE, e -> {
             TN3270Session s = emulator.getCurrentSession();
             if(s != null && !s.keyboardLocked && s.isConnected()) {
                 s.tabToNextField();
                 s.requestFocusInWindow();
             }
        }));
        
        row3.add(createButton("Enter", COL_ENTER, e -> sendAID(AID_ENTER)));
        
        keysPanel.add(row3, gbc);
        add(keysPanel, BorderLayout.CENTER);

        // --- RIGHT: STATUS PANEL ---
        JPanel statusPanel = createStatusPanel();
        add(statusPanel, BorderLayout.EAST);
    }
    
    private JPanel createStatusPanel() {
        JPanel panel = new JPanel(new GridLayout(3, 1, 2, 2));
        panel.setBackground(new Color(50, 50, 55));
        panel.setBorder(new EmptyBorder(0, 3, 0, 0));
        panel.setPreferredSize(new Dimension(72, 0));

        // 1. Ready/Wait Status
        JPanel kbStatus = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
				g.setFont(new Font("SansSerif", Font.BOLD, 9));
                
                TN3270Session s = emulator.getCurrentSession();
                boolean locked = (s != null && s.keyboardLocked);
                
                if (locked) {
                    g.setColor(new Color(255, 200, 0)); 
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(Color.BLACK);
                    g.drawString("X WAIT", 18, 18);
                } else {
                    g.setColor(new Color(0, 180, 60)); 
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(Color.WHITE);
                    g.drawString("READY", 20, 18);
                }
            }
        };
        panel.add(kbStatus);

        // 2. Connection Status
        JPanel connStatus = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setFont(new Font("SansSerif", Font.BOLD, 9));
                
                TN3270Session s = emulator.getCurrentSession();
                boolean connected = (s != null && s.isConnected());

                if (connected) {
                    g.setColor(new Color(70, 70, 75));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(Color.CYAN);
                    g.drawString("ONLINE", 18, 18);
                } else {
                    g.setColor(new Color(40, 40, 45));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(Color.GRAY);
                    g.drawString("OFFLINE", 16, 18);
                }
            }
        };
        panel.add(connStatus);

        // 3. Insert Mode
        JPanel insStatus = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setFont(new Font("SansSerif", Font.BOLD, 9));
                g.setColor(new Color(40, 40, 45));
                g.fillRect(0, 0, getWidth(), getHeight());
                
                TN3270Session s = emulator.getCurrentSession();
                boolean insert = (s != null && s.insertMode);

                if (insert) {
                    g.setColor(Color.ORANGE);
                    g.drawString("INS", 26, 18);
                    g.fillRect(12, 8, 8, 2); g.fillRect(15, 5, 2, 8);
                } else {
                    g.setColor(Color.LIGHT_GRAY);
                    g.drawString("OVR", 26, 18);
                }
            }
        };
        panel.add(insStatus);
        
        // Timer to refresh status lights
        javax.swing.Timer t = new javax.swing.Timer(200, e -> panel.repaint());
        t.start();
        return panel;
    }

    private void sendAID(int aid) {
        TN3270Session s = emulator.getCurrentSession();
        if (s != null && !s.keyboardLocked && s.isConnected()) {
            s.sendAID(aid);
            s.requestFocusInWindow();
        }
    }

    private JButton createButton(String text, Color bg, ActionListener l) {
        JButton b = new JButton(text) {
            @Override public Dimension getPreferredSize() {
                FontMetrics fm = getFontMetrics(getFont());
                return new Dimension(fm.stringWidth(getText()) + 6, 28);
            }

            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                Color fill = bg;
                if (getModel().isPressed()) fill = bg.darker();
                else if (getModel().isRollover()) fill = bg.brighter();
                
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 8, 8);
                
                g2.setColor(bg.darker());
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 8, 8);
                
                g2.setColor(COL_TEXT);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(getText())) / 2;
                int y = (getHeight() + fm.getAscent()) / 2 - 2;
                g2.drawString(getText(), x, y);
                
                g2.dispose();
            }
        };
        
        b.setFont(new Font("SansSerif", Font.BOLD, 10)); 
        b.setMargin(new Insets(0, 0, 0, 0)); 
        b.setFocusable(false); 
        b.setContentAreaFilled(false); 
        b.setBorderPainted(false);
        
        if (l != null) b.addActionListener(l);
        
        return b;
    }
}
