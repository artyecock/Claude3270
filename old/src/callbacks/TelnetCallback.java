package callbacks;

/**
 * Callback interface for Telnet negotiation events.
 */
public interface TelnetCallback {
    void onTN3270EFailed();
    void onTN3270EModeFailed();
    void onTN3270EModeEnabled();
    void onConnected();
    void onDisconnected();
}
