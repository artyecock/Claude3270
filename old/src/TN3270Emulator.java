import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.*;
import java.io.*;
import java.net.*;
import javax.net.ssl.*;
import java.util.*;

import config.*;
import terminal.*;
import protocol.*;
import callbacks.*;
import util.*;

import ui.TerminalCanvas;
import ui.StatusBar;
import ui.ModernKeyboardPanel;
import ui.EnhancedRibbonToolbar;
import ui.dialogs.*;

/**
 * Main TN3270 Terminal Emulator class. Integrates all components and provides
 * the main application framework.
 * 
 * Complete integration with Phase 6 (UI) and Phase 7 (Dialogs).
 */
public class TN3270Emulator extends Frame
		implements DataStreamListener, ProtocolCallback, TelnetCallback, InputCallback, TransferCallback {

	// Core components
	private ScreenBuffer screenBuffer;
	private CursorManager cursorManager;
	private InputHandler inputHandler;

	// Protocol components
	private TelnetProtocol telnetProtocol;
	private TN3270Protocol tn3270Protocol;
	private DataStreamReader dataStreamReader;
	private FileTransferManager fileTransferManager;
	private FileTransferProtocol fileTransferProtocol;

	// Configuration
	private ColorScheme currentColorScheme;
	private Map<Integer, KeyMapping> keyMap;

	// Connection
	private Socket socket;
	private InputStream input;
	private OutputStream output;
	private volatile boolean connected = false;
	private boolean useTLS = false;

	// Terminal state
	private String model;
	private int rows;
	private int cols;
	private int primaryRows;
	private int primaryCols;
	private int alternateRows;
	private int alternateCols;

	// UI components (Phase 6)
	private TerminalCanvas canvas;
	private StatusBar statusBar;
	private ModernKeyboardPanel keyboardPanel;
	private EnhancedRibbonToolbar toolbar;

	// File transfer progress
	private ProgressDialog progressDialog;

	/**
	 * Create a new TN3270 Emulator with specified model.
	 */
	public TN3270Emulator(String modelName) {
		super("TN3270 Emulator - " + modelName);

		this.model = modelName;

		// Get model dimensions
		Dimension dim = TerminalModels.getDimensions(model);
		if (dim == null) {
			System.err.println("Unknown model: " + model + ", using 3278-2");
			this.model = "3278-2";
			dim = TerminalModels.getDimensions(this.model);
		}

		// Set up screen sizes
		if (!model.endsWith("-2")) {
			primaryRows = 24;
			primaryCols = 80;
			alternateRows = dim.height;
			alternateCols = dim.width;
		} else {
			primaryRows = dim.height;
			primaryCols = dim.width;
			alternateRows = dim.height;
			alternateCols = dim.width;
		}

		// Start with alternate size
		rows = alternateRows;
		cols = alternateCols;

		// Initialize components
		initializeComponents();

		// Set up UI
		setupUI();

		// Window listener
		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				if (statusBar != null) {
					statusBar.dispose();
				}
				if (keyboardPanel != null) {
					keyboardPanel.dispose();
				}
				disconnect();
				dispose();

				// Check if this is the last window
				Frame[] frames = Frame.getFrames();
				int visibleCount = 0;
				for (Frame f : frames) {
					if (f.isVisible() && f != TN3270Emulator.this) {
						visibleCount++;
					}
				}

				if (visibleCount == 0) {
					System.exit(0);
				}
			}
		});

		pack();
		setVisible(true);
	}

	/**
	 * Initialize all core components.
	 */
	private void initializeComponents() {
		// Create screen buffer
		screenBuffer = new ScreenBuffer(rows, cols);

		// Create cursor manager
		cursorManager = new CursorManager(screenBuffer);

		// Load/initialize key mappings
		keyMap = new HashMap<>();
		KeyMapping.initializeDefaultMappings(keyMap);
		KeyMapping.loadKeyMappings(keyMap);

		// Create input handler
		inputHandler = new InputHandler(screenBuffer, cursorManager, keyMap, this);

		// Load color scheme
		currentColorScheme = ColorScheme.getScheme("Green on Black (Classic)");
		if (currentColorScheme == null) {
			currentColorScheme = new ColorScheme(Color.BLACK, Color.GREEN, Color.WHITE, new Color[] { Color.BLACK,
					Color.BLUE, Color.RED, Color.MAGENTA, Color.GREEN, Color.CYAN, Color.YELLOW, Color.WHITE });
		}

		// File transfer manager
		fileTransferManager = new FileTransferManager();
	}

	/**
     * Create traditional menu bar (File/Edit/View/Settings/Help).
     */
    private void createMenuBar() {
        MenuBar menuBar = new MenuBar();
        
        // ===== FILE MENU =====
        Menu fileMenu = new Menu("File");
        
        MenuItem newConnItem = new MenuItem("New Connection...");
        newConnItem.setShortcut(new MenuShortcut(KeyEvent.VK_N));
        newConnItem.addActionListener(e -> showConnectionDialog());
        fileMenu.add(newConnItem);
        
        fileMenu.addSeparator();
        
        MenuItem uploadItem = new MenuItem("Upload to Host...");
        uploadItem.setShortcut(new MenuShortcut(KeyEvent.VK_U));
        uploadItem.addActionListener(e -> showFileTransferDialog(false));
        fileMenu.add(uploadItem);
        
        MenuItem downloadItem = new MenuItem("Download from Host...");
        downloadItem.setShortcut(new MenuShortcut(KeyEvent.VK_D));
        downloadItem.addActionListener(e -> showFileTransferDialog(true));
        fileMenu.add(downloadItem);
        
        fileMenu.addSeparator();
        
        MenuItem disconnectItem = new MenuItem("Disconnect");
        disconnectItem.addActionListener(e -> disconnect());
        fileMenu.add(disconnectItem);
        
        MenuItem reconnectItem = new MenuItem("Reconnect");
        reconnectItem.addActionListener(e -> reconnect());
        fileMenu.add(reconnectItem);
        
        fileMenu.addSeparator();
        
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setShortcut(new MenuShortcut(KeyEvent.VK_Q));
        exitItem.addActionListener(e -> {
            disconnect();
            dispose();
            System.exit(0);
        });
        fileMenu.add(exitItem);
        
        menuBar.add(fileMenu);
        
        // ===== EDIT MENU =====
        Menu editMenu = new Menu("Edit");
        
        MenuItem copyItem = new MenuItem("Copy");
        copyItem.setShortcut(new MenuShortcut(KeyEvent.VK_C));
        copyItem.addActionListener(e -> copySelection());
        editMenu.add(copyItem);
        
        MenuItem pasteItem = new MenuItem("Paste");
        pasteItem.setShortcut(new MenuShortcut(KeyEvent.VK_V));
        pasteItem.addActionListener(e -> pasteFromClipboard());
        editMenu.add(pasteItem);
        
        editMenu.addSeparator();
        
        MenuItem selectAllItem = new MenuItem("Select All");
        selectAllItem.setShortcut(new MenuShortcut(KeyEvent.VK_A));
        selectAllItem.addActionListener(e -> selectAll());
        editMenu.add(selectAllItem);
        
        menuBar.add(editMenu);
        
        // ===== VIEW MENU =====
        Menu viewMenu = new Menu("View");
        
        CheckboxMenuItem showKeyboardItem = new CheckboxMenuItem("Show Keyboard Panel", true);
        showKeyboardItem.addItemListener(e -> {
            keyboardPanel.setVisible(showKeyboardItem.getState());
            pack();
        });
        viewMenu.add(showKeyboardItem);
        
        viewMenu.addSeparator();
        
        MenuItem fontSizeItem = new MenuItem("Font Size...");
        fontSizeItem.addActionListener(e -> showFontSizeDialog());
        viewMenu.add(fontSizeItem);
        
        MenuItem colorSchemeItem = new MenuItem("Color Scheme...");
        colorSchemeItem.addActionListener(e -> showColorSchemeDialog());
        viewMenu.add(colorSchemeItem);
        
        menuBar.add(viewMenu);
        
        // ===== SETTINGS MENU =====
        Menu settingsMenu = new Menu("Settings");
        
        MenuItem keyboardMapItem = new MenuItem("Keyboard Mapping...");
        keyboardMapItem.addActionListener(e -> showKeyboardMappingDialog());
        settingsMenu.add(keyboardMapItem);
        
        MenuItem terminalSettingsItem = new MenuItem("Terminal Settings...");
        terminalSettingsItem.addActionListener(e -> showTerminalSettingsDialog());
        settingsMenu.add(terminalSettingsItem);
        
        menuBar.add(settingsMenu);
        
        // ===== HELP MENU =====
        Menu helpMenu = new Menu("Help");
        
        MenuItem keyboardHelpItem = new MenuItem("Keyboard Reference");
        keyboardHelpItem.setShortcut(new MenuShortcut(KeyEvent.VK_F1));
        keyboardHelpItem.addActionListener(e -> showKeyboardReference());
        helpMenu.add(keyboardHelpItem);
        
        helpMenu.addSeparator();
        
        MenuItem aboutItem = new MenuItem("About");
        aboutItem.addActionListener(e -> showAboutDialog());
        helpMenu.add(aboutItem);
        
        menuBar.add(helpMenu);
        
        setMenuBar(menuBar);
    }
    
    /**
     * Show keyboard reference dialog.
     */
    private void showKeyboardReference() {
        Dialog dialog = new Dialog(this, "Keyboard Reference", false);
        dialog.setLayout(new BorderLayout(10, 10));
        
        dialog.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                dialog.dispose();
            }
        });
        
        TextArea textArea = new TextArea("", 25, 60, TextArea.SCROLLBARS_VERTICAL_ONLY);
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        
        StringBuilder help = new StringBuilder();
        help.append("TN3270 KEYBOARD REFERENCE\n");
        help.append("========================\n\n");
        
        help.append("FUNCTION KEYS:\n");
        help.append("  F1-F12         PF1-PF12 keys\n");
        help.append("  Enter          Send data to host\n");
        help.append("  Escape         Clear screen\n");
        help.append("  Insert         Toggle Insert/Replace mode\n\n");
        
        help.append("NAVIGATION:\n");
        help.append("  Arrow Keys     Move cursor\n");
        help.append("  Tab            Next unprotected field\n");
        help.append("  Shift+Tab      Previous unprotected field\n");
        help.append("  Home           Move to top-left\n");
        help.append("  Backspace      Delete previous character\n\n");
        
        help.append("EDITING:\n");
        help.append("  Ctrl+C         Copy selection\n");
        help.append("  Ctrl+V         Paste text\n");
        help.append("  Ctrl+A         Select all\n\n");
        
        help.append("SELECTION:\n");
        help.append("  Click+Drag     Select text\n");
        help.append("  Double-Click   Select word\n");
        help.append("  Triple-Click   Select line\n\n");
        
        help.append("FILE TRANSFER:\n");
        help.append("  Ctrl+U         Upload to host\n");
        help.append("  Ctrl+D         Download from host\n\n");
        
        help.append("OTHER:\n");
        help.append("  Ctrl+N         New connection\n");
        help.append("  Ctrl+Q         Quit\n");
        help.append("  F1             This help screen\n");
        
        textArea.setText(help.toString());
        
        dialog.add(textArea, BorderLayout.CENTER);
        
        Panel buttonPanel = new Panel(new FlowLayout());
        Button closeButton = new Button("Close");
        closeButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(closeButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    /**
     * Show about dialog.
     */
    private void showAboutDialog() {
        Dialog dialog = new Dialog(this, "About TN3270 Emulator", true);
        dialog.setLayout(new GridBagLayout());
        
        dialog.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                dialog.dispose();
            }
        });
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 20, 10, 20);
        gbc.gridx = 0;
        
        gbc.gridy = 0;
        Label titleLabel = new Label("TN3270 Terminal Emulator");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        dialog.add(titleLabel, gbc);
        
        gbc.gridy = 1;
        dialog.add(new Label("Version 2.0 - Enhanced Edition"), gbc);
        
        gbc.gridy = 2;
        dialog.add(new Label(" "), gbc); // Spacer
        
        gbc.gridy = 3;
        dialog.add(new Label("Features:"), gbc);
        
        gbc.gridy = 4;
        gbc.insets = new Insets(2, 40, 2, 20);
        dialog.add(new Label("• Multiple terminal models (3278/3279)"), gbc);
        
        gbc.gridy = 5;
        dialog.add(new Label("• TLS/SSL encryption"), gbc);
        
        gbc.gridy = 6;
        dialog.add(new Label("• IND$FILE file transfer"), gbc);
        
        gbc.gridy = 7;
        dialog.add(new Label("• WSF Set Reply Mode support"), gbc);
        
        gbc.gridy = 8;
        dialog.add(new Label("• Extended colors and highlighting"), gbc);
        
        gbc.gridy = 9;
        gbc.insets = new Insets(10, 20, 10, 20);
        dialog.add(new Label(" "), gbc); // Spacer
        
        gbc.gridy = 10;
        dialog.add(new Label("© 2024-2025 - All rights reserved"), gbc);
        
        gbc.gridy = 11;
        gbc.anchor = GridBagConstraints.CENTER;
        Button okButton = new Button("OK");
        okButton.addActionListener(e -> dialog.dispose());
        dialog.add(okButton, gbc);
        
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
	/**
	 * Set up the user interface.
	 */
	private void setupUI() {
		createMenuBar();
		
		setLayout(new BorderLayout());

		// Create toolbar
		EnhancedRibbonToolbar toolbar = new EnhancedRibbonToolbar(new EnhancedRibbonToolbar.ToolbarActionListener() {
			@Override
			public void onNewConnection() {
				showConnectionDialog();
			}

			@Override
			public void onDisconnect() {
				disconnect();
			}

			@Override
			public void onReconnect() {
				reconnect();
			}

			// @Override
			public void onUpload() {
				showFileTransferDialog(false);
			}

			// @Override
			public void onDownload() {
				showFileTransferDialog(true);
			}

			@Override
			public void onCopy() {
				inputHandler.copySelection();
			}

			@Override
			public void onPaste() {
				inputHandler.pasteFromClipboard();
			}

			@Override
			public void onSelectAll() {
				inputHandler.selectAll();
			}

			// @Override
			public void onColorScheme() {
				showColorSchemeDialog();
			}

			// @Override
			public void onFontSize() {
				showFontSizeDialog();
			}

			// @Override
			public void onKeyboardMapping() {
				showKeyboardMappingDialog();
			}

			// @Override
			public void onTerminalSettings() {
				showTerminalSettingsDialog();
			}
		});
		add(toolbar, BorderLayout.NORTH);

		// Create enhanced canvas
		canvas = new TerminalCanvas(screenBuffer, cursorManager, currentColorScheme);
		canvas.setColorScheme(currentColorScheme);
		canvas.addKeyListener(inputHandler);
		add(canvas, BorderLayout.CENTER);

		// Create bottom panel with status bar and keyboard
		Panel bottomPanel = new Panel(new BorderLayout());

		statusBar = new StatusBar();
		bottomPanel.add(statusBar, BorderLayout.NORTH);

		keyboardPanel = new ModernKeyboardPanel(new ModernKeyboardPanel.KeyboardActionListener() {
			@Override
			public void onPFKey(byte aid) {
				if (!isKeyboardLocked() && connected) {
					tn3270Protocol.sendAID(aid);
				}
			}

			@Override
			public void onPAKey(byte aid) {
				if (!isKeyboardLocked() && connected) {
					tn3270Protocol.sendAID(aid);
				}
			}

			@Override
			public void onClearKey() {
				if (!isKeyboardLocked() && connected) {
					screenBuffer.clearScreen();
					tn3270Protocol.sendAID(Constants.AID_CLEAR);
					canvas.repaint();
				}
			}

			@Override
			public void onResetKey() {
				tn3270Protocol.resetKeyboardLock();
			}

			@Override
			public void onEnterKey() {
				if (!isKeyboardLocked() && connected) {
					tn3270Protocol.sendAID(Constants.AID_ENTER);
				}
			}

			@Override
			public void onInsertKey() {
				inputHandler.toggleInsertMode();
			}

			@Override
			public void onEraseEOF() {
				// Implement erase to end of field
			}

			@Override
			public void onEraseEOL() {
				// Implement erase to end of line
			}

			@Override
			public void onNewline() {
				cursorManager.tabToNextField();
				canvas.repaint();
			}
		});
		bottomPanel.add(keyboardPanel, BorderLayout.SOUTH);

		add(bottomPanel, BorderLayout.SOUTH);

		// Window setup
		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				disconnect();
				dispose();
				System.exit(0);
			}
		});

		pack();
		setLocationRelativeTo(null);
		setVisible(true);

		canvas.requestFocus();
	}

	private void setupUIold() {
		setLayout(new BorderLayout());

		// Toolbar at top (Phase 6)
		toolbar = new EnhancedRibbonToolbar(new EnhancedRibbonToolbar.ToolbarActionListener() {
			@Override
			public void onNewConnection() {
				// showConnectionDialog();
				ConnectionDialog dialog = new ConnectionDialog(TN3270Emulator.this);
				ConnectionProfile profile = dialog.showDialog();
				if (profile != null) {
					disconnect();
					try {
						Thread.sleep(100);
					} catch (InterruptedException ex) {
					}
					// setUseTLS(profile.useTLS);
					setUseTLS(profile.useTLS());
					// connect(profile.hostname, profile.port);
					connect(profile.getHostname(), profile.getPort());
				}
			}

			@Override
			public void onDisconnect() {
				disconnect();
			}

			@Override
			public void onReconnect() {
				reconnect();
			}

			@Override
			public void onCopy() {
				copySelection();
			}

			@Override
			public void onPaste() {
				pasteFromClipboard();
			}

			@Override
			public void onSelectAll() {
				selectAll();
			}
		});
		add(toolbar, BorderLayout.NORTH);

		// Canvas in center (Phase 6)
		canvas = new TerminalCanvas(screenBuffer, cursorManager, currentColorScheme);
		canvas.addKeyListener(inputHandler);
		canvas.setFocusable(true);
		canvas.setSelectionListener(new TerminalCanvas.SelectionListener() {
			@Override
			public void onSelectionChanged(int start, int end) {
				if (statusBar != null) {
					int length = Math.abs(end - start) + 1;
					statusBar.setStatus("Selected " + length + " characters");
				}
			}
		});
		add(canvas, BorderLayout.CENTER);

		// Status bar and keyboard panel at bottom (Phase 6)
		statusBar = new StatusBar();

		keyboardPanel = new ModernKeyboardPanel(new ModernKeyboardPanel.KeyboardActionListener() {
			@Override
			public void onPFKey(byte aid) {
				if (isConnected() && !isKeyboardLocked()) {
					tn3270Protocol.sendAID(aid);
					canvas.requestFocus();
				} else {
					Toolkit.getDefaultToolkit().beep();
				}
			}

			@Override
			public void onPAKey(byte aid) {
				if (isConnected() && !isKeyboardLocked()) {
					tn3270Protocol.sendAID(aid);
					canvas.requestFocus();
				} else {
					Toolkit.getDefaultToolkit().beep();
				}
			}

			@Override
			public void onClearKey() {
				if (isConnected() && !isKeyboardLocked()) {
					screenBuffer.clearScreen();
					tn3270Protocol.sendAID(Constants.AID_CLEAR);
					canvas.repaint();
					canvas.requestFocus();
				} else {
					Toolkit.getDefaultToolkit().beep();
				}
			}

			@Override
			public void onResetKey() {
				if (tn3270Protocol != null) {
					tn3270Protocol.resetKeyboardLock();
					statusBar.setConnected(connected);
					statusBar.setMode(inputHandler.isInsertMode() ? "Insert" : "Replace", false);
					canvas.repaint();
				}
				canvas.requestFocus();
			}

			@Override
			public void onEnterKey() {
				if (isConnected() && !isKeyboardLocked()) {
					tn3270Protocol.sendAID(Constants.AID_ENTER);
					canvas.requestFocus();
				} else {
					Toolkit.getDefaultToolkit().beep();
				}
			}

			@Override
			public void onInsertKey() {
				inputHandler.toggleInsertMode();
				statusBar.setMode(inputHandler.isInsertMode() ? "Insert" : "Replace", isKeyboardLocked());
				canvas.requestFocus();
			}

			@Override
			public void onEraseEOF() {
				if (isConnected() && !isKeyboardLocked()) {
					eraseToEndOfField();
					canvas.requestFocus();
				} else {
					Toolkit.getDefaultToolkit().beep();
				}
			}

			@Override
			public void onEraseEOL() {
				if (isConnected() && !isKeyboardLocked()) {
					eraseToEndOfLine();
					canvas.requestFocus();
				} else {
					Toolkit.getDefaultToolkit().beep();
				}
			}

			@Override
			public void onNewline() {
				if (isConnected() && !isKeyboardLocked()) {
					cursorManager.tabToNextField();
					canvas.repaint();
					canvas.requestFocus();
				} else {
					Toolkit.getDefaultToolkit().beep();
				}
			}
		});

		Panel bottomPanel = new Panel(new BorderLayout());
		bottomPanel.add(statusBar, BorderLayout.NORTH);
		bottomPanel.add(keyboardPanel, BorderLayout.SOUTH);
		add(bottomPanel, BorderLayout.SOUTH);
	}

	/**
	 * Connect to host.
	 */
	public void connect(String hostname, int port) {
		try {
			if (useTLS) {
				SSLSocketFactory factory = createTrustAllSSLSocketFactory();
				socket = factory.createSocket(hostname, port);

				if (socket instanceof SSLSocket) {
					SSLSocket sslSocket = (SSLSocket) socket;
					sslSocket.setEnabledProtocols(new String[] { "TLSv1.2", "TLSv1.3" });
					sslSocket.startHandshake();
				}

				statusBar.setStatus("TLS connected to " + hostname + ":" + port);
			} else {
				socket = new Socket(hostname, port);
				statusBar.setStatus("Connected to " + hostname + ":" + port);
			}

			input = socket.getInputStream();
			output = socket.getOutputStream();
			connected = true;

			statusBar.setConnected(true);
			keyboardPanel.setConnected(true);

			// Initialize protocol components now that we have streams
			telnetProtocol = new TelnetProtocol(output, model);
			telnetProtocol.setCallback(this);

			tn3270Protocol = new TN3270Protocol(output, screenBuffer, telnetProtocol, primaryRows, primaryCols,
					alternateRows, alternateCols);
			tn3270Protocol.setCallback(this);

			fileTransferProtocol = new FileTransferProtocol(output, telnetProtocol, fileTransferManager);
			fileTransferProtocol.setCallback(this);

			// Start data stream reader
			dataStreamReader = new DataStreamReader(input, telnetProtocol, this);
			dataStreamReader.start();

			canvas.requestFocus();

		} catch (IOException e) {
			statusBar.setStatus("Connection failed: " + e.getMessage());
			statusBar.setConnected(false);
			keyboardPanel.setConnected(false);
			e.printStackTrace();
		}
	}

	/**
	 * Disconnect from host.
	 */
	public void disconnect() {
		connected = false;

		statusBar.setConnected(false);
		keyboardPanel.setConnected(false);

		if (dataStreamReader != null) {
			dataStreamReader.stop();
		}

		try {
			if (socket != null) {
				socket.close();
			}
		} catch (IOException e) {
			// Ignore
		}

		statusBar.setStatus("Disconnected");
	}

	/**
	 * Reconnect to previous host.
	 */
	private void reconnect() {
		if (socket != null && !socket.isClosed()) {
			try {
				String hostname = socket.getInetAddress().getHostName();
				int port = socket.getPort();
				disconnect();

				// Brief delay to ensure clean disconnect
				Thread.sleep(500);

				connect(hostname, port);
				statusBar.setStatus("Reconnected to " + hostname);
			} catch (Exception e) {
				statusBar.setStatus("Reconnect failed: " + e.getMessage());
			}
		} else {
			statusBar.setStatus("No previous connection to reconnect to");
			Toolkit.getDefaultToolkit().beep();
		}
	}

	/**
	 * Create SSL socket factory that trusts all certificates.
	 */
	private SSLSocketFactory createTrustAllSSLSocketFactory() {
		try {
			TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
				public java.security.cert.X509Certificate[] getAcceptedIssuers() {
					return null;
				}

				public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
				}

				public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
				}
			} };

			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, trustAllCerts, new java.security.SecureRandom());
			return sc.getSocketFactory();

		} catch (Exception e) {
			e.printStackTrace();
			return (SSLSocketFactory) SSLSocketFactory.getDefault();
		}
	}

	// ===== Phase 7 Dialog Methods =====

	/**
	 * Show connection dialog (Phase 7).
	 */
	private void showConnectionDialog() {
		ConnectionDialog dialog = new ConnectionDialog(this);
		ConnectionProfile profile = dialog.showDialog();
		if (profile != null) {
			disconnect();
			try {
				Thread.sleep(100);
			} catch (InterruptedException ex) {
			}
			// setUseTLS(profile.useTLS);
			setUseTLS(profile.useTLS());
			// connect(profile.hostname, profile.port);
			connect(profile.getHostname(), profile.getPort());
		}
	}

	/**
	 * Show file transfer dialog (Phase 7).
	 */
	private void showFileTransferDialog(boolean isDownload) {
		if (!connected) {
			MessageDialog.showError(this, "Connection Required", "Not connected to host");
			return;
		}

		FileTransferDialog dialog = new FileTransferDialog(this, isDownload);
		FileTransferDialog.TransferConfig config = dialog.showDialog();

		if (config != null) {
			initiateFileTransfer(config);
		}
	}

	/**
	 * Initiate file transfer with config (Phase 7).
	 */
	private void initiateFileTransfer(FileTransferDialog.TransferConfig config) {
		// Show progress dialog
		String operation = config.command.contains("GET") ? "Downloading" : "Uploading";
		progressDialog = new ProgressDialog(this, operation);
		progressDialog.updateProgress("Sending command to host...");
		progressDialog.updateStatus(new File(config.localFile).getName());

		progressDialog.setCancelListener(() -> {
			statusBar.setStatus("Transfer cancelled");
		});

		// Type command and send
		for (char c : config.command.toCharArray()) {
			int cursorPos = cursorManager.getCursorPos();
			if (!screenBuffer.isProtected(cursorPos)) {
				screenBuffer.setChar(cursorPos, c);
				screenBuffer.setModified(cursorPos);
				cursorManager.moveCursor(1);
			}
		}

		canvas.repaint();

		// Send ENTER
		tn3270Protocol.sendAID(Constants.AID_ENTER);

		progressDialog.showDialog();
	}

	/**
	 * Show color scheme dialog (Phase 7).
	 */
	private void showColorSchemeDialog() {
		ColorSchemeDialog dialog = new ColorSchemeDialog(this, currentColorScheme);
		ColorScheme newScheme = dialog.showDialog();
		if (newScheme != null) {
			currentColorScheme = newScheme;
			canvas.setColorScheme(newScheme);
			statusBar.setStatus("Color scheme changed");
		}
	}

	/**
	 * Show font size dialog (Phase 7).
	 */
	private void showFontSizeDialog() {
		FontSizeDialog dialog = new FontSizeDialog(this);
		int size = dialog.showDialog();
		if (size > 0) {
			Font newFont = new Font("Monospaced", Font.PLAIN, size);
			canvas.setTerminalFont(newFont);
			statusBar.setStatus("Font size changed to " + size);
		}
	}

	/**
	 * Show keyboard mapping dialog (Phase 7).
	 */
	private void showKeyboardMappingDialog() {
		KeyboardMappingDialog dialog = new KeyboardMappingDialog(this);
		dialog.showDialog();
	}

	/**
	 * Show terminal settings dialog (Phase 7).
	 */
	private void showTerminalSettingsDialog() {
		TerminalSettingsDialog dialog = new TerminalSettingsDialog(this, model);
		String newModel = dialog.showDialog();
		if (newModel != null && !newModel.equals(model)) {
			MessageDialog.showMessage(this, "Settings Saved",
					"Terminal model change will take effect on next connection.");
			model = newModel;
		}
	}

	// ===== Edit Operations (Phase 6) =====

	/**
	 * Copy selected text to clipboard.
	 */
	private void copySelection() {
		if (!canvas.hasSelection()) {
			Toolkit.getDefaultToolkit().beep();
			return;
		}

		int start = Math.min(canvas.getSelectionStart(), canvas.getSelectionEnd());
		int end = Math.max(canvas.getSelectionStart(), canvas.getSelectionEnd());

		StringBuilder sb = new StringBuilder();
		int startRow = start / cols;
		int endRow = end / cols;

		for (int row = startRow; row <= endRow; row++) {
			int rowStart = (row == startRow) ? start : row * cols;
			int rowEnd = (row == endRow) ? end : (row + 1) * cols - 1;

			for (int pos = rowStart; pos <= rowEnd && pos < screenBuffer.getBufferSize(); pos++) {
				char c = screenBuffer.getChar(pos);
				if (c == '\0')
					c = ' ';
				if (!screenBuffer.isFieldStart(pos)) {
					sb.append(c);
				}
			}

			if (row < endRow) {
				sb.append('\n');
			}
		}

		// Clean up trailing whitespace
		String text = sb.toString();
		String[] lines = text.split("\n");
		StringBuilder cleaned = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			cleaned.append(lines[i].replaceAll("\\s+$", ""));
			if (i < lines.length - 1) {
				cleaned.append('\n');
			}
		}

		try {
			Toolkit toolkit = Toolkit.getDefaultToolkit();
			Clipboard clipboard = toolkit.getSystemClipboard();
			StringSelection selection = new StringSelection(cleaned.toString());
			clipboard.setContents(selection, null);

			statusBar.setStatus("Copied " + (end - start + 1) + " characters");
			canvas.clearSelection();

		} catch (Exception ex) {
			statusBar.setStatus("Copy failed: " + ex.getMessage());
			ex.printStackTrace();
		}
	}

	/**
	 * Paste text from clipboard.
	 */
	private void pasteFromClipboard() {
		if (!connected || (tn3270Protocol != null && tn3270Protocol.isKeyboardLocked())) {
			Toolkit.getDefaultToolkit().beep();
			return;
		}

		try {
			Toolkit toolkit = Toolkit.getDefaultToolkit();
			Clipboard clipboard = toolkit.getSystemClipboard();
			String text = (String) clipboard.getData(DataFlavor.stringFlavor);

			if (text == null || text.isEmpty()) {
				return;
			}

			// Send text through input handler
			for (char c : text.toCharArray()) {
				if (c == '\n' || c == '\r') {
					cursorManager.tabToNextField();
					continue;
				}

				if (c < 32 || c > 126) {
					continue;
				}

				int cursorPos = cursorManager.getCursorPos();
				if (!screenBuffer.isProtected(cursorPos)) {
					screenBuffer.setChar(cursorPos, c);
					screenBuffer.setModified(cursorPos);
					cursorManager.moveCursor(1);

					int nextPos = cursorManager.getCursorPos();
					if (screenBuffer.isFieldStart(nextPos)) {
						cursorManager.tabToNextField();
					}
				} else {
					cursorManager.tabToNextField();
					cursorPos = cursorManager.getCursorPos();
					if (!screenBuffer.isProtected(cursorPos)) {
						screenBuffer.setChar(cursorPos, c);
						screenBuffer.setModified(cursorPos);
						cursorManager.moveCursor(1);
					}
				}
			}

			canvas.repaint();
			statusBar.setStatus("Pasted " + text.length() + " characters");

		} catch (Exception ex) {
			statusBar.setStatus("Paste failed: " + ex.getMessage());
			ex.printStackTrace();
		}
	}

	/**
	 * Select all text.
	 */
	private void selectAll() {
		// TODO: Implement proper select all in TerminalCanvas
		statusBar.setStatus("Select all - feature coming soon");
	}

	/**
	 * Erase to end of field.
	 */
	private void eraseToEndOfField() {
		int cursorPos = cursorManager.getCursorPos();
		if (screenBuffer.isProtected(cursorPos)) {
			Toolkit.getDefaultToolkit().beep();
			return;
		}

		int fieldStart = screenBuffer.findFieldStart(cursorPos);
		int fieldEnd = screenBuffer.findNextField(fieldStart);

		for (int i = cursorPos; i < fieldEnd && !screenBuffer.isFieldStart(i); i++) {
			screenBuffer.setChar(i, '\0');
		}

		screenBuffer.setModified(cursorPos);
		canvas.repaint();
		onStatusUpdate();
	}

	/**
	 * Erase to end of line.
	 */
	private void eraseToEndOfLine() {
		int cursorPos = cursorManager.getCursorPos();
		if (screenBuffer.isProtected(cursorPos)) {
			Toolkit.getDefaultToolkit().beep();
			return;
		}

		int row = cursorPos / cols;
		int endOfLine = (row + 1) * cols;
		int fieldStart = screenBuffer.findFieldStart(cursorPos);
		int fieldEnd = screenBuffer.findNextField(fieldStart);

		for (int i = cursorPos; i < endOfLine && i < fieldEnd && !screenBuffer.isFieldStart(i); i++) {
			screenBuffer.setChar(i, '\0');
		}

		screenBuffer.setModified(cursorPos);
		canvas.repaint();
		onStatusUpdate();
	}

	// ===== DataStreamListener Implementation =====

	@Override
	public void on3270Data(byte[] data) {
		// Check if this is a Data Chain (file transfer) command
		if (data.length >= 3 && data[0] == Constants.SFID_DATA_CHAIN) {
			fileTransferProtocol.handleDataChain(data, 0, data.length);
		} else {
			tn3270Protocol.process3270Data(data);
		}
	}

	/*
	 * @Override public void onConnectionLost(String reason) { connected = false;
	 * statusBar.setStatus(reason); statusBar.setConnected(false);
	 * keyboardPanel.setConnected(false); }
	 */
