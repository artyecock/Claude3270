package callbacks;

/**
 * Callback interface for input events.
 */
public interface InputCallback {
    boolean isKeyboardLocked();
    boolean isConnected();
    void onAIDKey(byte aid);
    void onClearScreen();
    void onRepaintRequested();
    void onStatusUpdate();
    void onScreenChanged();
    void onInsertModeChanged(boolean insertMode);
}
