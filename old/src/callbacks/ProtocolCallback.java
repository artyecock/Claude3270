package callbacks;

/**
 * Callback interface for protocol-level events.
 */
public interface ProtocolCallback {
    void onScreenChanged();
    void onRepaintRequested();
    void onKeyboardLockChanged(boolean locked);
    void onStatusUpdate();
    
    // Additional methods used by TN3270Protocol
    void requestRepaint();
    void updateStatus(String message);
    void onScreenSizeChanged(int rows, int cols);
    void playAlarm();
    void setKeyboardLocked(boolean locked);
}
