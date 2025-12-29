package com.tn3270;

import com.tn3270.ai.AIManager;
import com.tn3270.model.ScreenModel;
import com.tn3270.ui.StatusBar;
import com.tn3270.ui.TerminalPanel;
import com.tn3270.ui.dialogs.KeyboardSettingsDialog;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import javax.net.SocketFactory;
import javax.net.ssl.*;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.*;

import java.util.Base64;
import java.util.ArrayList;

import static com.tn3270.constants.TelnetConstants.*;
import static com.tn3270.constants.ProtocolConstants.*;
import static com.tn3270.util.EBCDIC.*;

public class TN3270Session extends JPanel implements KeyListener {

	public interface MemoryTransferCallback {
		void onDownloadComplete(byte[] content); // UPDATED: byte[] instead of String
		void onUploadComplete();
		void onError(String message);
	}

	private static final Map<String, Dimension> MODELS = new HashMap<>();
	static {
		MODELS.put("3278-2", new Dimension(80, 24));
		MODELS.put("3278-3", new Dimension(80, 32));
		MODELS.put("3278-4", new Dimension(80, 43));
		MODELS.put("3278-5", new Dimension(132, 27));
		MODELS.put("3279-2", new Dimension(80, 24));
		MODELS.put("3279-3", new Dimension(80, 32));
		MODELS.put("3290", new Dimension(160, 62));
	}

	private static final int STATE_DATA = 0;
	private static final int STATE_IAC = 1;
	private static final int STATE_SB = 2;
	private static final int STATE_WILL = 3;
	private static final int STATE_WONT = 4;
	private static final int STATE_DO = 5;
	private static final int STATE_DONT = 6;

	private static final byte SF_ID_SET_REPLY_MODE = (byte) 0x09;

	private enum FileTransferState {
		IDLE, OPEN_SENT, TRANSFER_IN_PROGRESS, CLOSE_SENT, ERROR
	}

	private enum FileTransferDirection {
		UPLOAD, DOWNLOAD
	}

	public enum HostType {
		TSO, CMS, LINUX
	}

	private enum ReplyMode {
		FIELD, EXTENDED_FIELD, CHARACTER
	}

	private static final Map<String, ColorScheme> COLOR_SCHEMES = new HashMap<>();

	public static class ColorScheme {
		Color background, defaultFg, cursor;
		Color[] colors;

		ColorScheme(Color bg, Color defaultFg, Color cursor, Color[] colors) {
			this.background = bg;
			this.defaultFg = defaultFg;
			this.cursor = cursor;
			this.colors = colors;
		}
	}

	static {
		// 1. Classic
		COLOR_SCHEMES.put("Green on Black (Classic)",
				new ColorScheme(Color.BLACK, Color.GREEN, Color.WHITE, new Color[] { Color.BLACK, Color.BLUE, Color.RED,
						Color.MAGENTA, Color.GREEN, Color.CYAN, Color.YELLOW, Color.WHITE }));

		// 2. White on Black (Monochrome Enforced)
		COLOR_SCHEMES.put("White on Black",
				new ColorScheme(Color.BLACK, Color.WHITE, new Color(255, 255, 0),
						new Color[] { Color.BLACK, new Color(200, 200, 200), // 1 Blue -> Dim White
								Color.WHITE, // 2 Red -> White
								Color.WHITE, // 3 Pink -> White
								Color.WHITE, // 4 Green -> White
								Color.WHITE, // 5 Turquoise -> White
								Color.WHITE, // 6 Yellow -> White
								Color.WHITE // 7 White -> White
						}));

		// 3. Amber on Black (Monochrome Enforced)
		Color amber = new Color(255, 176, 0);
		Color dimAmber = new Color(180, 130, 0);
		COLOR_SCHEMES.put("Amber on Black",
				new ColorScheme(Color.BLACK, amber, new Color(255, 200, 50), new Color[] { Color.BLACK, dimAmber, // 1
																													// Blue
																													// ->
																													// Dim
																													// Amber
						amber, // 2 Red -> Amber
						amber, // 3 Pink -> Amber
						amber, // 4 Green -> Amber
						amber, // 5 Turquoise -> Amber
						amber, // 6 Yellow -> Amber
						amber // 7 White -> Amber
				}));

		// 4. Green on Dark Green
		COLOR_SCHEMES.put("Green on Dark Green",
				new ColorScheme(new Color(0, 40, 0), new Color(51, 255, 51), new Color(102, 255, 102),
						new Color[] { new Color(0, 40, 0), new Color(0, 128, 128), new Color(0, 200, 0),
								new Color(102, 255, 102), new Color(51, 255, 51), new Color(153, 255, 153),
								new Color(204, 255, 204), new Color(230, 255, 230) }));

		// 5. IBM Blue
		COLOR_SCHEMES.put("IBM 3270 Blue",
				new ColorScheme(new Color(0, 0, 64), new Color(0, 255, 0), Color.WHITE,
						new Color[] { new Color(0, 0, 64), new Color(85, 170, 255), new Color(255, 85, 85),
								new Color(255, 85, 255), new Color(0, 255, 0), new Color(85, 255, 255),
								new Color(255, 255, 85), Color.WHITE }));

		// 6. Solarized
		COLOR_SCHEMES.put("Solarized Dark",
				new ColorScheme(new Color(0, 43, 54), new Color(131, 148, 150), new Color(147, 161, 161),
						new Color[] { new Color(0, 43, 54), new Color(38, 139, 210), new Color(220, 50, 47),
								new Color(211, 54, 130), new Color(133, 153, 0), new Color(42, 161, 152),
								new Color(181, 137, 0), new Color(238, 232, 213) }));
	}

	private ScreenModel screenModel;
	public TerminalPanel terminalPanel;
	// FIX: Promote scrollPane to class level so we can change viewport color
	private JScrollPane scrollPane;
	private ComponentAdapter resizeListener;
	// NEW: Control Flag for Auto-Fit Behavior
	// False = Manual Mode (Scrollbars appear on shrink, Black bars on grow)
	// True = Tile Mode (Font scales to fit window)
	private boolean autoFitOnResize = false;
	private StatusBar statusBar;
	private Timer blinkTimer;
	private Frame parentFrame;

	private Socket socket;
	private InputStream input;
	private OutputStream output;

	// NEW: Memory Transfer State
	private boolean isMemoryTransfer = false;
	private byte[] memoryUploadData; // Source for AI -> Host
	private ByteArrayOutputStream memoryDownloadBuffer; // Sink for Host -> AI
	private MemoryTransferCallback transferCallback;

	private volatile boolean connected = false;
	private Thread readerThread;
	private boolean useTLS = false;
	private String requestedLuName = "";
	private String currentHost = "";
	private int currentPort = 23;
	private String modelName = "3279-3";

	public boolean insertMode = false;
	public boolean keyboardLocked = false;
	private int lastAID = AID_ENTER;
	private boolean tn3270eMode = false;
	private boolean tn3270eAttempted = false;
	private int replyModeFlags = 0;
	private ReplyMode currentReplyMode = ReplyMode.FIELD;

	// File Transfer
	private FileTransferState ftState = FileTransferState.IDLE;
	private FileTransferDirection ftDirection = FileTransferDirection.DOWNLOAD;
	private int blockSequence = 0;
	private long transferredBytes = 0;
	private InputStream uploadStream;
	private OutputStream downloadStream;
	private File currentFile;
	private String currentFilename = null;
	private boolean ftIsText = true;
	private boolean ftIsMessage = false;
	private boolean ftHadSuccessfulTransfer = false;
	private boolean pendingCR = false;
	private HostType hostType = HostType.CMS;
	private final Object keyboardLockMonitor = new Object();

	// UI Dialogs
	private JDialog progressDialog;
	private JProgressBar transferProgressBar;
	private JLabel progressLabel, transferStatusLabel;
	private JButton cancelTransferButton;

	private boolean enableSound = true;
	private boolean autoAdvance = true;

	private boolean isProgrammaticResize = false;

	public enum CursorStyle {
		BLOCK("Block"), UNDERSCORE("Underscore"), I_BEAM("I-Beam");

		private String label;

		CursorStyle(String l) {
			this.label = l;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private CursorStyle cursorStyle = CursorStyle.BLOCK;

	private Map<Integer, KeyMapping> keyMap = new HashMap<>();
	private Map<Character, Character> inputCharMap = new HashMap<>();
	private static final String KEYMAP_FILE = System.getProperty("user.home") + File.separator + ".tn3270keymap";

	public static class KeyMapping implements Serializable {
		private static final long serialVersionUID = 1L;
		public char character;
		public Integer aid;
		public String description;

		public KeyMapping(char c, String d) {
			this.character = c;
			this.description = d;
		}

		public KeyMapping(int a, String d) {
			this.aid = a;
			this.description = d;
		}
	}

	public TN3270Session(String modelName) {
		this(modelName, null);
	}

	public TN3270Session(String modelName, Frame parent) {
		super(new BorderLayout());
		this.parentFrame = parent;
		this.modelName = (modelName != null && !modelName.trim().isEmpty()) ? modelName.trim() : "3278-2";
		this.screenModel = new ScreenModel(this.modelName, MODELS);
		this.terminalPanel = new TerminalPanel(screenModel);

		// FIX: ScrollPane is now a class field so applyColorScheme can access it
		scrollPane = new JScrollPane(terminalPanel);
		scrollPane.setBorder(null);
		scrollPane.getViewport().setBackground(Color.BLACK);
		// HIDE scrollbars by default to encourage auto-fit
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		add(scrollPane, BorderLayout.CENTER);

		this.statusBar = new StatusBar();
		add(statusBar, BorderLayout.SOUTH);

		// FIX: Auto-apply Amber theme for 3290 Plasma displays
		if ("3290".equals(this.modelName)) {
			applyColorScheme("Amber on Black");
		}

		// blinkTimer = new Timer(500, e -> terminalPanel.repaint());
		// blinkTimer.start();
		// FIX: Explicitly toggle the blink state
		blinkTimer = new Timer(500, e -> terminalPanel.toggleBlink());
		blinkTimer.start();

		setFocusable(true);

		// FIX: Disable Swing's TAB traversal
		setFocusTraversalKeysEnabled(false);

		addKeyListener(this);
		terminalPanel.addKeyListener(this);
		initializeKeyMappings();
		
		// --- FIX: Initialize Context Menu ---
		setupMouseShortcuts();

		// Define Listener - RESPECTS autoFitOnResize flag
		resizeListener = new ComponentAdapter() {
			@Override
			public void componentShown(ComponentEvent e) {
				terminalPanel.requestFocusInWindow();
				// On Tab Switch, if AutoFit is on (Tiles), verify fit.
				// If Manual (Tabs), we rely on snapWindow() called by the Emulator,
				// so we don't force a fit here to avoid overwriting scrollbar state.
				if (autoFitOnResize && scrollPane.getWidth() > 0 && scrollPane.getHeight() > 0) {
					terminalPanel.fitToSize(scrollPane.getWidth(), scrollPane.getHeight());
				}
			}

			@Override
			public void componentResized(ComponentEvent e) {
				// Only auto-scale the font if we are in Tile Mode
				if (autoFitOnResize && scrollPane.getWidth() > 0 && scrollPane.getHeight() > 0) {
					terminalPanel.fitToSize(scrollPane.getWidth(), scrollPane.getHeight());
				}
			}
		};

		// Add Listener
		addComponentListener(resizeListener);

		// ---------------------------------------------------------
		// FIX PART 1: Handle Tab Switching / Initial Display
		// ---------------------------------------------------------
		/*
		 * addHierarchyListener(e -> { if ((e.getChangeFlags() &
		 * HierarchyEvent.DISPLAYABILITY_CHANGED) != 0 && isDisplayable()) {
		 * 
		 * // 1. Initial Size Update SwingUtilities.invokeLater(() ->
		 * terminalPanel.updateSize());
		 * 
		 * // 2. AUTO-FIT LOGIC: If we are being added to a container with existing
		 * dimensions // (like a TabbedPane that is already on screen), fit the font to
		 * it. // If it's a new window (width=0), the Smart Default in TerminalPanel
		 * handles it. // //Container parentContainer = getParent(); //if
		 * (parentContainer != null && parentContainer.getWidth() > 0 &&
		 * parentContainer.getHeight() > 0) { // SwingUtilities.invokeLater(() -> //
		 * terminalPanel.fitToSize(parentContainer.getWidth(),
		 * parentContainer.getHeight()) // ); //} //
		 * 
		 * // If the container has size, fit the font immediately if (getWidth() > 0 &&
		 * getHeight() > 0) { terminalPanel.fitToSize(getWidth(), getHeight()); } } });
		 */

		// ---------------------------------------------------------
		// FIX PART 2: Handle Window Resizing (The "Black Bars" Fix)
		// ---------------------------------------------------------
		/*
		 * addComponentListener(new ComponentAdapter() {
		 * 
		 * @Override public void componentShown(ComponentEvent e) {
		 * terminalPanel.requestFocusInWindow(); }
		 * 
		 * @Override public void componentResized(ComponentEvent e) { // When window is
		 * resized, recalculate font to fill space if (getWidth() > 0 && getHeight() >
		 * 0) { terminalPanel.fitToSize(getWidth(), getHeight()); } } });
		 */

		// --- FIX 1: Initial Layout (HierarchyListener) ---
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0 && isDisplayable()) {
				SwingUtilities.invokeLater(() -> {
					terminalPanel.updateSize();

					// FIX: Subtract StatusBar height if scrollPane isn't laid out yet
					int w = scrollPane.getWidth();
					int h = scrollPane.getHeight();

					// Fallback if layout hasn't run yet
					if (w <= 0 || h <= 0) {
						w = getWidth();
						h = getHeight();
						if (statusBar != null && h > 0) {
							h -= statusBar.getPreferredSize().height;
						}
					}

					if (w > 0 && h > 0) {
						terminalPanel.fitToSize(w, h);
					}
				});
			}
		});

		// --- FIX 2: Dynamic Resizing (ComponentListener) ---
		/*
		 * addComponentListener(new ComponentAdapter() {
		 * 
		 * @Override public void componentShown(ComponentEvent e) {
		 * terminalPanel.requestFocusInWindow(); }
		 * 
		 * @Override public void componentResized(ComponentEvent e) { // FIX: Use
		 * scrollPane dimensions, NOT 'this' dimensions // 'this.getHeight()' includes
		 * the StatusBar, causing the calculation // to overshoot the available viewport
		 * space. if (scrollPane.getWidth() > 0 && scrollPane.getHeight() > 0) {
		 * terminalPanel.fitToSize(scrollPane.getWidth(), scrollPane.getHeight()); } }
		 * });
		 */
	}
	
	private void setupMouseShortcuts() {
		// Define our Interceptor Listener
		MouseAdapter aiShortcutListener = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				handleMouseAction(e);
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				handleMouseAction(e);
			}

			private void handleMouseAction(MouseEvent e) {
				// Determine if this event is for a "right-click" gesture on any platform.
				// This includes physical right-clicks (SwingUtilities.isRightMouseButton)
				// and OS-emulated right-clicks (e.isPopupTrigger, which handles Mac Ctrl+Click).
				boolean isRightClickGesture = SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger();

				// --- CRITICAL FIX ---
				// If it's part of a right-click gesture (press or release),
				// IMMEDIATELY CONSUME the event. This prevents the TerminalPanel's
				// default MouseListeners from seeing it and collapsing the selection.
				if (isRightClickGesture) {
					e.consume(); // Mark the event as handled.
				}

				// Only perform the AI action if it's the specific "popup trigger" event
				// (usually the release on Windows, or the press/release for Mac Ctrl+Click)
				if (e.isPopupTrigger()) {
					String selectedText = terminalPanel.getSelectedText();
					
					// Launch AI dialog. If no text selected, it opens empty.
					showAIChatDialog(getParentFrame(), selectedText != null ? selectedText : "");
				}
			}
		};

		// --- LISTENER PRIORITY HACK (Essential for overriding default behavior) ---
		// We must ensure 'aiShortcutListener' runs BEFORE any of TerminalPanel's default listeners.
		// Standard Swing adds listeners to the END of the list. We need to be at the FRONT.
		
		// 1. Temporarily remove all existing MouseListeners from TerminalPanel
		MouseListener[] existingListeners = terminalPanel.getMouseListeners();
		for (MouseListener l : existingListeners) {
			terminalPanel.removeMouseListener(l);
		}

		// 2. Add OUR interceptor listener first
		terminalPanel.addMouseListener(aiShortcutListener);

		// 3. Add the original listeners back BEHIND ours
		for (MouseListener l : existingListeners) {
			terminalPanel.addMouseListener(l);
		}
	}
	
	private void setupMouseShortcutsOld() {
		// 1. Define our Interceptor Listener
		MouseAdapter aiShortcutListener = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				handleMouseAction(e);
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				handleMouseAction(e);
			}

			private void handleMouseAction(MouseEvent e) {
				// DETECT TRIGGER:
				// 1. Standard Popup Trigger (Windows Right-Click, Mac Ctrl+Click)
				// 2. Explicit Mac check: Ctrl is down + Button 1
				boolean isTrigger = e.isPopupTrigger() || 
				                   (e.isControlDown() && SwingUtilities.isLeftMouseButton(e));

				if (isTrigger) {
					// CRITICAL: Consume immediately so the TerminalPanel (next listener)
					// does NOT see this event and does NOT clear the selection.
					e.consume();

					// Only fire on release (or press, depending on preference, but usually one fires)
					// We check consume to ensure we don't double-fire if both press/release match.
					// A simple way is to check the text presence again.
					
					String selectedText = terminalPanel.getSelectedText();
					if (selectedText != null && !selectedText.isEmpty()) {
						// We found text! Launch AI.
						showAIChatDialog(getParentFrame(), selectedText);
					} 
				}
			}
		};

		// 2. LISTENER PRIORITY HACK
		// We must ensure 'aiShortcutListener' runs BEFORE the TerminalPanel's internal listeners.
		// Standard Swing adds listeners to the END of the list. We need to be at the FRONT.
		
		// A. Remove all existing listeners (temporarily)
		MouseListener[] existingListeners = terminalPanel.getMouseListeners();
		for (MouseListener l : existingListeners) {
			terminalPanel.removeMouseListener(l);
		}

		// B. Add OUR listener first
		terminalPanel.addMouseListener(aiShortcutListener);

		// C. Add the original listeners back behind ours
		for (MouseListener l : existingListeners) {
			terminalPanel.addMouseListener(l);
		}
	}
	
    public void setHostType(HostType t) {
        this.hostType = t;
        // Optional: Update status bar to show TSO or CMS
        if (statusBar != null) statusBar.setStatus("Host System set to " + t);
    }

    public HostType getHostType() {
        return this.hostType;
    }

	public String getModelName() {
		return modelName;
	}

	public Frame getParentFrame() {
		if (parentFrame != null)
			return parentFrame;
		Window w = SwingUtilities.getWindowAncestor(this);
		return (w instanceof Frame) ? (Frame) w : null;
	}

	public void setUseTLS(boolean t) {
		useTLS = t;
	}

	public void setRequestedLuName(String l) {
		requestedLuName = l;
	}

	public boolean isConnected() {
		return connected;
	}

	public Map<Integer, KeyMapping> getKeyMap() {
		return keyMap;
	}

	public Map<Character, Character> getInputCharMap() {
		return inputCharMap;
	}

	@Override
	public boolean requestFocusInWindow() {
		if (terminalPanel != null)
			return terminalPanel.requestFocusInWindow();
		return super.requestFocusInWindow();
	}

	private void initializeKeyMappings() {
		keyMap.put(KeyEvent.VK_BACK_QUOTE, new KeyMapping('¬', "Not"));
		inputCharMap.put('¦', '|');
		loadKeyMappings();
	}

	@SuppressWarnings("unchecked")
	public void loadKeyMappings() {
		if (!new File(KEYMAP_FILE).exists())
			return;
		try (ObjectInputStream i = new ObjectInputStream(new FileInputStream(KEYMAP_FILE))) {
			Map<Integer, KeyMapping> m = (Map<Integer, KeyMapping>) i.readObject();
			keyMap.clear();
			keyMap.putAll(m);
		} catch (Exception e) {
		}
	}

	public void saveKeyMappings() {
		try (ObjectOutputStream o = new ObjectOutputStream(new FileOutputStream(KEYMAP_FILE))) {
			o.writeObject(new HashMap<>(keyMap));
		} catch (Exception e) {
		}
	}

	// =======================================================================
	// 9. DELEGATES & DIALOGS
	// =======================================================================

	public void applyColorScheme(String schemeName) {
		ColorScheme scheme = COLOR_SCHEMES.get(schemeName);
		if (scheme == null)
			return;

		screenModel.setScreenBackground(scheme.background);
		screenModel.setDefaultForeground(scheme.defaultFg);
		screenModel.setCursorColor(scheme.cursor);
		screenModel.setPalette(scheme.colors);

		terminalPanel.setBackground(scheme.background);
		terminalPanel.setForeground(scheme.defaultFg);

		// FIX: Ensure the ScrollPane viewport matches the background
		if (scrollPane != null && scrollPane.getViewport() != null) {
			scrollPane.getViewport().setBackground(scheme.background);
		}

		terminalPanel.repaint();
		if (statusBar != null)
			statusBar.setStatus("Color scheme: " + schemeName);
	}

	public void setShowCrosshair(boolean b) {
		if (terminalPanel != null)
			terminalPanel.setShowCrosshair(b);
	}

	private SSLSocketFactory createTrustAllSSLSocketFactory() {
		try {
			TrustManager[] t = new TrustManager[] { new X509TrustManager() {
				public void checkClientTrusted(java.security.cert.X509Certificate[] c, String s) {
				}

				public void checkServerTrusted(java.security.cert.X509Certificate[] c, String s) {
				}

				public java.security.cert.X509Certificate[] getAcceptedIssuers() {
					return null;
				}
			} };
			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, t, new java.security.SecureRandom());
			return sc.getSocketFactory();
		} catch (Exception e) {
			return (SSLSocketFactory) SSLSocketFactory.getDefault();
		}
	}

	public void connect(String h, int p) {
		this.currentHost = h;
		this.currentPort = p;
		new Thread(() -> {
			try {
				if (useTLS) {
					SocketFactory f = createTrustAllSSLSocketFactory();
					socket = f.createSocket(h, p);
					((SSLSocket) socket).setEnabledProtocols(new String[] { "TLSv1.2", "TLSv1.3" });
					socket.setSoTimeout(0);
					socket.setTcpNoDelay(true);
					((SSLSocket) socket).startHandshake();
					SwingUtilities.invokeLater(() -> {
						statusBar.setStatus("TLS: " + h);
						SwingUtilities.invokeLater(() -> terminalPanel.requestFocusInWindow());
					});
				} else {
					socket = new Socket();
					socket.connect(new InetSocketAddress(h, p), 5000);
					socket.setSoTimeout(0);
					socket.setTcpNoDelay(true);
					SwingUtilities.invokeLater(() -> {
						statusBar.setStatus("Conn: " + h);
						SwingUtilities.invokeLater(() -> terminalPanel.requestFocusInWindow());
					});
				}
				if (socket.isConnected()) {
					String ip = socket.getInetAddress().getHostAddress();
					SwingUtilities.invokeLater(() -> statusBar.setIP(ip));
				}
				input = socket.getInputStream();
				output = socket.getOutputStream();
				connected = true;
				tn3270eAttempted = false;
				tn3270eMode = false;
				screenModel.clearScreen();
				readerThread = new Thread(this::readLoop);
				readerThread.start();
			} catch (Exception e) {
				try {
					if (socket != null)
						socket.close();
				} catch (IOException x) {
				}
				connected = false;
				SwingUtilities.invokeLater(() -> {
					statusBar.setStatus("Failed");
					showMessageDialog("Connect Error: " + e.getMessage(), "Error", true);
				});
			}
		}).start();
	}

	public void disconnect() {
		connected = false;
		try {
			if (socket != null)
				socket.close();
		} catch (Exception e) {
		}
		statusBar.setStatus("Disconnected");
		statusBar.setIP("");
		terminalPanel.repaint();
	}

	public void reconnect() {
		if (currentHost != null && !currentHost.isEmpty()) {
			disconnect();
			try {
				Thread.sleep(500);
			} catch (Exception e) {
			}
			connect(currentHost, currentPort);
		}
	}

	private void readLoop() {
		byte[] buf = new byte[8192];
		ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
		ByteArrayOutputStream subnegBuffer = new ByteArrayOutputStream();
		int ts = STATE_DATA;
		try {
			while (connected) {
				int n = input.read(buf);
				if (n <= 0)
					break;
				for (int i = 0; i < n; i++) {
					// byte b = buf[i];
					int b = buf[i] & 0xFF; // Convert signed byte to unsigned int (0-255)
					switch (ts) {
					case STATE_DATA:
						if (b == IAC)
							ts = STATE_IAC;
						else
							dataStream.write(b);
						break;
					case STATE_IAC:
						if (b == IAC) {
							dataStream.write(0xFF);
							ts = STATE_DATA;
						} else if (b == SB) {
							ts = STATE_SB;
							subnegBuffer.reset();
						} else if (b == WILL)
							ts = STATE_WILL;
						else if (b == WONT)
							ts = STATE_WONT;
						else if (b == DO)
							ts = STATE_DO;
						else if (b == DONT)
							ts = STATE_DONT;
						else if (b == EOR) {
							if (dataStream.size() > 0) {
								process3270Data(dataStream.toByteArray());
								dataStream.reset();
							}
							ts = STATE_DATA;
						} else
							ts = STATE_DATA;
						break;
					case STATE_WILL:
						handleTelnet(WILL, b);
						ts = STATE_DATA;
						break;
					case STATE_WONT:
						handleTelnet(WONT, b);
						ts = STATE_DATA;
						break;
					case STATE_DO:
						handleTelnet(DO, b);
						ts = STATE_DATA;
						break;
					case STATE_DONT:
						handleTelnet(DONT, b);
						ts = STATE_DATA;
						break;
					case STATE_SB:
						if (b == SE) {
							byte[] sn = subnegBuffer.toByteArray();
							// if (sn.length > 0 && sn[sn.length - 1] == IAC) {
							if (sn.length > 0 && (sn[sn.length - 1] & 0xFF) == IAC) {
								handleSubneg(Arrays.copyOf(sn, sn.length - 1));
								ts = STATE_DATA;
							} else
								subnegBuffer.write(b);
						} else if (b == IAC)
							subnegBuffer.write(b);
						else
							subnegBuffer.write(b);
						break;
					}
				}
			}
		} catch (Exception e) {
			if (connected)
				statusBar.setStatus("Connection lost.");
		}
		disconnect();
	}

	private void handleTelnet(int cmd, int opt) throws IOException {
		if (cmd == DO && (opt == OPT_BINARY || opt == OPT_EOR))
			sendTelnet(WILL, opt);
		else if (cmd == WILL && (opt == OPT_BINARY || opt == OPT_EOR))
			sendTelnet(DO, opt);
		else if (cmd == DO && opt == OPT_TERMINAL_TYPE)
			sendTelnet(WILL, opt);
		else if (cmd == DO && opt == OPT_TN3270E) {
			if (!tn3270eAttempted) {
				tn3270eAttempted = true;
				sendTelnet(WILL, opt);
			} else
				sendTelnet(WONT, opt);
		} else if (cmd == WILL && opt == OPT_TN3270E) {
			if (!tn3270eAttempted) {
				tn3270eAttempted = true;
				sendTelnet(DO, opt);
			} else
				sendTelnet(DONT, opt);
		} else if (cmd == WONT && opt == OPT_TN3270E) {
			sendTelnet(DONT, opt);
			tn3270eMode = false;
		} else if (cmd == DONT && opt == OPT_TN3270E) {
			sendTelnet(WONT, opt);
			tn3270eMode = false;
		} else if (cmd == WILL)
			sendTelnet(DONT, opt);
		else if (cmd == DO)
			sendTelnet(WONT, opt);
		else if (cmd == WONT)
			sendTelnet(DONT, opt);
		else if (cmd == DONT)
			sendTelnet(WONT, opt);
	}

	private void handleSubneg(byte[] d) throws IOException {
		if (d.length < 2)
			return;
		if (d[0] == OPT_TERMINAL_TYPE && d[1] == 1) {
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			b.write(IAC);
			b.write(SB);
			b.write(OPT_TERMINAL_TYPE);
			b.write(0);
			String negModel = modelName;
			if ("3290".equals(modelName)) {
				negModel = "DYNAMIC";
			} else {
				negModel = modelName + "-E";
			}
			b.write(("IBM-" + negModel).getBytes());
			b.write(IAC);
			b.write(SE);
			output.write(b.toByteArray());
			output.flush();
		} else if (d[0] == OPT_TN3270E) {
			int op = d[1] & 0xFF;
			if (op == TN3270E_OP_SEND && d[2] == TN3270E_OP_DEVICE_TYPE) {
				ByteArrayOutputStream b = new ByteArrayOutputStream();
				b.write(IAC);
				b.write(SB);
				b.write(OPT_TN3270E);
				b.write(TN3270E_OP_DEVICE_TYPE);
				b.write(TN3270E_OP_REQUEST);
				String negModel = modelName;
				if ("3290".equals(modelName)) {
					negModel = "DYNAMIC";
				} else {
					negModel = modelName + "-E";
				}
				b.write(("IBM-" + negModel).getBytes());
				if (!requestedLuName.isEmpty()) {
					b.write(TN3270E_OP_CONNECT);
					b.write(requestedLuName.getBytes());
				}
				b.write(IAC);
				b.write(SE);
				output.write(b.toByteArray());
				output.flush();
			} else if (op == TN3270E_OP_DEVICE_TYPE && d[2] == TN3270E_OP_IS) {
				tn3270eMode = true;
				ByteArrayOutputStream b = new ByteArrayOutputStream();
				b.write(IAC);
				b.write(SB);
				b.write(OPT_TN3270E);
				b.write(TN3270E_OP_FUNCTIONS);
				b.write(TN3270E_OP_REQUEST);
				b.write(0x00);
				b.write(0x01);
				b.write(0x02);
				b.write(0x03);
				b.write(IAC);
				b.write(SE);
				output.write(b.toByteArray());
				output.flush();
			} else if (op == TN3270E_OP_FUNCTIONS) {
				if (d[2] == TN3270E_OP_IS) {
					sendQueryResponse();
				} else if (d[2] == TN3270E_OP_REQUEST) {
					ByteArrayOutputStream b = new ByteArrayOutputStream();
					b.write(IAC);
					b.write(SB);
					b.write(OPT_TN3270E);
					b.write(TN3270E_OP_FUNCTIONS);
					b.write(TN3270E_OP_IS);
					b.write(0x00);
					b.write(0x01);
					b.write(0x02);
					b.write(0x03);
					b.write(IAC);
					b.write(SE);
					output.write(b.toByteArray());
					output.flush();
				}
			}
		}
	}

	private void sendTelnet(int c, int o) throws IOException {
		output.write(new byte[] { (byte) IAC, (byte) c, (byte) o });
		output.flush();
	}

	/**
	 * Processes incoming 3270 data.
	 * 
	 * REGRESSION NOTE: TN3270E OPTIMISTIC NEGOTIATION We check 'data[0] ==
	 * TN3270E_DT_3270_DATA' before stripping the 5-byte header.
	 * 
	 * The Problem: Hosts often send the first screen of data (Standard 3270)
	 * immediately after sending the "DO TN3270E" negotiation request, without
	 * waiting for our "WILL TN3270E".
	 * 
	 * The Fix: Even if 'tn3270eMode' is true in our state machine, we must validate
	 * the header byte. If data[0] is NOT 0x00, it is likely an optimistic Standard
	 * 3270 packet slipping in during the transition. In that case, we treat offset
	 * as 0 (Standard) instead of 5 (Extended), preventing data corruption.
	 */
	private void process3270Data(byte[] data) {
		if (data.length < 1)
			return;
		int off = 0;
		// if (tn3270eMode && data.length >= 5 && data[0] == TN3270E_DT_3270_DATA)
		// off = 5;

		// FIX: Only strip TN3270E header if mode is active AND the header looks valid.
		// TN3270E Data Header always starts with DataType 0x00 (3270-DATA).
		// Standard 3270 Commands (Write, Erase/Write) start with 0xFx or 0x6F.
		if (tn3270eMode && data.length >= 5 && (data[0] & 0xFF) == TN3270E_DT_3270_DATA) {
			off = 5;
		} else if (tn3270eMode && data.length > 0 && data[0] != TN3270E_DT_3270_DATA) {
			// We negotiated TN3270E, but received a packet without the 0x00 header.
			// This is likely an optimistic packet sent by the host during negotiation
			// switch-over.
			// Treat it as standard 3270 data (offset 0).
			off = 0;
		} // Else print a datastream error message

		if (off >= data.length)
			return;

		// byte cmd = data[off++];
		int cmd = data[off++] & 0xFF;
		if (cmd == CMD_ERASE_WRITE_05 || cmd == CMD_ERASE_WRITE_F5 || cmd == CMD_ERASE_WRITE_ALTERNATE_7E
				|| cmd == CMD_ERASE_WRITE_ALTERNATE_0D)
			screenModel.clearScreen();
		if (cmd == CMD_WRITE_01 || cmd == CMD_WRITE_F1 || cmd == CMD_ERASE_WRITE_05 || cmd == CMD_ERASE_WRITE_F5
				|| cmd == CMD_ERASE_WRITE_ALTERNATE_0D || cmd == CMD_ERASE_WRITE_ALTERNATE_7E) {
			if (cmd == CMD_ERASE_WRITE_ALTERNATE_0D || cmd == CMD_ERASE_WRITE_ALTERNATE_7E) {
				if (!screenModel.isAlternateSize()) {
					screenModel.setUseAlternateSize(true);
					terminalPanel.updateSize();
					
					// FIX: Snap window on model change
                    SwingUtilities.invokeLater(this::snapWindow); 
				}
			} else if (cmd == CMD_ERASE_WRITE_05 || cmd == CMD_ERASE_WRITE_F5) {
				if (screenModel.isAlternateSize()) {
					screenModel.setUseAlternateSize(false);
					terminalPanel.updateSize();
					
					// FIX: Snap window on model change
                    SwingUtilities.invokeLater(this::snapWindow);
				}
			}
			if (off < data.length) {
				byte wcc = data[off++];
				if ((wcc & WCC_RESET) != 0) {
					keyboardLocked = false;
					replyModeFlags = 0;
					
					// FIX: Reset Insert Mode on Keyboard Unlock
					if (insertMode) {
						insertMode = false;
						// Visually reset cursor to Block (or user preference if we tracked it)
						terminalPanel.setCursorStyle(TerminalPanel.CursorStyle.BLOCK);
						if (statusBar != null) statusBar.setStatus("Overwrite Mode");
					}
				}
				if ((wcc & WCC_RESET_MDT) != 0)
					screenModel.resetMDT();
				if ((wcc & WCC_ALARM) != 0 && enableSound)
					Toolkit.getDefaultToolkit().beep();
				
				//processOrders(data, off);
				// FIX: Determine correct starting buffer address
				// CMD_WRITE (0x01/0xF1) starts at CURRENT cursor position.
				// CMD_ERASE_WRITE (0x05/etc) starts at 0 (screen was cleared).
				int initialPos = 0;
				if (cmd == CMD_WRITE_01 || cmd == CMD_WRITE_F1) {
					initialPos = screenModel.getCursorPos();
				}
				
				processOrders(data, off, initialPos);
				
				// --- FIX: Notify the uploader thread that screen state changed ---
				// We do this AFTER processing orders so isStatusNotAccepted() sees new text.
				synchronized (keyboardLockMonitor) {
					keyboardLockMonitor.notifyAll();
				}
			}
			keyboardLocked = false;
		} else if (cmd == CMD_READ_MODIFIED_F6 || cmd == CMD_READ_MODIFIED_06)
			sendAID(lastAID);
		else if (cmd == CMD_READ_BUFFER_02 || cmd == CMD_READ_BUFFER_F2)
			sendReadBuffer();
		else if (cmd == CMD_WSF_11 || cmd == CMD_WSF_F3)
			processWSF(data, off);
		else if (cmd == CMD_ERASE_ALL_UNPROTECTED_0F || cmd == CMD_ERASE_ALL_UNPROTECTED_6F)
			eraseAllUnprotected();
		terminalPanel.repaint();
		
		// Force layout re-check
		this.revalidate();
		
		updateStatusBar();
	}
	
	private void processOrders(byte[] data, int offset, int initialPos) {
		int p = initialPos; // FIX: Use passed initial position
		int i = offset;
		int[] idx = { 0 };
		char c;
		int bufLen = screenModel.getSize();
		
		// Reset state at start of Write/Erase Write
		screenModel.setCurrentColor((byte) 0);
		screenModel.setCurrentHighlight((byte) 0);
		screenModel.setCurrentCharset((byte) 0); // Default Charset: 0 = Standard, 1 = APL

		while (i < data.length) {
			int b = data[i++] & 0xFF;
			
			if (b == ORDER_SF) {
				if (i < data.length) {
					screenModel.setAttr(p, data[i++]);
					screenModel.setChar(p, ' ');
					screenModel.setExtendedColor(p, (byte) 0);
					screenModel.setHighlight(p, (byte) 0);
					screenModel.setCharset(p, (byte) 0); // Reset for field start
					
					// SF resets currents
					screenModel.setCurrentColor((byte) 0);
					screenModel.setCurrentHighlight((byte) 0);
					screenModel.setCurrentCharset((byte) 0);
					
					p = (p + 1) % bufLen;
				}
			} else if (b == ORDER_SFE) {
				if (i < data.length) {
					int count = data[i++] & 0xFF;
					byte a = 0, col = 0, hl = 0, cs = 0;
					
					// SFE resets currents logic? Usually yes for the field.
					screenModel.setCurrentColor((byte) 0);
					screenModel.setCurrentHighlight((byte) 0);
					screenModel.setCurrentCharset((byte) 0);

					for (int k = 0; k < count; k++) {
						if (i + 1 >= data.length) break;
						int t = data[i++] & 0xFF;
						int v = data[i++] & 0xFF;
						
						if (t == ATTR_FIELD || t == 0xC0) a = (byte) v;
						else if (t == ATTR_FOREGROUND) col = normalizeColor((byte) v);
						else if (t == ATTR_HIGHLIGHTING) hl = (byte) v;
						else if (t == ATTR_CHAR_SET) cs = (byte) v; // Capture Charset
					}
					
					screenModel.setChar(p, ' ');
					screenModel.setAttr(p, a);
					screenModel.setExtendedColor(p, col);
					screenModel.setHighlight(p, hl);
					screenModel.setCharset(p, cs);

					// Propagate SFE attributes to subsequent text
					if (col != 0) screenModel.setCurrentColor(col);
					if (hl != 0) screenModel.setCurrentHighlight(hl);
					if (cs != 0) screenModel.setCurrentCharset(cs);

					p = (p + 1) % bufLen;
				}
			} else if (b == ORDER_SBA) {
				if (i + 1 < data.length) {
					p = decode3270Address(data[i], data[i + 1]);
					i += 2;
				}
			} else if (b == ORDER_IC) {
				screenModel.setCursorPos(p);
			} else if (b == ORDER_PT) { // Program Tab (0x05)
                // Advance to next unprotected field, or insert nulls if in field?
                // Standard: Advance to first char of next unprotected field.
                // Simplified implementation:
                int start = p;
                while (true) {
                    p = (p + 1) % bufLen;
                    if (screenModel.isFieldStart(p)) continue;
                    if (!screenModel.isProtected(p)) break; // Found unprotected
                    if (p == start) break; // Loop safety
                }
			} else if (b == ORDER_RA) {
				if (i + 2 < data.length) {
					int end = decode3270Address(data[i], data[i + 1]);
					idx[0] = i + 2;
					// Note: RA might need to respect GE order if present?
					// Standard RA usually repeats a single byte.
					// If the byte is following GE (0x08), handle it.
					// But usually RA repeats the byte AS IS.
					// The attributes (Color/Charset) applied are the CURRENT ones.
					c = fetchDisplayChar(data, idx); // Checks for GE
					i = idx[0];
					
					while (p != end) {
						screenModel.setChar(p, c);
						screenModel.setAttr(p, (byte) 0);
						screenModel.setExtendedColor(p, screenModel.getCurrentColor());
						screenModel.setHighlight(p, screenModel.getCurrentHighlight());
						screenModel.setCharset(p, screenModel.getCurrentCharset());
						p = (p + 1) % bufLen;
					}
				}
			} else if (b == ORDER_EUA) {
				//
				// Regression alert: It this section of code is removed,
				// we will see instances where the PF Retrieve key returns
				// the command text preceded by 3 bytes of junk.
				// So, make sure this block of code does not get lost in the shuffle.
				//
				if (i + 1 < data.length) {
					int end = decode3270Address(data[i], data[i + 1]);
					i += 2;
					if (p == end) {
						for (int k = 0; k < bufLen; k++) {
							if (!screenModel.isProtected(k) && !screenModel.isFieldStart(k)) {
								screenModel.setChar(k, '\0');
								screenModel.setExtendedColor(k, (byte) 0);
								screenModel.setHighlight(k, (byte) 0);
								//screenModel.setCharset(p, (byte) 0);   // We probably need this
							}
						}
					} else {
						while (p != end) {
							if (!screenModel.isProtected(p) && !screenModel.isFieldStart(p)) {
								screenModel.setChar(p, '\0');
								screenModel.setExtendedColor(p, (byte) 0);
								screenModel.setHighlight(p, (byte) 0);
								//screenModel.setCharset(p, (byte) 0);     // We probably need this
							}
							p = (p + 1) % bufLen;
						}
					}
				}
			} else if (b == ORDER_SA) {
				if (i + 1 < data.length) {
					int t = data[i++] & 0xFF;
					byte v = data[i++];
					
					if (t == ATTR_FOREGROUND)
						screenModel.setCurrentColor(normalizeColor(v));
					else if (t == ATTR_HIGHLIGHTING)
						screenModel.setCurrentHighlight(v);
					else if (t == ATTR_CHAR_SET)
						screenModel.setCurrentCharset(v); // Handle SA Type 0x43
					else if (t == 0x00) {
						// Reset All
						screenModel.setCurrentColor((byte) 0);
						screenModel.setCurrentHighlight((byte) 0);
						screenModel.setCurrentCharset((byte) 0);
					}
				}
			} else if (b == ORDER_GE) {
				// Graphic Escape (0x08) - Single Character Override
				if (i < data.length) {
					byte val = data[i++];
					
                    // STRATEGY: Store the raw EBCDIC->ASCII mapping (Pipe |).
                    // We do NOT use the EBCDIC_TO_APL table here if it returns Unicode box chars.
                    // We want to store simple '|' (0x7C).
                    
                    if ((val & 0xFF) == 0x4F) {
                        c = '|'; 
                    } else {
                    	c = EBCDIC_TO_APL[val & 0xFF];
                    }
					
					screenModel.setChar(p, c);
					screenModel.setAttr(p, (byte) 0);
					screenModel.setExtendedColor(p, screenModel.getCurrentColor());
					screenModel.setHighlight(p, screenModel.getCurrentHighlight());
					screenModel.setCharset(p, CHARSET_APL);
					
					//
					// Sadly, CMS PIPELINES does not use GE for the vertical bar characters,
					// so it's difficult to force them to display "taller" than usual.  For
					// now, we just let them be.
					//
					
					p = (p + 1) % bufLen;
				}
			} else {
				// Standard Character
				byte currentCS = screenModel.getCurrentCharset();
				
                // Even if we are in APL mode (currentCS == APL), 
                // if the byte is 0x4F, store it as '|'.
                if ((b & 0xFF) == 0x4F) {
                    c = '|';
                } else {
                	if (currentCS == CHARSET_APL) {
						// Use APL Table
						c = EBCDIC_TO_APL[b & 0xFF];
					} else {
						// Use Standard Table
						c = EBCDIC_TO_ASCII[b & 0xFF];
						if (c == '\0') c = ' ';
					}
                }
				
				screenModel.setChar(p, c);
				screenModel.setAttr(p, (byte) 0);
				screenModel.setExtendedColor(p, screenModel.getCurrentColor());
				screenModel.setHighlight(p, screenModel.getCurrentHighlight());
				screenModel.setCharset(p, currentCS);
				
				p = (p + 1) % bufLen;
			}
		}
	}
	
