package callbacks;

/**
 * Callback interface for file transfer events.
 */
public interface TransferCallback {
    void onTransferStart(String filename, boolean isUpload);
    void onTransferProgress(int bytesTransferred, long totalBytes);
    void onTransferComplete(boolean success, String message);
    void onTransferError(String error);
    void onStatusMessage(String message);
}
