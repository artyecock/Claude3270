import java.awt.*;
import java.awt.event.*;

/**
 * Dialog for terminal settings (model, cursor blink, etc.).
 */
public class TerminalSettingsDialog extends Dialog {
    
    /**
     * Terminal settings configuration.
     */
    public static class Settings {
        public String model;
        public boolean cursorBlink;
        public boolean sound;
        public boolean autoAdvance;
        
        public Settings(String model, boolean cursorBlink, boolean sound, boolean autoAdvance) {
            this.model = model;
            this.cursorBlink = cursorBlink;
            this.sound = sound;
            this.autoAdvance = autoAdvance;
        }
    }
    
    private Choice modelChoice;
    private Checkbox cursorBlinkCheckbox;
    private Checkbox soundCheckbox;
    private Checkbox autoAdvanceCheckbox;
    
    private Settings result = null;
    
    /**
     * Create a terminal settings dialog.
     */
    public TerminalSettingsDialog(Frame parent, String currentModel) {
        // PASTE: showTerminalSettingsDialog() converted to constructor (lines ~1148-1220)
    }
    
    /**
     * Show the dialog and return settings.
     * Returns null if cancelled.
     */
    public Settings showDialog() {
        setVisible(true);
        return result;
    }
}