//here
// ===== DataStreamListener Implementation =====
	/*
	 * @Override public void on3270Data(byte[] data) { // Data is processed by
	 * TN3270Protocol - no action needed here }
	 */
	@Override
	public void onWSFData(byte[] data) {
		// WSF data is processed by TN3270Protocol - no action needed here
	}

	@Override
	public void onConnectionLost(String message) {
		statusBar.setStatus(message);
		statusBar.setConnected(false);
		keyboardPanel.setConnected(false);
		connected = false;
	}

//here
	/*
	 * @Override public void onStatusUpdate(String message) {
	 * //statusBar.setStatus(message); int cursorPos = cursorManager.getCursorPos();
	 * int row = cursorPos / cols + 1; int col = cursorPos % cols + 1; String mode =
	 * inputHandler.isInsertMode() ? "Insert" : "Replace"; boolean locked =
	 * tn3270Protocol != null && tn3270Protocol.isKeyboardLocked();
	 * 
	 * // Update status bar statusBar.setPosition(row, col); statusBar.setMode(mode,
	 * locked); statusBar.setConnected(connected); }
	 */
	// ===== ProtocolCallback Implementation =====
	@Override
	public void onScreenChanged() {
		if (canvas != null) {
			canvas.repaint();
		}
	}

	@Override
	public void requestRepaint() {
		if (canvas != null) {
			canvas.repaint();
		}
	}

	@Override
	public void updateStatus(String message) {
		statusBar.setStatus(message);
	}

	@Override
	public void setKeyboardLocked(boolean locked) {
		keyboardPanel.setKeyboardLocked(locked);
		statusBar.setMode(inputHandler.isInsertMode() ? "Insert" : "Replace", locked);
		if (canvas != null) {
			canvas.repaint();
		}
	}

	@Override
	public void onScreenSizeChanged(int newRows, int newCols) {
		this.rows = newRows;
		this.cols = newCols;
		canvas.updateSize();
		pack();
		canvas.repaint();
	}

	@Override
	public void onKeyboardLockChanged(boolean locked) {
		keyboardPanel.setKeyboardLocked(locked);
		onStatusUpdate();
	}

	@Override
	public void playAlarm() {
		Toolkit.getDefaultToolkit().beep();
	}

	// ===== TelnetCallback Implementation =====

	@Override
	public void onTN3270EModeEnabled() {
		statusBar.setStatus("TN3270E mode active");
	}

	@Override
	public void onTN3270EModeFailed() {
		statusBar.setStatus("Using TN3270 mode");
	}

	@Override
	public void onTN3270EFailed() {
		statusBar.setStatus("TN3270E not supported, using TN3270");
	}

	@Override
	public void onConnected() {
		connected = true;
		statusBar.setStatus("Connected to host");
		statusBar.setConnected(true);
		keyboardPanel.setConnected(true);
	}

	@Override
	public void onDisconnected() {
		connected = false;
		statusBar.setStatus("Disconnected from host");
		statusBar.setConnected(false);
		keyboardPanel.setConnected(false);
		if (canvas != null) {
			canvas.repaint();
		}
	}

	// @Override
	public String getTerminalType() {
		return "IBM-" + model + "-E";
	}

	// @Override
	public void onQueryRequested() {
		// Query response is handled by TN3270Protocol
	}

	// ===== InputCallback Implementation =====

	@Override
	public void onAIDKey(byte aid) {
		if (tn3270Protocol != null) {
			tn3270Protocol.sendAID(aid);
		}
	}

	@Override
	public void onClearScreen() {
		screenBuffer.clearScreen();
	}

	@Override
	public void onRepaintRequested() {
		if (canvas != null) {
			canvas.repaint();
		}
	}

	@Override
	public void onStatusUpdate() {
		int cursorPos = cursorManager.getCursorPos();
		int row = cursorPos / cols + 1;
		int col = cursorPos % cols + 1;

		// Get insert mode from InputHandler
		boolean insertMode = inputHandler.isInsertMode();
		String mode = insertMode ? "Insert" : "Replace";

		statusBar.setPosition(row, col);
		statusBar.setMode(mode, tn3270Protocol.isKeyboardLocked());

		/*
		 * String mode = inputHandler.isInsertMode() ? "Insert" : "Replace"; boolean
		 * locked = tn3270Protocol != null && tn3270Protocol.isKeyboardLocked();
		 * 
		 * statusBar.setPosition(row, col); statusBar.setMode(mode, locked);
		 */

		statusBar.setConnected(connected);
	}

	@Override
	public void onInsertModeChanged(boolean insertMode) {
		keyboardPanel.setInsertMode(insertMode);
		onStatusUpdate();
	}

	// @Override
	public void onAlertSound() {
		Toolkit.getDefaultToolkit().beep();
	}

	@Override
	public boolean isKeyboardLocked() {
		return tn3270Protocol != null && tn3270Protocol.isKeyboardLocked();
	}

	@Override
	public boolean isConnected() {
		return connected;
	}

	// ===== TransferCallback Implementation =====

	@Override
	public void onTransferStart(String filename, boolean isUpload) {
		statusBar.setStatus((isUpload ? "Uploading: " : "Downloading: ") + filename);
		keyboardPanel.setTransferActive(true);
		if (progressDialog != null) {
			progressDialog.updateProgress("Transfer started...");
			progressDialog.updateStatus(filename);
		}
	}

	/*
	 * @Override public void onTransferProgress(int blockNumber, long
	 * bytesTransferred) { statusBar.setStatus(String.format("Block %d | %d bytes",
	 * blockNumber, bytesTransferred)); if (progressDialog != null) {
	 * progressDialog.updateProgress("Block " + blockNumber);
	 * progressDialog.updateStatus(bytesTransferred + " bytes transferred"); } }
	 */
	@Override
	public void onTransferProgress(int bytesTransferred, long totalBytes) {
		statusBar.setStatus(String.format("Transferred: %d / %d bytes", bytesTransferred, totalBytes));
		if (progressDialog != null) {
			progressDialog.updateProgress("Transferring...");
			progressDialog.updateStatus(bytesTransferred + " / " + totalBytes + " bytes");
		}
	}
	/*
	 * @Override public void onTransferComplete(String message) {
	 * statusBar.setStatus("Transfer complete: " + message);
	 * keyboardPanel.setTransferActive(false); if (progressDialog != null) {
	 * progressDialog.close(); progressDialog = null; }
	 * MessageDialog.showMessage(this, "Transfer Complete", message); }
	 */

	@Override
	public void onTransferComplete(boolean success, String message) {
		statusBar.setStatus("Transfer complete: " + message);
		keyboardPanel.setTransferActive(false);
		if (progressDialog != null) {
			progressDialog.close();
			progressDialog = null;
		}
		if (success) {
			MessageDialog.showMessage(this, "Transfer Complete", message);
		} else {
			MessageDialog.showError(this, "Transfer Failed", message);
		}
	}

	@Override
	public void onTransferError(String reason) {
		statusBar.setStatus("Transfer error: " + reason);
		keyboardPanel.setTransferActive(false);
		if (progressDialog != null) {
			progressDialog.close();
			progressDialog = null;
		}
		MessageDialog.showError(this, "Transfer Error", reason);
	}

	@Override
	public void onStatusMessage(String message) {
		statusBar.setStatus(message);
	}

	// ===== Helper Methods =====

	/**
	 * Set whether to use TLS.
	 */
	public void setUseTLS(boolean useTLS) {
		this.useTLS = useTLS;
	}

	/**
	 * Main entry point.
	 */
	public static void main(String[] args) {
		if (args.length < 1) {
			System.out.println("Usage: java TN3270Emulator <hostname> [port] [model] [options]");
			System.out.println("Models: 3278-2 (24x80), 3278-3 (32x80), 3278-4 (43x80), 3278-5 (27x132)");
			System.out.println("        3279-2 (24x80 Color), 3279-3 (32x80 Color)");
			System.out.println("Options:");
			System.out.println("  --tls        Use TLS/SSL encryption");
			System.exit(1);
		}

		String hostname = args[0];
		int port = 23;
		String model = "3279-3";
		boolean useTLS = false;

		for (int i = 1; i < args.length; i++) {
			if (args[i].equals("--tls")) {
				useTLS = true;
			} else if (args[i].startsWith("3278-") || args[i].startsWith("3279-")) {
				model = args[i].trim();
			} else {
				try {
					port = Integer.parseInt(args[i]);
				} catch (NumberFormatException e) {
					// Ignore
				}
			}
		}

		TN3270Emulator emulator = new TN3270Emulator(model);
		emulator.setUseTLS(useTLS);
		emulator.connect(hostname, port);
	}
}
