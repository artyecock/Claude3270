package ui.dialogs;

import java.awt.*;
import java.awt.event.*;
import java.io.*;

/**
 * Dialog for configuring file transfers (IND$FILE).
 */
public class FileTransferDialog extends Dialog {
    
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
    
    private boolean isDownload;
    private TransferConfig config = null;
    private boolean cancelled = true;
    
    public static class TransferConfig {
        public String localFile;
        public String hostDataset;
        public String hostType;
        public boolean isAscii;
        public boolean useCrlf;
        public boolean append;
        public String recfm;
        public String lrecl;
        public String blksize;
        public String space;
        public String command;
        
        public TransferConfig() {
            this.isAscii = true;
            this.useCrlf = true;
            this.append = false;
            this.recfm = "F";
            this.lrecl = "80";
            this.blksize = "6160";
            this.space = "1,1";
        }
    }
    
    public FileTransferDialog(Frame parent, boolean isDownload) {
        super(parent, isDownload ? "Download from Host" : "Upload to Host", true);
        this.isDownload = isDownload;
        
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                cancelled = true;
                dispose();
            }
        });
        
        // Host Type
        gbc.gridx = 0;
        gbc.gridy = 0;
        add(new Label("Host Type:"), gbc);
        
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        hostTypeChoice = new Choice();
        hostTypeChoice.add("TSO (z/OS)");
        hostTypeChoice.add("CMS (z/VM)");
        hostTypeChoice.select(1);
        hostTypeChoice.addItemListener(e -> updateHostTypeFields());
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
        browseButton.addActionListener(e -> browseForFile());
        add(browseButton, gbc);
        
        // Host dataset
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        add(new Label("Host Dataset:"), gbc);
        
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        hostDatasetField = new TextField("USER.TEST.DATA", 40);
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
        
        // Options
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 3;
        crlfCheckbox = new Checkbox("Add CRLF line endings (ASCII only)", true);
        add(crlfCheckbox, gbc);
        
        gbc.gridy = 5;
        appendCheckbox = new Checkbox("Append to existing file", false);
        add(appendCheckbox, gbc);
        
        // Record format (for uploads only)
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
            
            gbc.gridy = 6;
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
        okButton.addActionListener(e -> onStartTransfer());
        buttonPanel.add(okButton);
        
        Button cancelButton = new Button("Cancel");
        cancelButton.addActionListener(e -> {
            cancelled = true;
            dispose();
        });
        buttonPanel.add(cancelButton);
        
        add(buttonPanel, gbc);
        
        updateHostTypeFields();
        
        pack();
        setLocationRelativeTo(parent);
    }
    
    private void updateHostTypeFields() {
        if (hostTypeChoice.getSelectedIndex() == 0) {
            // TSO
            hostDatasetField.setText("USER.TEST.DATA");
        } else {
            // CMS
            hostDatasetField.setText("TEST DATA A");
        }
    }
    
    private void browseForFile() {
        FileDialog fileDialog = new FileDialog(
            (Frame) getParent(),
            isDownload ? "Select destination file" : "Select file to upload",
            isDownload ? FileDialog.SAVE : FileDialog.LOAD
        );
        fileDialog.setVisible(true);
        
        String dir = fileDialog.getDirectory();
        String file = fileDialog.getFile();
        
        if (dir != null && file != null) {
            localFileField.setText(dir + file);
        }
    }
    
    private void onStartTransfer() {
        String localFile = localFileField.getText().trim();
        String hostDataset = hostDatasetField.getText().trim();
        
        if (localFile.isEmpty()) {
            MessageDialog.showError(
                (Frame) getParent(),
                "Validation Error",
                "Please specify a local file"
            );
            return;
        }
        
        if (hostDataset.isEmpty()) {
            MessageDialog.showError(
                (Frame) getParent(),
                "Validation Error",
                "Please specify a host dataset/file name"
            );
            return;
        }
        
        // Validate file exists for upload
        if (!isDownload) {
            File f = new File(localFile);
            if (!f.exists()) {
                MessageDialog.showError(
                    (Frame) getParent(),
                    "File Not Found",
                    "Local file does not exist: " + localFile
                );
                return;
            }
        }
        
        // Build config
        config = new TransferConfig();
        config.localFile = localFile;
        config.hostDataset = hostDataset;
        config.hostType = hostTypeChoice.getSelectedItem();
        config.isAscii = (modeChoice.getSelectedIndex() == 0);
        config.useCrlf = crlfCheckbox.getState();
        config.append = appendCheckbox.getState();
        
        if (!isDownload && recfmChoice != null) {
            config.recfm = recfmChoice.getSelectedItem();
            config.lrecl = lreclField.getText().trim();
            config.blksize = blksizeField.getText().trim();
            config.space = spaceField.getText().trim();
        }
        
        // Build IND$FILE command
        config.command = buildCommand(config);
        
        cancelled = false;
        dispose();
    }
    
    private String buildCommand(TransferConfig cfg) {
        StringBuilder cmd = new StringBuilder("IND$FILE ");
        
        if (isDownload) {
            cmd.append("GET ");
        } else {
            cmd.append("PUT ");
        }
        
        cmd.append(cfg.hostDataset);
        
        if (cfg.hostType.startsWith("CMS")) {
            // CMS format
            StringBuilder params = new StringBuilder();
            if (cfg.isAscii) params.append(" ASCII");
            if (cfg.useCrlf && cfg.isAscii) params.append(" CRLF");
            if (cfg.append) params.append(" APPEND");
            
            if (!isDownload && !cfg.recfm.isEmpty()) {
                params.append(" RECFM ").append(cfg.recfm);
                if (!cfg.lrecl.isEmpty()) {
                    params.append(" LRECL ").append(cfg.lrecl);
                }
            }
            
            if (params.length() > 0) {
                cmd.append(" (").append(params.toString().trim()).append(")");
            }
        } else {
            // TSO format
            if (cfg.isAscii) cmd.append(" ASCII");
            if (cfg.useCrlf && cfg.isAscii) cmd.append(" CRLF");
            if (cfg.append) cmd.append(" APPEND");
            
            if (!isDownload) {
                if (!cfg.recfm.isEmpty()) {
                    cmd.append(" RECFM(").append(cfg.recfm).append(")");
                }
                if (!cfg.lrecl.isEmpty()) {
                    cmd.append(" LRECL(").append(cfg.lrecl).append(")");
                }
                if (!cfg.blksize.isEmpty()) {
                    cmd.append(" BLKSIZE(").append(cfg.blksize).append(")");
                }
                if (!cfg.space.isEmpty()) {
                    cmd.append(" SPACE(").append(cfg.space).append(")");
                }
            }
        }
        
        return cmd.toString();
    }
    
    public TransferConfig showDialog() {
        setVisible(true);
        return cancelled ? null : config;
    }
}
