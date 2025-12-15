package ui.dialogs;

import config.ColorScheme;

import java.awt.*;
import java.awt.event.*;

/**
 * Dialog for selecting or customizing color schemes.
 */
public class ColorSchemeDialog extends Dialog {
    
    private Choice schemeChoice;
    private ColorScheme selectedScheme = null;
    private boolean cancelled = true;
    
    public ColorSchemeDialog(Frame parent, ColorScheme currentScheme) {
        super(parent, "Color Scheme", true);
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                cancelled = true;
                dispose();
            }
        });
        
        // Title
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        add(new Label("Select Color Scheme:"), gbc);
        
        // Scheme selector
        gbc.gridy = 1;
        schemeChoice = new Choice();
        schemeChoice.add("Green on Black (Classic)");
        schemeChoice.add("White on Black");
        schemeChoice.add("Amber on Black");
        schemeChoice.add("Green on Dark Green");
        schemeChoice.add("IBM 3270 Blue");
        schemeChoice.add("Solarized Dark");
        add(schemeChoice, gbc);
        
        // Buttons
        gbc.gridy = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        Panel buttonPanel = new Panel(new FlowLayout());
        
        Button okButton = new Button("Apply");
        okButton.addActionListener(e -> {
            String scheme = schemeChoice.getSelectedItem();
            selectedScheme = ColorScheme.getScheme(scheme);
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
    
    public ColorScheme showDialog() {
        setVisible(true);
        return cancelled ? null : selectedScheme;
    }
}
