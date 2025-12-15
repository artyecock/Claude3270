package ui.dialogs;

import java.awt.*;
import java.awt.event.*;

/**
 * Dialog for terminal settings.
 */
public class TerminalSettingsDialog extends Dialog {
    
    private Choice modelChoice;
    private Checkbox cursorBlinkCheckbox;
    private Checkbox soundCheckbox;
    
    private String selectedModel = null;
    private boolean cancelled = true;
    
    public TerminalSettingsDialog(Frame parent, String currentModel) {
        super(parent, "Terminal Settings", true);
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
        
        // Model selection
        gbc.gridx = 0;
        gbc.gridy = 0;
        add(new Label("Terminal Model:"), gbc);
        
        gbc.gridx = 1;
        modelChoice = new Choice();
        modelChoice.add("3278-2 (24x80)");
        modelChoice.add("3279-2 (24x80 Color)");
        modelChoice.add("3278-3 (32x80)");
        modelChoice.add("3279-3 (32x80 Color)");
        modelChoice.add("3278-4 (43x80)");
        modelChoice.add("3278-5 (27x132)");
        
        // Select current model
        for (int i = 0; i < modelChoice.getItemCount(); i++) {
            if (modelChoice.getItem(i).startsWith(currentModel)) {
                modelChoice.select(i);
                break;
            }
        }
        add(modelChoice, gbc);
        
        // Options
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        cursorBlinkCheckbox = new Checkbox("Cursor Blink", true);
        add(cursorBlinkCheckbox, gbc);
        
        gbc.gridy = 2;
        soundCheckbox = new Checkbox("Enable Sound", true);
        add(soundCheckbox, gbc);
        
        // Buttons
        gbc.gridy = 3;
        gbc.anchor = GridBagConstraints.CENTER;
        Panel buttonPanel = new Panel(new FlowLayout());
        
        Button okButton = new Button("OK");
        okButton.addActionListener(e -> {
            String modelStr = modelChoice.getSelectedItem();
            selectedModel = modelStr.substring(0, 7).trim();
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
    
    public String showDialog() {
        setVisible(true);
        return cancelled ? null : selectedModel;
    }
    
    public boolean getCursorBlink() {
        return cursorBlinkCheckbox.getState();
    }
    
    public boolean getSound() {
        return soundCheckbox.getState();
    }
}