/*
	private void processOrders(byte[] data, int offset) {
		int p = 0, i = offset;
		int[] idx = { 0 };
		char c;
		int bufLen = screenModel.getSize();
		while (i < data.length) {
			// byte b = data[i++];
			// FIX: Mask order code
			int b = data[i++] & 0xFF;
			if (b == ORDER_SF) {
				if (i < data.length) {
					screenModel.setAttr(p, data[i++]);
					screenModel.setChar(p, ' ');
					screenModel.setExtendedColor(p, (byte) 0);
					screenModel.setHighlight(p, (byte) 0);
					screenModel.setCurrentColor((byte) 0);
					screenModel.setCurrentHighlight((byte) 0);
					p = (p + 1) % bufLen;
				}
			} else if (b == ORDER_SFE) {
				if (i < data.length) {
					int count = data[i++] & 0xFF;
					byte a = 0, col = 0, hl = 0;
					screenModel.setCurrentColor((byte) 0);
					screenModel.setCurrentHighlight((byte) 0);
					for (int k = 0; k < count; k++) {
						if (i + 1 >= data.length)
							break;
						// byte t = data[i++], v = data[i++];
						// if (t == ATTR_FIELD || t == 0xC0)
						int t = data[i++] & 0xFF; // Convert to positive integer
						int v = data[i++] & 0xFF; // Good practice to mask value too
						if (t == ATTR_FIELD || t == 0xC0)
							a = (byte) v;
						else if (t == ATTR_FOREGROUND)
							col = normalizeColor((byte) v);
						else if (t == ATTR_HIGHLIGHTING)
							hl = (byte) v;
					}
					screenModel.setChar(p, ' ');
					screenModel.setAttr(p, a);
					screenModel.setExtendedColor(p, col);
					screenModel.setHighlight(p, hl);

					// FIX: Propagate these attributes to the text that follows!
					// Without this, the text "RUNNING" uses the default (0) attributes.
					if (col != 0)
						screenModel.setCurrentColor(col);
					if (hl != 0)
						screenModel.setCurrentHighlight(hl);

					p = (p + 1) % bufLen;
				}
			} else if (b == ORDER_SBA) {
				if (i + 1 < data.length) {
					p = decode3270Address(data[i], data[i + 1]);
					i += 2;
				}
			} else if (b == ORDER_IC) {
				screenModel.setCursorPos(p);
				if (screenModel.isFieldStart(p))
					screenModel.setCursorPos((p + 1) % bufLen);
			} else if (b == ORDER_RA) {
				if (i + 2 < data.length) {
					int end = decode3270Address(data[i], data[i + 1]);
					idx[0] = i + 2;
					c = fetchDisplayChar(data, idx);
					i = idx[0];
					while (p != end) {
						screenModel.setChar(p, c);
						screenModel.setAttr(p, (byte) 0);
						screenModel.setExtendedColor(p, screenModel.getCurrentColor());
						screenModel.setHighlight(p, screenModel.getCurrentHighlight());
						p = (p + 1) % bufLen;
					}
				}
			} else if (b == ORDER_EUA) {
				if (i + 1 < data.length) {
					int end = decode3270Address(data[i], data[i + 1]);
					i += 2;
					if (p == end) {
						for (int k = 0; k < bufLen; k++) {
							if (!screenModel.isProtected(k) && !screenModel.isFieldStart(k)) {
								screenModel.setChar(k, '\0');
								screenModel.setExtendedColor(k, (byte) 0);
								screenModel.setHighlight(k, (byte) 0);
							}
						}
					} else {
						while (p != end) {
							if (!screenModel.isProtected(p) && !screenModel.isFieldStart(p)) {
								screenModel.setChar(p, '\0');
								screenModel.setExtendedColor(p, (byte) 0);
								screenModel.setHighlight(p, (byte) 0);
							}
							p = (p + 1) % bufLen;
						}
					}
				}
			} else if (b == ORDER_GE) {
				idx[0] = i - 1;
				c = fetchDisplayChar(data, idx);
				i = idx[0];
				screenModel.setChar(p, c);
				screenModel.setAttr(p, (byte) 0);
				screenModel.setExtendedColor(p, screenModel.getCurrentColor());
				screenModel.setHighlight(p, screenModel.getCurrentHighlight());
				p = (p + 1) % bufLen;
			} else if (b == ORDER_PT) {
				for (int k = 0; k < screenModel.getSize(); k++) {
					if (screenModel.isFieldStart(p) && !screenModel.isProtected(p)) {
						p = (p + 1) % bufLen;
						break;
					}
					p = (p + 1) % bufLen;
				}
			} else if (b == ORDER_SA) {
				if (i + 1 < data.length) {
					// byte t = data[i++], v = data[i++];
					int t = data[i++] & 0xFF; // Fix SA Type
					byte v = data[i++];
					if (t == ATTR_FOREGROUND)
						screenModel.setCurrentColor(normalizeColor(v));
					else if (t == ATTR_HIGHLIGHTING)
						screenModel.setCurrentHighlight(v);
					else if (t == ATTR_CHAR_SET)
						screenModel.setCurrentCharset(v); // Handle SA Type 0x43
					else if (t == 0x00) {
						screenModel.setCurrentColor((byte) 0);
						screenModel.setCurrentHighlight((byte) 0);
					}
				}
			} else {
				c = EBCDIC_TO_ASCII[b & 0xFF];
				if (c == '\0')
					c = ' ';
				screenModel.setChar(p, c);
				screenModel.setAttr(p, (byte) 0);
				screenModel.setExtendedColor(p, screenModel.getCurrentColor());
				screenModel.setHighlight(p, screenModel.getCurrentHighlight());
				p = (p + 1) % bufLen;
			}
		}
	}
*/
	private byte normalizeColor(byte raw) {
		if (raw >= (byte) 0xF1 && raw <= (byte) 0xF7)
			return (byte) (raw - 0xF0);
		return raw;
	}

	private void sendReadBuffer() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write((byte) lastAID);

			int cPos = screenModel.getCursorPos();
			byte[] addr = encode3270Address(cPos);
			baos.write(addr[0]);
			baos.write(addr[1]);

			int size = screenModel.getSize();
			byte[] attrs = screenModel.getAttributes();
			char[] chars = screenModel.getBuffer();
			byte[] colors = screenModel.getExtendedColors();
			byte[] highlights = screenModel.getHighlight();
			byte[] charsets = screenModel.getCharsets(); // Get Charsets

			// Track running state for SA orders (Only used in Character Mode)
			byte runningColor = 0;
			byte runningHighlight = 0;
			byte runningCharset = 0; // Track Charset

			for (int i = 0; i < size; i++) {
				if (screenModel.isFieldStart(i)) {
					byte a = attrs[i];
					byte c = colors[i];
					byte h = highlights[i];
					byte cs = charsets[i]; // Field Charset

					// Field Start always resets running character attributes
					runningColor = 0;
					runningHighlight = 0;

					if (currentReplyMode == ReplyMode.CHARACTER && (c != 0 || h != 0 || cs != 0)) {
						// --- EXTENDED MODE: Use SFE (0x29) ---
						baos.write(ORDER_SFE);

						// Calculate count: Basic(1) + Color?(1) + Highlight?(1)
						int count = 1;
						if (c != 0) count++;
						if (h != 0) count++;
						if (cs != 0) count++; // Add Charset count
						baos.write(count);

						// 1. Basic Attribute (Type 0xC0)
						baos.write(0xC0);
						baos.write(a);

						// 2. Extended Color (Type 0x42)
						if (c != 0) {
							baos.write(ATTR_FOREGROUND);
							baos.write(c);
						}

						// 3. Extended Highlight (Type 0x41)
						if (h != 0) {
							baos.write(ATTR_HIGHLIGHTING);
							baos.write(h);
						}
						
                        // 4. Character Set (Type 0x43)
                        if (cs != 0) {
                            baos.write(ATTR_CHAR_SET);
                            baos.write(cs);
                        }
					} else {
						// --- STANDARD MODE: Use SF (0x1D) ---
						baos.write(ORDER_SF);
						baos.write(a);
					}
				} else {
					// --- DATA CONTENT ---
					if (currentReplyMode == ReplyMode.CHARACTER) {
						byte c = colors[i];
						byte h = highlights[i];
						byte cs = charsets[i];

						// Inject SA (Set Attribute 0x28) if color changes from running state
						if (c != runningColor) {
							baos.write(ORDER_SA);
							baos.write(ATTR_FOREGROUND);
							baos.write(c);
							runningColor = c;
						}

						// Inject SA if highlight changes
						if (h != runningHighlight) {
							baos.write(ORDER_SA);
							baos.write(ATTR_HIGHLIGHTING);
							baos.write(h);
							runningHighlight = h;
						}
						
                        // FIX: Preserve Character Set changes (e.g. APL)
                        if (cs != runningCharset) {
                            baos.write(ORDER_SA);
                            baos.write(ATTR_CHAR_SET); // 0x43
                            baos.write(cs);
                            runningCharset = cs;
                        }
					}

					// Write the character
					char ch = chars[i];
					byte ebcdicByte = 0;
					byte cs = charsets[i];
					/*
					if (ch == '\0')
						baos.write(0x00);
					else if (ch < 256 && ASCII_TO_EBCDIC[ch] != 0)
						baos.write(ASCII_TO_EBCDIC[ch]);
					else
						baos.write(0x40);
					*/
                    if (cs == CHARSET_APL) {
                        // Reverse lookup in EBCDIC_TO_APL table
                        // (Optimization: You could build a reverse map static array for speed)
                    	// (But, why waste 64k on a mostly empty table?)
                        for (int k=0; k<256; k++) {
                            if (EBCDIC_TO_APL[k] == ch) {
                                ebcdicByte = (byte) k;
                                break;
                            }
                        }
                        //ebcdicByte = EBCDIC.APL_TO_EBCDIC[ch];
                        if (ebcdicByte == 0) ebcdicByte = (byte) 0x40; // Fallback
                    } else {
                        // Standard ASCII/Unicode -> EBCDIC
                        if (ch < 256) ebcdicByte = ASCII_TO_EBCDIC[ch];
                        else ebcdicByte = (byte) 0x40; // Unknown
                    }
                    
                    if (ebcdicByte != 0) baos.write(ebcdicByte);
                    else baos.write(0x00); // Nulls are 0x00
				}
			}

			sendData(baos.toByteArray());
			keyboardLocked = true;
			updateStatusBar();

		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void sendAID(int aid) {
		lastAID = aid;
		int cPos = screenModel.getCursorPos();
		keyboardLocked = true;
		updateStatusBar();
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write((byte) aid);
			baos.write(encode3270Address(cPos)[0]);
			baos.write(encode3270Address(cPos)[1]);

			if (aid == AID_CLEAR)
				resetReplyModeToDefault();

			boolean isReadMod = (aid == AID_ENTER || (aid >= AID_PF1 && aid <= AID_PF24) || aid == AID_PA1
					|| aid == AID_PA2 || aid == AID_PA3 || aid == AID_CLEAR);

			if (isReadMod) {
				if (aid == AID_ENTER || (aid >= AID_PF1 && aid <= AID_PF24)) {
					int screenSize = screenModel.getSize();
					
					// --- FIX: Detect Formatted vs Unformatted Screen ---
					boolean isFormatted = false;
					for (int i = 0; i < screenSize; i++) {
						if (screenModel.isFieldStart(i)) {
							isFormatted = true;
							break;
						}
					}

					if (isFormatted) {
						// --- EXISTING LOGIC: Formatted Screen (Fields) ---
						boolean extended = (currentReplyMode == ReplyMode.EXTENDED_FIELD);
						for (int i = 0; i < screenSize; i++) {
							// Find modified fields
							if (screenModel.isFieldStart(i) && (screenModel.getAttr(i) & 0x01) != 0) {
								int fieldStart = i;
								int end = screenModel.findNextField(i);

								// Find data bounds
								int dataStart = fieldStart + 1;
								while (dataStart < end && screenModel.getChar(dataStart) == '\0')
									dataStart++;
								int dataEnd = end - 1;
								while (dataEnd > fieldStart
										&& (screenModel.getChar(dataEnd) == '\0' || screenModel.getChar(dataEnd) == ' '))
									dataEnd--;

								if (dataStart <= dataEnd) {
									baos.write(ORDER_SBA);
									byte[] addr = encode3270Address(dataStart);
									baos.write(addr[0]);
									baos.write(addr[1]);

									for (int j = dataStart; j <= dataEnd; j++) {
										if (!screenModel.isFieldStart(j)) {
											char c = screenModel.getChar(j);
											if (c != '\0') {
												if (c < 256 && ASCII_TO_EBCDIC[c] != 0)
													baos.write(ASCII_TO_EBCDIC[c]);
												else
													baos.write(0x40);
											}
										}
									}
								}
							}
						}
					} else {
						// --- NEW LOGIC: Unformatted Screen (Raw Buffer) ---
						// If the screen was cleared (e.g. by AID_CLEAR), there are no fields.
						// We must send the raw buffer contents (excluding nulls) to satisfy hosts 
						// like z/VM that expect data packets for console input.
						for (int i = 0; i < screenSize; i++) {
							char c = screenModel.getChar(i);
							if (c != '\0') {
								if (c < 256 && ASCII_TO_EBCDIC[c] != 0)
									baos.write(ASCII_TO_EBCDIC[c]);
								else
									baos.write(0x40);
							}
						}
					}
				}
			}
			sendData(baos.toByteArray());
		} catch (IOException e) {
		}
		terminalPanel.repaint();
	}

	private void eraseAllUnprotected() {
		int size = screenModel.getSize();
		for (int i = 0; i < size; i++) {
			if (screenModel.isFieldStart(i))
				screenModel.setAttr(i, (byte) (screenModel.getAttr(i) & ~0x01));
			else if (!screenModel.isProtected(i)) {
				screenModel.setChar(i, '\0');
				screenModel.setExtendedColor(i, (byte) 0);
				screenModel.setHighlight(i, (byte) 0);
			}
		}
		keyboardLocked = false;
		terminalPanel.repaint();
		updateStatusBar();
	}

	private void processWSF(byte[] data, int offset) {
		int i = offset;
		while (i + 2 < data.length) {
			int length = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
			if (length < 3 || i + length > data.length)
				break;
			// byte sfid = data[i + 2];
			int sfid = data[i + 2] & 0xFF;
			if (sfid == 0x01) {
				if (i + 4 < data.length)
					sendQueryResponse();
			} else if (sfid == SFID_DATA_CHAIN)
				handleDataChain(data, i, length);
			else if (sfid == SF_ID_SET_REPLY_MODE)
				handleSetReplyModeSF(data, i, length);
			i += length;
		}
	}

	private void handleSetReplyModeSF(byte[] sfBuf, int offset, int len) {
		// Structure: Length(2) + SFID(1) + Partition(1) + Mode(1) + Attrs(...)
		// offset points to Length MSB based on processWSF call logic.
		if (len < 5 || offset + 4 >= sfBuf.length)
			return;

		byte mode = sfBuf[offset + 4];

		switch (mode) {
		case 0x00:
			currentReplyMode = ReplyMode.FIELD;
			break;
		case 0x01:
			currentReplyMode = ReplyMode.EXTENDED_FIELD;
			break;
		case 0x02:
			currentReplyMode = ReplyMode.CHARACTER;
			break;
		default:
			currentReplyMode = ReplyMode.FIELD;
			break;
		}
	}

	private void resetReplyModeToDefault() {
		currentReplyMode = ReplyMode.FIELD;
		replyModeFlags = 0;
	}

	private void sendQueryResponse() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			int r = screenModel.getAlternateRows();
			int c = screenModel.getAlternateCols();
			baos.write(new byte[] { 0x00, 0x18, (byte) 0x81, (byte) 0x80, (byte) 0x81, (byte) 0x84, (byte) 0x85,
					(byte) 0x86, (byte) 0x87, (byte) 0x88, (byte) 0x8C, (byte) 0x8F, (byte) 0x95, (byte) 0x99,
					(byte) 0x9D, (byte) 0xA6, (byte) 0xA8, (byte) 0xAB, (byte) 0xB0, (byte) 0xB1, (byte) 0xB2,
					(byte) 0xB3, (byte) 0xB4, (byte) 0xB6, 0x00, 0x17, (byte) 0x81, (byte) 0x81, 0x01, 0x00,
					(byte) ((c >> 8) & 0xFF), (byte) (c & 0xFF), (byte) ((r >> 8) & 0xFF), (byte) (r & 0xFF), 0x00,
					0x00, 0x02, 0x00, (byte) 0x89, 0x00, 0x02, 0x00, (byte) 0x85, 0x09, 0x10, 0x0A, 0x00, 0x00, 0x08,
					(byte) 0x81, (byte) 0x84, 0x01, (byte) 0xE0, 0x00, 0x04, 0x00, 0x1B, (byte) 0x81, (byte) 0x85,
					(byte) 0x82, 0x00, 0x09, 0x0C, 0x00, 0x00, 0x00, 0x00, 0x07, 0x00, 0x10, 0x00, 0x02, (byte) 0xB9,
					0x00, 0x25, 0x01, 0x00, (byte) 0xF1, 0x03, (byte) 0xC3, 0x01, 0x36, 0x00, 0x16, (byte) 0x81,
					(byte) 0x86, 0x00, 0x08, 0x00, (byte) 0xF4, (byte) 0xF1, (byte) 0xF1, (byte) 0xF2, (byte) 0xF2,
					(byte) 0xF3, (byte) 0xF3, (byte) 0xF4, (byte) 0xF4, (byte) 0xF5, (byte) 0xF5, (byte) 0xF6,
					(byte) 0xF6, (byte) 0xF7, (byte) 0xF7, 0x00, 0x0D, (byte) 0x81, (byte) 0x87, 0x04, 0x00,
					(byte) 0xF0, (byte) 0xF1, (byte) 0xF1, (byte) 0xF2, (byte) 0xF2, (byte) 0xF4, (byte) 0xF4, 0x00,
					0x07, (byte) 0x81, (byte) 0x88, 0x00, 0x01, 0x02, 0x00, 0x07, (byte) 0x81, (byte) 0x8C, 0x00, 0x00,
					0x00, 0x00, 0x0C, (byte) 0x81, (byte) 0x95, 0x00, 0x00, 0x09, (byte) 0xC4, 0x09, (byte) 0xC4, 0x01,
					0x01, 0x00, 0x06, (byte) 0x81, (byte) 0x99, 0x00, 0x00, 0x00, 0x11, (byte) 0x81, (byte) 0xA6, 0x00,
					0x00, 0x0B, 0x01, 0x00, (byte) ((c >> 8) & 0xFF), (byte) (c & 0xFF), 0x00, 0x18,
					(byte) ((c >> 8) & 0xFF), (byte) (c & 0xFF), (byte) ((r >> 8) & 0xFF), (byte) (r & 0xFF), 0x00,
					0x1A, (byte) 0x81, (byte) 0x8F, 0x00, 0x00, (byte) 0xA3, (byte) 0x95, (byte) 0xF3, (byte) 0xF2,
					(byte) 0xF7, (byte) 0xF0, 0x40, 0x40, (byte) 0xC3, (byte) 0x93, (byte) 0x81, (byte) 0xA4,
					(byte) 0x84, (byte) 0x85, (byte) 0xC1, (byte) 0xC9, 0x04, 0x01, 0x00, (byte) 0xAE, 0x00, 0x19,
					(byte) 0x81, (byte) 0x9D, 0x00, 0x01, 0x0E, 0x00, 0x0E, 0x00, 0x0F, 0x00, (byte) 0xAE, (byte) 0xC3,
					(byte) 0x93, (byte) 0x81, (byte) 0xA4, (byte) 0x84, (byte) 0x85, 0x61, (byte) 0xC1, (byte) 0xC9,
					0x40, 0x40, 0x40, 0x00, 0x09, (byte) 0x81, (byte) 0xA8, 0x02, 0x00, (byte) 0xF0, (byte) 0xFF,
					(byte) 0xFF, 0x00, 0x11, (byte) 0x81, (byte) 0xAB, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00,
					0x00, 0x04, 0x01, 0x00, 0x01 });
			sendStructuredFieldResponse(baos.toByteArray());
		} catch (IOException e) {
		}
	}

	private void sendStructuredFieldResponse(byte[] sfData) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(AID_STRUCTURED_FIELD);
		baos.write(sfData);
		sendData(baos.toByteArray());
	}

	private void sendData(byte[] data) throws IOException {
		ByteArrayOutputStream fullPacket = new ByteArrayOutputStream();
		if (tn3270eMode) {
			fullPacket.write(TN3270E_DT_3270_DATA);
			fullPacket.write(0);
			fullPacket.write(0);
			fullPacket.write(0);
			fullPacket.write(0);
		}
		for (byte b : data) {
			fullPacket.write(b);
			if (b == (byte) 0xFF)
				fullPacket.write((byte) 0xFF);
		}
		fullPacket.write(IAC);
		fullPacket.write(EOR);
		output.write(fullPacket.toByteArray());
		output.flush();
	}

	private int decode3270Address(byte b1, byte b2) {
		int i = ((b1 & 0xFF) << 8) | (b2 & 0xFF);
		if ((b1 & 0xC0) != 0)
			i = ((b1 & 0x3F) << 6) | (b2 & 0x3F);
		return i % screenModel.getSize();
	}

	private byte[] encode3270Address(int a) {
		a &= 0x3FFF;
		if (a >= 0x1000)
			return new byte[] { (byte) (a >> 8), (byte) a };
		return new byte[] { ADDRESS_TABLE[(a >> 6) & 0x3F], ADDRESS_TABLE[a & 0x3F] };
	}

	private char fetchDisplayChar(byte[] data, int[] idxRef) {
		int i = idxRef[0];
		if (!safeConsume(data, i, 1))
			return ' ';
		byte b = data[i];
		if (b == ORDER_GE) {
			if (!safeConsume(data, i + 1, 1)) {
				idxRef[0] = i + 1;
				return ' ';
			}
			byte op = data[i + 1];
			idxRef[0] = i + 2;
			return EBCDIC_TO_APL[op & 0xFF];
		} else {
			idxRef[0] = i + 1;
			return EBCDIC_TO_ASCII[b & 0xFF];
		}
	}

	private boolean safeConsume(byte[] d, int i, int n) {
		return i + n <= d.length;
	}

	public void showFileTransferDialog(boolean isDownload) {
		if (!connected) {
			JOptionPane.showMessageDialog(getParentFrame(), "Not connected to host.", "Connection Required",
					JOptionPane.WARNING_MESSAGE);
			return;
		}
		JDialog dialog = new JDialog(getParentFrame(), isDownload ? "Download from Host" : "Upload to Host", true);
		dialog.setLayout(new BorderLayout());
		
		JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton transferBtn = new JButton(isDownload ? "Download" : "Upload");
		JButton cancelBtn = new JButton("Cancel");
		btnPanel.add(cancelBtn);
		btnPanel.add(transferBtn);

		JPanel mainPanel = new JPanel(new GridBagLayout());
		mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;

		gbc.gridx = 0; gbc.gridy = 0;
		mainPanel.add(new JLabel("Host System:"), gbc);
		gbc.gridx = 1; gbc.gridwidth = 2;
		JComboBox<String> hostTypeBox = new JComboBox<>(new String[] { "TSO (z/OS)", "CMS (z/VM)" });
		hostTypeBox.setSelectedIndex(hostType == HostType.TSO ? 0 : 1);
		mainPanel.add(hostTypeBox, gbc);

		gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3;
		JCheckBox useClipboardCheck = new JCheckBox("Transfer to/from Clipboard (Text Only)");
		mainPanel.add(useClipboardCheck, gbc);

		gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
		JLabel fileLabel = new JLabel("Local File:");
		mainPanel.add(fileLabel, gbc);
		gbc.gridx = 1;
		JTextField localFileField = new JTextField(30);
		mainPanel.add(localFileField, gbc);
		gbc.gridx = 2;
		JButton browseBtn = new JButton("Browse...");
		mainPanel.add(browseBtn, gbc);

		gbc.gridx = 0; gbc.gridy = 3;
		JLabel datasetLabel = new JLabel("Host Dataset:");
		mainPanel.add(datasetLabel, gbc);
		gbc.gridx = 1; gbc.gridwidth = 2;
		JTextField hostDatasetField = new JTextField(30);
		hostDatasetField.setText(hostType == HostType.TSO ? "USER.TEST.DATA" : "TEST DATA A");
		mainPanel.add(hostDatasetField, gbc);

		gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 1;
		mainPanel.add(new JLabel("Transfer Mode:"), gbc);
		gbc.gridx = 1; gbc.gridwidth = 2;
		JComboBox<String> modeBox = new JComboBox<>(new String[] { "ASCII (Text)", "BINARY" });
		mainPanel.add(modeBox, gbc);

		gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 3;
		JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		JCheckBox crlfCheck = new JCheckBox("CRLF (Text Mode)", true);
		JCheckBox appendCheck = new JCheckBox("Append", false);
		optionsPanel.add(crlfCheck);
		optionsPanel.add(Box.createHorizontalStrut(15));
		optionsPanel.add(appendCheck);
		mainPanel.add(optionsPanel, gbc);

		gbc.gridy = 6;
		JPanel allocPanel = new JPanel(new GridBagLayout());
		allocPanel.setBorder(BorderFactory.createTitledBorder("Host Allocation / Format Parameters"));
		GridBagConstraints agbc = new GridBagConstraints();
		agbc.insets = new Insets(2, 5, 2, 5);
		agbc.fill = GridBagConstraints.HORIZONTAL;
		
		agbc.gridx = 0; agbc.gridy = 0;
		allocPanel.add(new JLabel("RECFM:"), agbc);
		agbc.gridx = 1;
		JComboBox<String> recfmBox = new JComboBox<>(new String[] { "V", "F", "U", "" });
		allocPanel.add(recfmBox, agbc);
		agbc.gridx = 2;
		allocPanel.add(new JLabel("LRECL:"), agbc);
		agbc.gridx = 3;
		JTextField lreclField = new JTextField("", 5);
		allocPanel.add(lreclField, agbc);
		
		agbc.gridx = 0; agbc.gridy = 1;
		allocPanel.add(new JLabel("BLKSIZE:"), agbc);
		agbc.gridx = 1;
		JTextField blksizeField = new JTextField("", 6);
		allocPanel.add(blksizeField, agbc);
		agbc.gridx = 2;
		JLabel spaceLabel = new JLabel("SPACE:");
		allocPanel.add(spaceLabel, agbc);
		agbc.gridx = 3;
		JTextField spaceField = new JTextField("", 8);
		allocPanel.add(spaceField, agbc);
		
		mainPanel.add(allocPanel, gbc);

		// LISTENERS
		useClipboardCheck.addActionListener(e -> {
			boolean useClip = useClipboardCheck.isSelected();
			localFileField.setEnabled(!useClip);
			browseBtn.setEnabled(!useClip);
			fileLabel.setText(useClip ? "Source/Dest:" : "Local File:");
			localFileField.setText(useClip ? "(System Clipboard)" : "");
			
			modeBox.setEnabled(!useClip);
			if (useClip) {
				modeBox.setSelectedIndex(0); 
				crlfCheck.setSelected(true); 
				crlfCheck.setEnabled(false);
			} else {
				crlfCheck.setEnabled(modeBox.getSelectedIndex() == 0);
			}
			recfmBox.setEnabled(!useClip);
			lreclField.setEnabled(!useClip);
			blksizeField.setEnabled(!useClip);
			spaceField.setEnabled(!useClip);
		});

		hostTypeBox.addActionListener(e -> {
			boolean tso = (hostTypeBox.getSelectedIndex() == 0);
			datasetLabel.setText(tso ? "Host Dataset:" : "Host File:");
			spaceLabel.setVisible(tso);
			spaceField.setVisible(tso);
			
			if (!useClipboardCheck.isSelected() && !localFileField.getText().isEmpty()) {
				String path = localFileField.getText();
				String name = new File(path).getName().toUpperCase();
				if (tso) hostDatasetField.setText(name);
				else hostDatasetField.setText(name.replace('.', ' ') + " A");
			}
		});

		browseBtn.addActionListener(e -> {
			JFileChooser fc = new JFileChooser();
			if (isDownload ? fc.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION
					: fc.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
				File f = fc.getSelectedFile();
				localFileField.setText(f.getAbsolutePath());
				
				String name = f.getName().toUpperCase();
				boolean isTSO = (hostTypeBox.getSelectedIndex() == 0);
				if (isTSO) hostDatasetField.setText(name);
				else {
					String cmsName = name.replace('.', ' ');
					if (!cmsName.endsWith(" A")) cmsName += " A";
					hostDatasetField.setText(cmsName);
				}
			}
		});

		modeBox.addActionListener(e -> {
			boolean isAscii = (modeBox.getSelectedIndex() == 0);
			crlfCheck.setEnabled(isAscii);
			crlfCheck.setSelected(isAscii);
		});

		// --- TRANSFER ACTION ---
		transferBtn.addActionListener(e -> {
			boolean ascii = (modeBox.getSelectedIndex() == 0);
			boolean isClipboard = useClipboardCheck.isSelected();
			
			HostType selectedHostType = hostTypeBox.getSelectedIndex() == 0 ? HostType.TSO : HostType.CMS;
			String dataset = hostDatasetField.getText().trim();

			if (dataset.isEmpty()) {
				JOptionPane.showMessageDialog(dialog, "Please specify a Host Dataset/File.", "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}
			
			// --- AUTO-DETECT LINUX ---
			// Priority: 1. Inferred type (if Linux) 2. Dropdown Selection
			HostType inferredType = inferHostType(dataset);
			HostType actualHostType = (inferredType == HostType.LINUX) ? HostType.LINUX : selectedHostType;

			// ============================
			// 1. CLIPBOARD TRANSFER
			// ============================
			if (isClipboard) {
				dialog.dispose(); 

				if (isDownload) {
					// DOWNLOAD HOST -> CLIPBOARD
					// Routes to Linux or Mainframe logic based on actualHostType
					downloadTextFromHost(dataset, actualHostType, new MemoryTransferCallback() {
						// UPDATED: Now receives byte[] to support binary safety
						public void onDownloadComplete(byte[] content) {
							// Safety check for binary garbage on clipboard
							if (AIManager.isLikelyBinary(content)) {
								int confirm = JOptionPane.showConfirmDialog(getParentFrame(),
									"The file '" + dataset + "' appears to be binary.\n" +
									"Placing this data on the Clipboard may result in garbage text.\n\n" +
									"Proceed anyway?",
									"Binary Detection Warning",
									JOptionPane.YES_NO_OPTION,
									JOptionPane.WARNING_MESSAGE);
								if (confirm != JOptionPane.YES_OPTION) return;
							}
							try {
								// Convert to String for Clipboard (UTF-8 assumption safe for text)
								String text = new String(content, StandardCharsets.UTF_8);
								Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
								JOptionPane.showMessageDialog(getParentFrame(), 
										"Downloaded " + content.length + " bytes to Clipboard.\nFrom: " + dataset);
							} catch (Exception ex) {
								onError("Clipboard access failed: " + ex.getMessage());
							}
						}
						public void onUploadComplete() {}
						public void onError(String msg) {
							JOptionPane.showMessageDialog(getParentFrame(), "Download Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
						}
					});
				} else {
					// UPLOAD CLIPBOARD -> HOST
					try {
						String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
						if (text == null) text = "";
						
						uploadTextToHost(text, dataset, actualHostType, new MemoryTransferCallback() {
							public void onUploadComplete() {
								JOptionPane.showMessageDialog(getParentFrame(), "Successfully uploaded Clipboard content to " + dataset);
							}
							public void onDownloadComplete(byte[] c) {}
							public void onError(String msg) {
								JOptionPane.showMessageDialog(getParentFrame(), "Upload Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
							}
						});
					} catch (Exception ex) {
						JOptionPane.showMessageDialog(dialog, "Could not read text from Clipboard.", "Error", JOptionPane.ERROR_MESSAGE);
					}
				}
				return;
			}

			// ============================
			// 2. FILE TRANSFER
			// ============================
			String localPath = localFileField.getText().trim();
			if (localPath.isEmpty()) {
				JOptionPane.showMessageDialog(dialog, "Please specify a Local File.", "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}
			File localFile = new File(localPath);

			if (actualHostType == HostType.LINUX) {
				// --- LINUX FILE HANDLING (New Branch) ---
				dialog.dispose();
				if (isDownload) {
					downloadTextFromLinuxConsole(dataset, new MemoryTransferCallback() {
						public void onDownloadComplete(byte[] content) {
							try {
								// Write RAW BYTES (Preserves binary fidelity)
								java.nio.file.Files.write(localFile.toPath(), content);
								JOptionPane.showMessageDialog(getParentFrame(), "Downloaded to " + localFile.getName());
							} catch (IOException ex) { onError("File write error: " + ex.getMessage()); }
						}
						public void onUploadComplete() {}
						public void onError(String msg) {
							JOptionPane.showMessageDialog(getParentFrame(), "Download Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
						}
					});
				} else {
					// Upload
					if (!localFile.exists()) {
						JOptionPane.showMessageDialog(getParentFrame(), "Local file not found.", "Error", JOptionPane.ERROR_MESSAGE);
						return;
					}
					try {
						byte[] data = java.nio.file.Files.readAllBytes(localFile.toPath());
						uploadTextToLinuxConsole(data, dataset, new MemoryTransferCallback() {
							public void onUploadComplete() {
								JOptionPane.showMessageDialog(getParentFrame(), "Upload Complete.");
							}
							public void onDownloadComplete(byte[] c) {}
							public void onError(String msg) {
								JOptionPane.showMessageDialog(getParentFrame(), "Upload Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
							}
						});
					} catch (IOException ex) {
						JOptionPane.showMessageDialog(getParentFrame(), "Read error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
					}
				}
				return;
			}

			// --- MAINFRAME IND$FILE HANDLING (Existing) ---
			String lrecl = lreclField.getText().trim();
			if (!isDownload && lrecl.isEmpty() && ascii)
				lrecl = "255";
			
			this.hostType = actualHostType; // Use the verified host type
			
			String cmd = buildIndFileCommand(isDownload, hostType == HostType.TSO, dataset,
					ascii, crlfCheck.isSelected(), appendCheck.isSelected(), (String) recfmBox.getSelectedItem(), lrecl,
					blksizeField.getText().trim(), spaceField.getText().trim());
			
			dialog.dispose();
			initiateFileTransfer(localPath, cmd, isDownload);
		});

		cancelBtn.addActionListener(e -> dialog.dispose());
		
		dialog.add(mainPanel, BorderLayout.CENTER);
		dialog.add(btnPanel, BorderLayout.SOUTH);

		boolean startTso = (hostTypeBox.getSelectedIndex() == 0);
		spaceLabel.setVisible(startTso);
		spaceField.setVisible(startTso);
		
		dialog.pack();
		dialog.setLocationRelativeTo(getParentFrame());
		dialog.setVisible(true);
	}
	
	/*
	public void showFileTransferDialog(boolean isDownload) {
		if (!connected) {
			JOptionPane.showMessageDialog(getParentFrame(), "Not connected to host.", "Connection Required",
					JOptionPane.WARNING_MESSAGE);
			return;
		}
		JDialog dialog = new JDialog(getParentFrame(), isDownload ? "Download from Host" : "Upload to Host", true);
		dialog.setLayout(new BorderLayout());
		
		// 1. Define Logic Buttons EARLY so they are in scope for listeners
		JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton transferBtn = new JButton(isDownload ? "Download" : "Upload");
		JButton cancelBtn = new JButton("Cancel");
		btnPanel.add(cancelBtn);
		btnPanel.add(transferBtn);

		// 2. Layout Main Panel
		JPanel mainPanel = new JPanel(new GridBagLayout());
		mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;

		// --- Row 0: HOST SYSTEM ---
		gbc.gridx = 0; gbc.gridy = 0;
		mainPanel.add(new JLabel("Host System:"), gbc);
		gbc.gridx = 1; gbc.gridwidth = 2;
		JComboBox<String> hostTypeBox = new JComboBox<>(new String[] { "TSO (z/OS)", "CMS (z/VM)" });
		hostTypeBox.setSelectedIndex(hostType == HostType.TSO ? 0 : 1);
		mainPanel.add(hostTypeBox, gbc);

		// --- Row 1: CLIPBOARD TOGGLE ---
		gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3;
		JCheckBox useClipboardCheck = new JCheckBox("Transfer to/from Clipboard (Text Only)");
		mainPanel.add(useClipboardCheck, gbc);

		// --- Row 2: FILE SELECTION ---
		gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
		JLabel fileLabel = new JLabel("Local File:");
		mainPanel.add(fileLabel, gbc);
		gbc.gridx = 1;
		JTextField localFileField = new JTextField(30);
		mainPanel.add(localFileField, gbc);
		gbc.gridx = 2;
		JButton browseBtn = new JButton("Browse...");
		mainPanel.add(browseBtn, gbc);

		// --- Row 3: DATASET ---
		gbc.gridx = 0; gbc.gridy = 3;
		JLabel datasetLabel = new JLabel("Host Dataset:");
		mainPanel.add(datasetLabel, gbc);
		gbc.gridx = 1; gbc.gridwidth = 2;
		JTextField hostDatasetField = new JTextField(30);
		hostDatasetField.setText(hostType == HostType.TSO ? "USER.TEST.DATA" : "TEST DATA A");
		mainPanel.add(hostDatasetField, gbc);

		// --- Row 4: TRANSFER MODE ---
		gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 1;
		mainPanel.add(new JLabel("Transfer Mode:"), gbc);
		gbc.gridx = 1; gbc.gridwidth = 2;
		JComboBox<String> modeBox = new JComboBox<>(new String[] { "ASCII (Text)", "BINARY" });
		mainPanel.add(modeBox, gbc);

		// --- Row 5: OPTIONS ---
		gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 3;
		JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		JCheckBox crlfCheck = new JCheckBox("CRLF (Text Mode)", true);
		JCheckBox appendCheck = new JCheckBox("Append", false);
		optionsPanel.add(crlfCheck);
		optionsPanel.add(Box.createHorizontalStrut(15));
		optionsPanel.add(appendCheck);
		mainPanel.add(optionsPanel, gbc);

		// --- Row 6: ALLOCATION PARAMETERS ---
		gbc.gridy = 6;
		JPanel allocPanel = new JPanel(new GridBagLayout());
		allocPanel.setBorder(BorderFactory.createTitledBorder("Host Allocation / Format Parameters"));
		GridBagConstraints agbc = new GridBagConstraints();
		agbc.insets = new Insets(2, 5, 2, 5);
		agbc.fill = GridBagConstraints.HORIZONTAL;
		
		agbc.gridx = 0; agbc.gridy = 0;
		allocPanel.add(new JLabel("RECFM:"), agbc);
		agbc.gridx = 1;
		JComboBox<String> recfmBox = new JComboBox<>(new String[] { "V", "F", "U", "" });
		allocPanel.add(recfmBox, agbc);
		agbc.gridx = 2;
		allocPanel.add(new JLabel("LRECL:"), agbc);
		agbc.gridx = 3;
		JTextField lreclField = new JTextField("", 5);
		allocPanel.add(lreclField, agbc);
		
		agbc.gridx = 0; agbc.gridy = 1;
		allocPanel.add(new JLabel("BLKSIZE:"), agbc);
		agbc.gridx = 1;
		JTextField blksizeField = new JTextField("", 6);
		allocPanel.add(blksizeField, agbc);
		agbc.gridx = 2;
		JLabel spaceLabel = new JLabel("SPACE:");
		allocPanel.add(spaceLabel, agbc);
		agbc.gridx = 3;
		JTextField spaceField = new JTextField("", 8);
		allocPanel.add(spaceField, agbc);
		
		mainPanel.add(allocPanel, gbc);

		// =======================================================================
		// 3. LISTENERS
		// =======================================================================

		// --- A. CLIPBOARD LISTENER ---
		useClipboardCheck.addActionListener(e -> {
			boolean useClip = useClipboardCheck.isSelected();
			
			localFileField.setEnabled(!useClip);
			browseBtn.setEnabled(!useClip);
			fileLabel.setText(useClip ? "Source/Dest:" : "Local File:");
			localFileField.setText(useClip ? "(System Clipboard)" : "");
			
			modeBox.setEnabled(!useClip);
			if (useClip) {
				modeBox.setSelectedIndex(0); 
				crlfCheck.setSelected(true); 
				crlfCheck.setEnabled(false);
			} else {
				crlfCheck.setEnabled(modeBox.getSelectedIndex() == 0);
			}
			
			recfmBox.setEnabled(!useClip);
			lreclField.setEnabled(!useClip);
			blksizeField.setEnabled(!useClip);
			spaceField.setEnabled(!useClip);
		});

		// --- B. HOST TYPE LISTENER ---
		hostTypeBox.addActionListener(e -> {
			boolean tso = (hostTypeBox.getSelectedIndex() == 0);
			datasetLabel.setText(tso ? "Host Dataset:" : "Host File:");
			spaceLabel.setVisible(tso);
			spaceField.setVisible(tso);
			
			if (!useClipboardCheck.isSelected() && !localFileField.getText().isEmpty()) {
				String path = localFileField.getText();
				String name = new File(path).getName().toUpperCase();
				if (tso) hostDatasetField.setText(name);
				else hostDatasetField.setText(name.replace('.', ' ') + " A");
			}
		});

		// --- C. BROWSE LISTENER ---
		browseBtn.addActionListener(e -> {
			JFileChooser fc = new JFileChooser();
			if (isDownload ? fc.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION
					: fc.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
				File f = fc.getSelectedFile();
				localFileField.setText(f.getAbsolutePath());
				
				String name = f.getName().toUpperCase();
				boolean isTSO = (hostTypeBox.getSelectedIndex() == 0);
				if (isTSO) hostDatasetField.setText(name);
				else {
					String cmsName = name.replace('.', ' ');
					if (!cmsName.endsWith(" A")) cmsName += " A";
					hostDatasetField.setText(cmsName);
				}
			}
		});

		// --- D. MODE LISTENER ---
		modeBox.addActionListener(e -> {
			boolean isAscii = (modeBox.getSelectedIndex() == 0);
			crlfCheck.setEnabled(isAscii);
			crlfCheck.setSelected(isAscii);
		});

		// --- E. TRANSFER BUTTON ---
		transferBtn.addActionListener(e -> {
			boolean ascii = (modeBox.getSelectedIndex() == 0);
			boolean isClipboard = useClipboardCheck.isSelected();
			
			HostType selectedHostType = hostTypeBox.getSelectedIndex() == 0 ? HostType.TSO : HostType.CMS;
			String dataset = hostDatasetField.getText().trim();

			if (dataset.isEmpty()) {
				JOptionPane.showMessageDialog(dialog, "Please specify a Host Dataset/File.", "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}
			
			// --- AUTO-DETECT LINUX ---
			// If filename contains "/", assume Linux, overriding the dropdown.
			HostType inferredType = inferHostType(dataset);
			HostType actualHostType = (inferredType == HostType.LINUX) ? HostType.LINUX : selectedHostType;

			// ============================
			// 1. CLIPBOARD TRANSFER
			// ============================
			if (isClipboard) {
				dialog.dispose(); 

				if (actualHostType == HostType.LINUX) {
					// --- LINUX CLIPBOARD HANDLING ---
					if (isDownload) {
						downloadTextFromLinuxConsole(dataset, new MemoryTransferCallback() {
							public void onDownloadComplete(String content) {
								try {
									Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(content), null);
									JOptionPane.showMessageDialog(getParentFrame(), "Downloaded from Linux to Clipboard.");
								} catch (Exception ex) { onError(ex.getMessage()); }
							}
							public void onUploadComplete() {}
							public void onError(String msg) {
								JOptionPane.showMessageDialog(getParentFrame(), "Linux Download Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
							}
						});
					} else {
						// Upload
						try {
							String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
							if (text == null) text = "";
							uploadTextToLinuxConsole(text.getBytes(StandardCharsets.UTF_8), dataset, new MemoryTransferCallback() {
								public void onUploadComplete() {
									JOptionPane.showMessageDialog(getParentFrame(), "Uploaded Clipboard to Linux.");
								}
								public void onDownloadComplete(String c) {}
								public void onError(String msg) {
									JOptionPane.showMessageDialog(getParentFrame(), "Linux Upload Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
								}
							});
						} catch (Exception ex) {
							JOptionPane.showMessageDialog(getParentFrame(), "Clipboard read error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
						}
					}
					return;
				}

				// --- MAINFRAME CLIPBOARD HANDLING (Existing) ---
				if (isDownload) {
					downloadTextFromHost(dataset, actualHostType, new MemoryTransferCallback() {
						public void onDownloadComplete(String content) {
                            if (AIManager.isLikelyBinary(content.getBytes())) {
                                int confirm = JOptionPane.showConfirmDialog(getParentFrame(),
                                    "The file '" + dataset + "' appears to be binary.\n" +
                                    "Placing this data on the Clipboard may result in garbage text.\n\n" +
                                    "Proceed anyway?",
                                    "Binary Detection Warning",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.WARNING_MESSAGE);
                                if (confirm != JOptionPane.YES_OPTION) return;
                            }
							try {
								Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(content), null);
								JOptionPane.showMessageDialog(getParentFrame(), 
										"Downloaded " + content.length() + " chars to Clipboard.\nFrom: " + dataset);
							} catch (Exception ex) {
								onError("Clipboard access failed: " + ex.getMessage());
							}
						}
						public void onUploadComplete() {}
						public void onError(String msg) {
							JOptionPane.showMessageDialog(getParentFrame(), "Download Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
						}
					});
				} else {
					try {
						String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
						if (text == null) text = "";
						
						uploadTextToHost(text, dataset, actualHostType, new MemoryTransferCallback() {
							public void onUploadComplete() {
								JOptionPane.showMessageDialog(getParentFrame(), "Successfully uploaded Clipboard content to " + dataset);
							}
							public void onDownloadComplete(String c) {}
							public void onError(String msg) {
								JOptionPane.showMessageDialog(getParentFrame(), "Upload Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
							}
						});
					} catch (Exception ex) {
						JOptionPane.showMessageDialog(dialog, "Could not read text from Clipboard.", "Error", JOptionPane.ERROR_MESSAGE);
					}
				}
				return;
			}

			// ============================
			// 2. FILE TRANSFER
			// ============================
			
			String localPath = localFileField.getText().trim();
			if (localPath.isEmpty()) {
				JOptionPane.showMessageDialog(dialog, "Please specify a Local File.", "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}
			File localFile = new File(localPath);

			if (actualHostType == HostType.LINUX) {
				// --- LINUX FILE HANDLING ---
				dialog.dispose();
				if (isDownload) {
					downloadTextFromLinuxConsole(dataset, new MemoryTransferCallback() {
						public void onDownloadComplete(String content) {
							try {
								java.nio.file.Files.write(localFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
								JOptionPane.showMessageDialog(getParentFrame(), "Downloaded to " + localFile.getName());
							} catch (IOException ex) { onError("File write error: " + ex.getMessage()); }
						}
						public void onUploadComplete() {}
						public void onError(String msg) {
							JOptionPane.showMessageDialog(getParentFrame(), "Download Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
						}
					});
				} else {
					// Upload
					if (!localFile.exists()) {
						JOptionPane.showMessageDialog(getParentFrame(), "Local file not found.", "Error", JOptionPane.ERROR_MESSAGE);
						return;
					}
					try {
						byte[] data = java.nio.file.Files.readAllBytes(localFile.toPath());
						uploadTextToLinuxConsole(data, dataset, new MemoryTransferCallback() {
							public void onUploadComplete() {
								JOptionPane.showMessageDialog(getParentFrame(), "Upload Complete.");
							}
							public void onDownloadComplete(String c) {}
							public void onError(String msg) {
								JOptionPane.showMessageDialog(getParentFrame(), "Upload Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
							}
						});
					} catch (IOException ex) {
						JOptionPane.showMessageDialog(getParentFrame(), "Read error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
					}
				}
				return;
			}

			// --- MAINFRAME IND$FILE HANDLING (Existing) ---
			String lrecl = lreclField.getText().trim();
			if (!isDownload && lrecl.isEmpty() && ascii)
				lrecl = "255";
			
			this.hostType = actualHostType;
			
			String cmd = buildIndFileCommand(isDownload, hostType == HostType.TSO, dataset,
					ascii, crlfCheck.isSelected(), appendCheck.isSelected(), (String) recfmBox.getSelectedItem(), lrecl,
					blksizeField.getText().trim(), spaceField.getText().trim());
			
			dialog.dispose();
			initiateFileTransfer(localPath, cmd, isDownload);
		});

		// --- F. CANCEL BUTTON ---
		cancelBtn.addActionListener(e -> dialog.dispose());
		
		// 4. Add Panels to Dialog
		dialog.add(mainPanel, BorderLayout.CENTER);
		dialog.add(btnPanel, BorderLayout.SOUTH);

		// 5. Set Initial State & Show
		boolean startTso = (hostTypeBox.getSelectedIndex() == 0);
		spaceLabel.setVisible(startTso);
		spaceField.setVisible(startTso);
		
		dialog.pack();
		dialog.setLocationRelativeTo(getParentFrame());
		dialog.setVisible(true);
	}
	*/
	/*
	public void showFileTransferDialogGreat(boolean isDownload) {
		if (!connected) {
			JOptionPane.showMessageDialog(getParentFrame(), "Not connected to host.", "Connection Required",
					JOptionPane.WARNING_MESSAGE);
			return;
		}
		JDialog dialog = new JDialog(getParentFrame(), isDownload ? "Download from Host" : "Upload to Host", true);
		dialog.setLayout(new BorderLayout());
		
		// 1. Define Logic Buttons EARLY so they are in scope for listeners
		JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton transferBtn = new JButton(isDownload ? "Download" : "Upload");
		JButton cancelBtn = new JButton("Cancel");
		btnPanel.add(cancelBtn);
		btnPanel.add(transferBtn);

		// 2. Layout Main Panel
		JPanel mainPanel = new JPanel(new GridBagLayout());
		mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;

		// --- Row 0: HOST SYSTEM ---
		gbc.gridx = 0; gbc.gridy = 0;
		mainPanel.add(new JLabel("Host System:"), gbc);
		gbc.gridx = 1; gbc.gridwidth = 2;
		JComboBox<String> hostTypeBox = new JComboBox<>(new String[] { "TSO (z/OS)", "CMS (z/VM)" });
		hostTypeBox.setSelectedIndex(hostType == HostType.TSO ? 0 : 1);
		mainPanel.add(hostTypeBox, gbc);

		// --- Row 1: CLIPBOARD TOGGLE ---
		gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3;
		JCheckBox useClipboardCheck = new JCheckBox("Transfer to/from Clipboard (Text Only)");
		mainPanel.add(useClipboardCheck, gbc);

		// --- Row 2: FILE SELECTION ---
		gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
		JLabel fileLabel = new JLabel("Local File:");
		mainPanel.add(fileLabel, gbc);
		gbc.gridx = 1;
		JTextField localFileField = new JTextField(30);
		mainPanel.add(localFileField, gbc);
		gbc.gridx = 2;
		JButton browseBtn = new JButton("Browse...");
		mainPanel.add(browseBtn, gbc);

		// --- Row 3: DATASET ---
		gbc.gridx = 0; gbc.gridy = 3;
		JLabel datasetLabel = new JLabel("Host Dataset:");
		mainPanel.add(datasetLabel, gbc);
		gbc.gridx = 1; gbc.gridwidth = 2;
		JTextField hostDatasetField = new JTextField(30);
		hostDatasetField.setText(hostType == HostType.TSO ? "USER.TEST.DATA" : "TEST DATA A");
		mainPanel.add(hostDatasetField, gbc);

		// --- Row 4: TRANSFER MODE ---
		gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 1;
		mainPanel.add(new JLabel("Transfer Mode:"), gbc);
		gbc.gridx = 1; gbc.gridwidth = 2;
		JComboBox<String> modeBox = new JComboBox<>(new String[] { "ASCII (Text)", "BINARY" });
		mainPanel.add(modeBox, gbc);

		// --- Row 5: OPTIONS ---
		gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 3;
		JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		JCheckBox crlfCheck = new JCheckBox("CRLF (Text Mode)", true);
		JCheckBox appendCheck = new JCheckBox("Append", false);
		optionsPanel.add(crlfCheck);
		optionsPanel.add(Box.createHorizontalStrut(15));
		optionsPanel.add(appendCheck);
		mainPanel.add(optionsPanel, gbc);

		// --- Row 6: ALLOCATION PARAMETERS ---
		gbc.gridy = 6;
		JPanel allocPanel = new JPanel(new GridBagLayout());
		allocPanel.setBorder(BorderFactory.createTitledBorder("Host Allocation / Format Parameters"));
		GridBagConstraints agbc = new GridBagConstraints();
		agbc.insets = new Insets(2, 5, 2, 5);
		agbc.fill = GridBagConstraints.HORIZONTAL;
		
		agbc.gridx = 0; agbc.gridy = 0;
		allocPanel.add(new JLabel("RECFM:"), agbc);
		agbc.gridx = 1;
		JComboBox<String> recfmBox = new JComboBox<>(new String[] { "V", "F", "U", "" });
		allocPanel.add(recfmBox, agbc);
		agbc.gridx = 2;
		allocPanel.add(new JLabel("LRECL:"), agbc);
		agbc.gridx = 3;
		JTextField lreclField = new JTextField("", 5);
		allocPanel.add(lreclField, agbc);
		
		agbc.gridx = 0; agbc.gridy = 1;
		allocPanel.add(new JLabel("BLKSIZE:"), agbc);
		agbc.gridx = 1;
		JTextField blksizeField = new JTextField("", 6);
		allocPanel.add(blksizeField, agbc);
		agbc.gridx = 2;
		JLabel spaceLabel = new JLabel("SPACE:");
		allocPanel.add(spaceLabel, agbc);
		agbc.gridx = 3;
		JTextField spaceField = new JTextField("", 8);
		allocPanel.add(spaceField, agbc);
		
		mainPanel.add(allocPanel, gbc);

		// =======================================================================
		// 3. LISTENERS
		// =======================================================================

		// --- A. CLIPBOARD LISTENER ---
		useClipboardCheck.addActionListener(e -> {
			boolean useClip = useClipboardCheck.isSelected();
			
			localFileField.setEnabled(!useClip);
			browseBtn.setEnabled(!useClip);
			fileLabel.setText(useClip ? "Source/Dest:" : "Local File:");
			localFileField.setText(useClip ? "(System Clipboard)" : "");
			
			modeBox.setEnabled(!useClip);
			if (useClip) {
				modeBox.setSelectedIndex(0); 
				crlfCheck.setSelected(true); 
				crlfCheck.setEnabled(false);
			} else {
				crlfCheck.setEnabled(modeBox.getSelectedIndex() == 0);
			}
			
			recfmBox.setEnabled(!useClip);
			lreclField.setEnabled(!useClip);
			blksizeField.setEnabled(!useClip);
			spaceField.setEnabled(!useClip);
		});

		// --- B. HOST TYPE LISTENER ---
		hostTypeBox.addActionListener(e -> {
			boolean tso = (hostTypeBox.getSelectedIndex() == 0);
			datasetLabel.setText(tso ? "Host Dataset:" : "Host File:");
			spaceLabel.setVisible(tso);
			spaceField.setVisible(tso);
			
			if (!useClipboardCheck.isSelected() && !localFileField.getText().isEmpty()) {
				String path = localFileField.getText();
				String name = new File(path).getName().toUpperCase();
				if (tso) hostDatasetField.setText(name);
				else hostDatasetField.setText(name.replace('.', ' ') + " A");
			}
		});

		// --- C. BROWSE LISTENER ---
		browseBtn.addActionListener(e -> {
			JFileChooser fc = new JFileChooser();
			if (isDownload ? fc.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION
					: fc.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
				File f = fc.getSelectedFile();
				localFileField.setText(f.getAbsolutePath());
				
				String name = f.getName().toUpperCase();
				boolean isTSO = (hostTypeBox.getSelectedIndex() == 0);
				if (isTSO) hostDatasetField.setText(name);
				else {
					String cmsName = name.replace('.', ' ');
					if (!cmsName.endsWith(" A")) cmsName += " A";
					hostDatasetField.setText(cmsName);
				}
			}
		});

		// --- D. MODE LISTENER ---
		modeBox.addActionListener(e -> {
			boolean isAscii = (modeBox.getSelectedIndex() == 0);
			crlfCheck.setEnabled(isAscii);
			crlfCheck.setSelected(isAscii);
		});

		// --- E. TRANSFER BUTTON ---
		transferBtn.addActionListener(e -> {
			boolean ascii = (modeBox.getSelectedIndex() == 0);
			boolean isClipboard = useClipboardCheck.isSelected();
			
			HostType selectedHostType = hostTypeBox.getSelectedIndex() == 0 ? HostType.TSO : HostType.CMS;
			String dataset = hostDatasetField.getText().trim();

			if (dataset.isEmpty()) {
				JOptionPane.showMessageDialog(dialog, "Please specify a Host Dataset/File.", "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}

			if (isClipboard) {
				dialog.dispose(); 

				if (isDownload) {
					// DOWNLOAD HOST -> CLIPBOARD
					downloadTextFromHost(dataset, selectedHostType, new MemoryTransferCallback() {
						public void onDownloadComplete(String content) {
                            if (AIManager.isLikelyBinary(content.getBytes())) {
                                int confirm = JOptionPane.showConfirmDialog(getParentFrame(),
                                    "The file '" + dataset + "' appears to be binary.\n" +
                                    "Placing this data on the Clipboard may result in garbage text.\n\n" +
                                    "Proceed anyway?",
                                    "Binary Detection Warning",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.WARNING_MESSAGE);
                                if (confirm != JOptionPane.YES_OPTION) return;
                            }
							try {
								Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(content), null);
								JOptionPane.showMessageDialog(getParentFrame(), 
										"Downloaded " + content.length() + " chars to Clipboard.\nFrom: " + dataset);
							} catch (Exception ex) {
								onError("Clipboard access failed: " + ex.getMessage());
							}
						}
						public void onUploadComplete() {}
						public void onError(String msg) {
							JOptionPane.showMessageDialog(getParentFrame(), "Download Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
						}
					});
				} else {
					// UPLOAD CLIPBOARD -> HOST
					try {
						String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
						if (text == null) text = "";
						
						uploadTextToHost(text, dataset, selectedHostType, new MemoryTransferCallback() {
							public void onUploadComplete() {
								JOptionPane.showMessageDialog(getParentFrame(), "Successfully uploaded Clipboard content to " + dataset);
							}
							public void onDownloadComplete(String c) {}
							public void onError(String msg) {
								JOptionPane.showMessageDialog(getParentFrame(), "Upload Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
							}
						});
					} catch (Exception ex) {
						JOptionPane.showMessageDialog(dialog, "Could not read text from Clipboard.", "Error", JOptionPane.ERROR_MESSAGE);
					}
				}
				return;
			}

			// --- FILE LOGIC ---
			String lrecl = lreclField.getText().trim();
			if (!isDownload && lrecl.isEmpty() && ascii)
				lrecl = "255";
			
			this.hostType = selectedHostType;
			
			String cmd = buildIndFileCommand(isDownload, hostType == HostType.TSO, dataset,
					ascii, crlfCheck.isSelected(), appendCheck.isSelected(), (String) recfmBox.getSelectedItem(), lrecl,
					blksizeField.getText().trim(), spaceField.getText().trim());
			
			dialog.dispose();
			initiateFileTransfer(localFileField.getText().trim(), cmd, isDownload);
		});

		// --- F. CANCEL BUTTON ---
		cancelBtn.addActionListener(e -> dialog.dispose());
		
		// 4. Add Panels to Dialog
		dialog.add(mainPanel, BorderLayout.CENTER);
		dialog.add(btnPanel, BorderLayout.SOUTH);

		// 5. Set Initial State & Show
		boolean startTso = (hostTypeBox.getSelectedIndex() == 0);
		spaceLabel.setVisible(startTso);
		spaceField.setVisible(startTso);
		
		dialog.pack();
		dialog.setLocationRelativeTo(getParentFrame());
		dialog.setVisible(true);
	}
	
	public void showFileTransferDialogPrettyGood(boolean isDownload) {
		if (!connected) {
			JOptionPane.showMessageDialog(getParentFrame(), "Not connected to host.", "Connection Required",
					JOptionPane.WARNING_MESSAGE);
			return;
		}
		JDialog dialog = new JDialog(getParentFrame(), isDownload ? "Download from Host" : "Upload to Host", true);
		dialog.setLayout(new BorderLayout());
		JPanel mainPanel = new JPanel(new GridBagLayout());
		mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;

		gbc.gridx = 0;
		gbc.gridy = 0;
		mainPanel.add(new JLabel("Host System:"), gbc);
		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JComboBox<String> hostTypeBox = new JComboBox<>(new String[] { "TSO (z/OS)", "CMS (z/VM)" });
		hostTypeBox.setSelectedIndex(hostType == HostType.TSO ? 0 : 1);
		mainPanel.add(hostTypeBox, gbc);

		// --- CLIPBOARD TOGGLE ---
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 3;
		JCheckBox useClipboardCheck = new JCheckBox("Transfer to/from Clipboard (Text Only)");
		mainPanel.add(useClipboardCheck, gbc);

		// --- FILE SELECTION ---
		gbc.gridx = 0;
		gbc.gridy = 2;
		gbc.gridwidth = 1;
		JLabel fileLabel = new JLabel("Local File:");
		mainPanel.add(fileLabel, gbc);
		gbc.gridx = 1;
		JTextField localFileField = new JTextField(30);
		mainPanel.add(localFileField, gbc);
		gbc.gridx = 2;
		JButton browseBtn = new JButton("Browse...");
		mainPanel.add(browseBtn, gbc);

		gbc.gridx = 0;
		gbc.gridy = 3;
		JLabel datasetLabel = new JLabel("Host Dataset:");
		mainPanel.add(datasetLabel, gbc);
		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JTextField hostDatasetField = new JTextField(30);
		hostDatasetField.setText(hostType == HostType.TSO ? "USER.TEST.DATA" : "TEST DATA A");
		mainPanel.add(hostDatasetField, gbc);

		gbc.gridx = 0;
		gbc.gridy = 4;
		gbc.gridwidth = 1;
		mainPanel.add(new JLabel("Transfer Mode:"), gbc);
		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JComboBox<String> modeBox = new JComboBox<>(new String[] { "ASCII (Text)", "BINARY" });
		mainPanel.add(modeBox, gbc);

		gbc.gridx = 0;
		gbc.gridy = 5;
		gbc.gridwidth = 3;
		JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		JCheckBox crlfCheck = new JCheckBox("CRLF (Text Mode)", true);
		JCheckBox appendCheck = new JCheckBox("Append", false);
		optionsPanel.add(crlfCheck);
		optionsPanel.add(Box.createHorizontalStrut(15));
		optionsPanel.add(appendCheck);
		mainPanel.add(optionsPanel, gbc);

		// --- RE-ORDERED LOGIC: Add Clipboard Listener HERE ---
		// We define this AFTER modeBox and crlfCheck are created so we can control them.
		useClipboardCheck.addActionListener(e -> {
			boolean useClip = useClipboardCheck.isSelected();
			
			// 1. Toggle File Fields
			localFileField.setEnabled(!useClip);
			browseBtn.setEnabled(!useClip);
			fileLabel.setText(useClip ? "Source/Dest:" : "Local File:");
			localFileField.setText(useClip ? "(System Clipboard)" : "");
			
			// 2. Disable/Force Transfer Mode
			modeBox.setEnabled(!useClip);
			if (useClip) {
				modeBox.setSelectedIndex(0); // Force ASCII
				crlfCheck.setSelected(true); // Force CRLF (Standard for clipboard text)
				crlfCheck.setEnabled(false); // Lock CRLF
			} else {
				// Restore capability
				crlfCheck.setEnabled(modeBox.getSelectedIndex() == 0);
			}
		});
		// -----------------------------------------------------

		gbc.gridy = 6;
		JPanel allocPanel = new JPanel(new GridBagLayout());
		allocPanel.setBorder(BorderFactory.createTitledBorder("Host Allocation / Format Parameters"));
		GridBagConstraints agbc = new GridBagConstraints();
		agbc.insets = new Insets(2, 5, 2, 5);
		agbc.fill = GridBagConstraints.HORIZONTAL;
		agbc.gridx = 0;
		agbc.gridy = 0;
		allocPanel.add(new JLabel("RECFM:"), agbc);
		agbc.gridx = 1;
		JComboBox<String> recfmBox = new JComboBox<>(new String[] { "V", "F", "U", "" });
		allocPanel.add(recfmBox, agbc);
		agbc.gridx = 2;
		allocPanel.add(new JLabel("LRECL:"), agbc);
		agbc.gridx = 3;
		JTextField lreclField = new JTextField("", 5);
		allocPanel.add(lreclField, agbc);
		agbc.gridx = 0;
		agbc.gridy = 1;
		allocPanel.add(new JLabel("BLKSIZE:"), agbc);
		agbc.gridx = 1;
		JTextField blksizeField = new JTextField("", 6);
		allocPanel.add(blksizeField, agbc);
		agbc.gridx = 2;
		JLabel spaceLabel = new JLabel("SPACE:");
		allocPanel.add(spaceLabel, agbc);
		agbc.gridx = 3;
		JTextField spaceField = new JTextField("", 8);
		allocPanel.add(spaceField, agbc);
		mainPanel.add(allocPanel, gbc);

		dialog.add(mainPanel, BorderLayout.CENTER);
		JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton transferBtn = new JButton(isDownload ? "Download" : "Upload");
		JButton cancelBtn = new JButton("Cancel");
		btnPanel.add(cancelBtn);
		btnPanel.add(transferBtn);
		dialog.add(btnPanel, BorderLayout.SOUTH);

		// Host Type Logic
		hostTypeBox.addActionListener(e -> {
			boolean tso = (hostTypeBox.getSelectedIndex() == 0);
			datasetLabel.setText(tso ? "Host Dataset:" : "Host File:");
			spaceLabel.setVisible(tso);
			spaceField.setVisible(tso);
			
			// Hinting logic (only if not clipboard)
			if (!useClipboardCheck.isSelected() && !localFileField.getText().isEmpty()) {
				String path = localFileField.getText();
				String name = new File(path).getName().toUpperCase();
				if (tso) hostDatasetField.setText(name);
				else hostDatasetField.setText(name.replace('.', ' ') + " A");
			}
		});

		// Browse Logic
		browseBtn.addActionListener(e -> {
			JFileChooser fc = new JFileChooser();
			if (isDownload ? fc.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION
					: fc.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
				File f = fc.getSelectedFile();
				localFileField.setText(f.getAbsolutePath());
				
				// Hinting
				String name = f.getName().toUpperCase();
				boolean isTSO = (hostTypeBox.getSelectedIndex() == 0);
				if (isTSO) hostDatasetField.setText(name);
				else {
					String cmsName = name.replace('.', ' ');
					if (!cmsName.endsWith(" A")) cmsName += " A";
					hostDatasetField.setText(cmsName);
				}
			}
		});

		modeBox.addActionListener(e -> {
			boolean isAscii = (modeBox.getSelectedIndex() == 0);
			crlfCheck.setEnabled(isAscii);
			crlfCheck.setSelected(isAscii);
		});

		// --- TRANSFER ACTION ---
		transferBtn.addActionListener(e -> {
			boolean ascii = (modeBox.getSelectedIndex() == 0);
			boolean isClipboard = useClipboardCheck.isSelected();
			
			// Determine Host Type from Dropdown
			HostType selectedHostType = hostTypeBox.getSelectedIndex() == 0 ? HostType.TSO : HostType.CMS;
			String dataset = hostDatasetField.getText().trim();

			if (dataset.isEmpty()) {
				JOptionPane.showMessageDialog(dialog, "Please specify a Host Dataset/File.", "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}

			// --- CLIPBOARD PATH ---
			if (isClipboard) {
				dialog.dispose(); // Close dialog first

				if (isDownload) {
					// DOWNLOAD HOST -> CLIPBOARD
					downloadTextFromHost(dataset, selectedHostType, new MemoryTransferCallback() {
						public void onDownloadComplete(String content) {
                            // Guard Rail: Check for Binary Garbage
                            if (AIManager.isLikelyBinary(content.getBytes())) {
                                int confirm = JOptionPane.showConfirmDialog(getParentFrame(),
                                    "The file '" + dataset + "' appears to be binary.\n" +
                                    "Placing this data on the Clipboard may result in garbage text.\n\n" +
                                    "Proceed anyway?",
                                    "Binary Detection Warning",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.WARNING_MESSAGE);
                                if (confirm != JOptionPane.YES_OPTION) return;
                            }

							try {
								Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(content), null);
								JOptionPane.showMessageDialog(getParentFrame(), 
										"Downloaded " + content.length() + " chars to Clipboard.\nFrom: " + dataset);
							} catch (Exception ex) {
								onError("Clipboard access failed: " + ex.getMessage());
							}
						}
						public void onUploadComplete() {}
						public void onError(String msg) {
							JOptionPane.showMessageDialog(getParentFrame(), "Download Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
						}
					});
				} else {
					// UPLOAD CLIPBOARD -> HOST
					try {
						String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
						if (text == null) text = "";
						
						uploadTextToHost(text, dataset, selectedHostType, new MemoryTransferCallback() {
							public void onUploadComplete() {
								JOptionPane.showMessageDialog(getParentFrame(), "Successfully uploaded Clipboard content to " + dataset);
							}
							public void onDownloadComplete(String c) {}
							public void onError(String msg) {
								JOptionPane.showMessageDialog(getParentFrame(), "Upload Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
							}
						});
					} catch (Exception ex) {
						JOptionPane.showMessageDialog(dialog, "Could not read text from Clipboard.", "Error", JOptionPane.ERROR_MESSAGE);
					}
				}
				return;
			}

			// --- FILE PATH (Existing Logic) ---
			String lrecl = lreclField.getText().trim();
			if (!isDownload && lrecl.isEmpty() && ascii)
				lrecl = "255";
			
			this.hostType = selectedHostType;
			
			String cmd = buildIndFileCommand(isDownload, hostType == HostType.TSO, dataset,
					ascii, crlfCheck.isSelected(), appendCheck.isSelected(), (String) recfmBox.getSelectedItem(), lrecl,
					blksizeField.getText().trim(), spaceField.getText().trim());
			
			dialog.dispose();
			initiateFileTransfer(localFileField.getText().trim(), cmd, isDownload);
		});

		cancelBtn.addActionListener(e -> dialog.dispose());
		dialog.pack();
		dialog.setLocationRelativeTo(getParentFrame());
		dialog.setVisible(true);
	}
	
	public void showFileTransferDialogWorkingWell(boolean isDownload) {
		if (!connected) {
			JOptionPane.showMessageDialog(getParentFrame(), "Not connected to host.", "Connection Required",
					JOptionPane.WARNING_MESSAGE);
			return;
		}
		JDialog dialog = new JDialog(getParentFrame(), isDownload ? "Download from Host" : "Upload to Host", true);
		dialog.setLayout(new BorderLayout());
		JPanel mainPanel = new JPanel(new GridBagLayout());
		mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;

		gbc.gridx = 0;
		gbc.gridy = 0;
		mainPanel.add(new JLabel("Host System:"), gbc);
		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JComboBox<String> hostTypeBox = new JComboBox<>(new String[] { "TSO (z/OS)", "CMS (z/VM)" });
		hostTypeBox.setSelectedIndex(hostType == HostType.TSO ? 0 : 1);
		mainPanel.add(hostTypeBox, gbc);

		// --- NEW: CLIPBOARD TOGGLE ---
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 3;
		JCheckBox useClipboardCheck = new JCheckBox("Transfer to/from Clipboard (Text Only)");
		mainPanel.add(useClipboardCheck, gbc);

		// --- FILE SELECTION ---
		gbc.gridx = 0;
		gbc.gridy = 2;
		gbc.gridwidth = 1;
		JLabel fileLabel = new JLabel("Local File:");
		mainPanel.add(fileLabel, gbc);
		gbc.gridx = 1;
		JTextField localFileField = new JTextField(30);
		mainPanel.add(localFileField, gbc);
		gbc.gridx = 2;
		JButton browseBtn = new JButton("Browse...");
		mainPanel.add(browseBtn, gbc);

		// Logic to toggle fields based on Clipboard Checkbox
		useClipboardCheck.addActionListener(e -> {
			boolean useClip = useClipboardCheck.isSelected();
			localFileField.setEnabled(!useClip);
			browseBtn.setEnabled(!useClip);
			fileLabel.setText(useClip ? "Source/Dest:" : "Local File:");
			localFileField.setText(useClip ? "(System Clipboard)" : "");
		});

		gbc.gridx = 0;
		gbc.gridy = 3;
		JLabel datasetLabel = new JLabel("Host Dataset:");
		mainPanel.add(datasetLabel, gbc);
		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JTextField hostDatasetField = new JTextField(30);
		hostDatasetField.setText(hostType == HostType.TSO ? "USER.TEST.DATA" : "TEST DATA A");
		mainPanel.add(hostDatasetField, gbc);

		gbc.gridx = 0;
		gbc.gridy = 4;
		gbc.gridwidth = 1;
		mainPanel.add(new JLabel("Transfer Mode:"), gbc);
		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JComboBox<String> modeBox = new JComboBox<>(new String[] { "ASCII (Text)", "BINARY" });
		mainPanel.add(modeBox, gbc);

		gbc.gridx = 0;
		gbc.gridy = 5;
		gbc.gridwidth = 3;
		JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		JCheckBox crlfCheck = new JCheckBox("CRLF (Text Mode)", true);
		JCheckBox appendCheck = new JCheckBox("Append", false);
		optionsPanel.add(crlfCheck);
		optionsPanel.add(Box.createHorizontalStrut(15));
		optionsPanel.add(appendCheck);
		mainPanel.add(optionsPanel, gbc);

		gbc.gridy = 6;
		JPanel allocPanel = new JPanel(new GridBagLayout());
		allocPanel.setBorder(BorderFactory.createTitledBorder("Host Allocation / Format Parameters"));
		GridBagConstraints agbc = new GridBagConstraints();
		agbc.insets = new Insets(2, 5, 2, 5);
		agbc.fill = GridBagConstraints.HORIZONTAL;
		agbc.gridx = 0;
		agbc.gridy = 0;
		allocPanel.add(new JLabel("RECFM:"), agbc);
		agbc.gridx = 1;
		JComboBox<String> recfmBox = new JComboBox<>(new String[] { "V", "F", "U", "" });
		allocPanel.add(recfmBox, agbc);
		agbc.gridx = 2;
		allocPanel.add(new JLabel("LRECL:"), agbc);
		agbc.gridx = 3;
		JTextField lreclField = new JTextField("", 5);
		allocPanel.add(lreclField, agbc);
		agbc.gridx = 0;
		agbc.gridy = 1;
		allocPanel.add(new JLabel("BLKSIZE:"), agbc);
		agbc.gridx = 1;
		JTextField blksizeField = new JTextField("", 6);
		allocPanel.add(blksizeField, agbc);
		agbc.gridx = 2;
		JLabel spaceLabel = new JLabel("SPACE:");
		allocPanel.add(spaceLabel, agbc);
		agbc.gridx = 3;
		JTextField spaceField = new JTextField("", 8);
		allocPanel.add(spaceField, agbc);
		mainPanel.add(allocPanel, gbc);

		dialog.add(mainPanel, BorderLayout.CENTER);
		JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton transferBtn = new JButton(isDownload ? "Download" : "Upload");
		JButton cancelBtn = new JButton("Cancel");
		btnPanel.add(cancelBtn);
		btnPanel.add(transferBtn);
		dialog.add(btnPanel, BorderLayout.SOUTH);

		// Host Type Logic
		hostTypeBox.addActionListener(e -> {
			boolean tso = (hostTypeBox.getSelectedIndex() == 0);
			datasetLabel.setText(tso ? "Host Dataset:" : "Host File:");
			spaceLabel.setVisible(tso);
			spaceField.setVisible(tso);
			
			// Hinting logic (only if not clipboard)
			if (!useClipboardCheck.isSelected() && !localFileField.getText().isEmpty()) {
				String path = localFileField.getText();
				String name = new File(path).getName().toUpperCase();
				if (tso) hostDatasetField.setText(name);
				else hostDatasetField.setText(name.replace('.', ' ') + " A");
			}
		});

		// Browse Logic
		browseBtn.addActionListener(e -> {
			JFileChooser fc = new JFileChooser();
			if (isDownload ? fc.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION
					: fc.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
				File f = fc.getSelectedFile();
				localFileField.setText(f.getAbsolutePath());
				
				// Hinting
				String name = f.getName().toUpperCase();
				boolean isTSO = (hostTypeBox.getSelectedIndex() == 0);
				if (isTSO) hostDatasetField.setText(name);
				else {
					String cmsName = name.replace('.', ' ');
					if (!cmsName.endsWith(" A")) cmsName += " A";
					hostDatasetField.setText(cmsName);
				}
			}
		});

		modeBox.addActionListener(e -> {
			boolean isAscii = (modeBox.getSelectedIndex() == 0);
			crlfCheck.setEnabled(isAscii);
			crlfCheck.setSelected(isAscii);
		});

		// --- TRANSFER ACTION ---
		transferBtn.addActionListener(e -> {
			boolean ascii = (modeBox.getSelectedIndex() == 0);
			boolean isClipboard = useClipboardCheck.isSelected();
			
			// Determine Host Type from Dropdown (Explicit User Choice overrides auto-detect here)
			HostType selectedHostType = hostTypeBox.getSelectedIndex() == 0 ? HostType.TSO : HostType.CMS;
			String dataset = hostDatasetField.getText().trim();

			if (dataset.isEmpty()) {
				JOptionPane.showMessageDialog(dialog, "Please specify a Host Dataset/File.", "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}

			// --- CLIPBOARD PATH ---
			if (isClipboard) {
				if (!ascii) {
					JOptionPane.showMessageDialog(dialog, "Clipboard transfer only supports ASCII (Text) mode.", "Restricted", JOptionPane.WARNING_MESSAGE);
					return;
				}
				
				dialog.dispose(); // Close dialog first

				if (isDownload) {
					// DOWNLOAD HOST -> CLIPBOARD
					downloadTextFromHost(dataset, selectedHostType, new MemoryTransferCallback() {
						public void onDownloadComplete(String content) {
							try {
		                        // --- NEW GUARD RAIL ---
	                            // Check if the result looks like binary garbage, even if transferred in ASCII mode
	                            if (AIManager.isLikelyBinary(content.getBytes())) {
	                                int confirm = JOptionPane.showConfirmDialog(getParentFrame(),
	                                    "The file '" + dataset + "' appears to be binary.\n" +
	                                    "Placing this data on the Clipboard may result in garbage text.\n\n" +
	                                    "Proceed anyway?",
	                                    "Binary Detection Warning",
	                                    JOptionPane.YES_NO_OPTION,
	                                    JOptionPane.WARNING_MESSAGE);
	                                
	                                if (confirm != JOptionPane.YES_OPTION) return;
	                            }
	                            // ----------------------
								Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(content), null);
								JOptionPane.showMessageDialog(getParentFrame(), 
										"Downloaded " + content.length() + " chars to Clipboard.\nFrom: " + dataset);
							} catch (Exception ex) {
								onError("Clipboard access failed: " + ex.getMessage());
							}
						}
						public void onUploadComplete() {}
						public void onError(String msg) {
							JOptionPane.showMessageDialog(getParentFrame(), "Download Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
						}
					});
				} else {
					// UPLOAD CLIPBOARD -> HOST
					try {
						String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
						if (text == null) text = "";
						
						uploadTextToHost(text, dataset, selectedHostType, new MemoryTransferCallback() {
							public void onUploadComplete() {
								JOptionPane.showMessageDialog(getParentFrame(), "Successfully uploaded Clipboard content to " + dataset);
							}
							public void onDownloadComplete(String c) {}
							public void onError(String msg) {
								JOptionPane.showMessageDialog(getParentFrame(), "Upload Failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
							}
						});
					} catch (Exception ex) {
						JOptionPane.showMessageDialog(dialog, "Could not read text from Clipboard.", "Error", JOptionPane.ERROR_MESSAGE);
					}
				}
				return;
			}

			// --- FILE PATH (Existing Logic) ---
			String lrecl = lreclField.getText().trim();
			if (!isDownload && lrecl.isEmpty() && ascii)
				lrecl = "255";
			
			this.hostType = selectedHostType;
			
			String cmd = buildIndFileCommand(isDownload, hostType == HostType.TSO, dataset,
					ascii, crlfCheck.isSelected(), appendCheck.isSelected(), (String) recfmBox.getSelectedItem(), lrecl,
					blksizeField.getText().trim(), spaceField.getText().trim());
			
			dialog.dispose();
			initiateFileTransfer(localFileField.getText().trim(), cmd, isDownload);
		});

		cancelBtn.addActionListener(e -> dialog.dispose());
		dialog.pack();
		dialog.setLocationRelativeTo(getParentFrame());
		dialog.setVisible(true);
	}
	
	public void showFileTransferDialogWorking(boolean isDownload) {
		if (!connected) {
			JOptionPane.showMessageDialog(getParentFrame(), "Not connected to host.", "Connection Required",
					JOptionPane.WARNING_MESSAGE);
			return;
		}
		JDialog dialog = new JDialog(getParentFrame(), isDownload ? "Download from Host" : "Upload to Host", true);
		dialog.setLayout(new BorderLayout());
		JPanel mainPanel = new JPanel(new GridBagLayout());
		mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;

		gbc.gridx = 0;
		gbc.gridy = 0;
		mainPanel.add(new JLabel("Host System:"), gbc);
		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JComboBox<String> hostTypeBox = new JComboBox<>(new String[] { "TSO (z/OS)", "CMS (z/VM)" });
		hostTypeBox.setSelectedIndex(hostType == HostType.TSO ? 0 : 1);
		mainPanel.add(hostTypeBox, gbc);

		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 1;
		mainPanel.add(new JLabel("Local File:"), gbc);
		gbc.gridx = 1;
		JTextField localFileField = new JTextField(30);
		mainPanel.add(localFileField, gbc);
		gbc.gridx = 2;
		JButton browseBtn = new JButton("Browse...");
		mainPanel.add(browseBtn, gbc);

		gbc.gridx = 0;
		gbc.gridy = 2;
		JLabel datasetLabel = new JLabel("Host Dataset:");
		mainPanel.add(datasetLabel, gbc);
		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JTextField hostDatasetField = new JTextField(30);
		hostDatasetField.setText(hostType == HostType.TSO ? "USER.TEST.DATA" : "TEST DATA A");
		mainPanel.add(hostDatasetField, gbc);

		gbc.gridx = 0;
		gbc.gridy = 3;
		gbc.gridwidth = 1;
		mainPanel.add(new JLabel("Transfer Mode:"), gbc);
		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JComboBox<String> modeBox = new JComboBox<>(new String[] { "ASCII (Text)", "BINARY" });
		mainPanel.add(modeBox, gbc);

		gbc.gridx = 0;
		gbc.gridy = 4;
		gbc.gridwidth = 3;
		JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		JCheckBox crlfCheck = new JCheckBox("CRLF (Text Mode)", true);
		JCheckBox appendCheck = new JCheckBox("Append", false);
		optionsPanel.add(crlfCheck);
		optionsPanel.add(Box.createHorizontalStrut(15));
		optionsPanel.add(appendCheck);
		mainPanel.add(optionsPanel, gbc);

		gbc.gridy = 5;
		JPanel allocPanel = new JPanel(new GridBagLayout());
		allocPanel.setBorder(BorderFactory.createTitledBorder("Host Allocation / Format Parameters"));
		GridBagConstraints agbc = new GridBagConstraints();
		agbc.insets = new Insets(2, 5, 2, 5);
		agbc.fill = GridBagConstraints.HORIZONTAL;
		agbc.gridx = 0;
		agbc.gridy = 0;
		allocPanel.add(new JLabel("RECFM:"), agbc);
		agbc.gridx = 1;
		JComboBox<String> recfmBox = new JComboBox<>(new String[] { "V", "F", "U", "" });
		allocPanel.add(recfmBox, agbc);
		agbc.gridx = 2;
		allocPanel.add(new JLabel("LRECL:"), agbc);
		agbc.gridx = 3;
		JTextField lreclField = new JTextField("", 5);
		allocPanel.add(lreclField, agbc);
		agbc.gridx = 0;
		agbc.gridy = 1;
		allocPanel.add(new JLabel("BLKSIZE:"), agbc);
		agbc.gridx = 1;
		JTextField blksizeField = new JTextField("", 6);
		allocPanel.add(blksizeField, agbc);
		agbc.gridx = 2;
		JLabel spaceLabel = new JLabel("SPACE:");
		allocPanel.add(spaceLabel, agbc);
		agbc.gridx = 3;
		JTextField spaceField = new JTextField("", 8);
		allocPanel.add(spaceField, agbc);
		mainPanel.add(allocPanel, gbc);

		dialog.add(mainPanel, BorderLayout.CENTER);
		JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton transferBtn = new JButton(isDownload ? "Download" : "Upload");
		JButton cancelBtn = new JButton("Cancel");
		btnPanel.add(cancelBtn);
		btnPanel.add(transferBtn);
		dialog.add(btnPanel, BorderLayout.SOUTH);

		// Host Type Logic
		hostTypeBox.addActionListener(e -> {
			boolean tso = (hostTypeBox.getSelectedIndex() == 0);
			datasetLabel.setText(tso ? "Host Dataset:" : "Host File:");
			spaceLabel.setVisible(tso);
			spaceField.setVisible(tso);

			// Re-trigger hinting if file is selected
			if (!localFileField.getText().isEmpty()) {
				String path = localFileField.getText();
				String name = new File(path).getName().toUpperCase();
				if (tso)
					hostDatasetField.setText(name); // TSO: FILE.TXT
				else
					hostDatasetField.setText(name.replace('.', ' ') + " A"); // CMS: FILE TXT A
			}
		});

		// Browse Logic with Auto-Hinting
		browseBtn.addActionListener(e -> {
			JFileChooser fc = new JFileChooser();
			if (isDownload ? fc.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION
					: fc.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
				File f = fc.getSelectedFile();
				localFileField.setText(f.getAbsolutePath());

				// Smart Hinting Logic
				String name = f.getName().toUpperCase();
				boolean isTSO = (hostTypeBox.getSelectedIndex() == 0);
				if (isTSO) {
					// TSO: Keep dots, e.g. MY.DATA.TXT
					hostDatasetField.setText(name);
				} else {
					// CMS: Replace dots with spaces, append Mode A
					// e.g. PROFILE.EXEC -> PROFILE EXEC A
					String cmsName = name.replace('.', ' ');
					if (!cmsName.endsWith(" A"))
						cmsName += " A";
					hostDatasetField.setText(cmsName);
				}
			}
		});

		modeBox.addActionListener(e -> {
			boolean isAscii = (modeBox.getSelectedIndex() == 0);
			crlfCheck.setEnabled(isAscii);
			if (!isAscii)
				crlfCheck.setSelected(false);
			else
				crlfCheck.setSelected(true);
		});
		transferBtn.addActionListener(e -> {
			boolean ascii = (modeBox.getSelectedIndex() == 0);
			String lrecl = lreclField.getText().trim();
			if (!isDownload && lrecl.isEmpty() && ascii)
				lrecl = "255";
			hostType = hostTypeBox.getSelectedIndex() == 0 ? HostType.TSO : HostType.CMS;
			String cmd = buildIndFileCommand(isDownload, hostType == HostType.TSO, hostDatasetField.getText().trim(),
					ascii, crlfCheck.isSelected(), appendCheck.isSelected(), (String) recfmBox.getSelectedItem(), lrecl,
					blksizeField.getText().trim(), spaceField.getText().trim());
			dialog.dispose();
			initiateFileTransfer(localFileField.getText().trim(), cmd, isDownload);
		});
		cancelBtn.addActionListener(e -> dialog.dispose());
		dialog.pack();
		dialog.setLocationRelativeTo(getParentFrame());
		dialog.setVisible(true);
	}
*/
	private void handleDataChain(byte[] data, int offset, int length) {
		if (offset + 3 >= data.length)
			return;
		byte op = data[offset + 3];
		switch (op) {
		case DC_OPEN:
			handleDCOpen(data, offset, length);
			break;
		case DC_CLOSE:
			handleDCClose(data, offset, length);
			break;
		case DC_SET_CURSOR:
			handleDCSetCursor(data, offset, length);
			break;
		case DC_GET:
			handleDCGet(data, offset, length);
			break;
		case DC_INSERT:
			handleDCInsert(data, offset, length);
			break;
		}
	}

	public void initiateFileTransfer(String localFilePath, String command, boolean isDownload) {
		try {
			currentFile = new File(localFilePath);
			ftDirection = isDownload ? FileTransferDirection.DOWNLOAD : FileTransferDirection.UPLOAD;
			ftIsText = command.toUpperCase().contains("ASCII") || command.toUpperCase().contains("CRLF");
			ftIsMessage = false;
			ftHadSuccessfulTransfer = false;
			transferredBytes = 0;
			// showProgressDialog(isDownload ? "Downloading..." : "Uploading...");
			// updateProgressDialog("Sending command...", "Bytes: 0");
			// FIX: Show dialog immediately with indeterminate state until Host responds
			// with Open
			showProgressDialog(isDownload ? "Downloading..." : "Uploading...", 0);
			updateProgressDialog("Sending command...", "Bytes: 0", 0);
			int homePos = -1, size = screenModel.getSize();
			for (int i = 0; i < size; i++) {
				if (screenModel.isFieldStart(i) && (screenModel.getAttr(i) & 0x20) == 0) {
					homePos = i + 1;
					break;
				}
			}
			if (homePos != -1)
				screenModel.setCursorPos(homePos % size);
			else
				tabToNextField();
			eraseToEndOfField();
			int cPos = screenModel.getCursorPos();
			for (char c : command.toCharArray()) {
				if (!screenModel.isProtected(cPos)) {
					screenModel.setChar(cPos, c);
					screenModel.setModified(cPos);
					cPos = (cPos + 1) % size;
					screenModel.setCursorPos(cPos);
				}
			}
			terminalPanel.repaint();
			sendAID(AID_ENTER);
		} catch (Exception e) {
			statusBar.setStatus("Transfer Error: " + e.getMessage());
			closeProgressDialog();
		}
	}

	public String buildIndFileCommand(boolean isDownload, boolean isTSO, String hostDataset, boolean isAscii,
			boolean useCrlf, boolean append, String recfm, String lrecl, String blksize, String space) {
		StringBuilder cmd = new StringBuilder();
		cmd.append("IND$FILE ");
		cmd.append(isDownload ? "GET " : "PUT ");
		cmd.append(hostDataset);
		if (!isTSO) {
			StringBuilder params = new StringBuilder();
			if (isAscii)
				params.append(" ASCII");
			if (useCrlf && isAscii)
				params.append(" CRLF");
			if (append)
				params.append(" APPEND");
			if (!isDownload && !recfm.isEmpty()) {
				params.append(" RECFM ").append(recfm);
				if (!lrecl.isEmpty())
					params.append(" LRECL ").append(lrecl);
			}
			if (params.length() > 0)
				cmd.append(" (").append(params.toString().trim()).append(")");
		} else {
			if (isAscii)
				cmd.append(" ASCII");
			if (useCrlf && isAscii)
				cmd.append(" CRLF");
			if (append)
				cmd.append(" APPEND");
			if (!isDownload) {
				if (!recfm.isEmpty())
					cmd.append(" RECFM(").append(recfm).append(")");
				if (!lrecl.isEmpty())
					cmd.append(" LRECL(").append(lrecl).append(")");
				if (!blksize.isEmpty())
					cmd.append(" BLKSIZE(").append(blksize).append(")");
				if (!space.isEmpty())
					cmd.append(" SPACE(").append(space).append(")");
			} else {
				if (isAscii && !recfm.isEmpty())
					cmd.append(" RECFM(").append(recfm).append(")");
			}
		}
		return cmd.toString();
	}
	
	private void handleDCOpen(byte[] data, int offset, int length) {
		String filename = "";
		// Parse the filename from the SF header (usually FT:DATA or FT:MSG)
		for (int i = offset; i < offset + length - 3; i++) {
			if (data[i] == 0x46 && data[i + 1] == 0x54 && data[i + 2] == 0x3A) { // "FT:"
				StringBuilder sb = new StringBuilder();
				for (int j = i; j < offset + length; j++) {
					byte b = data[j];
					if (b == 0x00 || b == (byte) 0xFF)
						break;
					sb.append((char) (b & 0xFF));
				}
				filename = sb.toString().trim();
				break;
			}
		}
		
		// If the filename we parsed isn't empty, update our state
		if (!filename.isEmpty()) {
			// If we are initiating a memory transfer, we might want to keep the requested name
			// but usually the Host sends 'FT:DATA' for the actual data phase.
			// Only update if it's not generic 'FT:DATA' or if we aren't in memory mode?
			// Actually, just updating it is fine for logging.
			if (!isMemoryTransfer) {
				currentFilename = filename;
			}
		}

		// Handle Messages (FT:MSG) - e.g. completion or error messages
		if (filename != null && filename.contains("FT:MSG")) {
			ftIsMessage = true;
			blockSequence = 0;
			sendDCOpenResponse(true, 0);
			return;
		}
		
		ftIsMessage = false;
		
		// Determine Direction: 0x01 at offset+14 means Host GETs (Upload), otherwise Host PUTs (Download)
		boolean hostWillGet = (offset + 14 < data.length) && (data[offset + 14] == 0x01);
		
		try {
			// --- FIX: Logic check for File vs Memory ---
			// Only complain about missing currentFile if we are NOT doing a memory transfer
			if (!isMemoryTransfer && currentFile == null) {
				closeProgressDialog();
				showMessageDialog("No file specified", "Transfer Error", true);
				sendDCOpenResponse(false, 0x1B00);
				return;
			}

			if (hostWillGet) {
				// --- UPLOAD (Host Reads) ---
				if (isMemoryTransfer) {
					// Memory Upload
					if (memoryUploadData == null) {
						sendDCOpenResponse(false, 0x1B00);
						return;
					}
					uploadStream = new ByteArrayInputStream(memoryUploadData);
					pendingCR = false;
					transferredBytes = 0;
					showProgressDialog("Uploading to Host...", memoryUploadData.length);
					updateProgressDialog("Sending data...", "Bytes: 0", 0);
				} else {
					// File Upload
					if (!currentFile.exists()) {
						closeProgressDialog();
						showMessageDialog("File not found", "Error", true);
						sendDCOpenResponse(false, 0x1B00);
						return;
					}
					uploadStream = new FileInputStream(currentFile);
					pendingCR = false;
					transferredBytes = 0;
					long totalSize = currentFile.length();
					showProgressDialog("Uploading...", (int) totalSize);
					updateProgressDialog("Sending data...", "Bytes: 0", 0);
				}
			} else {
				// --- DOWNLOAD (Host Writes) ---
				if (isMemoryTransfer) {
					// Memory Download
					memoryDownloadBuffer = new ByteArrayOutputStream();
					downloadStream = memoryDownloadBuffer; // Polymorphism at work
					transferredBytes = 0;
					showProgressDialog("Downloading from Host...", 0);
					updateProgressDialog("Receiving data...", "Bytes: 0", 0);
				} else {
					// File Download
					downloadStream = new FileOutputStream(currentFile);
					transferredBytes = 0;
					showProgressDialog("Downloading...", 0);
					updateProgressDialog("Receiving data...", "Bytes: 0", 0);
				}
			}
			
			ftState = FileTransferState.TRANSFER_IN_PROGRESS;
			blockSequence = 0;
			sendDCOpenResponse(true, 0);
			
		} catch (IOException e) {
			closeProgressDialog();
			showMessageDialog("File error: " + e.getMessage(), "Error", true);
			sendDCOpenResponse(false, 0x2000);
		}
	}

	private void handleDCOpenOld(byte[] data, int offset, int length) {
		String filename = "";
		for (int i = offset; i < offset + length - 3; i++) {
			if (data[i] == 0x46 && data[i + 1] == 0x54 && data[i + 2] == 0x3A) {
				StringBuilder sb = new StringBuilder();
				for (int j = i; j < offset + length; j++) {
					byte b = data[j];
					if (b == 0x00 || b == (byte) 0xFF)
						break;
					sb.append((char) (b & 0xFF));
				}
				filename = sb.toString().trim();
				break;
			}
		}
		currentFilename = filename;
		if (filename != null && filename.contains("FT:MSG")) {
			ftIsMessage = true;
			blockSequence = 0;
			sendDCOpenResponse(true, 0);
			return;
		}
		ftIsMessage = false;
		boolean hostWillGet = (offset + 14 < data.length) && (data[offset + 14] == 0x01);
		try {
			if (currentFile == null) {
				closeProgressDialog();
				showMessageDialog("No file specified", "Transfer Error", true);
				sendDCOpenResponse(false, 0x1B00);
				return;
			}
			if (hostWillGet) {
				// HOST WANTS TO READ (UPLOAD)
				if (isMemoryTransfer) {
					if (memoryUploadData == null) {
						sendDCOpenResponse(false, 0x1B00);
						return;
					}
					// Wrap byte array in stream
					uploadStream = new ByteArrayInputStream(memoryUploadData);
					transferredBytes = 0;
					showProgressDialog("Uploading to Host...", memoryUploadData.length);
				} else {
					if (!currentFile.exists()) {
						closeProgressDialog();
						showMessageDialog("File not found", "Error", true);
						sendDCOpenResponse(false, 0x1B00);
						return;
					}
					uploadStream = new FileInputStream(currentFile);
					pendingCR = false;
					transferredBytes = 0;
					// updateProgressDialog("Sending data...", "Bytes: 0");
					long totalSize = currentFile.length();

					// FIX: Initialize with Max Size
					showProgressDialog("Uploading...", (int) totalSize);
					updateProgressDialog("Sending data...", "Bytes: 0", 0);
				}
			} else {
				// HOST WANTS TO WRITE (DOWNLOAD)
				if (isMemoryTransfer) {
					memoryDownloadBuffer = new ByteArrayOutputStream();
					downloadStream = memoryDownloadBuffer; // Polymorphism! ByteArrayOutputStream is an OutputStream
					transferredBytes = 0;
					showProgressDialog("Downloading from Host...", 0);
				} else {
					downloadStream = new FileOutputStream(currentFile);
					transferredBytes = 0;
					// updateProgressDialog("Receiving data...", "Bytes: 0");
					// FIX: Initialize indeterminate
					showProgressDialog("Downloading...", 0);
					updateProgressDialog("Receiving data...", "Bytes: 0", 0);
				}
			}
			ftState = FileTransferState.TRANSFER_IN_PROGRESS;
			blockSequence = 0;
			sendDCOpenResponse(true, 0);
		} catch (IOException e) {
			closeProgressDialog();
			showMessageDialog("File error: " + e.getMessage(), "Error", true);
			sendDCOpenResponse(false, 0x2000);
		}
	}

	private void handleDCClose(byte[] data, int offset, int length) {
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
			closeProgressDialog();
			sendDCCloseResponse(false, 0x7100);
			return;
		}
		ftIsMessage = false;
		ftHadSuccessfulTransfer = false;
		blockSequence = 0;
		sendDCCloseResponse(true, 0);
	}

	private void handleDCSetCursor(byte[] data, int offset, int length) {
		int payloadOffset = offset + 4;
		if (payloadOffset < data.length && data[payloadOffset] == ORDER_SBA) {
			if (payloadOffset + 2 < data.length) {
				int newPos = decode3270Address(data[payloadOffset + 1], data[payloadOffset + 2]);
				if (newPos >= 0 && newPos < screenModel.getSize()) {
					screenModel.setCursorPos(newPos);
					terminalPanel.repaint();
					updateStatusBar();
				}
			}
		}
	}

	private void handleDCGet(byte[] data, int offset, int length) {
		if (uploadStream == null) {
			sendDCGetResponse(false, 0x2200, null, 0);
			return;
		}
		try {
			ByteArrayOutputStream blockBuffer = new ByteArrayOutputStream(2048);
			int ch;
			boolean eof = false;
			if (ftIsText) {
				while (blockBuffer.size() < 1900) {
					ch = uploadStream.read();
					if (ch == -1) {
						eof = true;
						break;
					}
					if (ch == '\n') {
						blockBuffer.write(0x0D);
						blockBuffer.write(0x0A);
					} else if (ch == '\r') {
						continue;
					} else
						blockBuffer.write(ch);
					if (blockBuffer.size() > 2000)
						break;
				}
			} else {
				byte[] chunk = new byte[2000];
				int count = uploadStream.read(chunk);
				if (count > 0)
					blockBuffer.write(chunk, 0, count);
				else
					eof = true;
			}
			byte[] dataToSend = blockBuffer.toByteArray();
			if (dataToSend.length > 0) {
				blockSequence++;
				transferredBytes += dataToSend.length;
				sendDCGetResponse(true, 0, dataToSend, dataToSend.length);
				// updateProgressDialog("Uploading block " + blockSequence, "Bytes: " +
				// transferredBytes);
				// FIX: Update with Value
				updateProgressDialog("Uploading block " + blockSequence, "Bytes: " + transferredBytes,
						(int) transferredBytes);
			} else if (eof) {
				uploadStream.close();
				uploadStream = null;
				sendDCGetResponse(false, 0x2200, null, 0);
			}
		} catch (IOException e) {
			sendDCGetResponse(false, 0x2000, null, 0);
			closeProgressDialog();
			showMessageDialog("Upload error", "Error", true);
		}
	}
	
	private void handleDCInsert(byte[] data, int offset, int length) {
		if (ftIsMessage) {
			if (blockSequence > 0) {
				blockSequence++;
				sendDCInsertResponse(true, 0);
				return;
			}
			int markerOffset = offset + 7;
			if (markerOffset + 2 >= data.length || data[markerOffset] != 0x61)
				return;
			int dataLen = (((data[markerOffset + 1] & 0xFF) << 8) | (data[markerOffset + 2] & 0xFF)) - 5;
			if (dataLen > 0 && markerOffset + 3 + dataLen <= data.length) {
				String message = new String(data, markerOffset + 3, dataLen);
				// Fix: Aggressively remove '$' from anywhere in the completion message
				message = message.replace('$', ' ').trim();
				blockSequence++;
				sendDCInsertResponse(true, 0);
				try {
					if (downloadStream != null)
						downloadStream.close();
					if (uploadStream != null)
						uploadStream.close();
				} catch (Exception e) {
				}
				closeProgressDialog();
				boolean isError = message.contains("Error") || message.contains("TRANS13");
				
				// --- FIX: Bypass Dialog for Memory Transfers ---
				if (isMemoryTransfer) {
					if (transferCallback != null) {
						if (isError) {
							transferCallback.onError(message);
						} else {
							if (ftDirection == FileTransferDirection.DOWNLOAD) {
								String res = "";
								if (memoryDownloadBuffer != null) {
									res = new String(memoryDownloadBuffer.toByteArray(), StandardCharsets.UTF_8);
								}
								//transferCallback.onDownloadComplete(res);
								// UPDATED: Return raw bytes directly. 
								// Fixes binary file corruption for Mainframe transfers too.
								transferCallback.onDownloadComplete(memoryDownloadBuffer.toByteArray());
							} else {
								transferCallback.onUploadComplete();
							}
						}
					}
					// Reset State
					isMemoryTransfer = false;
					memoryUploadData = null;
					memoryDownloadBuffer = null;
					transferCallback = null;
					ftState = FileTransferState.IDLE;
					return; // <--- RETURN HERE to skip showMessageDialog
				}
				// -----------------------------------------------

				showMessageDialog(message, isError ? "Transfer Error" : "Transfer Status", isError);
				ftState = FileTransferState.IDLE;
			}
			return;
		}
		
		// --- DATA BLOCK HANDLING (Bottom Half) ---
		if (downloadStream == null) {
			sendDCInsertResponse(false, 0x4700);
			return;
		}
		int markerOffset = offset + 7;
		if (markerOffset >= offset + length || data[markerOffset] != 0x61)
			return;
		try {
			int dataLen = (((data[markerOffset + 1] & 0xFF) << 8) | (data[markerOffset + 2] & 0xFF)) - 5;
			if (dataLen > 0) {
				byte[] fileData = new byte[dataLen];
				System.arraycopy(data, markerOffset + 3, fileData, 0, dataLen);
				if (ftIsText) {
					ByteArrayOutputStream clean = new ByteArrayOutputStream();
					for (int i = 0; i < dataLen; i++) {
						byte b = fileData[i];
						if (b == 0x0D)
							continue;
						if (b == 0x0A)
							clean.write('\n');
						else if (b != 0x1A)
							clean.write(b);
					}
					downloadStream.write(clean.toByteArray());
				} else
					downloadStream.write(fileData);
				transferredBytes += dataLen;
				blockSequence++;
				sendDCInsertResponse(true, 0);
				
				// Optional: Only update progress bar if we have a dialog open
				if (progressDialog != null && progressDialog.isVisible()) {
					updateProgressDialog("Downloading block " + blockSequence, "Bytes: " + transferredBytes,
							(int) transferredBytes);
				}
			} else
				sendDCInsertResponse(true, 0);
		} catch (IOException e) {
			closeProgressDialog();
			sendDCInsertResponse(false, 0x4700);
		}
	}
/*
	private void handleDCInsertOld(byte[] data, int offset, int length) {
		if (ftIsMessage) {
			if (blockSequence > 0) {
				blockSequence++;
				sendDCInsertResponse(true, 0);
				return;
			}
			int markerOffset = offset + 7;
			if (markerOffset + 2 >= data.length || data[markerOffset] != 0x61)
				return;
			int dataLen = (((data[markerOffset + 1] & 0xFF) << 8) | (data[markerOffset + 2] & 0xFF)) - 5;
			if (dataLen > 0 && markerOffset + 3 + dataLen <= data.length) {
				String message = new String(data, markerOffset + 3, dataLen);
				// if (message.endsWith("$"))
				// message = message.substring(0, message.length() - 1);
				// FIX: Aggressively remove '$' from anywhere in the completion message
				message = message.replace('$', ' ').trim();
				blockSequence++;
				sendDCInsertResponse(true, 0);
				try {
					if (downloadStream != null)
						downloadStream.close();
					if (uploadStream != null)
						uploadStream.close();
				} catch (Exception e) {
				}
				//
			    // NEW: Handle Memory Completion
			    if (isMemoryTransfer && transferCallback != null) {
			        if (message.contains("Error") || message.contains("TRANS13")) {
			            transferCallback.onError(message);
			        } else {
			            if (ftDirection == FileTransferDirection.DOWNLOAD) {
			                // Convert the buffer to String
			                String result = new String(memoryDownloadBuffer.toByteArray(), StandardCharsets.UTF_8);
			                // Windows/Mainframe newline cleanup if needed
			                transferCallback.onDownloadComplete(result); 
			            } else {
			                transferCallback.onUploadComplete();
			            }
			        }
			        // Reset flag
			        isMemoryTransfer = false;
			        memoryUploadData = null;
			        memoryDownloadBuffer = null;
			        transferCallback = null;
			    }
				//
				closeProgressDialog();
				boolean isError = message.contains("Error") || message.contains("TRANS13");
				showMessageDialog(message, isError ? "Transfer Error" : "Transfer Status", isError);
				ftState = FileTransferState.IDLE;
			}
			return;
		}
		if (downloadStream == null) {
			sendDCInsertResponse(false, 0x4700);
			return;
		}
		int markerOffset = offset + 7;
		if (markerOffset >= offset + length || data[markerOffset] != 0x61)
			return;
		try {
			int dataLen = (((data[markerOffset + 1] & 0xFF) << 8) | (data[markerOffset + 2] & 0xFF)) - 5;
			if (dataLen > 0) {
				byte[] fileData = new byte[dataLen];
				System.arraycopy(data, markerOffset + 3, fileData, 0, dataLen);
				if (ftIsText) {
					ByteArrayOutputStream clean = new ByteArrayOutputStream();
					for (int i = 0; i < dataLen; i++) {
						byte b = fileData[i];
						if (b == 0x0D)
							continue;
						if (b == 0x0A)
							clean.write('\n');
						else if (b != 0x1A)
							clean.write(b);
					}
					downloadStream.write(clean.toByteArray());
				} else
					downloadStream.write(fileData);
				transferredBytes += dataLen;
				blockSequence++;
				sendDCInsertResponse(true, 0);
				// updateProgressDialog("Downloading block " + blockSequence, "Bytes: " +
				// transferredBytes);
				// FIX: Pass 0 or current bytes to update (determinate bar won't move if
				// indeterminate, which is fine)
				updateProgressDialog("Downloading block " + blockSequence, "Bytes: " + transferredBytes,
						(int) transferredBytes);
			} else
				sendDCInsertResponse(true, 0);
		} catch (IOException e) {
			closeProgressDialog();
			sendDCInsertResponse(false, 0x4700);
		}
	}
*/
	private void sendDCOpenResponse(boolean success, int errorCode) {
		sendResp((byte) DC_OPEN, success, errorCode);
	}

	private void sendDCCloseResponse(boolean success, int errorCode) {
		sendResp((byte) DC_CLOSE, success, errorCode);
	}

	private void sendDCInsertResponse(boolean success, int errorCode) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			if (success) {
				baos.write(0x00);
				baos.write(0x0B);
				baos.write(SFID_DATA_CHAIN);
				baos.write(DC_INSERT);
				baos.write(0x05);
				baos.write(0x63);
				baos.write(0x06);
				baos.write((blockSequence >> 24) & 0xFF);
				baos.write((blockSequence >> 16) & 0xFF);
				baos.write((blockSequence >> 8) & 0xFF);
				baos.write(blockSequence & 0xFF);
			} else {
				baos.write(0x00);
				baos.write(0x09);
				baos.write(SFID_DATA_CHAIN);
				baos.write(DC_INSERT);
				baos.write(RESP_NEGATIVE);
				baos.write(0x69);
				baos.write(0x04);
				baos.write((errorCode >> 8) & 0xFF);
				baos.write(errorCode & 0xFF);
			}
			sendStructuredFieldResponse(baos.toByteArray());
		} catch (IOException e) {
		}
	}

	private void sendDCGetResponse(boolean success, int errorCode, byte[] data, int dataLen) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			if (success && data != null) {
				int dataLenField = dataLen + 5;
				int responseLen = 2 + 1 + 1 + 1 + 1 + 1 + 4 + 1 + 1 + 1 + 2 + dataLen;
				baos.write((responseLen >> 8) & 0xFF);
				baos.write(responseLen & 0xFF);
				baos.write(SFID_DATA_CHAIN);
				baos.write(DC_GET);
				baos.write(0x05);
				baos.write(0x63);
				baos.write(0x06);
				baos.write((blockSequence >> 24) & 0xFF);
				baos.write((blockSequence >> 16) & 0xFF);
				baos.write((blockSequence >> 8) & 0xFF);
				baos.write(blockSequence & 0xFF);
				baos.write(0xC0);
				baos.write(0x80);
				baos.write(0x61);
				baos.write((dataLenField >> 8) & 0xFF);
				baos.write(dataLenField & 0xFF);
				baos.write(data, 0, dataLen);
			} else {
				baos.write(0x00);
				baos.write(0x09);
				baos.write(SFID_DATA_CHAIN);
				baos.write(DC_GET);
				baos.write(RESP_NEGATIVE);
				baos.write(0x69);
				baos.write(0x04);
				baos.write((errorCode >> 8) & 0xFF);
				baos.write(errorCode & 0xFF);
			}
			sendStructuredFieldResponse(baos.toByteArray());
		} catch (IOException e) {
		}
	}

	private void sendResp(byte op, boolean success, int errorCode) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			if (success) {
				baos.write(0x00);
				baos.write(0x05);
				baos.write(SFID_DATA_CHAIN);
				baos.write(op);
				baos.write(RESP_POSITIVE);
			} else {
				baos.write(0x00);
				baos.write(0x09);
				baos.write(SFID_DATA_CHAIN);
				baos.write(op);
				baos.write(RESP_NEGATIVE);
				baos.write(0x69);
				baos.write(0x04);
				baos.write((errorCode >> 8) & 0xFF);
				baos.write(errorCode & 0xFF);
			}
			sendStructuredFieldResponse(baos.toByteArray());
		} catch (IOException e) {
		}
	}

	// =======================================================================
	// 7. UI HELPERS & DIALOGS
	// =======================================================================

	private void showProgressDialog(String title, int max) {
		if (progressDialog != null)
			progressDialog.dispose();
		progressDialog = new JDialog(getParentFrame(), title, false);
		progressDialog.setLayout(new BorderLayout());

		JPanel p = new JPanel(new GridLayout(0, 1));
		p.setBorder(new EmptyBorder(10, 10, 10, 10));

		progressLabel = new JLabel("Initializing...");
		transferProgressBar = new JProgressBar();

		if (max > 0) {
			transferProgressBar.setIndeterminate(false);
			transferProgressBar.setMinimum(0);
			transferProgressBar.setMaximum(max);
			transferProgressBar.setValue(0);
			transferProgressBar.setStringPainted(true);
		} else {
			transferProgressBar.setIndeterminate(true);
		}

		transferStatusLabel = new JLabel("");
		cancelTransferButton = new JButton("Cancel");
		cancelTransferButton.addActionListener(e -> {
			ftState = FileTransferState.IDLE;
			closeProgressDialog();
		});

		p.add(progressLabel);
		p.add(transferProgressBar);
		p.add(transferStatusLabel);
		p.add(cancelTransferButton);

		progressDialog.add(p);
		progressDialog.pack();
		progressDialog.setLocationRelativeTo(getParentFrame());
		progressDialog.setVisible(true);
	}

	private void updateProgressDialog(String msg, String status, int current) {
		SwingUtilities.invokeLater(() -> {
			if (progressLabel != null)
				progressLabel.setText(msg);
			if (transferStatusLabel != null)
				transferStatusLabel.setText(status);
			if (transferProgressBar != null && !transferProgressBar.isIndeterminate()) {
				transferProgressBar.setValue(current);
			}
		});
	}

	private void closeProgressDialog() {
		SwingUtilities.invokeLater(() -> {
			if (progressDialog != null)
				progressDialog.dispose();
		});
	}

	private void showMessageDialog(String msg, String title, boolean isError) {
		JOptionPane.showMessageDialog(getParentFrame(), msg, title,
				isError ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
	}

	private void updateStatusBar() {
		if (statusBar != null)
			statusBar.updatePosition(screenModel.getRows(), screenModel.getCols(), screenModel.getCursorPos());
	}

	// =======================================================================
	// 8. INPUT HANDLING
	// =======================================================================

	public void keyPressed(KeyEvent e) {
		int keyCode = e.getKeyCode();
		if (keyCode == KeyEvent.VK_CONTROL || keyCode == KeyEvent.VK_META || keyCode == KeyEvent.VK_ALT
				|| keyCode == KeyEvent.VK_SHIFT)
			return;
		boolean isModifier = e.isControlDown() || e.isMetaDown();
		if (isModifier) {
			if (e.getKeyCode() == KeyEvent.VK_C) {
				copySelection();
				return;
			}
			if (e.getKeyCode() == KeyEvent.VK_V) {
				pasteFromClipboard();
				return;
			}
			if (e.getKeyCode() == KeyEvent.VK_A) {
				selectAll();
				return;
			}
			if (e.getKeyCode() == KeyEvent.VK_EQUALS) {
				changeFontSize(2);
				return;
			}
			if (e.getKeyCode() == KeyEvent.VK_MINUS) {
				changeFontSize(-2);
				return;
			}
		}
		if (terminalPanel.hasSelection())
			terminalPanel.clearSelection();

		switch (keyCode) {
		case KeyEvent.VK_LEFT:
			moveCursorX(-1);
			return;
		case KeyEvent.VK_RIGHT:
			moveCursorX(1);
			return;
		case KeyEvent.VK_UP:
			moveCursorY(-1);
			return;
		case KeyEvent.VK_DOWN:
			moveCursorY(1);
			return;
		case KeyEvent.VK_HOME:
			screenModel.setCursorPos(0);
			terminalPanel.repaint();
			updateStatusBar();
			return;
		}

		if (keyboardLocked || !connected) {
			if (enableSound)
				Toolkit.getDefaultToolkit().beep();
			return;
		}

		KeyMapping mapping = keyMap.get(keyCode);
		if (mapping != null) {
			if (mapping.aid != null) {
				if ("EraseEOF".equals(mapping.description))
					eraseToEndOfField();
				else
					sendAID(mapping.aid);
				return;
			}
		}

		if (keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12) {
			boolean shift = e.isShiftDown();
			int fKey = keyCode - KeyEvent.VK_F1 + 1;
			byte aid;
			if (shift) {
				if (fKey <= 9)
					aid = (byte) (0xC1 + (fKey - 1));
				else if (fKey == 10)
					aid = (byte) 0x4A;
				else if (fKey == 11)
					aid = (byte) 0x4B;
				else
					aid = (byte) 0x4C;
			} else {
				if (fKey <= 9)
					aid = (byte) (AID_PF1 + (fKey - 1));
				else if (fKey == 10)
					aid = (byte) 0x7A;
				else if (fKey == 11)
					aid = (byte) 0x7B;
				else
					aid = (byte) 0x7C;
			}
			sendAID(aid);
			return;
		}

		switch (keyCode) {
		case KeyEvent.VK_ESCAPE:
			screenModel.clearScreen();
			sendAID(AID_CLEAR);
			return;
		case KeyEvent.VK_ENTER:
			if (terminalPanel.hasSelection()) {
				String txt = terminalPanel.getSelectedText();
				if (!txt.isEmpty()) {
					AIManager.getInstance().showChatDialog(getParentFrame(), txt);
					terminalPanel.clearSelection();
					return;
				}
			}
			sendAID(AID_ENTER);
			return;
		case KeyEvent.VK_INSERT:
			toggleInsertMode();
			return;
		case KeyEvent.VK_TAB:
			if (e.isShiftDown())
				tabToPreviousField();
			else
				tabToNextField();
			return;
		case KeyEvent.VK_BACK_SPACE:
			int cPos = screenModel.getCursorPos();
			if (!screenModel.isProtected(cPos)) {
				moveCursorX(-1);
				cPos = screenModel.getCursorPos();
				if (!screenModel.isProtected(cPos)) {
					screenModel.setChar(cPos, ' ');
					screenModel.setModified(cPos);
					terminalPanel.repaint();
				}
			}
			return;
		}
	}

	public void keyTyped(KeyEvent e) {
		if (keyboardLocked || !connected)
			return;
		char c = e.getKeyChar();
		KeyMapping mapping = keyMap.get(e.getKeyCode());
		if (mapping != null && mapping.aid == null)
			c = mapping.character;
		c = inputCharMap.getOrDefault(c, c);
		if (c < 32 || c > 126)
			return;
		int cPos = screenModel.getCursorPos();
		if (!screenModel.isProtected(cPos)) {
			if (insertMode) {
				int fieldStart = screenModel.findFieldStart(cPos);
				int end = screenModel.findNextField(fieldStart);
				int last = end - 1;
				if (screenModel.isFieldStart(last))
					last--;
				char lc = screenModel.getChar(last);
				if (lc != '\0' && lc != ' ') {
					if (enableSound)
						Toolkit.getDefaultToolkit().beep();
					return;
				}
				for (int i = last; i > cPos; i--) {
					if (!screenModel.isFieldStart(i) && !screenModel.isFieldStart(i - 1))
						screenModel.setChar(i, screenModel.getChar(i - 1));
				}
			}
			screenModel.setChar(cPos, c);
			screenModel.setModified(cPos);
			moveCursorX(1);
			if (autoAdvance && screenModel.isFieldStart(screenModel.getCursorPos()))
				tabToNextField();
			terminalPanel.repaint();
		} else if (enableSound)
			Toolkit.getDefaultToolkit().beep();
	}

	public void keyReleased(KeyEvent e) {
	}

	private void moveCursorX(int delta) {
		int cols = screenModel.getCols();
		int currentRow = screenModel.getCursorPos() / cols;
		int currentCol = screenModel.getCursorPos() % cols;
		int newCol = ((currentCol + delta) % cols + cols) % cols;
		screenModel.setCursorPos(currentRow * cols + newCol);
		terminalPanel.repaint();
		updateStatusBar();
	}

	private void moveCursorY(int delta) {
		int rows = screenModel.getRows();
		int cols = screenModel.getCols();
		int currentRow = screenModel.getCursorPos() / cols;
		int currentCol = screenModel.getCursorPos() % cols;
		int newRow = ((currentRow + delta) % rows + rows) % rows;
		screenModel.setCursorPos(newRow * cols + currentCol);
		terminalPanel.repaint();
		updateStatusBar();
	}

	public void tabToNextField() {
		int start = screenModel.getCursorPos();
		int p = start;
		int sz = screenModel.getSize();
		do {
			p = (p + 1) % sz;
			if (screenModel.isFieldStart(p) && (screenModel.getAttr(p) & 0x20) == 0) {
				screenModel.setCursorPos((p + 1) % sz);
				terminalPanel.repaint();
				updateStatusBar();
				return;
			}
		} while (p != start);
		screenModel.setCursorPos(start);
		terminalPanel.repaint();
		updateStatusBar();
	}

	public void tabToPreviousField() {
		int start = screenModel.getCursorPos();
		int p = start;
		int sz = screenModel.getSize();
		do {
			p = (p - 1 + sz) % sz;
			if (screenModel.isFieldStart(p) && (screenModel.getAttr(p) & 0x20) == 0) {
				screenModel.setCursorPos((p + 1) % sz);
				terminalPanel.repaint();
				updateStatusBar();
				return;
			}
		} while (p != start);
		screenModel.setCursorPos(start);
		terminalPanel.repaint();
		updateStatusBar();
	}

	public void eraseToEndOfField() {
		int p = screenModel.getCursorPos();
		if (screenModel.isProtected(p))
			return;
		int end = screenModel.findNextField(p);
		int sz = screenModel.getSize();
		for (int i = p; i != end && !screenModel.isFieldStart(i); i = (i + 1) % sz)
			screenModel.setChar(i, '\0');
		screenModel.setModified(p);
		terminalPanel.repaint();
		updateStatusBar();
	}

	public void copySelection() {
		String txt = terminalPanel.getSelectedText();
		if (txt.isEmpty())
			return;
		try {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(txt), null);
			statusBar.setStatus("Copied to clipboard");
			terminalPanel.clearSelection();
		} catch (Exception e) {
		}
	}

	public void pasteFromClipboard() {
		try {
			String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
			if (text == null)
				return;
			for (char c : text.toCharArray()) {
				if (c == '\n') {
					tabToNextField();
					continue;
				}
				if (c < 32)
					continue;
				int p = screenModel.getCursorPos();
				if (!screenModel.isProtected(p)) {
					screenModel.setChar(p, c);
					screenModel.setModified(p);
					moveCursorX(1);
				} else
					tabToNextField();
			}
			terminalPanel.repaint();
		} catch (Exception e) {
		}
	}

	public void selectAll() {
		terminalPanel.selectAll();
	}

	public String getSelectedText() {
		return terminalPanel.getSelectedText();
	}

	private void clearSelection() {
		terminalPanel.clearSelection();
	}

	// -----------------------------------------------------------------------
	// NEW: Smart Window Resizing Logic
	// -----------------------------------------------------------------------
	// NEW: Setter for the Emulator to toggle modes
	public void setAutoFitOnResize(boolean b) {
		this.autoFitOnResize = b;
		// If switching TO AutoFit (Tiles), force a fit immediately
		if (b && scrollPane != null && scrollPane.getWidth() > 0) {
			terminalPanel.fitToSize(scrollPane.getWidth(), scrollPane.getHeight());
		}
	}

	// UPDATED: Ensure this is public so TN3270Emulator can call it
	/**
	 * Snaps the Window to fit the Terminal Content.
	 * 
	 * REGRESSION NOTE: LOOP PREVENTION & SCREEN BOUNDS Used when the User changes
	 * Font Size manually. We resize the Container to fit the Font, rather than
	 * resizing the Font to fit the Container.
	 * 
	 * Critical Logic: 1. isProgrammaticResize: We set this flag to TRUE before
	 * calling window.pack(). This prevents our 'componentResized' listener from
	 * firing and triggering the Auto-Fit logic, which would calculate a new font
	 * and undo the user's selection. 2. Screen Bounds: We clamp the new size to the
	 * user's screen resolution to ensure the window doesn't grow beyond the monitor
	 * edges.
	 * 
	 * Regression: Ghost Scrollbars on Session Start/Clear
     * Issue: Vertical and horizontal scrollbars were appearing immediately after 
     * a new session connected or after a CLEAR key was pressed, even though the window 
     * appeared large enough to fit the content.
     * Cause: A race condition between Java Swing's layout manager and the High-DPI font 
     * rendering. The pack() method calculated the window size using integer math, while 
     * the text rendering used fractional metrics. This often resulted in the content being 
     * 1-2 pixels larger than the calculated viewport, triggering the JScrollPane to display 
     * scrollbars.
     * Fix: Updated TN3270Session.snapWindow() to temporarily disable scrollbar policies during 
     * the layout calculation and added a 4-pixel safety buffer to the final window dimensions 
     * to absorb rounding errors. Added explicit validate() calls during session initialization 
     * to ensure the layout is current before sizing.
	 */
	public void snapWindow() {
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window == null || !window.isDisplayable()) return;

        isProgrammaticResize = true;
        try {
            // 1. SAVE & DISABLE POLICIES
            // We force scrollbars to NEVER so pack() calculates the tightest possible 
            // window size around the content without reserving space for bars.
            int vPolicy = scrollPane.getVerticalScrollBarPolicy();
            int hPolicy = scrollPane.getHorizontalScrollBarPolicy();
            
            scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
            scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            
            // 2. PACK (Calculate size based purely on content)
            window.pack();
            
            // 3. RESTORE POLICIES
            scrollPane.setVerticalScrollBarPolicy(vPolicy);
            scrollPane.setHorizontalScrollBarPolicy(hPolicy);

            // 4. ADD SAFETY BUFFER & CLAMP
            // We add +4 pixels (width/height) to handle High-DPI font rounding errors.
            // This ensures the viewport is slightly larger than the content, 
            // preventing "AS_NEEDED" scrollbars from appearing.
            int w = window.getWidth() + 4;
            int h = window.getHeight() + 4;

            GraphicsConfiguration gc = window.getGraphicsConfiguration();
            Rectangle screenBounds = gc.getBounds();
            Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);

            int maxWidth = screenBounds.width - screenInsets.left - screenInsets.right;
            int maxHeight = screenBounds.height - screenInsets.top - screenInsets.bottom;

            w = Math.min(w, maxWidth);
            h = Math.min(h, maxHeight);

            // 5. APPLY
            if (w != window.getWidth() || h != window.getHeight()) {
                window.setSize(w, h);
            }
        } catch (Exception e) {
            // Ignore race conditions during init
        } finally {
            SwingUtilities.invokeLater(() -> isProgrammaticResize = false);
        }
    }
	
	public void snapWindowPrior() {
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window == null || !window.isDisplayable()) return;

        isProgrammaticResize = true;
        try {
            // 1. Pack to preferred size (Calculated by Layout Manager)
            window.pack(); 

            // 2. CRITICAL FIX: Add a tiny buffer (2px) to width/height.
            // This absorbs high-DPI font rounding errors that cause 
            // the JScrollPane to think it needs scrollbars.
            int w = window.getWidth() + 2;
            int h = window.getHeight() + 2;

            // 3. Clamp to Screen Bounds (Existing logic)
            GraphicsConfiguration gc = window.getGraphicsConfiguration();
            Rectangle screenBounds = gc.getBounds();
            Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);

            int maxWidth = screenBounds.width - screenInsets.left - screenInsets.right;
            int maxHeight = screenBounds.height - screenInsets.top - screenInsets.bottom;

            w = Math.min(w, maxWidth);
            h = Math.min(h, maxHeight);

            // 4. Apply Size
            window.setSize(w, h);
            
        } catch (Exception e) {
            // Ignore race conditions during init
        } finally {
            SwingUtilities.invokeLater(() -> isProgrammaticResize = false);
        }
    }
	
	public void snapWindowOld() {
		Window window = SwingUtilities.getWindowAncestor(this);
		if (window == null || !window.isDisplayable())
			return;

		isProgrammaticResize = true;
		try {
			window.pack();

			GraphicsConfiguration gc = window.getGraphicsConfiguration();
			Rectangle screenBounds = gc.getBounds();
			Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);

			int maxWidth = screenBounds.width - screenInsets.left - screenInsets.right;
			int maxHeight = screenBounds.height - screenInsets.top - screenInsets.bottom;

			int newW = Math.min(window.getWidth(), maxWidth);
			int newH = Math.min(window.getHeight(), maxHeight);

			if (newW != window.getWidth() || newH != window.getHeight()) {
				window.setSize(newW, newH);
			}
		} catch (Exception e) {
			// Ignore race conditions during init
		} finally {
			SwingUtilities.invokeLater(() -> isProgrammaticResize = false);
		}
	}

	// --- SMART WINDOW RESIZE LOGIC ---
	private void changeFontSize(int delta) {
		if (terminalPanel == null)
			return;

		int currentSize = terminalPanel.getTerminalFont().getSize();
		int newSize = currentSize + delta;

		if (newSize < 6)
			newSize = 6;
		if (newSize > 72)
			newSize = 72;

		if (newSize != currentSize) {
			// 1. TEMPORARILY REMOVE LISTENER
			// This prevents the "Auto-Fit" logic from fighting our manual change
			// when the window size snaps or clamps to screen bounds.
			removeComponentListener(resizeListener);

			try {
				// 2. Set Font
				terminalPanel.setFont(new Font("Monospaced", Font.PLAIN, newSize));
				statusBar.setStatus("Font size set to " + newSize + "pt");

				// 3. Snap Window to Content
				/*
				 * Window window = SwingUtilities.getWindowAncestor(this); if (window != null &&
				 * window.isDisplayable()) { window.pack();
				 * 
				 * // Constrain to Screen GraphicsConfiguration gc =
				 * window.getGraphicsConfiguration(); Rectangle bounds = gc.getBounds(); Insets
				 * insets = Toolkit.getDefaultToolkit().getScreenInsets(gc); int maxW =
				 * bounds.width - insets.left - insets.right; int maxH = bounds.height -
				 * insets.top - insets.bottom;
				 * 
				 * int w = Math.min(window.getWidth(), maxW); int h =
				 * Math.min(window.getHeight(), maxH);
				 * 
				 * if (w != window.getWidth() || h != window.getHeight()) { window.setSize(w,
				 * h); } }
				 */
				SwingUtilities.invokeLater(this::snapWindow);
			} finally {
				// 4. RESTORE LISTENER (Deferred)
				// We use invokeLater to ensure all resize events from the 'pack/setSize'
				// have flushed through the system before we start listening again.
				SwingUtilities.invokeLater(() -> addComponentListener(resizeListener));
			}
		}
	}

	public void toggleInsertMode() {
		insertMode = !insertMode;
		if (insertMode) {
			terminalPanel.setCursorStyle(TerminalPanel.CursorStyle.UNDERSCORE);
			statusBar.setStatus("Insert Mode");
		} else {
			terminalPanel.setCursorStyle(TerminalPanel.CursorStyle.BLOCK);
			statusBar.setStatus("Overwrite Mode");
		}
		terminalPanel.repaint();
	}
	
	public void showFontSizeDialog() {
		JDialog dialog = new JDialog(getParentFrame(), "Terminal Font Size", true);
		dialog.setLayout(new BorderLayout(10, 10));
		JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
		mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JLabel lbl = new JLabel("Size (pt):");
		int currentSize = terminalPanel.getTerminalFont().getSize();
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(currentSize, 6, 72, 1));
		JSlider slider = new JSlider(6, 48, currentSize);
		spinner.addChangeListener(e -> slider.setValue((Integer) spinner.getValue()));
		slider.addChangeListener(e -> spinner.setValue(slider.getValue()));
		controls.add(lbl);
		controls.add(spinner);
		controls.add(slider);
		JTextArea preview = new JTextArea("  Local VM  \n  READY...  ");
		preview.setFont(terminalPanel.getTerminalFont());
		preview.setBackground(screenModel.getScreenBackground());
		preview.setForeground(screenModel.getDefaultForeground());
		preview.setEditable(false);
		preview.setBorder(new LineBorder(Color.GRAY));
		slider.addChangeListener(e -> {
			int s = slider.getValue();
			preview.setFont(new Font("Monospaced", Font.PLAIN, s));
		});
		mainPanel.add(controls, BorderLayout.NORTH);
		mainPanel.add(new JScrollPane(preview), BorderLayout.CENTER);
		JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton ok = new JButton("Apply");
		JButton cancel = new JButton("Cancel");
		
		// --- FIX: Wrap logic in try-finally to ensure dialog closes ---
		ok.addActionListener(e -> {
			try {
				int newSize = (Integer) spinner.getValue();
				int delta = newSize - terminalPanel.getTerminalFont().getSize();
				changeFontSize(delta);
			} catch (Exception ex) {
				ex.printStackTrace(); // Log the hidden error
			} finally {
				dialog.dispose();
			}
		});
		
		cancel.addActionListener(e -> dialog.dispose());
		btnPanel.add(cancel);
		btnPanel.add(ok);
		dialog.add(mainPanel, BorderLayout.CENTER);
		dialog.add(btnPanel, BorderLayout.SOUTH);
		dialog.pack();
		dialog.setSize(400, 300);
		dialog.setLocationRelativeTo(getParentFrame());
		dialog.setVisible(true);
	}
	
	public void showColorSchemeDialog() {
		JDialog dialog = new JDialog(getParentFrame(), "Color Settings", true);
		dialog.setLayout(new BorderLayout());

		JPanel mainPanel = new JPanel(new GridBagLayout());
		mainPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.insets = new Insets(5, 5, 5, 5);

		// Presets
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.gridwidth = 2;
		mainPanel.add(new JLabel("Load Preset Scheme:"), gbc);

		gbc.gridy = 1;
		JComboBox<String> schemeBox = new JComboBox<>();
		schemeBox.addItem("Green on Black (Classic)");
		schemeBox.addItem("White on Black");
		schemeBox.addItem("Amber on Black");
		schemeBox.addItem("Green on Dark Green");
		schemeBox.addItem("IBM 3270 Blue");
		schemeBox.addItem("Solarized Dark");

		JButton applyPreset = new JButton("Apply");
		
		// --- FIX: Wrap logic in try-finally to ensure dialog closes ---
		applyPreset.addActionListener(e -> {
			try {
				applyColorScheme((String) schemeBox.getSelectedItem());
			} catch (Exception ex) {
				ex.printStackTrace();
			} finally {
				dialog.dispose();
			}
		});

		JPanel presetPanel = new JPanel(new BorderLayout(5, 0));
		presetPanel.add(schemeBox, BorderLayout.CENTER);
		presetPanel.add(applyPreset, BorderLayout.EAST);
		mainPanel.add(presetPanel, gbc);

		// RESTORED: Customize Section
		gbc.gridy = 2;
		mainPanel.add(new JSeparator(), gbc);
		gbc.gridy = 3;
		mainPanel.add(new JLabel("Customize Current Colors:"), gbc);

		gbc.gridy = 4;
		gbc.gridwidth = 1;
		JButton btnBg = new JButton("Background...");
		btnBg.addActionListener(e -> {
			Color newC = JColorChooser.showDialog(dialog, "Screen Background", screenModel.getScreenBackground());
			if (newC != null) {
				screenModel.setScreenBackground(newC);
				terminalPanel.setBackground(newC);
				if (scrollPane != null && scrollPane.getViewport() != null)
					scrollPane.getViewport().setBackground(newC);
				terminalPanel.repaint();
			}
		});
		mainPanel.add(btnBg, gbc);

		gbc.gridx = 1;
		JButton btnFg = new JButton("Default Text...");
		btnFg.addActionListener(e -> {
			Color newC = JColorChooser.showDialog(dialog, "Default Text", screenModel.getDefaultForeground());
			if (newC != null) {
				screenModel.setDefaultForeground(newC);
				terminalPanel.setForeground(newC);
				terminalPanel.repaint();
			}
		});
		mainPanel.add(btnFg, gbc);

		gbc.gridx = 0;
		gbc.gridy = 5;
		JButton btnCur = new JButton("Cursor Color...");
		btnCur.addActionListener(e -> {
			Color newC = JColorChooser.showDialog(dialog, "Cursor Color", screenModel.getCursorColor());
			if (newC != null) {
				screenModel.setCursorColor(newC);
				terminalPanel.repaint();
			}
		});
		mainPanel.add(btnCur, gbc);

		dialog.add(mainPanel, BorderLayout.CENTER);

		JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton close = new JButton("Close");
		close.addActionListener(e -> dialog.dispose());
		footer.add(close);
		dialog.add(footer, BorderLayout.SOUTH);

		dialog.pack();
		dialog.setLocationRelativeTo(getParentFrame());
		dialog.setVisible(true);
	}

	public void showKeyboardMappingDialog() {
		new KeyboardSettingsDialog(getParentFrame(), this).setVisible(true);
	}

	public void showTerminalSettingsDialog() {
		JDialog dialog = new JDialog(getParentFrame(), "Terminal Settings", true);
		dialog.setLayout(new BorderLayout());
		JPanel p = new JPanel(new GridLayout(0, 2, 10, 10));
		p.setBorder(new EmptyBorder(10, 10, 10, 10));
		p.add(new JLabel("Cursor Style:"));
		JComboBox<String> cursorBox = new JComboBox<>(new String[] { "Block", "Underscore", "I-Beam" });
		p.add(cursorBox);
		JCheckBox blink = new JCheckBox("Cursor Blink", true);
		JCheckBox sound = new JCheckBox("Sound", enableSound);

		// NEW CHECKBOX
		JCheckBox crosshair = new JCheckBox("Crosshair", terminalPanel.isShowCrosshair());

		p.add(blink);
		p.add(sound);
		p.add(crosshair); // Add to panel

		JButton ok = new JButton("OK");
		ok.addActionListener(e -> {
			String style = (String) cursorBox.getSelectedItem();
			if (style.equals("Block"))
				terminalPanel.setCursorStyle(TerminalPanel.CursorStyle.BLOCK);
			else if (style.equals("Underscore"))
				terminalPanel.setCursorStyle(TerminalPanel.CursorStyle.UNDERSCORE);
			else
				terminalPanel.setCursorStyle(TerminalPanel.CursorStyle.I_BEAM);
			this.enableSound = sound.isSelected();

			// APPLY CROSSHAIR
			terminalPanel.setShowCrosshair(crosshair.isSelected());

			dialog.dispose();
		});
		dialog.add(p, BorderLayout.CENTER);
		dialog.add(ok, BorderLayout.SOUTH);
		dialog.pack();
		dialog.setLocationRelativeTo(getParentFrame());
		dialog.setVisible(true);
	}

	public int getFontSize() {
		return terminalPanel.getTerminalFont().getSize();
	}

	public void setFontSize(int size) {
		if (terminalPanel == null)
			return;
		// Re-use logic to ensure safety
		changeFontSize(size - terminalPanel.getTerminalFont().getSize());
	}

	public void setFontSizeNoResize(int size) {
		if (terminalPanel == null)
			return;
		// Update the font and status, but DO NOT call snapWindow()
		// We let the parent Emulator handle the packing/resizing.
		terminalPanel.setFont(new Font("Monospaced", Font.PLAIN, size));
		statusBar.setStatus("Font size set to " + size + "pt");
	}

	public void setCursorStyle(CursorStyle style) {
		terminalPanel.setCursorStyle(style == CursorStyle.BLOCK ? TerminalPanel.CursorStyle.BLOCK
				: style == CursorStyle.UNDERSCORE ? TerminalPanel.CursorStyle.UNDERSCORE
						: TerminalPanel.CursorStyle.I_BEAM);
	}

	public void showAIChatDialog(Frame owner, String text) {
		AIManager.getInstance().showChatDialog(owner, text);
	}

	/**
	 * Uploads a String (e.g., AI Code Block) directly to a Host Dataset.
	 */
	/**
	 * Uploads a String (e.g., AI Code Block) directly to a Host Dataset.
	 * 
	 * FIX: Prioritize Linux detection. If the filename looks like a Linux path,
	 * force HostType.LINUX even if the UI dialog passed TSO/CMS.
	 */
	/**
	 * Uploads a String (e.g., AI Code Block) directly to a Host Dataset.
	 * 
	 * FIX:
	 * 1. Prioritizes HostType.LINUX if the filename looks like a path.
	 * 2. Passes the callback into 'uploadTextToLinuxConsole' so the Success dialog
	 *    waits for the thread to actually finish.
	 */
	public void uploadTextToHost(String textContent, String hostDataset, HostType ignoredType, MemoryTransferCallback callback) {
		// 1. Intelligent Detection
		HostType inferred = inferHostType(hostDataset);
		
		// Trust the filename over the dropdown if it looks like Linux
		if (inferred == HostType.LINUX) {
			this.hostType = HostType.LINUX;
		} else if (ignoredType == null) {
			this.hostType = inferred;
		} else {
			this.hostType = ignoredType;
		}
		
		this.isMemoryTransfer = true;
		this.transferCallback = callback;
		this.memoryUploadData = textContent.getBytes(StandardCharsets.UTF_8);
		
		// BRANCH: LINUX CONSOLE PASTE
		if (this.hostType == HostType.LINUX) {
			// Pass raw bytes and the callback to the thread
			uploadTextToLinuxConsole(this.memoryUploadData, hostDataset, callback);
			return; 
		}

		// BRANCH: MAINFRAME IND$FILE
		this.currentFilename = "AI_Generated_Content"; 
		
		// Smart LRECL Calculation
		int maxLineLen = 80;
		if (textContent != null) {
			String[] lines = textContent.split("\\r?\\n");
			for (String line : lines) {
				if (line.length() > maxLineLen) maxLineLen = line.length();
			}
		}
		int lrecl = Math.max(255, maxLineLen);
		
		String cmd = buildIndFileCommand(
				false, 
				this.hostType == HostType.TSO, 
				hostDataset, 
				true, true, false, "V", String.valueOf(lrecl), "", ""
		);
		
		showProgressDialog("Uploading to Host (" + this.hostType + ")...", memoryUploadData.length);
		sendTransferCommand(cmd);
	}
	
	/**
	 * Downloads a Host Dataset directly to a String for the AI.
	 */
	/**
	 * Downloads text from Linux via Screen Scraping (Corrected).
	 * 
	 * FIXES:
	 * 1. BACKWARD SCAN LIMIT: Explicitly stops scanning at the PREVIOUS marker or 
	 *    the script invocation command. This prevents reading duplicate data from 
	 *    previous chunks (the primary cause of Base64 decode errors).
	 * 2. GARBAGE FILTER: Filters out "ACK" responses and short lines (<4 chars) 
	 *    to avoid corrupting the Base64 buffer.
	 * 3. DYNAMIC BATCHING: Uses smart clearing to optimize speed.
	 */
	
	public void downloadTextFromHost(String hostDataset, HostType ignoredType, MemoryTransferCallback callback) {
	    // --- AUTO-DETECT LOGIC ---
		/*
		// We prioritize the passed type if specific, otherwise infer
		if (ignoredType == null) {
			this.hostType = inferHostType(hostDataset);
		} else {
			this.hostType = ignoredType;
		}
		*/
		// 1. Intelligent Detection
		HostType inferred = inferHostType(hostDataset);

		// If it looks like Linux, trust the filename over the dropdown
		if (inferred == HostType.LINUX) {
			this.hostType = HostType.LINUX;
		} else if (ignoredType == null) {
			this.hostType = inferred;
		} else {
			this.hostType = ignoredType;
		}

		// BRANCH: LINUX CONSOLE SCRAPE
		if (this.hostType == HostType.LINUX) {
			downloadTextFromLinuxConsole(hostDataset, callback);
			return;
		}
		
		// BRANCH: MAINFRAME IND$FILE
	    this.isMemoryTransfer = true;
	    this.transferCallback = callback;
	    this.memoryDownloadBuffer = new ByteArrayOutputStream();
	    
	    // --- AUTO-DETECT LOGIC ---
	    this.hostType = inferHostType(hostDataset);
	    
	    this.currentFilename = hostDataset;

	    // Build command using the DETECTED type
	    String cmd = buildIndFileCommand(true, this.hostType == HostType.TSO, hostDataset, true, true, false, "", "", "", "");
	    
	    showProgressDialog("Downloading from Host (" + this.hostType + ")...", 0);
	    sendTransferCommand(cmd);
	}
	
	public void downloadTextFromHostOld(String hostDataset, HostType type, MemoryTransferCallback callback) {
		this.isMemoryTransfer = true;
		this.transferCallback = callback;
		this.memoryDownloadBuffer = new ByteArrayOutputStream();
		this.hostType = type;
		this.currentFilename = hostDataset;

		// Build command: IND$FILE GET dataset ASCII CRLF
		String cmd = buildIndFileCommand(true, type == HostType.TSO, hostDataset, true, true, false, "", "", "", "");

		showProgressDialog("Downloading from Host...", 0);
		sendTransferCommand(cmd);
	}

	// Refactored helper to send the command string (extracted from
	// initiateFileTransfer)
	private void sendTransferCommand(String command) {
		try {
			// ... (Same logic as initiateFileTransfer: clear field, type command, enter)
			// ...
			int homePos = -1, size = screenModel.getSize();
			for (int i = 0; i < size; i++) {
				if (screenModel.isFieldStart(i) && (screenModel.getAttr(i) & 0x20) == 0) {
					homePos = i + 1;
					break;
				}
			}
			if (homePos != -1)
				screenModel.setCursorPos(homePos % size);
			else
				tabToNextField();

			eraseToEndOfField();

			int cPos = screenModel.getCursorPos();
			for (char c : command.toCharArray()) {
				if (!screenModel.isProtected(cPos)) {
					screenModel.setChar(cPos, c);
					screenModel.setModified(cPos);
					cPos = (cPos + 1) % size;
					screenModel.setCursorPos(cPos);
				}
			}
			terminalPanel.repaint();
			sendAID(AID_ENTER);
		} catch (Exception e) {
			if (transferCallback != null)
				transferCallback.onError(e.getMessage());
		}
	}
	
	public static HostType inferHostType(String filename) {
	    if (filename == null) return HostType.TSO; // Default safety
	    
	    String clean = filename.trim();
	    
		// Rule 0: Linux Indicators
		// - Contains '/' (Absolute path or relative path like 'dir/file')
		// - Starts with './' (Explicit relative path)
		// - Starts with '.' followed by letters (Hidden file like '.bashrc', distinct from TSO '.qualifier')
		if (clean.contains("/") || clean.startsWith("./") || (clean.startsWith(".") && !clean.contains("..") && !clean.toUpperCase().equals(clean))) {
            // The case check !clean.toUpperCase().equals(clean) is a weak heuristic 
            // implying if it has lowercase, it's likely Linux, but '/' is the strong signal.
            return HostType.LINUX;
		}
	    
	    // Rule 1: CMS uses spaces to separate FN FT FM (e.g., "PROFILE EXEC A")
	    // TSO datasets typically do not contain spaces (unless enclosed in quotes, which is rare for IND$FILE input)
	    if (clean.contains(" ")) {
	        return HostType.CMS;
	    }
	    
	    // Rule 2: TSO often uses parentheses for PDS members (e.g., "MY.LIB(MEMBER)")
	    if (clean.contains("(") && clean.contains(")")) {
	        return HostType.TSO;
	    }
	    
	    // Rule 3: TSO uses dots for qualifiers (e.g., "A.B.C"). 
	    // CMS *can* use dots in filenames, but usually they are accompanied by spaces for the filetype.
	    // If it's just "A.B.C" with no spaces, it's almost certainly TSO.
	    if (clean.contains(".")) {
	        return HostType.TSO;
	    }
	    
	    // Fallback: Single word (e.g. "MYFILE").
	    // On TSO, this implies "userid.MYFILE".
	    // On CMS, a single word is usually invalid for IND$FILE (needs Filetype).
	    // Therefore, TSO is the safer default.
	    return HostType.TSO;
	}
	
	private boolean isStatusNotAccepted() {
		// CP Status is usually in the last 20-30 characters of the screen
		int size = screenModel.getSize();
		int checkLen = 40;
		int start = Math.max(0, size - checkLen);
		
		String bottomText = screenModel.getString(start, checkLen);
		return bottomText.contains("NOT ACCEPTED");
	}

		/**
		 * Uploads text to Linux via Base64 + Batched ACK Flow Control.
		 * 
		 * OPTIMIZATION:
		 * 1. The shell function 'rx' now maintains a line counter 'i' and returns "ACK$i".
		 * 2. Java waits for the specific numbered ACK (e.g., "ACK1", "ACK5") to ensure synchronization.
		 * 3. We only clear the screen every 10 lines, rather than every line, drastically reducing overhead.
		 */
	/**
	 * Uploads text to Linux via Base64 + ACK Flow Control.
	 * 
	 * OPTIMIZATIONS:
	 * 1. Added Status Bar updates.
	 */
	/**
	 * Uploads text to Linux via Base64 + ACK Flow Control (Dynamic Screen Management).
	 * 
	 * OPTIMIZATIONS:
	 * 1. DYNAMIC CLEARING: Inspects ScreenModel cursor position. Only clears the screen
	 *    when we are within 5 lines of the bottom. This automatically adapts to
	 *    screen size (24 vs 43 lines) and 'stty echo' status.
	 * 2. ECHO SUPPRESSION: Sends 'stty -echo' to maximize throughput.
	 */
	/**
	 * Uploads data to Linux via Base64 + ACK Flow Control.
	 * 
	 * FIXES:
	 * 1. Accepts 'byte[]' to support binary files.
	 * 2. Accepts 'MemoryTransferCallback' to notify completion correctly.
	 * 3. Uses 'stty -echo' + 10-line batches for speed and stability.
	 */
	/**
	 * Uploads data to Linux via Base64 + ACK Flow Control.
	 * 
	 * FIXES:
	 * 1. URL-SAFE BASE64: Uses '-' and '_' instead of '+' and '/' to avoid EBCDIC 
	 *    translation issues that corrupt binary streams.
	 * 2. DECODE PIPELINE: Uses 'tr' to restore standard Base64 before decoding.
	 * 3. COMPATIBILITY: Removed '-i' flag from base64 command (incompatible with BusyBox).
	 */
	private void uploadTextToLinuxConsole(byte[] content, String filename, MemoryTransferCallback callback) {
		if (content == null || content.length == 0) {
			if (callback != null) callback.onError("No content to upload.");
			return;
		}

		String safeName = filename.trim().replaceAll("[^a-zA-Z0-9./_-]", "_");
		String tempFile = safeName + ".b64";
		String delimiter = "EOF" + java.util.UUID.randomUUID().toString().substring(0, 5).toUpperCase();
		
		// USE URL ENCODER (Safe for EBCDIC/ASCII transit)
		// Replaces '+' with '-' and '/' with '_'
		String b64 = java.util.Base64.getUrlEncoder().encodeToString(content);
		
		List<String> chunks = new ArrayList<>();
		int chunkLen = 76; 
		for (int i = 0; i < b64.length(); i += chunkLen) {
			chunks.add(b64.substring(i, Math.min(b64.length(), i + chunkLen)));
		}

		new Thread(() -> {
			SwingUtilities.invokeLater(() -> terminalPanel.setPaintingEnabled(false));
			try {
				// 1. PRE-CLEAR & CONFIG
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Initializing..."));
				sendAID(AID_CLEAR);
				Thread.sleep(250);
				
				sendSafely("stty -echo", AID_ENTER);
				Thread.sleep(250);

				// 2. DEFINE RECEIVER FUNCTION
				// Uses printf to ensure strict newline handling
				String part1 = "rx(){ echo 'RDY';i=0;while read -r l;do";
				String part2 = "if [ \"$l\" = \"$2\" ];then break;fi;printf \"%s\\n\" \"$l\">>\"$1\";";
				String part3 = "i=$((i+1));echo \"ACK$i\";done;}";
				
				sendSafely(part1, AID_ENTER);
				sendSafely(part2, AID_ENTER);
				sendSafely(part3, AID_ENTER);
				Thread.sleep(500);

				// 3. START RECEIVER
				sendSafely("", AID_CLEAR);
				Thread.sleep(250);
				sendSafely("rx " + tempFile + " " + delimiter, AID_ENTER);
				
				if (!waitForString("RDY", 5000)) {
					throw new IOException("Handshake failed: Host did not reply with RDY.");
				}
				
				sendSafely("", AID_CLEAR); 
				Thread.sleep(100);

				// 4. SEND LOOP
				int total = chunks.size();
				// Use 10-line batches to account for prompts/status lines
				int batchSize = 10; 
				
				for (int i = 0; i < total; i++) {
					String chunk = chunks.get(i);
					int seq = i + 1;
					
					SwingUtilities.invokeLater(() -> statusBar.setStatus(
						String.format("Linux Upload: Chunk %d of %d", seq, total)));
					
					sendText(chunk);
					sendAID(AID_ENTER);
					
					String expectedAck = "ACK" + seq;
					if (!waitForString(expectedAck, 3000)) {
						sendAID(AID_CLEAR);
						if (!waitForString(expectedAck, 2000)) {
							throw new IOException("Timeout waiting for " + expectedAck);
						}
					}
					
					if (seq % batchSize == 0) {
						sendAID(AID_CLEAR);
						Thread.sleep(50);
					}
				}

				// 5. FINISH
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Finalizing..."));
				sendText(delimiter);
				sendAID(AID_ENTER);
				Thread.sleep(500); 

				// 6. DECODE & CLEANUP
				// Pipeline: Cat -> Translate URL-Safe back to Std -> Base64 Decode
				String decodeCmd = "cat " + tempFile + " | tr '_-' '/+' | base64 -d > " + safeName;
				sendSafely(decodeCmd, AID_ENTER);
				Thread.sleep(500);
				
				sendSafely("rm " + tempFile, AID_ENTER);
				Thread.sleep(200);
				
				sendSafely("unset -f rx", AID_ENTER);
				sendSafely("", AID_CLEAR);
				
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Complete"));
				
				if (callback != null) callback.onUploadComplete();

			} catch (Exception e) {
				e.printStackTrace();
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Error"));
				if (callback != null) callback.onError(e.getMessage());
			} finally {
				try {
					sendSafely("stty echo", AID_ENTER);
					sendSafely("", AID_CLEAR);
				} catch (Exception ex) { }
				
				SwingUtilities.invokeLater(() -> {
					terminalPanel.setPaintingEnabled(true);
					terminalPanel.repaint();
				});
			}
		}).start();
	}
	
	private void uploadTextToLinuxConsoleGood(byte[] content, String filename, MemoryTransferCallback callback) {
		if (content == null || content.length == 0) {
			if (callback != null) callback.onError("No content to upload.");
			return;
		}

		String safeName = filename.trim().replaceAll("[^a-zA-Z0-9./_-]", "_");
		String tempFile = safeName + ".b64";
		String delimiter = "EOF" + java.util.UUID.randomUUID().toString().substring(0, 5).toUpperCase();
		
		String b64 = java.util.Base64.getEncoder().encodeToString(content);
		List<String> chunks = new ArrayList<>();
		int chunkLen = 76; 
		for (int i = 0; i < b64.length(); i += chunkLen) {
			chunks.add(b64.substring(i, Math.min(b64.length(), i + chunkLen)));
		}

		new Thread(() -> {
			SwingUtilities.invokeLater(() -> terminalPanel.setPaintingEnabled(false));
			try {
				// 1. PRE-CLEAR & CONFIG
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Initializing..."));
				sendSafely("", AID_CLEAR);
				Thread.sleep(250);
				
				// Disable echo to save screen space
				sendSafely("stty -echo", AID_ENTER);
				Thread.sleep(250);

				// 2. DEFINE RECEIVER FUNCTION
				String part1 = "rx(){ echo 'RDY';i=0;while read -r l;do";
				String part2 = "if [ \"$l\" = \"$2\" ];then break;fi;echo \"$l\">>\"$1\";";
				String part3 = "i=$((i+1));echo \"ACK$i\";done;}";
				
				sendSafely(part1, AID_ENTER);
				sendSafely(part2, AID_ENTER);
				sendSafely(part3, AID_ENTER);
				Thread.sleep(500);

				// 3. START RECEIVER
				sendSafely("", AID_CLEAR);
				Thread.sleep(250);
				sendSafely("rx " + tempFile + " " + delimiter, AID_ENTER);
				
				if (!waitForString("RDY", 5000)) {
					throw new IOException("Handshake failed: Host did not reply with RDY.");
				}
				
				sendSafely("", AID_CLEAR); 
				Thread.sleep(100);

				// 4. SEND LOOP
				int total = chunks.size();
				int batchSize = 10; // Safe limit with echo disabled
				
				for (int i = 0; i < total; i++) {
					String chunk = chunks.get(i);
					int seq = i + 1;
					
					// Update UI
					final int current = i + 1;
					SwingUtilities.invokeLater(() -> statusBar.setStatus(
						String.format("Linux Upload: Chunk %d of %d", current, total)));
					
					sendText(chunk);
					sendAID(AID_ENTER);
					
					String expectedAck = "ACK" + seq;
					if (!waitForString(expectedAck, 3000)) {
						System.err.println("Timeout waiting for " + expectedAck);
						// Recovery attempt
						sendAID(AID_CLEAR);
						if (!waitForString(expectedAck, 2000)) break;
					}
					
					if (seq % batchSize == 0) {
						sendAID(AID_CLEAR);
						Thread.sleep(50);
					}
				}

				// 5. FINISH
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Finalizing..."));
				sendText(delimiter);
				sendAID(AID_ENTER);
				Thread.sleep(500); 

				// 6. DECODE & CLEANUP
				sendSafely("base64 -d " + tempFile + " > " + safeName, AID_ENTER);
				Thread.sleep(500);
				
				sendSafely("rm " + tempFile, AID_ENTER);
				Thread.sleep(200);
				
				sendSafely("unset -f rx", AID_ENTER);
				sendSafely("", AID_CLEAR);
				
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Complete"));
				
				// TRIGGER CALLBACK ONLY NOW
				if (callback != null) callback.onUploadComplete();

			} catch (Exception e) {
				e.printStackTrace();
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Error"));
				if (callback != null) callback.onError(e.getMessage());
			} finally {
				// Restore Echo state
				try {
					sendSafely("stty echo", AID_ENTER);
					sendSafely("", AID_CLEAR);
				} catch (Exception ex) { /* Ignore */ }
				
				SwingUtilities.invokeLater(() -> {
					terminalPanel.setPaintingEnabled(true);
					terminalPanel.repaint();
				});
			}
		}).start();
	}
	
	private void uploadTextToLinuxConsoleGolden(String content, String filename) {
		if (content == null || content.isEmpty()) return;

		String safeName = filename.trim().replaceAll("[^a-zA-Z0-9./_-]", "_");
		String tempFile = safeName + ".b64";
		String delimiter = "EOF" + java.util.UUID.randomUUID().toString().substring(0, 5).toUpperCase();
		
		String b64 = java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
		List<String> chunks = new ArrayList<>();
		int chunkLen = 76; 
		for (int i = 0; i < b64.length(); i += chunkLen) {
			chunks.add(b64.substring(i, Math.min(b64.length(), i + chunkLen)));
		}

		new Thread(() -> {
			SwingUtilities.invokeLater(() -> terminalPanel.setPaintingEnabled(false));
			try {
				// 1. PRE-CLEAR & CONFIG
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Initializing..."));
				sendSafely("", AID_CLEAR);
				Thread.sleep(250);
				
				// Disable echo to save screen space
				sendSafely("stty -echo", AID_ENTER);
				Thread.sleep(250);

				// 2. DEFINE RECEIVER FUNCTION
				String part1 = "rx(){ echo 'RDY';i=0;while read -r l;do";
				String part2 = "if [ \"$l\" = \"$2\" ];then break;fi;echo \"$l\">>\"$1\";";
				String part3 = "i=$((i+1));echo \"ACK$i\";done;}";
				
				sendSafely(part1, AID_ENTER);
				sendSafely(part2, AID_ENTER);
				sendSafely(part3, AID_ENTER);
				Thread.sleep(500);

				// 3. START RECEIVER
				sendSafely("", AID_CLEAR);
				Thread.sleep(250);
				sendSafely("rx " + tempFile + " " + delimiter, AID_ENTER);
				
				if (!waitForString("RDY", 5000)) {
					System.err.println("Handshake failed: Host did not reply with RDY.");
					sendSafely("", AID_CLEAR);
					return;
				}
				
				sendSafely("", AID_CLEAR); 
				Thread.sleep(100);

				// 4. SEND LOOP
				int total = chunks.size();
				for (int i = 0; i < total; i++) {
					String chunk = chunks.get(i);
					int seq = i + 1;
					
					// Update UI
					final int current = i + 1;
					SwingUtilities.invokeLater(() -> statusBar.setStatus(
						String.format("Linux Upload: Chunk %d of %d", current, total)));
					
					// --- DYNAMIC SCREEN MANAGEMENT ---
					// Check how much vertical space we have left.
					int cursorRow = screenModel.getCursorPos() / screenModel.getCols();
					int totalRows = screenModel.getRows();
					
					// We need roughly 3-4 lines for the next operation (Input + Echo + ACK + Prompt)
					// If we are within 5 lines of the bottom, CLEAR to avoid "MORE..." status.
					if ((totalRows - cursorRow) < 5) {
						sendAID(AID_CLEAR);
						Thread.sleep(50); // Short pause for z/VM
					}
					// ---------------------------------
					
					sendText(chunk);
					sendAID(AID_ENTER);
					
					String expectedAck = "ACK" + seq;
					if (!waitForString(expectedAck, 3000)) {
						System.err.println("Timeout waiting for " + expectedAck);
						// Recovery attempt
						sendAID(AID_CLEAR);
						if (!waitForString(expectedAck, 2000)) break;
					}
				}

				// 5. FINISH
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Finalizing..."));
				sendText(delimiter);
				sendAID(AID_ENTER);
				Thread.sleep(500); 

				// 6. DECODE & CLEANUP
				sendSafely("base64 -d " + tempFile + " > " + safeName, AID_ENTER);
				Thread.sleep(500);
				
				sendSafely("rm " + tempFile, AID_ENTER);
				Thread.sleep(200);
				
				sendSafely("unset -f rx", AID_ENTER);
				sendSafely("", AID_CLEAR);
				
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Complete"));

			} catch (Exception e) {
				e.printStackTrace();
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Error"));
			} finally {
				// Restore Echo state blindly
				try {
					sendSafely("stty echo", AID_ENTER);
					sendSafely("", AID_CLEAR);
				} catch (Exception ex) { /* Ignore */ }
				
				SwingUtilities.invokeLater(() -> {
					terminalPanel.setPaintingEnabled(true);
					terminalPanel.repaint();
				});
			}
		}).start();
	}
	
	private void uploadTextToLinuxConsoleLast(String content, String filename) {
		if (content == null || content.isEmpty()) return;

		String safeName = filename.trim().replaceAll("[^a-zA-Z0-9./_-]", "_");
		String tempFile = safeName + ".b64";
		String delimiter = "EOF" + java.util.UUID.randomUUID().toString().substring(0, 5).toUpperCase();
		
		String b64 = java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
		List<String> chunks = new ArrayList<>();
		int chunkLen = 76; 
		for (int i = 0; i < b64.length(); i += chunkLen) {
			chunks.add(b64.substring(i, Math.min(b64.length(), i + chunkLen)));
		}

		new Thread(() -> {
			SwingUtilities.invokeLater(() -> terminalPanel.setPaintingEnabled(false));
			try {
				// 1. PRE-CLEAR
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Initializing..."));
				sendSafely("", AID_CLEAR);
				Thread.sleep(250);

				// 2. DEFINE RECEIVER FUNCTION
				String part1 = "rx(){ echo 'RDY';i=0;while read -r l;do";
				String part2 = "if [ \"$l\" = \"$2\" ];then break;fi;echo \"$l\">>\"$1\";";
				String part3 = "i=$((i+1));echo \"ACK$i\";done;}";
				
				sendSafely(part1, AID_ENTER);
				sendSafely(part2, AID_ENTER);
				sendSafely(part3, AID_ENTER);
				Thread.sleep(500);

				// 3. CLEAR DEFINITION
				sendSafely("", AID_CLEAR);
				Thread.sleep(250);

				// 4. START RECEIVER
				sendSafely("rx " + tempFile + " " + delimiter, AID_ENTER);
				
				if (!waitForString("RDY", 5000)) {
					System.err.println("Handshake failed: Host did not reply with RDY.");
					sendSafely("", AID_CLEAR);
					return;
				}
				
				sendSafely("", AID_CLEAR); 
				Thread.sleep(100);

				// 5. SEND LOOP
				int total = chunks.size();
				for (int i = 0; i < total; i++) {
					String chunk = chunks.get(i);
					int seq = i + 1;
					
					// Status Update
					final int current = i + 1;
					SwingUtilities.invokeLater(() -> statusBar.setStatus(
						String.format("Linux Upload: Chunk %d of %d", current, total)));
					
					sendText(chunk);
					sendAID(AID_ENTER);
					
					String expectedAck = "ACK" + seq;
					// Reduced timeout for speed, host should be responsive
					if (!waitForString(expectedAck, 3000)) {
						System.err.println("Timeout waiting for " + expectedAck);
						break;
					}
					
					// A standard 3270 screen is 24 lines (or more). 10 is safe.
					if (seq % 10 == 0) {
						sendAID(AID_CLEAR);
						Thread.sleep(50);
					}
				}

				// 6. FINISH
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Finalizing..."));
				sendText(delimiter);
				sendAID(AID_ENTER);
				Thread.sleep(500); 

				// 7. DECODE & CLEANUP
				sendSafely("base64 -d " + tempFile + " > " + safeName, AID_ENTER);
				Thread.sleep(500);
				
				sendSafely("rm " + tempFile, AID_ENTER);
				Thread.sleep(200);
				
				sendSafely("unset -f rx", AID_ENTER);
				sendSafely("", AID_CLEAR);
				
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Complete"));

			} catch (Exception e) {
				e.printStackTrace();
				SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Upload: Error"));
			} finally {
				SwingUtilities.invokeLater(() -> {
					terminalPanel.setPaintingEnabled(true);
					terminalPanel.repaint();
				});
			}
		}).start();
	}
	
		private void uploadTextToLinuxConsoleWorking(String content, String filename) {
			if (content == null || content.isEmpty()) return;

			String safeName = filename.trim().replaceAll("[^a-zA-Z0-9./_-]", "_");
			String tempFile = safeName + ".b64";
			String delimiter = "EOF" + java.util.UUID.randomUUID().toString().substring(0, 5).toUpperCase();
			
			String b64 = java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
			List<String> chunks = new ArrayList<>();
			int chunkLen = 76; 
			for (int i = 0; i < b64.length(); i += chunkLen) {
				chunks.add(b64.substring(i, Math.min(b64.length(), i + chunkLen)));
			}

			new Thread(() -> {
				SwingUtilities.invokeLater(() -> terminalPanel.setPaintingEnabled(false));
				try {
					// 1. PRE-CLEAR
					sendSafely("", AID_CLEAR);
					Thread.sleep(250);

					// 2. DEFINE RECEIVER FUNCTION (With Counter)
					// Part 1: Header + Init counter 'i'
					String part1 = "rx(){ echo 'RDY';i=0;while read -r l;do";
					// Part 2: Check delimiter + Append
					String part2 = "if [ \"$l\" = \"$2\" ];then break;fi;echo \"$l\">>\"$1\";";
					// Part 3: Increment counter + Echo Numbered ACK + Close
					String part3 = "i=$((i+1));echo \"ACK$i\";done;}";
					
					sendSafely(part1, AID_ENTER);
					sendSafely(part2, AID_ENTER);
					sendSafely(part3, AID_ENTER);
					Thread.sleep(500);

					// 3. CRITICAL: CLEAR DEFINITION FROM SCREEN
					sendSafely("", AID_CLEAR);
					Thread.sleep(250);

					// 4. START RECEIVER & HANDSHAKE
					sendSafely("rx " + tempFile + " " + delimiter, AID_ENTER);
					
					if (!waitForString("RDY", 5000)) {
						System.err.println("Handshake failed: Host did not reply with RDY.");
						sendSafely("", AID_CLEAR);
						return;
					}
					
					// Clear the "RDY" to start clean
					sendSafely("", AID_CLEAR); 
					Thread.sleep(100);

					// 5. SEND LOOP (Batched)
					for (int i = 0; i < chunks.size(); i++) {
						String chunk = chunks.get(i);
						int seq = i + 1; // 1-based sequence for shell output
						
						sendText(chunk);
						sendAID(AID_ENTER);
						
						// Wait for SPECIFIC Numbered ACK (e.g., "ACK1", "ACK15")
						// This ensures we don't confuse old ACKs with the current one.
						String expectedAck = "ACK" + seq;
						if (!waitForString(expectedAck, 5000)) {
							System.err.println("Timeout waiting for " + expectedAck);
							break;
						}
						
						// BATCHED CLEAR: Only clear every 10 lines to speed up transfer
						if (seq % 10 == 0) {
							sendAID(AID_CLEAR);
							// Wait briefly for clear to take effect so we don't read stale text
							Thread.sleep(50);
						}
					}

					// 6. FINISH
					sendText(delimiter);
					sendAID(AID_ENTER);
					Thread.sleep(500); 

					// 7. DECODE & CLEANUP
					sendSafely("base64 -d " + tempFile + " > " + safeName, AID_ENTER);
					Thread.sleep(500);
					
					sendSafely("rm " + tempFile, AID_ENTER);
					Thread.sleep(200);
					
					sendSafely("unset -f rx", AID_ENTER);
					sendSafely("", AID_CLEAR);

				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					SwingUtilities.invokeLater(() -> {
						terminalPanel.setPaintingEnabled(true);
						terminalPanel.repaint();
					});
				}
			}).start();
		}
	
	/**
	 * Downloads text from Linux via Screen Scraping (ACK Flow Control).
	 * 
	 * FIXES:
	 * 1. FLOW CONTROL: Sends "ACK" + ENTER to acknowledge every chunk.
	 *    - This sets the MDT (Modified Data Tag), forcing z/VM to pass the input 
	 *      to the Linux shell (bypassing "VM READ").
	 *    - It provides a visual confirmation in the console history that chunks 
	 *      were processed.
	 * 2. SCRAPING: Uses fixed-window scanning (10 lines) for efficiency.
	 */
		/**
		 * Downloads text from Linux via Screen Scraping.
		 * 
		 * OPTIMIZATIONS:
		 * 1. Uses "Single Enter + Data" flow control (fastest method).
		 * 2. Added Status Bar updates.
		 */
		/**
		 * Downloads text from Linux via Screen Scraping (Dynamic Batching).
		 * 
		 * OPTIMIZATIONS:
		 * 1. DYNAMIC CLEARING: Identifies the row of the current "---WAIT---" marker.
		 *    If there is room for the NEXT chunk (10 lines + overhead) on the current screen,
		 *    we skip the AID_CLEAR. This allows batching multiple chunks per screen refresh.
		 * 2. FLOW CONTROL: Uses "ACK" + Enter to satisfy the shell read.
		 */
		/**
		 * Downloads text from Linux via Screen Scraping (Corrected).
		 * 
		 * FIXES:
		 * 1. BACKWARD SCAN LIMIT: Explicitly stops scanning at the PREVIOUS marker or 
		 *    the script invocation command. This prevents reading duplicate data from 
		 *    previous chunks (the primary cause of Base64 decode errors).
		 * 2. GARBAGE FILTER: Filters out "ACK" responses and short lines (<4 chars) 
		 *    to avoid corrupting the Base64 buffer.
		 * 3. DYNAMIC BATCHING: Uses smart clearing to optimize speed.
		 */
		/**
		 * Downloads text from Linux via Screen Scraping (URL-Safe / EBCDIC Robust).
		 * 
		 * FIXES:
		 * 1. CHAR SAFETY: Pipes Linux output through "tr '+/' '-_'" to convert standard Base64 
		 *    to URL-Safe Base64. This prevents EBCDIC translation issues with '+' and '/'.
		 * 2. JAVA DECODING: Uses UrlDecoder to handle the safe characters.
		 * 3. REGEX: Updated to accept '-' and '_' instead of '+' and '/'.
		 */
		/**
		 * Downloads text from Linux via Screen Scraping (Corrected TR Syntax).
		 * 
		 * FIXES:
		 * 1. TR COMMAND SYNTAX: Changed "tr '+/' '-_'" to "tr '/+' '_-'" to avoid 
		 *    the leading hyphen being interpreted as a command flag by the Linux shell.
		 *    This ensures proper conversion to URL-Safe Base64 (- and _).
		 * 2. MAPPING ACCURACY: Maintains RFC 4648 mapping (+ -> - and / -> _) by 
		 *    swapping both sets (Index 0 maps to Index 0, Index 1 to Index 1).
		 */
		/**
		 * Downloads text from Linux via Screen Scraping (Binary Safe).
		 * 
		 * FIXES:
		 * 1. BINARY INTEGRITY: Passes raw byte[] to the callback. This prevents 
		 *    UTF-8 expansion of binary data (which caused the file size increase).
		 * 2. FLOW CONTROL: Uses "ACK" + Enter for robust input handling.
		 * 3. CLEANUP: Handles history clearing and safe decoding.
		 */
		/**
		 * Downloads text from Linux via Screen Scraping (Robust).
		 * 
		 * FIXES:
		 * 1. FILTERING: Ignores "ACK" and short lines (<4 chars) to prevent stream corruption.
		 * 2. NORMALIZATION: Scrapes both standard and URL-safe Base64 chars, then normalizes 
		 *    to standard Base64 before decoding. This handles cases where 'tr' might fail on the host.
		 * 3. FLOW CONTROL: Uses "ACK" + Enter for robust input handling.
		 */
		private void downloadTextFromLinuxConsole(String filename, MemoryTransferCallback callback) {
			new Thread(() -> {
				SwingUtilities.invokeLater(() -> terminalPanel.setPaintingEnabled(false));
				try {
					String safeName = filename.trim();
					String id = java.util.UUID.randomUUID().toString().substring(0, 5);
					String tempFile = "dl_" + id + ".tmp";
					String splitPrefix = "chk_" + id + "_";
					String scriptName = "run_" + id + ".sh";
					StringBuilder base64Buffer = new StringBuilder();
					
					final int CHUNK_SIZE = 10; 
					
					// 1. Initial Setup
					SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Download: Initializing..."));
					sendAID(AID_CLEAR);
					Thread.sleep(500);

					// Prepare Source: Encode -> Translate to URL-Safe (Robust Syntax) -> Save
					String prepCmd = "base64 -w 76 " + safeName + " | tr '/+' '_-' > " + tempFile;
					sendSafely(prepCmd, AID_ENTER);
					Thread.sleep(250);
					
					sendSafely("split -l " + CHUNK_SIZE + " " + tempFile + " " + splitPrefix, AID_ENTER);
					Thread.sleep(250);

					List<String> scriptLines = new ArrayList<>();
					scriptLines.add("i=0");
					scriptLines.add("for f in " + splitPrefix + "*; do");
					scriptLines.add("  cat $f");
					scriptLines.add("  echo \"---WAIT $i---\""); 
					scriptLines.add("  read -r _"); 
					scriptLines.add("  rm $f");
					scriptLines.add("  i=$((i+1))");
					scriptLines.add("done");
					scriptLines.add("rm " + tempFile);
					scriptLines.add("echo \"---EOF---\"");
					scriptLines.add("rm " + scriptName); 

					sendSafely("echo '" + scriptLines.get(0) + "' > " + scriptName, AID_ENTER);
					Thread.sleep(100);
					for (int j = 1; j < scriptLines.size(); j++) {
						sendSafely("echo '" + scriptLines.get(j) + "' >> " + scriptName, AID_ENTER);
						Thread.sleep(50);
					}

					sendAID(AID_CLEAR);
					Thread.sleep(500);
					sendSafely("sh " + scriptName, AID_ENTER);
					
					// 2. Scrape Loop
					boolean downloading = true;
					int chunkIndex = 0;
					
					while (downloading) {
						String currentMarker = "---WAIT " + chunkIndex + "---";
						
						final int cIdx = chunkIndex + 1;
						final int bytes = base64Buffer.length();
						SwingUtilities.invokeLater(() -> statusBar.setStatus(
							String.format("Linux Download: Chunk %d (%d chars)...", cIdx, bytes)));
						
						String state = waitForSmartMarker(currentMarker, "---EOF---", 5000);
						
						if (state == null) {
							sendAID(AID_CLEAR);
							state = waitForSmartMarker(currentMarker, "---EOF---", 5000);
							if (state == null) throw new IOException("Timeout waiting for " + currentMarker);
						}
						
						if (state.equals(currentMarker)) {
							int rows = screenModel.getRows();
							int cols = screenModel.getCols();
							int markerRow = -1;
							
							for (int r = 0; r < rows; r++) {
								String line = screenModel.getString(r * cols, cols);
								if (line.contains(currentMarker)) {
									markerRow = r;
									break;
								}
							}
							
							if (markerRow != -1) {
								List<String> validLines = new ArrayList<>();
								int linesScanned = 0;
								
								for (int r = markerRow - 1; r >= 0 && linesScanned < CHUNK_SIZE; r--) {
									String line = screenModel.getString(r * cols, cols).trim();
									linesScanned++;
									
									// FILTER: Ignore ACK echo and short garbage
									if (line.equals("ACK")) continue;
									if (line.length() < 4) continue; 
									
									// REGEX: Permissive match (Standard OR URL-Safe)
									if (line.matches("^[A-Za-z0-9+/\\-_=]+$")) {
										validLines.add(0, line);
									}
								}
								for (String l : validLines) base64Buffer.append(l);
							}
							
							// --- Advance ---
							sendAID(AID_CLEAR);
							Thread.sleep(250); 
							sendSafely("ACK", AID_ENTER);
							
							chunkIndex++;
						} else {
							downloading = false;
						}
					}
					
					sendSafely("", AID_CLEAR);
					SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Download: Complete"));
					
					try {
						// NORMALIZE: Ensure we have standard Base64 before decoding
						// Replaces URL-safe chars (-_) with Standard (+/)
						String standardB64 = base64Buffer.toString().replace('-', '+').replace('_', '/');
						
						byte[] decoded = java.util.Base64.getDecoder().decode(standardB64);
						
						if (callback != null) callback.onDownloadComplete(decoded);
					} catch (IllegalArgumentException e) {
						if (callback != null) callback.onError("Base64 Decode: " + e.getMessage());
					}

				} catch (Exception e) {
					SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Download: Error"));
					if (callback != null) callback.onError(e.getMessage());
				} finally {
					SwingUtilities.invokeLater(() -> {
						terminalPanel.setPaintingEnabled(true);
						terminalPanel.repaint();
					});
				}
			}).start();
		}
		
		private void downloadTextFromLinuxConsoleOld(String filename, MemoryTransferCallback callback) {
			new Thread(() -> {
				SwingUtilities.invokeLater(() -> terminalPanel.setPaintingEnabled(false));
				try {
					String safeName = filename.trim();
					String id = java.util.UUID.randomUUID().toString().substring(0, 5);
					String tempFile = "dl_" + id + ".tmp";
					String splitPrefix = "chk_" + id + "_";
					String scriptName = "run_" + id + ".sh";
					StringBuilder base64Buffer = new StringBuilder();
					
					final int CHUNK_SIZE = 10; 
					
					// 1. Initial Setup
					SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Download: Initializing..."));
					sendAID(AID_CLEAR);
					Thread.sleep(500);

					// Prepare Source: Encode -> Translate to URL-Safe -> Save
					String prepCmd = "base64 -w 76 " + safeName + " | tr '/+' '_-' > " + tempFile;
					sendSafely(prepCmd, AID_ENTER);
					Thread.sleep(250);
					
					sendSafely("split -l " + CHUNK_SIZE + " " + tempFile + " " + splitPrefix, AID_ENTER);
					Thread.sleep(250);

					List<String> scriptLines = new ArrayList<>();
					scriptLines.add("i=0");
					scriptLines.add("for f in " + splitPrefix + "*; do");
					scriptLines.add("  cat $f");
					scriptLines.add("  echo \"---WAIT $i---\""); 
					scriptLines.add("  read -r _"); 
					scriptLines.add("  rm $f");
					scriptLines.add("  i=$((i+1))");
					scriptLines.add("done");
					scriptLines.add("rm " + tempFile);
					scriptLines.add("echo \"---EOF---\"");
					scriptLines.add("rm " + scriptName); 

					sendSafely("echo '" + scriptLines.get(0) + "' > " + scriptName, AID_ENTER);
					Thread.sleep(100);
					for (int j = 1; j < scriptLines.size(); j++) {
						sendSafely("echo '" + scriptLines.get(j) + "' >> " + scriptName, AID_ENTER);
						Thread.sleep(50);
					}

					sendAID(AID_CLEAR);
					Thread.sleep(500);
					sendSafely("sh " + scriptName, AID_ENTER);
					
					// 2. Scrape Loop
					boolean downloading = true;
					int chunkIndex = 0;
					
					while (downloading) {
						String currentMarker = "---WAIT " + chunkIndex + "---";
						
						// Status
						final int cIdx = chunkIndex + 1;
						final int bytes = base64Buffer.length();
						SwingUtilities.invokeLater(() -> statusBar.setStatus(
							String.format("Linux Download: Chunk %d (%d chars)...", cIdx, bytes)));
						
						String state = waitForSmartMarker(currentMarker, "---EOF---", 5000);
						
						if (state == null) {
							sendAID(AID_CLEAR);
							state = waitForSmartMarker(currentMarker, "---EOF---", 5000);
							if (state == null) throw new IOException("Timeout waiting for " + currentMarker);
						}
						
						if (state.equals(currentMarker)) {
							int rows = screenModel.getRows();
							int cols = screenModel.getCols();
							int markerRow = -1;
							
							for (int r = 0; r < rows; r++) {
								String line = screenModel.getString(r * cols, cols);
								if (line.contains(currentMarker)) {
									markerRow = r;
									break;
								}
							}
							
							if (markerRow != -1) {
								List<String> validLines = new ArrayList<>();
								int linesScanned = 0;
								
								for (int r = markerRow - 1; r >= 0 && linesScanned < CHUNK_SIZE; r--) {
									String line = screenModel.getString(r * cols, cols).trim();
									linesScanned++;
									if (line.matches("^[A-Za-z0-9\\-_=]+$")) {
										validLines.add(0, line);
									}
								}
								for (String l : validLines) base64Buffer.append(l);
							}
							
							sendAID(AID_CLEAR);
							Thread.sleep(250); 
							sendSafely("ACK", AID_ENTER);
							
							chunkIndex++;
						} else {
							downloading = false;
						}
					}
					
					sendSafely("", AID_CLEAR);
					SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Download: Complete"));
					
					try {
						byte[] decoded = java.util.Base64.getUrlDecoder().decode(base64Buffer.toString());
						if (callback != null) callback.onDownloadComplete(decoded);
					} catch (IllegalArgumentException e) {
						if (callback != null) callback.onError("Base64 Decode: " + e.getMessage());
					}

				} catch (Exception e) {
					SwingUtilities.invokeLater(() -> statusBar.setStatus("Linux Download: Error"));
					if (callback != null) callback.onError(e.getMessage());
				} finally {
					SwingUtilities.invokeLater(() -> {
						terminalPanel.setPaintingEnabled(true);
						terminalPanel.repaint();
					});
				}
			}).start();
		}
		
    /**
     * Types a string into the emulator as if the user typed it.
     * Handles basic ASCII mapping.
     */
    private void sendText(String text) {
        if (text == null) return;
        for (char c : text.toCharArray()) {
            int p = screenModel.getCursorPos();
            if (!screenModel.isProtected(p)) {
                screenModel.setChar(p, c);
                screenModel.setModified(p);
                // Move cursor
                int cols = screenModel.getCols();
                int next = (p + 1) % screenModel.getSize();
                
                // Handle wrap (simple) or field skip?
                // For a paste/type operation, simple cursor move is usually enough
                // unless we hit a protected field.
                if (screenModel.isProtected(next)) {
                    // Try to find next unprotected field (Auto-Skip behavior)
                    next = screenModel.findNextField(next);
                    // If next field starts at 'next', we need to move 1 past the attribute byte
                    if (screenModel.isFieldStart(next)) {
                        next = (next + 1) % screenModel.getSize();
                    }
                }
                screenModel.setCursorPos(next);
            } else {
                // Tried to type in protected field. Skip or Stop?
                // For a "Paste" operation, skipping to the next field is usually friendlier.
                tabToNextField();
                // Retry the character? Or just drop it?
                // Usually dropping it is safer than desynchronizing the stream.
            }
        }
        // Do NOT call repaint() here for performance if we are in a loop; 
        // let the caller handle repaints or the timer will pick it up.
    }
    
    /**
	 * Robust sender that handles the z/VM "NOT ACCEPTED" penalty box.
	 */
    /**
	 * Robust sender that handles the z/VM "NOT ACCEPTED" penalty box.
	 * Returns true if successful, false if retries exhausted.
	 */
	private boolean sendSafely(String text, int aid) throws InterruptedException {
		int retries = 0;
		// Increased retries to 10 to handle heavy load/slow guests
		while (retries < 10) {
			
			// 1. PRE-CHECK: Am I already in the penalty box?
			// If we try to type while "NOT ACCEPTED" is on screen, it will just fail again.
			if (isStatusNotAccepted()) {
				// System.out.println("Waiting for penalty box to clear...");
				waitForStatusClear();
				// Add a tiny breather after it clears to let CP buffers settle
				Thread.sleep(100); 
			}

			// 2. SEND: Type and Hit Enter
			if (text != null && !text.isEmpty()) {
				sendText(text);
			}
			sendAID(aid);

			// 3. WAIT: Wait for Host Response (Unlock/Update)
			synchronized (keyboardLockMonitor) {
				keyboardLockMonitor.wait(5000); 
			}

			// 4. POST-CHECK: Did the host reject it?
			if (isStatusNotAccepted()) {
				// The input we just sent was discarded.
				// We must loop back and RE-SEND the text.
				retries++;
				
				// Wait for the message to clear before the next loop iteration
				waitForStatusClear();
				Thread.sleep(200); // Extra safety buffer after a rejection
			} else {
				// Success!
				return true;
			}
		}
		
		System.err.println("Failed to send text after " + retries + " retries.");
		return false;
	}
	
	/**
	 * Blocks until the "NOT ACCEPTED" status disappears from the screen
	 * or a timeout occurs.
	 */
	private void waitForStatusClear() {
		long endTime = System.currentTimeMillis() + 5000; // 5s max wait
		while (isStatusNotAccepted() && System.currentTimeMillis() < endTime) {
			try {
				synchronized (keyboardLockMonitor) {
					// Wait for the next screen update from the host
					keyboardLockMonitor.wait(500); 
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
	}
	
	private boolean waitForString(String expected, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String content = screenModel.getString(0, screenModel.getSize());
            if (content.contains(expected)) return true;
            synchronized (keyboardLockMonitor) {
                try { keyboardLockMonitor.wait(200); } 
                catch (InterruptedException e) { return false; }
            }
        }
        return false;
    }
	
	// NEW Helper: Wait for one of multiple strings, return the one found
	private String waitForAnyString(String[] expected, int timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			String content = screenModel.getString(0, screenModel.getSize());
			for (String s : expected) {
				if (content.contains(s)) return s;
			}
			synchronized (keyboardLockMonitor) {
				try { keyboardLockMonitor.wait(200); } 
				catch (InterruptedException e) { return null; }
			}
		}
		return null;
	}
	
	/**
	 * Scans the screen for markers, IGNORING lines that contain 'echo'.
	 * This prevents false detection from command history.
	 */
	private String waitForSmartMarker(String waitMarker, String eofMarker, int timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			int rows = screenModel.getRows();
			int cols = screenModel.getCols();
			
			for (int r = 0; r < rows; r++) {
				String line = screenModel.getString(r * cols, cols);
				
				// CRITICAL FILTER: Ignore command history lines
				if (line.contains("echo") || line.contains(">>")) continue;
				
				if (line.contains(waitMarker)) return waitMarker;
				if (line.contains(eofMarker)) return eofMarker;
			}
			
			synchronized (keyboardLockMonitor) {
				try { keyboardLockMonitor.wait(200); } 
				catch (InterruptedException e) { return null; }
			}
		}
		return null;
	}
}
