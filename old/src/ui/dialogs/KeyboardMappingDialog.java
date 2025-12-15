package ui.dialogs;

import java.awt.*;
import java.awt.event.*;
import java.util.*;

/**
 * Dialog for keyboard mapping configuration.
 * Full implementation with visual keyboard in Phase 7.2.
 */
public class KeyboardMappingDialog extends Dialog {
    
    public KeyboardMappingDialog(Frame parent) {
        super(parent, "Keyboard Mapping", true);
        setLayout(new BorderLayout(10, 10));
        
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                dispose();
            }
        });
        
        // Placeholder message
        Label label = new Label("Keyboard mapping configuration coming soon...");
        label.setFont(new Font("SansSerif", Font.PLAIN, 12));
        Panel messagePanel = new Panel();
        messagePanel.add(label);
        add(messagePanel, BorderLayout.CENTER);
        
        Button closeButton = new Button("Close");
        closeButton.addActionListener(e -> dispose());
        Panel buttonPanel = new Panel();
        buttonPanel.add(closeButton);
        add(buttonPanel, BorderLayout.SOUTH);
        
        pack();
        setLocationRelativeTo(parent);
    }
    
    public void showDialog() {
        setVisible(true);
    }
}
