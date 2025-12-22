package com.tn3270.ai;

import com.tn3270.ui.RoundedBorder;
import com.tn3270.TN3270Emulator;
import com.tn3270.TN3270Session;
import com.tn3270.ai.AIManager; // For helpers

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.*;

public class AIChatWindow extends JDialog {
    private final Frame owner;
    public final AIChatScrollView chatView;
    private final JTextArea inputArea;
    private final Choice modelChoice;
    private final JButton sendBtn, tryNextBtn, saveBtn, loadBtn, prefsBtn;
    private final JLabel spinner;
    private final AIStreamingClient streamingClient;
    private final AIHistoryStore historyStore;
    private final AIConfig config;
    private String lastPrompt = null;

    private final Color BG_MAIN = new Color(245, 247, 250);
    private final Color BG_INPUT = Color.WHITE;
    private final Color TEXT_COLOR = new Color(30, 30, 30);
    
    private final com.tn3270.TN3270Emulator emulator;

    public AIChatWindow(Frame owner, AIModelProvider prov, AIConfig cfg) {
        super((Frame) null, "AI Assistant", false); 
        
        this.owner = owner;
        this.config = cfg;
        this.streamingClient = new AIStreamingClient(prov);
        //this.historyStore = new AIHistoryStore(cfg.get("ai.autosave.dir", "ai_history"));
        // --- FIX: Anchor history directory to User Home ---
        String rawDir = cfg.get("ai.autosave.dir", "ai_history");
        java.io.File dir = new java.io.File(rawDir);
        
        // If the path is relative (e.g. "ai_history"), prepend user.home
        if (!dir.isAbsolute()) {
            dir = new java.io.File(System.getProperty("user.home"), rawDir);
        }
        
        this.historyStore = new AIHistoryStore(dir.getAbsolutePath());
        
        // Cast owner to emulator
        this.emulator = (owner instanceof com.tn3270.TN3270Emulator) ? (com.tn3270.TN3270Emulator) owner : null;

        setLayout(new BorderLayout());
        setSize(950, 750);
        getContentPane().setBackground(BG_MAIN);

        // --- TOP TOOLBAR ---
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 12));
        top.setBackground(Color.WHITE);
        top.setBorder(new MatteBorder(0, 0, 1, 0, new Color(230, 230, 230)));

        JLabel lblModel = new JLabel("Model:");
        lblModel.setFont(new Font("SansSerif", Font.BOLD, 12));

        modelChoice = new Choice();
        //for (String m : cfg.getModels()) modelChoice.add(m);
        // Ask the provider (AIManager) for the aggregated list
        for (String m : prov.listModels()) modelChoice.add(m);

        sendBtn    = createColorButton("Send",    new Color(0, 120, 215), Color.WHITE);
        tryNextBtn = createColorButton("Retry",   new Color(255, 140, 0), Color.WHITE);
        
        // --- NEW BUTTONS ---
        JButton attachBtn = createColorButton("Attach Host", new Color(102, 51, 153), Color.WHITE); // Purple
        JButton saveHostBtn = createColorButton("Save Host", new Color(70, 130, 180), Color.WHITE); // Steel Blue
        
        saveBtn    = createColorButton("Save",    new Color(40, 167, 69), Color.WHITE);
        loadBtn    = createColorButton("Load",    new Color(108, 117, 125), Color.WHITE);
        prefsBtn   = createColorButton("Config",  new Color(23, 162, 184), Color.WHITE);

        spinner = new JLabel("Idle");
        spinner.setForeground(Color.GRAY);
        spinner.setFont(new Font("SansSerif", Font.ITALIC, 11));

        top.add(lblModel);
        top.add(modelChoice);
        top.add(Box.createHorizontalStrut(15));
        top.add(sendBtn);
        top.add(tryNextBtn);
        top.add(Box.createHorizontalStrut(15));
        top.add(attachBtn);
        top.add(saveHostBtn);
        top.add(Box.createHorizontalStrut(15));
        top.add(saveBtn);
        top.add(loadBtn);
        top.add(Box.createHorizontalStrut(15));
        top.add(prefsBtn);
        top.add(Box.createHorizontalStrut(15));
        top.add(spinner);

        add(top, BorderLayout.NORTH);

        Font chatFont = new Font("Monospaced", Font.PLAIN, 13);
        //chatView = new AIChatScrollView(chatFont, TEXT_COLOR, Color.WHITE);
        // Pass 'this::saveTextToHost' as the "OnSave" callback
        chatView = new AIChatScrollView(chatFont, TEXT_COLOR, Color.WHITE, this::saveTextToHost);
        
        JPanel chatWrapper = new JPanel(new BorderLayout());
        chatWrapper.setBackground(Color.WHITE);
        chatWrapper.setBorder(new EmptyBorder(0, 0, 0, 0));
        chatWrapper.add(chatView, BorderLayout.CENTER);
        add(chatWrapper, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 8)); 
        bottom.setBackground(BG_MAIN);
        bottom.setBorder(new EmptyBorder(15, 20, 15, 20));

        JLabel greeting = new JLabel("Hi, what can I help you with?");
        greeting.setFont(new Font("SansSerif", Font.BOLD, 14));
        greeting.setForeground(new Color(80, 80, 90));
        bottom.add(greeting, BorderLayout.NORTH);

        inputArea = new JTextArea(3, 80);
        inputArea.setFont(chatFont);
        inputArea.setBackground(BG_INPUT);
        inputArea.setForeground(TEXT_COLOR);
        inputArea.setCaretColor(TEXT_COLOR);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setBorder(new EmptyBorder(8, 8, 8, 8)); 

        JScrollPane inputScroll = new JScrollPane(inputArea);
        // Local RoundedBorder definition or import needed.
        // For simplicity, let's define it locally or import it.
        // Assuming com.tn3270.ui.RoundedBorder exists (we will create it).
        inputScroll.setBorder(new com.tn3270.ui.RoundedBorder(12, new Color(200, 200, 200)));
        inputScroll.setBackground(BG_MAIN); 
        inputScroll.getViewport().setBackground(BG_INPUT);

        bottom.add(inputScroll, BorderLayout.CENTER);

        JLabel hint = new JLabel("Enter to send, Shift+Enter for newline");
        hint.setForeground(Color.GRAY);
        hint.setFont(new Font("SansSerif", Font.PLAIN, 10));
        hint.setHorizontalAlignment(SwingConstants.RIGHT);
        bottom.add(hint, BorderLayout.SOUTH);

        add(bottom, BorderLayout.SOUTH);

        sendBtn.addActionListener(e -> doSend());
        tryNextBtn.addActionListener(e -> doTryNext());
        
        attachBtn.addActionListener(e -> doAttachHostFile());
        saveHostBtn.addActionListener(e -> doSaveToHost());
        
        prefsBtn.addActionListener(e -> new AIPreferencesPanel(owner, config).showDialog());

        inputArea.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    doSend();
                }
            }
        });

        JPopupMenu popup = new JPopupMenu();
        JMenuItem cut = new JMenuItem("Cut");
        cut.addActionListener(e -> inputArea.cut());
        JMenuItem copy = new JMenuItem("Copy");
        copy.addActionListener(e -> inputArea.copy());
        JMenuItem paste = new JMenuItem("Paste");
        paste.addActionListener(e -> inputArea.paste());
        popup.add(cut); popup.add(copy); popup.add(paste);
        inputArea.setComponentPopupMenu(popup);
    }

    private JButton createColorButton(String text, Color baseColor, Color textColor) {
        JButton btn = new JButton(text);
        btn.setBackground(baseColor);
        btn.setForeground(textColor);
        btn.setFocusPainted(false);
        btn.setFont(new Font("SansSerif", Font.BOLD, 11));
        
        btn.setBorder(new javax.swing.border.CompoundBorder(
            new com.tn3270.ui.RoundedBorder(10, baseColor.darker()),
            new javax.swing.border.EmptyBorder(4, 12, 4, 12)
        ));
        
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setContentAreaFilled(false);
        btn.setOpaque(false);
        
        btn.setUI(new javax.swing.plaf.basic.BasicButtonUI() {
            @Override
            public void paint(Graphics g, JComponent c) {
                AbstractButton b = (AbstractButton) c;
                ButtonModel model = b.getModel();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                Color fill = baseColor;
                if (model.isPressed()) fill = baseColor.darker();
                else if (model.isRollover()) fill = baseColor.brighter();
                
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), 10, 10);
                
                super.paint(g2, c);
                g2.dispose();
            }
        });
        
        return btn;
    }

    public void showWithPrefill(String selectedText, String sysPrompt, boolean autoSend) {
        if (selectedText != null && !selectedText.isEmpty()) {
            inputArea.setText(selectedText);
            inputArea.setCaretPosition(0);
            inputArea.requestFocusInWindow();
        }

        if (sysPrompt != null && !sysPrompt.isEmpty() && !sysPrompt.startsWith("You are")) {
            chatView.addMessage("assistant", "[System Context]\n" + sysPrompt, inputArea.getFont(),
                    new Color(100, 100, 100), new Color(255, 255, 220));
        }

        setLocationRelativeTo(owner);
        setVisible(true);

        if (autoSend && selectedText != null && !selectedText.trim().isEmpty()) {
            SwingUtilities.invokeLater(this::doSend);
        }
    }

    private void doSend() {
        String prompt = inputArea.getText().trim();
        if (prompt.isEmpty()) return;

        String model = modelChoice.getSelectedItem();
        lastPrompt = prompt;

        chatView.addMessage("user", prompt, inputArea.getFont(), Color.WHITE, new Color(0, 120, 215));

        inputArea.setText("");
        spinner.setText("Sending to " + model + "...");

        String sys = config.get("ai.prompt.default", "You are assisting a TN3270 mainframe user.");

        streamingClient.sendStream(model, prompt, sys, chunk -> {
            EventQueue.invokeLater(() -> {
                Component[] comps = chatView.listPanel.getComponents();
                AIMessageBubble lastBubble = null;

                for (int i = comps.length - 1; i >= 0; i--) {
                    if (comps[i] instanceof AIMessageBubble) {
                        lastBubble = (AIMessageBubble) comps[i];
                        break;
                    }
                }

                if (lastBubble != null && "assistant".equals(lastBubble.who)) {
                    lastBubble.appendText(chunk);
                } else {
                    chatView.addMessage("assistant", chunk, inputArea.getFont(), Color.BLACK, new Color(240, 242, 245));
                }
            });
        }, ex -> EventQueue.invokeLater(() -> {
            chatView.addMessage("assistant", "[Error] " + ex.getMessage(), inputArea.getFont(),
                    new Color(180, 0, 0), new Color(255, 235, 235));
            spinner.setText("Error");
        }), () -> EventQueue.invokeLater(() -> spinner.setText("Idle")));
    }

    private void doTryNext() {
        if (lastPrompt == null) return;
        int idx = modelChoice.getSelectedIndex();
        if (idx < 0) return;
        int next = (idx + 1) % modelChoice.getItemCount();
        modelChoice.select(next);
        inputArea.setText(lastPrompt);
        doSend();
    }
    
 // =======================================================================
    // LOGIC: DOWNLOAD FROM MAINFRAME -> AI PROMPT
    // =======================================================================
    private void doAttachHostFile() {
        if (emulator == null) return;
        com.tn3270.TN3270Session session = emulator.getCurrentSession();
        
        if (session == null || !session.isConnected()) {
            JOptionPane.showMessageDialog(this, "No active session connected.");
            return;
        }

        //String dataset = JOptionPane.showInputDialog(this, "Enter Host Dataset (e.g. USER.SOURCE(MAIN)):");
        String dataset = JOptionPane.showInputDialog(this, "Enter Host Dataset (e.g. USER.SOURCE(MAIN) or PROFILE EXEC A):");
        if (dataset == null || dataset.trim().isEmpty()) return;

        // Use the AIManager singleton for helpers
        AIManager aiMgr = AIManager.getInstance();

        com.tn3270.TN3270Session.MemoryTransferCallback callback = new com.tn3270.TN3270Session.MemoryTransferCallback() {
            @Override
            public void onDownloadComplete(String content) {
                // 1. Check for Binary Garbage
                if (AIManager.isLikelyBinary(content.getBytes())) {
                    int confirm = JOptionPane.showConfirmDialog(AIChatWindow.this, 
                        "The file '" + dataset + "' appears to be binary.\nAttach anyway?", 
                        "Binary Warning", JOptionPane.YES_NO_OPTION);
                    if (confirm != JOptionPane.YES_OPTION) return;
                }

                // 2. Augment Prompt with Context (RAG)
                // Note: We default to TSO for now, or you can try to detect from session
                String augmented = aiMgr.buildAugmentedPrompt(dataset, content, "TSO");

                SwingUtilities.invokeLater(() -> {
                    // Append to input area so the user can see/edit it before sending
                    inputArea.append("\n" + augmented);
                    // Also scroll to bottom
                    inputArea.setCaretPosition(inputArea.getDocument().getLength());
                    JOptionPane.showMessageDialog(AIChatWindow.this, "File attached successfully.");
                });
            }

            @Override
            public void onUploadComplete() { }

            @Override
            public void onError(String message) {
                JOptionPane.showMessageDialog(AIChatWindow.this, "Download Error: " + message);
            }
        };

        // Trigger Session Logic (TSO Default)
        //session.downloadTextFromHost(dataset, com.tn3270.TN3270Session.HostType.TSO, callback); 
        session.downloadTextFromHost(dataset, session.getHostType(), callback);
    }

    // =======================================================================
    // LOGIC: UPLOAD AI RESPONSE -> MAINFRAME
    // =======================================================================
    private void doSaveToHost() {
        // 1. Try Input Area Selection
        String text = inputArea.getSelectedText();
        
        // 2. Fallback to Clipboard
        if (text == null) {
            try {
                text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            } catch(Exception e) {}
        }

        if (text == null || text.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "To save manually, select text in the Input Area or copy text to the Clipboard.");
            return;
        }
        
        saveTextToHost(text);
    }
    
    private void doSaveToHostOld() {
        if (emulator == null) return;
        com.tn3270.TN3270Session session = emulator.getCurrentSession();
        if (session == null || !session.isConnected()) {
            JOptionPane.showMessageDialog(this, "No active session connected.");
            return;
        }

        // Try to get selected text from Chat View (Bubbles) OR Input Area
        String text = null;
        
        // 1. Check Input Area Selection
        text = inputArea.getSelectedText();
        
        // 2. If nothing, we might want to let the user select a specific bubble context?
        // Since your chatView uses a custom ScrollView with Bubbles, standard text selection 
        // might be tricky unless your AIMessageBubble supports copying.
        // Fallback: If input is empty, maybe use the last AI response?
        if (text == null && lastPrompt != null) {
             // For now, let's just rely on Input Area or Clipboard.
             // Or prompt the user: "Copy the text you want to save to the clipboard first?"
        }
        
        // Check Clipboard as fallback if nothing selected
        if (text == null) {
            try {
                text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            } catch(Exception e) {}
        }

        if (text == null || text.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select text in the input area or copy text to clipboard first.");
            return;
        }

        final String textToUpload = text; // Final for lambda

        String dataset = JOptionPane.showInputDialog(this, "Save clipboard/selection to Dataset:");
        if (dataset == null) return;

        session.uploadTextToHost(textToUpload, dataset, session.getHostType(), new com.tn3270.TN3270Session.MemoryTransferCallback() {
            @Override
            public void onUploadComplete() {
                JOptionPane.showMessageDialog(AIChatWindow.this, "Saved to " + dataset);
            }
            @Override
            public void onDownloadComplete(String c) {}
            @Override
            public void onError(String msg) {
                JOptionPane.showMessageDialog(AIChatWindow.this, "Upload Error: " + msg);
            }
        });
    }
    
    /**
     * Public method to upload specific text to the host.
     * Can be called by Toolbar Buttons OR Chat Bubbles.
     */
    public void saveTextToHost(String textContent) {
        if (emulator == null) return;
        com.tn3270.TN3270Session session = emulator.getCurrentSession();
        
        if (session == null || !session.isConnected()) {
            JOptionPane.showMessageDialog(this, "No active session connected.");
            return;
        }

        if (textContent == null || textContent.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No text provided to save.");
            return;
        }

        // Auto-detect host type based on dataset name, so we just ask for the name.
        String dataset = JOptionPane.showInputDialog(this, "Save content to Host Dataset:");
        if (dataset == null || dataset.trim().isEmpty()) return;

        // Perform the upload
        session.uploadTextToHost(textContent, dataset, null, new com.tn3270.TN3270Session.MemoryTransferCallback() {
            @Override
            public void onUploadComplete() {
                JOptionPane.showMessageDialog(AIChatWindow.this, "Successfully saved to " + dataset);
            }
            @Override
            public void onDownloadComplete(String c) {}
            @Override
            public void onError(String msg) {
                JOptionPane.showMessageDialog(AIChatWindow.this, "Upload Error: " + msg);
            }
        });
    }
}
