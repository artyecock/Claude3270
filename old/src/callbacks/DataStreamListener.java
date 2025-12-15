package callbacks;

/**
 * Callback interface for 3270 data stream events.
 */
public interface DataStreamListener {
    void on3270Data(byte[] data);
    void onWSFData(byte[] data);
    void onConnectionLost(String message);
}
