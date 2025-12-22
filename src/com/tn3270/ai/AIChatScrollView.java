package com.tn3270.ai;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.util.function.Consumer;

public class AIChatScrollView extends JPanel {
    public final JPanel listPanel;
    private final JScrollPane scrollPane;
    private final Consumer<String> onSaveToHost;

    //public AIChatScrollView(Font uiFont, Color fg, Color bg) {
    public AIChatScrollView(Font font, Color textCol, Color bgCol, Consumer<String> onSaveToHost) {
        super(new BorderLayout());
        
        this.onSaveToHost = onSaveToHost;

        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(Color.WHITE);

        scrollPane = new JScrollPane(listPanel);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        DefaultCaret caret = new DefaultCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        add(scrollPane, BorderLayout.CENTER);
    }

    public AIMessageBubble addMessage(String who, String text, Font uiFont, Color fg, Color bg) {
        //AIMessageBubble bubble = new AIMessageBubble(who, text, uiFont, fg, bg);
        AIMessageBubble bubble = new AIMessageBubble(who, text, uiFont, fg, bg, onSaveToHost);

        bubble.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        listPanel.add(bubble);
        listPanel.add(Box.createRigidArea(new Dimension(0, 5)));

        listPanel.revalidate();
        listPanel.repaint();

        EventQueue.invokeLater(() -> {
            JScrollBar vertical = scrollPane.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });

        return bubble;
    }

    public void clear() {
        listPanel.removeAll();
        listPanel.revalidate();
        listPanel.repaint();
    }

    public String exportConversationJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"conversation\":[");
        Component[] comps = listPanel.getComponents();
        boolean first = true;
        for (Component c : comps) {
            if (c instanceof AIMessageBubble) {
                if (!first)
                    sb.append(",");
                first = false;
                // NOTE: Actual text retrieval would require getter in AIMessageBubble
            }
        }
        sb.append("]}");
        return sb.toString();
    }
}
