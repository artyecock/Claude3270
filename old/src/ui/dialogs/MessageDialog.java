package ui.dialogs;

import java.awt.*;
import java.awt.event.*;

/**
 * Utility class for showing various types of message dialogs.
 * Provides simple, confirm, input, and multi-line message dialogs.
 * 
 * Extracted from TN3270Emulator-Monolithic.java lines 1773-1368
 */
public class MessageDialog {
    
    /**
     * Show a simple message dialog with OK button.
     * 
     * @param parent Parent frame
     * @param title Dialog title
     * @param message Message to display
     */
    public static void showMessage(Frame parent, String title, String message) {
        Dialog dialog = new Dialog(parent, title, true);
        dialog.setLayout(new BorderLayout(10, 10));
        
        Label label = new Label(message);
        label.setFont(new Font("SansSerif", Font.PLAIN, 12));
        Panel messagePanel = new Panel();
        messagePanel.add(label);
        dialog.add(messagePanel, BorderLayout.CENTER);
        
        Button okButton = new Button("OK");
        okButton.addActionListener(e -> dialog.dispose());
        Panel buttonPanel = new Panel();
        buttonPanel.add(okButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                dialog.dispose();
            }
        });
        
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
    
    /**
     * Show an error message dialog.
     * 
     * @param parent Parent frame
     * @param title Dialog title
     * @param message Error message to display
     */
    public static void showError(Frame parent, String title, String message) {
        Dialog dialog = new Dialog(parent, title, true);
        dialog.setLayout(new BorderLayout(10, 10));
        
        // Error message with red icon
        Panel messagePanel = new Panel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        
        // Simple error indicator
        Canvas errorIcon = new Canvas() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(32, 32);
            }
            
            @Override
            public void paint(Graphics g) {
                g.setColor(new Color(220, 50, 50));
                g.fillOval(0, 0, 32, 32);
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, 24));
                g.drawString("!", 12, 24);
            }
        };
        messagePanel.add(errorIcon);
        
        Label label = new Label(message);
        label.setFont(new Font("SansSerif", Font.PLAIN, 12));
        messagePanel.add(label);
        
        dialog.add(messagePanel, BorderLayout.CENTER);
        
        Button okButton = new Button("OK");
        okButton.addActionListener(e -> dialog.dispose());
        Panel buttonPanel = new Panel();
        buttonPanel.add(okButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                dialog.dispose();
            }
        });
        
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
    
    /**
     * Show a multi-line message dialog.
     * 
     * @param parent Parent frame
     * @param title Dialog title
     * @param message Multi-line message to display
     */
    public static void showMultilineMessage(Frame parent, String title, String message) {
        Dialog dialog = new Dialog(parent, title, true);
        dialog.setLayout(new BorderLayout(10, 10));
        
        TextArea textArea = new TextArea(message, 8, 50, TextArea.SCROLLBARS_VERTICAL_ONLY);
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        dialog.add(textArea, BorderLayout.CENTER);
        
        Button okButton = new Button("OK");
        okButton.addActionListener(e -> dialog.dispose());
        Panel buttonPanel = new Panel();
        buttonPanel.add(okButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                dialog.dispose();
            }
        });
        
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
    
    /**
     * Show a confirmation dialog with Yes/No buttons.
     * 
     * @param parent Parent frame
     * @param title Dialog title
     * @param message Message to display
     * @return 0 for Yes, 1 for No
     */
    public static int showConfirm(Frame parent, String title, String message) {
        Dialog dialog = new Dialog(parent, title, true);
        dialog.setLayout(new BorderLayout(10, 10));
        
        Label label = new Label(message);
        label.setFont(new Font("SansSerif", Font.PLAIN, 12));
        Panel messagePanel = new Panel();
        messagePanel.add(label);
        dialog.add(messagePanel, BorderLayout.CENTER);
        
        final int[] result = { 1 }; // Default to No
        
        Panel buttonPanel = new Panel(new FlowLayout());
        
        Button yesButton = new Button("Yes");
        yesButton.addActionListener(e -> {
            result[0] = 0;
            dialog.dispose();
        });
        buttonPanel.add(yesButton);
        
        Button noButton = new Button("No");
        noButton.addActionListener(e -> {
            result[0] = 1;
            dialog.dispose();
        });
        buttonPanel.add(noButton);
        
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                result[0] = 1;
                dialog.dispose();
            }
        });
        
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        
        return result[0];
    }
    
    /**
     * Show an input dialog with text field.
     * 
     * @param parent Parent frame
     * @param title Dialog title
     * @param message Prompt message
     * @return User input, or null if cancelled
     */
    public static String showInput(Frame parent, String title, String message) {
        Dialog dialog = new Dialog(parent, title, true);
        dialog.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        Label label = new Label(message);
        label.setFont(new Font("SansSerif", Font.PLAIN, 12));
        dialog.add(label, gbc);
        
        gbc.gridy = 1;
        TextField textField = new TextField(30);
        textField.setFont(new Font("SansSerif", Font.PLAIN, 12));
        dialog.add(textField, gbc);
        
        final String[] result = { null };
        
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        Panel buttonPanel = new Panel(new FlowLayout());
        
        Button okButton = new Button("OK");
        okButton.addActionListener(e -> {
            result[0] = textField.getText();
            dialog.dispose();
        });
        buttonPanel.add(okButton);
        
        Button cancelButton = new Button("Cancel");
        cancelButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(cancelButton);
        
        dialog.add(buttonPanel, gbc);
        
        // Enter key submits
        textField.addActionListener(e -> {
            result[0] = textField.getText();
            dialog.dispose();
        });
        
        dialog.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                dialog.dispose();
            }
        });
        
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        textField.requestFocus();
        dialog.setVisible(true);
        
        return result[0];
    }
    
    /**
     * Show an input dialog with initial value.
     * 
     * @param parent Parent frame
     * @param title Dialog title
     * @param message Prompt message
     * @param initialValue Initial text field value
     * @return User input, or null if cancelled
     */
    public static String showInput(Frame parent, String title, String message, String initialValue) {
        Dialog dialog = new Dialog(parent, title, true);
        dialog.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        Label label = new Label(message);
        label.setFont(new Font("SansSerif", Font.PLAIN, 12));
        dialog.add(label, gbc);
        
        gbc.gridy = 1;
        TextField textField = new TextField(initialValue, 30);
        textField.setFont(new Font("SansSerif", Font.PLAIN, 12));
        dialog.add(textField, gbc);
        
        final String[] result = { null };
        
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        Panel buttonPanel = new Panel(new FlowLayout());
        
        Button okButton = new Button("OK");
        okButton.addActionListener(e -> {
            result[0] = textField.getText();
            dialog.dispose();
        });
        buttonPanel.add(okButton);
        
        Button cancelButton = new Button("Cancel");
        cancelButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(cancelButton);
        
        dialog.add(buttonPanel, gbc);
        
        textField.addActionListener(e -> {
            result[0] = textField.getText();
            dialog.dispose();
        });
        
        dialog.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                dialog.dispose();
            }
        });
        
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        textField.selectAll();
        textField.requestFocus();
        dialog.setVisible(true);
        
        return result[0];
    }
}
