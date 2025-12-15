package ui;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;

/**
 * Enhanced ribbon toolbar with icon-based buttons for connection,
 * file transfer, editing, and settings operations.
 */
public class EnhancedRibbonToolbar extends Panel {
    
    private ToolbarActionListener listener;
    
    /**
     * Callback interface for toolbar actions.
     */
    public interface ToolbarActionListener {
        void onNewConnection();
        void onDisconnect();
        void onReconnect();
        void onCopy();
        void onPaste();
        void onSelectAll();
    }
    
    /**
     * Creates a new enhanced ribbon toolbar.
     */
    public EnhancedRibbonToolbar(ToolbarActionListener listener) {
        this.listener = listener;
        setLayout(new BorderLayout());
        setBackground(new Color(245, 245, 245));
        
        // Main toolbar
        Panel mainBar = new Panel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        mainBar.setBackground(new Color(245, 245, 245));
        
        // Connection group
        mainBar.add(createIconButton("New\nConnection", "new_conn", e -> {
            if (listener != null) listener.onNewConnection();
        }));
        mainBar.add(createIconButton("Disconnect", "disconnect", e -> {
            if (listener != null) listener.onDisconnect();
        }));
        mainBar.add(createIconButton("Reconnect", "reconnect", e -> {
            if (listener != null) listener.onReconnect();
        }));
        mainBar.add(createSeparator());
        
        // Edit group
        mainBar.add(createIconButton("Copy", "copy", e -> {
            if (listener != null) listener.onCopy();
        }));
        mainBar.add(createIconButton("Paste", "paste", e -> {
            if (listener != null) listener.onPaste();
        }));
        mainBar.add(createIconButton("Select All", "select_all", e -> {
            if (listener != null) listener.onSelectAll();
        }));
        
        add(mainBar, BorderLayout.CENTER);
        
        // Bottom border line
        add(new Canvas() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(0, 2);
            }
            
            @Override
            public void paint(Graphics g) {
                g.setColor(new Color(200, 200, 200));
                g.fillRect(0, 0, getWidth(), 1);
            }
        }, BorderLayout.SOUTH);
    }
    
    /**
     * Creates a vertical separator.
     */
    private Component createSeparator() {
        return new Canvas() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(1, 55);
            }
            
            @Override
            public void paint(Graphics g) {
                g.setColor(new Color(220, 220, 220));
                g.fillRect(0, 5, 1, 45);
            }
        };
    }
    
    /**
     * Creates an icon button with text label.
     */
    private Button createIconButton(String text, String iconName, ActionListener action) {
        Button btn = new Button() {
            private boolean hover = false;
            private BufferedImage icon;
            
            {
                icon = createEnhancedIcon(iconName);
                
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hover = true;
                        repaint();
                    }
                    
                    @Override
                    public void mouseExited(MouseEvent e) {
                        hover = false;
                        repaint();
                    }
                });
            }
            
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(75, 55);
            }
            
            @Override
            public void paint(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, 
                                   RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, 
                                   RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                
                // Background
                if (hover) {
                    g2.setColor(new Color(230, 240, 255));
                    g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 6, 6);
                    g2.setColor(new Color(150, 180, 220));
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 6, 6);
                }
                
                // Icon
                if (icon != null) {
                    int iconX = (getWidth() - 32) / 2;
                    g2.drawImage(icon, iconX, 5, null);
                }
                
                // Text (multi-line support)
                g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
                g2.setColor(Color.BLACK);
                String[] lines = text.split("\n");
                int y = getHeight() - 14;
                for (String line : lines) {
                    FontMetrics fm = g2.getFontMetrics();
                    int textWidth = fm.stringWidth(line);
                    int textX = (getWidth() - textWidth) / 2;
                    g2.drawString(line, textX, y);
                    y += 10;
                }
            }
        };
        
        btn.addActionListener(action);
        return btn;
    }
    
    /**
     * Creates an enhanced icon for a button.
     */
    private BufferedImage createEnhancedIcon(String name) {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, 
                          RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        
        switch (name) {
            case "new_conn":
                // Computer with plug
                g.setColor(new Color(70, 130, 180));
                g.fillRoundRect(4, 8, 24, 16, 4, 4);
                g.setColor(new Color(100, 160, 210));
                g.fillRoundRect(6, 10, 20, 12, 2, 2);
                g.setColor(new Color(50, 180, 50));
                g.fillOval(24, 4, 6, 6);
                break;
                
            case "disconnect":
                // Computer with X
                g.setColor(new Color(180, 70, 70));
                g.fillRoundRect(4, 8, 24, 16, 4, 4);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2.5f));
                g.drawLine(10, 14, 22, 20);
                g.drawLine(22, 14, 10, 20);
                break;
                
            case "reconnect":
                // Circular arrow
                g.setColor(new Color(70, 130, 180));
                g.drawArc(8, 8, 16, 16, 45, 270);
                int[] xPoints = {24, 28, 24};
                int[] yPoints = {12, 16, 20};
                g.fillPolygon(xPoints, yPoints, 3);
                break;
                
            case "copy":
                // Two documents
                g.setColor(new Color(100, 100, 100));
                g.fillRoundRect(8, 10, 14, 18, 2, 2);
                g.setColor(new Color(150, 150, 150));
                g.fillRoundRect(12, 6, 14, 18, 2, 2);
                g.setColor(Color.WHITE);
                g.drawLine(14, 10, 22, 10);
                g.drawLine(14, 14, 22, 14);
                g.drawLine(14, 18, 22, 18);
                break;
                
            case "paste":
                // Clipboard
                g.setColor(new Color(120, 120, 120));
                g.fillRoundRect(8, 8, 16, 20, 2, 2);
                g.setColor(new Color(180, 180, 180));
                g.fillRoundRect(12, 4, 8, 6, 2, 2);
                g.setColor(Color.WHITE);
                g.fillRect(11, 12, 10, 2);
                g.fillRect(11, 16, 10, 2);
                g.fillRect(11, 20, 10, 2);
                break;
                
            case "select_all":
                // Selection rectangle
                g.setColor(new Color(100, 150, 255));
                g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_BUTT, 
                           BasicStroke.JOIN_MITER, 10.0f, new float[]{4.0f}, 0.0f));
                g.drawRect(6, 6, 20, 20);
                g.setColor(new Color(100, 150, 255, 50));
                g.fillRect(7, 7, 18, 18);
                break;
        }
        
        g.dispose();
        return img;
    }
    
    @Override
    public Dimension getPreferredSize() {
        return new Dimension(0, 60);
    }
}
