package ui.dialogs;

import java.awt.*;
import java.awt.event.*;

/**
 * Dialog for selecting font size.
 */
public class FontSizeDialog extends Dialog {
    
    private Choice sizeChoice;
    private int selectedSize = 14;
    private boolean cancelled = true;
    
    public FontSizeDialog(Frame parent) {
        super(parent, "Font Size", true);
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                cancelled = true;
                dispose();
            }
        });
        
        gbc.gridx = 0;
        gbc.gridy = 0;
        add(new Label("Font Size:"), gbc);
        
        gbc.gridx = 1;
        sizeChoice = new Choice();
        for (int size = 8; size <= 24; size += 2) {
            sizeChoice.add(String.valueOf(size));
        }
        sizeChoice.select("14");
        add(sizeChoice, gbc);
        
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        Panel buttonPanel = new Panel(new FlowLayout());
        
        Button okButton = new Button("OK");
        okButton.addActionListener(e -> {
            selectedSize = Integer.parseInt(sizeChoice.getSelectedItem());
            cancelled = false;
            dispose();
        });
        buttonPanel.add(okButton);
        
        Button cancelButton = new Button("Cancel");
        cancelButton.addActionListener(e -> {
            cancelled = true;
            dispose();
        });
        buttonPanel.add(cancelButton);
        
        add(buttonPanel, gbc);
        
        pack();
        setLocationRelativeTo(parent);
    }
    
    public int showDialog() {
        setVisible(true);
        return cancelled ? -1 : selectedSize;
    }
}
