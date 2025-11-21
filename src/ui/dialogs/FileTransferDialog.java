import java.awt.*;
import java.awt.event.*;
import java.io.File;

/**
 * 
 * Dialog for configuring file transfer parameters.
 */
public class FileTransferDialog extends Dialog {
	/**
	 * 
	 * File transfer configuration result.
	 */
	public static class TransferConfig {
		public String localFile;
		public String hostDataset;
		public String command;
		public FileTransferManager.HostType hostType;
		public boolean isText;

		public TransferConfig(String localFile, String hostDataset, String command,
				FileTransferManager.HostType hostType, boolean isText) {
			this.localFile = localFile;
			this.hostDataset = hostDataset;
			this.command = command;
			this.hostType = hostType;
			this.isText = isText;
		}
	}

	private boolean isDownload;
	private TextField localFileField;
	private TextField hostDatasetField;
	private Choice hostTypeChoice;
	private Choice modeChoice;
	private Checkbox crlfCheckbox;
	private Checkbox appendCheckbox;
	private Choice recfmChoice;
	private TextField lreclField;
	private TextField blksizeField;
	private TextField spaceField;
	private Label datasetLabel;
	private TransferConfig result = null;

	/**
	 * 
	 * Create a file transfer dialog.
	 */
	public FileTransferDialog(Frame parent, boolean isDownload) {
		super(parent, isDownload ? "Download from Host" : "Upload to Host", true);
		this.isDownload = isDownload;
		setLayout(new GridBagLayout());
		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dispose();
			}
		});
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		// Host Type
		gbc.gridx = 0;
		gbc.gridy = 0;
		add(new Label("Host Type:"), gbc);
		gbc.gridx = 1;
		gbc.gridwidth = 2;
		hostTypeChoice = new Choice();
		hostTypeChoice.add("TSO (z/OS)");
		hostTypeChoice.add("CMS (z/VM)");
		hostTypeChoice.select(1); // CMS default
		hostTypeChoice.addItemListener(e -> onHostTypeChanged());
		add(hostTypeChoice, gbc);
		// Local file
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 1;
		add(new Label("Local File:"), gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;
		localFileField = new TextField(40);
		add(localFileField, gbc);
		gbc.gridx = 2;
		gbc.weightx = 0;
		Button browseButton = new Button("Browse...");
		browseButton.addActionListener(e -> onBrowse());
		add(browseButton, gbc);
		// Host dataset/file
		gbc.gridx = 0;
		gbc.gridy = 2;
		gbc.weightx = 0;
		datasetLabel = new Label("Host File:");
		add(datasetLabel, gbc);
		gbc.gridx = 1;
		gbc.gridwidth = 2;
		gbc.weightx = 1.0;
		hostDatasetField = new TextField(40);
		hostDatasetField.setText("TEST DATA A");
		add(hostDatasetField, gbc);
		// Transfer mode
		gbc.gridx = 0;
		gbc.gridy = 3;
		gbc.gridwidth = 1;
		gbc.weightx = 0;
		add(new Label("Transfer Mode:"), gbc);
		gbc.gridx = 1;
		gbc.gridwidth = 2;
		modeChoice = new Choice();
		modeChoice.add("ASCII (Text)");
		modeChoice.add("BINARY");
		modeChoice.addItemListener(e -> {
			crlfCheckbox.setEnabled(modeChoice.getSelectedIndex() == 0);
		});
		add(modeChoice, gbc);
		// CRLF option
		gbc.gridx = 0;
		gbc.gridy = 4;
		gbc.gridwidth = 3;
		crlfCheckbox = new Checkbox("Add CRLF line endings (ASCII only)", true);
		add(crlfCheckbox, gbc);
		// APPEND option
		gbc.gridy = 5;
		appendCheckbox = new Checkbox("Append to existing file", false);
		add(appendCheckbox, gbc);
		// Record format options (only for upload)
		if (!isDownload) {
			Panel recfmPanel = new Panel(new FlowLayout(FlowLayout.LEFT));
			recfmPanel.add(new Label("RECFM:"));
			recfmChoice = new Choice();
			recfmChoice.add("F");
			recfmChoice.add("V");
			recfmChoice.add("U");
			recfmPanel.add(recfmChoice);
			recfmPanel.add(new Label("  LRECL:"));
			lreclField = new TextField("80", 6);
			recfmPanel.add(lreclField);

			recfmPanel.add(new Label("  BLKSIZE:"));
			blksizeField = new TextField("6160", 6);
			recfmPanel.add(blksizeField);

			gbc.gridx = 0;
			gbc.gridy = 6;
			gbc.gridwidth = 3;
			add(recfmPanel, gbc);

			// TSO-only: SPACE parameter
			Panel spacePanel = new Panel(new FlowLayout(FlowLayout.LEFT));
			spacePanel.add(new Label("SPACE:"));
			spaceField = new TextField("1,1", 10);
			spacePanel.add(spaceField);
			spacePanel.add(new Label("(TSO only - Primary,Secondary)"));

			gbc.gridy = 7;
			add(spacePanel, gbc);
		}
		// Buttons
		gbc.gridy = isDownload ? 6 : 8;
		gbc.gridx = 0;
		gbc.gridwidth = 3;
		gbc.anchor = GridBagConstraints.CENTER;
		Panel buttonPanel = new Panel(new FlowLayout());
		Button okButton = new Button("Start Transfer");
		Button cancelButton = new Button("Cancel");
		okButton.addActionListener(e -> onStartTransfer());
		cancelButton.addActionListener(e -> dispose());
		buttonPanel.add(okButton);
		buttonPanel.add(cancelButton);
		add(buttonPanel, gbc);
		// Enter key on text fields
		ActionListener enterAction = e -> onStartTransfer();
		localFileField.addActionListener(enterAction);
		hostDatasetField.addActionListener(enterAction);
		pack();
		setLocationRelativeTo(parent);
	}

	/**
	 * 
	 * Show the dialog and return configuration. Returns null if cancelled.
	 */
	public TransferConfig showDialog() {
		setVisible(true);
		return result;
	}

	/**
	 * 
	 * Handle browse button.
	 */
	private void onBrowse() {
		FileDialog fileDialog = new FileDialog((Frame) getOwner(),
				isDownload ? "Select destination file" : "Select file to upload",
				isDownload ? FileDialog.SAVE : FileDialog.LOAD);
		fileDialog.setVisible(true);
		String dir = fileDialog.getDirectory();
		String file = fileDialog.getFile();
		if (dir != null && file != null) {
			localFileField.setText(dir + file);
		}
	}

	/**
	 * 
	 * Handle host type change.
	 */
	private void onHostTypeChanged() {
		if (hostTypeChoice.getSelectedIndex() == 0) {
			//TSO
			datasetLabel.setText("Host Dataset:");
			hostDatasetField.setText("USER.TEST.DATA");
		} else {
			//CMS
			datasetLabel.setText("Host File:");
			hostDatasetField.setText("TEST DATA A");
		}
	}

	/**
	 * 
	 * Handle start transfer button.
	 */
	private void onStartTransfer() {
		if (!validateFields()) {
			return;
		}
		String localFile = localFileField.getText().trim();
		String hostDataset = hostDatasetField.getText().trim();
		//Validate file exists for upload
		if (!isDownload) {
			File f = new File(localFile);
			if (!f.exists()) {
				MessageDialog.showMessage(this, "Local file does not exist: " + localFile, "File Not Found");
				return;
			}
		}
		//Determine host type
		FileTransferManager.HostType hostType = hostTypeChoice.getSelectedIndex() == 0
				? FileTransferManager.HostType.TSO
				: FileTransferManager.HostType.CMS;
		boolean isAscii = modeChoice.getSelectedIndex() == 0;
		boolean useCrlf = crlfCheckbox.getState();
		boolean append = appendCheckbox.getState();
		String recfm = "";
		String lrecl = "";
		String blksize = "";
		String space = "";
		if (!isDownload) {
			recfm = recfmChoice != null ? recfmChoice.getSelectedItem() : "";
			lrecl = lreclField != null ? lreclField.getText().trim() : "";
			blksize = blksizeField != null ? blksizeField.getText().trim() : "";
			space = spaceField != null ? spaceField.getText().trim() : "";
		}
		//Build command using FileTransferManager
		FileTransferManager tempManager = new FileTransferManager();
		String command = tempManager.buildIndFileCommand(isDownload, hostType, hostDataset, isAscii, useCrlf, append,
				recfm, lrecl, blksize, space);
		result = new TransferConfig(localFile, hostDataset, command, hostType, isAscii);
		dispose();
	}

	/**
	 * 
	 * Validate form fields.
	 */
	private boolean validateFields() {
		String localFile = localFileField.getText().trim();
		String hostDataset = hostDatasetField.getText().trim();
		if (localFile.isEmpty()) {
			MessageDialog.showMessage(this, "Please specify a local file", "Validation Error");
			return false;
		}
		if (hostDataset.isEmpty()) {
			MessageDialog.showMessage(this, "Please specify a host dataset/file name", "Validation Error");
			return false;
		}
		return true;
	}
}
