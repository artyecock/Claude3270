import java.io.*;

/**
 * 
 * Manages file transfer state and operations for IND$FILE protocol.
 */
public class FileTransferManager {
	/**
	 * 
	 * File transfer state enumeration.
	 */
	public enum FileTransferState {
		IDLE, OPEN_SENT, TRANSFER_IN_PROGRESS, CLOSE_SENT, ERROR
	}

	/**
	 * 
	 * File transfer direction enumeration.
	 */
	public enum FileTransferDirection {
		UPLOAD,  // PC to Host
		DOWNLOAD // Host to PC
	}

	/**
	 * 
	 * Host type enumeration.
	 */
	public enum HostType {
		TSO, // z/OS
		CMS // z/VM
	}

	// State fields
	private FileTransferState state = FileTransferState.IDLE;
	private FileTransferDirection direction;
	private HostType hostType = HostType.CMS;
	private int blockSequence = 0;
	private boolean isText = true;
	private boolean isMessage = false;
	private boolean hadSuccessfulTransfer = false;
	// File handles
	private FileOutputStream downloadStream = null;
	private FileInputStream uploadStream = null;
	private File currentFile = null;
	private String currentFilename = null;

	/**
	 * 
	 * Build IND$FILE command string based on host type and parameters.
	 */
	public String buildIndFileCommand(boolean isDownload, HostType hostType, String hostDataset, boolean isAscii,
			boolean useCrlf, boolean append, String recfm, String lrecl, String blksize, String space) {
		StringBuilder cmd = new StringBuilder();
		cmd.append("IND$FILE ");
		// Command verb
		if (isDownload) {
			cmd.append("GET ");
		} else {
			cmd.append("PUT ");
		}
		// Dataset/filename
		cmd.append(hostDataset);
		if (hostType == HostType.CMS) {
			// CMS format - parameters in parentheses
			StringBuilder params = new StringBuilder();
			if (isAscii) {
				params.append(" ASCII");
			}
			if (useCrlf && isAscii) {
				params.append(" CRLF");
			}
			if (append) {
				params.append(" APPEND");
			}

			// For PUT (upload), add RECFM and LRECL
			if (!isDownload && !recfm.isEmpty()) {
				params.append(" RECFM ").append(recfm);
				if (!lrecl.isEmpty()) {
					params.append(" LRECL ").append(lrecl);
				}
			}

			if (params.length() > 0) {
				cmd.append(" (").append(params.toString().trim()).append(")");
			}
		} else {
			// TSO format - parameters with parentheses around values
			if (isAscii) {
				cmd.append(" ASCII");
			}
			if (useCrlf && isAscii) {
				cmd.append(" CRLF");
			}
			if (append) {
				cmd.append(" APPEND");
			}
			// For PUT (upload), add RECFM, LRECL, BLKSIZE, SPACE
			if (!isDownload) {
				if (!recfm.isEmpty()) {
					cmd.append(" RECFM(").append(recfm).append(")");
				}
				if (!lrecl.isEmpty()) {
					cmd.append(" LRECL(").append(lrecl).append(")");
				}
				if (!blksize.isEmpty()) {
					cmd.append(" BLKSIZE(").append(blksize).append(")");
				}
				if (!space.isEmpty()) {
					cmd.append(" SPACE(").append(space).append(")");
				}
			} else {
				// For GET (download), only add format parameters if specified
				if (isAscii && !recfm.isEmpty()) {
					cmd.append(" RECFM(").append(recfm).append(")");
				}
				if (isAscii && !lrecl.isEmpty()) {
					cmd.append(" LRECL(").append(lrecl).append(")");
				}
				if (!blksize.isEmpty()) {
					cmd.append(" BLKSIZE(").append(blksize).append(")");
				}
			}
		}
		return cmd.toString();
	}

	/**
	 * 
	 * Open file for transfer.
	 */
	public void openFile(File file, boolean isDownload, boolean isText) throws IOException {
		this.currentFile = file;
		this.isText = isText;
		if (isDownload) {
			downloadStream = new FileOutputStream(file);
		} else {
			if (!file.exists()) {
				throw new FileNotFoundException("File not found: " + file.getName());
			}
			uploadStream = new FileInputStream(file);
		}
	}

	/**
	 * 
	 * Close current file streams.
	 */
	public void closeFile() {
		try {
			if (downloadStream != null) {
				downloadStream.flush();
				downloadStream.close();
				downloadStream = null;
			}
			if (uploadStream != null) {
				uploadStream.close();
				uploadStream = null;
			}
		} catch (IOException e) {
			System.err.println("Error closing file: " + e.getMessage());
		}
	}

	/**
	 * 
	 * Reset transfer state.
	 */
	public void reset() {
		closeFile();
		state = FileTransferState.IDLE;
		blockSequence = 0;
		isMessage = false;
		hadSuccessfulTransfer = false;
		currentFile = null;
		currentFilename = null;
	}

	// Getters and setters
	public FileTransferState getState() {
		return state;
	}

	public void setState(FileTransferState state) {
		this.state = state;
	}

	public FileTransferDirection getDirection() {
		return direction;
	}

	public void setDirection(FileTransferDirection direction) {
		this.direction = direction;
	}

	public HostType getHostType() {
		return hostType;
	}

	public void setHostType(HostType hostType) {
		this.hostType = hostType;
	}

	public int getBlockSequence() {
		return blockSequence;
	}

	public void setBlockSequence(int seq) {
		this.blockSequence = seq;
	}

	public void incrementBlockSequence() {
		blockSequence++;
	}

	public boolean isText() {
		return isText;
	}

	public void setIsText(boolean isText) {
		this.isText = isText;
	}

	public boolean isMessage() {
		return isMessage;
	}

	public void setIsMessage(boolean isMessage) {
		this.isMessage = isMessage;
	}

	public boolean hadSuccessfulTransfer() {
		return hadSuccessfulTransfer;
	}

	public void setHadSuccessfulTransfer(boolean success) {
		this.hadSuccessfulTransfer = success;
	}

	public File getCurrentFile() {
		return currentFile;
	}

	public void setCurrentFile(File file) {
		this.currentFile = file;
	}

	public String getCurrentFilename() {
		return currentFilename;
	}

	public void setCurrentFilename(String filename) {
		this.currentFilename = filename;
	}

	public FileInputStream getUploadStream() {
		return uploadStream;
	}

	public FileOutputStream getDownloadStream() {
		return downloadStream;
	}
}
