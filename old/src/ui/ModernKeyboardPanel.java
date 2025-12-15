package ui;

import config.Constants;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import javax.swing.Timer;

/**
 * Modern keyboard panel with styled buttons for PF keys and special functions.
 */
public class ModernKeyboardPanel extends Panel {
    
    // State fields
    private boolean connected = false;
    private boolean keyboardLocked = false;
    private boolean insertMode = false;
    private boolean transferActive = false;
    private Timer statusTimer;
    
    public interface KeyboardActionListener {
        void onPFKey(byte aid);
        void onPAKey(byte aid);
        void onClearKey();
        void onResetKey();
        void onEnterKey();
        void onInsertKey();
        void onEraseEOF();
        void onEraseEOL();
        void onNewline();
    }
    
    private KeyboardActionListener listener;
    
    public ModernKeyboardPanel(KeyboardActionListener listener) {
        this.listener = listener;
        setLayout(new BorderLayout(5, 5));
        setBackground(new Color(50, 50, 55));
        
        Panel mainPanel = new Panel(new GridLayout(3, 1, 3, 3));
        mainPanel.setBackground(new Color(50, 50, 55));
        
        // Row 1: PF1-PF12
        Panel row1 = new Panel(new GridLayout(1, 12, 3, 3));
        row1.setBackground(new Color(50, 50, 55));
        for (int i = 1; i <= 12; i++) {
            final int pfNum = i;
            final byte aid = (byte)(Constants.AID_PF1 + pfNum - 1);
            row1.add(createStyledButton("F" + pfNum, new Color(70, 130, 180), 
                e -> listener.onPFKey(aid)));
        }
        mainPanel.add(row1);
        
        // Row 2: PF13-PF24
        Panel row2 = new Panel(new GridLayout(1, 12, 3, 3));
        row2.setBackground(new Color(50, 50, 55));
        for (int i = 13; i <= 24; i++) {
            final int pfNum = i;
            final byte aid = (byte)(Constants.AID_PF13 + pfNum - 13);
            row2.add(createStyledButton("F" + pfNum, new Color(70, 130, 180), 
                e -> listener.onPFKey(aid)));
        }
        mainPanel.add(row2);
        
        // Row 3: Action keys
        Panel row3 = new Panel(new FlowLayout(FlowLayout.CENTER, 5, 3));
        row3.setBackground(new Color(50, 50, 55));
        
        row3.add(createStyledButton("CLEAR", new Color(150, 50, 50), 
            e -> listener.onClearKey(), 60));
        row3.add(createStyledButton("PA1", new Color(180, 130, 70), 
            e -> listener.onPAKey(Constants.AID_PA1), 60));
        row3.add(createStyledButton("PA2", new Color(180, 130, 70), 
            e -> listener.onPAKey(Constants.AID_PA2), 60));
        row3.add(createStyledButton("PA3", new Color(180, 130, 70), 
            e -> listener.onPAKey(Constants.AID_PA3), 60));
        row3.add(createStyledButton("RESET", new Color(100, 100, 100), 
            e -> listener.onResetKey(), 60));
        row3.add(createStyledButton("INSERT", new Color(100, 100, 150), 
            e -> listener.onInsertKey(), 60));
        row3.add(createStyledButton("ERASE EOL", new Color(120, 100, 100), 
            e -> listener.onEraseEOL(), 85));
        row3.add(createStyledButton("NEWLINE", new Color(100, 120, 100), 
            e -> listener.onNewline(), 85));
        row3.add(createStyledButton("ENTER", new Color(50, 150, 50), 
            e -> listener.onEnterKey(), 80));
        
        mainPanel.add(row3);
        add(mainPanel, BorderLayout.CENTER);
    }
    
    private Button createStyledButton(String text, Color color, ActionListener action) {
        return createStyledButton(text, color, action, 60);
    }
    
    private Button createStyledButton(String text, Color color, ActionListener action, int width) {
        Button btn = new Button(text) {
            private boolean pressed = false;
            private boolean hover = false;
            
            {
                addMouseListener(new MouseAdapter() {
                    public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                    public void mousePressed(MouseEvent e) { pressed = true; repaint(); }
                    public void mouseReleased(MouseEvent e) { pressed = false; repaint(); }
                });
            }
            
            public Dimension getPreferredSize() {
                return new Dimension(width, 28);
            }
            
            public void paint(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, 
                                   RenderingHints.VALUE_ANTIALIAS_ON);
                
                Color baseColor = color;
                if (pressed) baseColor = baseColor.darker();
                else if (hover) baseColor = baseColor.brighter();
                
                GradientPaint gradient = new GradientPaint(
                    0, 0, baseColor.brighter(), 0, getHeight(), baseColor.darker());
                g2.setPaint(gradient);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                
                g2.setColor(pressed ? baseColor.darker().darker() : baseColor.darker());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("SansSerif", Font.BOLD, 10));
                FontMetrics fm = g2.getFontMetrics();
                int textX = (getWidth() - fm.stringWidth(text)) / 2;
                int textY = (getHeight() + fm.getAscent()) / 2 - 2;
                
                g2.setColor(new Color(0, 0, 0, 100));
                g2.drawString(text, textX + 1, textY + 1);
                g2.setColor(Color.WHITE);
                g2.drawString(text, textX, textY);
            }
        };
        btn.addActionListener(action);
        return btn;
    }

    public void dispose() {
        if (statusTimer != null) {
            statusTimer.stop();
            statusTimer = null;
        }
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
        repaintStatus();
    }

    public void setKeyboardLocked(boolean locked) {
        this.keyboardLocked = locked;
        repaintStatus();
    }

    public void setInsertMode(boolean insertMode) {
        this.insertMode = insertMode;
        repaintStatus();
    }

    public void setTransferActive(boolean active) {
        this.transferActive = active;
        repaintStatus();
    }

    private void repaintStatus() {
        Component[] components = getComponents();
        for (Component c : components) {
            if (c instanceof Panel) c.repaint();
        }
    }
}
