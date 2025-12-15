package ai;

import java.awt.*;
import java.awt.event.*;

public class AIQueryDialog extends Dialog {

    private final TextArea contextArea;
    private final TextArea questionArea;
    private final Choice modelChoice;
    private final AIModelProvider provider;

    public interface Callback {
        void onResult(String result);
    }

    public AIQueryDialog(Frame parent,
                            AIModelProvider provider,
                            String[] models,
                            String contextText,
                            Callback cb) {

        super(parent, "Ask AI", true);
        this.provider = provider;

        setLayout(new BorderLayout(8,8));
        setSize(600, 500);

        // CENTER PANEL
        Panel center = new Panel(new GridLayout(3,1));

        // Context (read-only)
        contextArea = new TextArea(contextText, 6, 60, TextArea.SCROLLBARS_VERTICAL_ONLY);
        contextArea.setEditable(false);
        center.add(new LabeledPanel("Screen Selection:", contextArea));

        // Question input
        questionArea = new TextArea("", 4, 60, TextArea.SCROLLBARS_VERTICAL_ONLY);
        center.add(new LabeledPanel("Your Question:", questionArea));

        // Model picker
        modelChoice = new Choice();
        for (String m : models) modelChoice.add(m);
        center.add(new LabeledPanel("AI Model:", modelChoice));

        add(center, BorderLayout.CENTER);

        // BUTTON AREA
        Panel buttons = new Panel(new FlowLayout(FlowLayout.RIGHT));
        Button sendBtn = new Button("Send");
        Button cancelBtn = new Button("Cancel");

        sendBtn.addActionListener(e -> {
            String question = questionArea.getText().trim();
            if (question.isEmpty()) return;

            try {
                String result = provider.send(modelChoice.getSelectedItem(),
                                              question,
                                              contextText);
                cb.onResult(result);
            } catch (Exception ex) {
                cb.onResult("Error contacting AI: " + ex.getMessage());
            }
            dispose();
        });

        cancelBtn.addActionListener(e -> dispose());

        buttons.add(sendBtn);
        buttons.add(cancelBtn);
        add(buttons, BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dispose(); }
        });
    }

    // Helper to label components
    private static class LabeledPanel extends Panel {
        public LabeledPanel(String label, Component c) {
            super(new BorderLayout());
            add(new Label(label), BorderLayout.NORTH);
            add(c, BorderLayout.CENTER);
        }
    }
}

