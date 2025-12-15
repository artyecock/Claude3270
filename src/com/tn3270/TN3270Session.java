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

import static com.tn3270.constants.TelnetConstants.*;
import static com.tn3270.constants.ProtocolConstants.*;
import static com.tn3270.util.EBCDIC.*;

public class TN3270Session extends JPanel implements KeyListener {

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

	private enum HostType {
		TSO, CMS
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
    // True  = Tile Mode (Font scales to fit window)
    private boolean autoFitOnResize = false;
	private StatusBar statusBar;
	private Timer blinkTimer;
	private Frame parentFrame;

	private Socket socket;
	private InputStream input;
	private OutputStream output;
	private volatile boolean connected = false;
	private Thread readerThread;
	private boolean useTLS = false;
	private String requestedLuName = "";
	private String currentHost = "";
	private int currentPort = 23;
	private String modelName = "3279-3";

	public boolean insertMode = false;
	public boolean keyboardLocked = false;
	private byte lastAID = AID_ENTER;
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
		public Byte aid;
		public String description;

		public KeyMapping(char c, String d) {
			this.character = c;
			this.description = d;
		}

		public KeyMapping(byte a, String d) {
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

		blinkTimer = new Timer(500, e -> terminalPanel.repaint());
		blinkTimer.start();

		setFocusable(true);
		
        // FIX: Disable Swing's TAB traversal
        setFocusTraversalKeysEnabled(false);
        
		addKeyListener(this);
		terminalPanel.addKeyListener(this);
		initializeKeyMappings();
		
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
        
        /*
        // Define the Resize Listener
        resizeListener = new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                // Handle Tab Switching: When tab appears, fit content to the existing window
                terminalPanel.requestFocusInWindow();
                if (scrollPane.getWidth() > 0 && scrollPane.getHeight() > 0) {
                     terminalPanel.fitToSize(scrollPane.getWidth(), scrollPane.getHeight());
                }
            }

            @Override
            public void componentResized(ComponentEvent e) {
                // Handle User Drag: Fit font to new window size
                if (scrollPane.getWidth() > 0 && scrollPane.getHeight() > 0) {
                     terminalPanel.fitToSize(scrollPane.getWidth(), scrollPane.getHeight());
                }
            }
        };
        */
        
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
		addComponentListener(new ComponentAdapter() {
			@Override
			public void componentShown(ComponentEvent e) {
				terminalPanel.requestFocusInWindow();
			}

			@Override
			public void componentResized(ComponentEvent e) {
				// FIX: Use scrollPane dimensions, NOT 'this' dimensions
				// 'this.getHeight()' includes the StatusBar, causing the calculation
				// to overshoot the available viewport space.
				if (scrollPane.getWidth() > 0 && scrollPane.getHeight() > 0) {
					terminalPanel.fitToSize(scrollPane.getWidth(), scrollPane.getHeight());
				}
			}
		});
		*/
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

	// ... [Networking, Telnet, Protocol, File Transfer methods remain IDENTICAL to
	// previous correct versions] ...
	// (Omitted here for brevity as they are unchanged. Ensure handleSubneg,
	// processWSF, etc., are the ones from the last successful test.)

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

	public void applyColorSchemeOld(String schemeName) {
		ColorScheme scheme = COLOR_SCHEMES.get(schemeName);
		if (scheme == null)
			return;

		// 1. Update Model
		screenModel.setScreenBackground(scheme.background);
		screenModel.setDefaultForeground(scheme.defaultFg);
		screenModel.setCursorColor(scheme.cursor);
		screenModel.setPalette(scheme.colors);

		// 2. Update Component Colors (Swing needs this if opaque)
		terminalPanel.setBackground(scheme.background);
		terminalPanel.setForeground(scheme.defaultFg);

		// 3. FIX: Update ScrollPane Viewport (The area surrounding the terminal)
		if (scrollPane != null && scrollPane.getViewport() != null) {
			scrollPane.getViewport().setBackground(scheme.background);
		}

		terminalPanel.repaint();
		if (statusBar != null)
			statusBar.setStatus("Color scheme: " + schemeName);
	}
	
    public void setShowCrosshair(boolean b) {
        if (terminalPanel != null) terminalPanel.setShowCrosshair(b);
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
					byte b = buf[i];
					switch (ts) {
					case STATE_DATA:
						if (b == IAC)
							ts = STATE_IAC;
						else
							dataStream.write(b);
						break;
					case STATE_IAC:
						if (b == IAC) {
							dataStream.write((byte) 0xFF);
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
						else if (b == (byte) 0xEF) {
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
							if (sn.length > 0 && sn[sn.length - 1] == IAC) {
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

	private void handleTelnet(byte cmd, byte opt) throws IOException {
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

	private void sendTelnet(byte c, byte o) throws IOException {
		output.write(new byte[] { IAC, c, o });
		output.flush();
	}

	/**
	 * Processes incoming 3270 data.
	 * 
	 * REGRESSION NOTE: TN3270E OPTIMISTIC NEGOTIATION
	 * We check 'data[0] == TN3270E_DT_3270_DATA' before stripping the 5-byte header.
	 * 
	 * The Problem: Hosts often send the first screen of data (Standard 3270) immediately 
	 * after sending the "DO TN3270E" negotiation request, without waiting for our "WILL TN3270E".
	 * 
	 * The Fix: Even if 'tn3270eMode' is true in our state machine, we must validate the 
	 * header byte. If data[0] is NOT 0x00, it is likely an optimistic Standard 3270 packet 
	 * slipping in during the transition. In that case, we treat offset as 0 (Standard) 
	 * instead of 5 (Extended), preventing data corruption.
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
		if (tn3270eMode && data.length >= 5 && data[0] == TN3270E_DT_3270_DATA) {
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
		
		byte cmd = data[off++];
		if (cmd == CMD_ERASE_WRITE_05 || cmd == CMD_ERASE_WRITE_F5 || cmd == CMD_ERASE_WRITE_ALTERNATE_7E
				|| cmd == CMD_ERASE_WRITE_ALTERNATE_0D)
			screenModel.clearScreen();
		if (cmd == CMD_WRITE_01 || cmd == CMD_WRITE_F1 || cmd == CMD_ERASE_WRITE_05 || cmd == CMD_ERASE_WRITE_F5
				|| cmd == CMD_ERASE_WRITE_ALTERNATE_0D || cmd == CMD_ERASE_WRITE_ALTERNATE_7E) {
			if (cmd == CMD_ERASE_WRITE_ALTERNATE_0D || cmd == CMD_ERASE_WRITE_ALTERNATE_7E) {
				if (!screenModel.isAlternateSize()) {
					screenModel.setUseAlternateSize(true);
					terminalPanel.updateSize();
				}
			} else if (cmd == CMD_ERASE_WRITE_05 || cmd == CMD_ERASE_WRITE_F5) {
				if (screenModel.isAlternateSize()) {
					screenModel.setUseAlternateSize(false);
					terminalPanel.updateSize();
				}
			}
			if (off < data.length) {
				byte wcc = data[off++];
				if ((wcc & WCC_RESET) != 0) {
					keyboardLocked = false;
					replyModeFlags = 0;
				}
				if ((wcc & WCC_RESET_MDT) != 0)
					screenModel.resetMDT();
				if ((wcc & WCC_ALARM) != 0 && enableSound)
					Toolkit.getDefaultToolkit().beep();
				processOrders(data, off);
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
		updateStatusBar();
	}

	private void processOrders(byte[] data, int offset) {
		int p = 0, i = offset;
		int[] idx = { 0 };
		char c;
		int bufLen = screenModel.getSize();
		while (i < data.length) {
			byte b = data[i++];
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
						byte t = data[i++], v = data[i++];
						if (t == ATTR_FIELD || t == 0xC0)
							a = v;
						else if (t == ATTR_FOREGROUND)
							col = normalizeColor(v);
						else if (t == ATTR_HIGHLIGHTING)
							hl = v;
					}
					screenModel.setChar(p, ' ');
					screenModel.setAttr(p, a);
					screenModel.setExtendedColor(p, col);
					screenModel.setHighlight(p, hl);
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
					byte t = data[i++], v = data[i++];
					if (t == ATTR_FOREGROUND)
						screenModel.setCurrentColor(normalizeColor(v));
					else if (t == ATTR_HIGHLIGHTING)
						screenModel.setCurrentHighlight(v);
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

	private byte normalizeColor(byte raw) {
		if (raw >= (byte) 0xF1 && raw <= (byte) 0xF7)
			return (byte) (raw - 0xF0);
		return raw;
	}

	private void sendReadBuffer() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write(lastAID);
			int cPos = screenModel.getCursorPos();
			baos.write(encode3270Address(cPos)[0]);
			baos.write(encode3270Address(cPos)[1]);

			int size = screenModel.getSize();
			// Check if we are in Extended Mode (Reply Mode 1 or 2)
			boolean extended = (currentReplyMode == ReplyMode.EXTENDED_FIELD
					|| currentReplyMode == ReplyMode.CHARACTER);

			for (int i = 0; i < size; i++) {
				if (screenModel.isFieldStart(i)) {
					if (extended) {
						// Send SFE (Start Field Extended)
						baos.write(ORDER_SFE);
						baos.write(0x01); // Count = 1 (Basic Field Attribute)
						// If we stored extended attributes on field start, we'd add them here.
						// For now, minimal compliance:
						baos.write(0xC0);
						baos.write(screenModel.getAttr(i));
					} else {
						// Standard SF
						baos.write(ORDER_SF);
						baos.write(screenModel.getAttr(i));
					}
				} else {
					char c = screenModel.getChar(i);
					// Check for Extended Attributes on individual characters
					if (extended) {
						byte color = screenModel.getExtendedColors()[i];
						byte hl = screenModel.getHighlight()[i];

						// If color exists, emit SA order
						if (color != 0) {
							baos.write(ORDER_SA);
							baos.write(ATTR_FOREGROUND);
							baos.write(color); // TODO: Might need re-mapping back to IBM codes if we stored indices
						}
						// If highlight exists, emit SA order
						if (hl != 0) {
							baos.write(ORDER_SA);
							baos.write(ATTR_HIGHLIGHTING);
							baos.write(hl);
						}

						// Note: To be fully compliant, we should also emit SA(Reset) if prev char had
						// attrs
						// and this one doesn't, but Read Buffer is often just a dump.
					}

					if (c == '\0')
						baos.write(0x00);
					else if (c < 256 && ASCII_TO_EBCDIC[c] != 0)
						baos.write(ASCII_TO_EBCDIC[c]);
					else
						baos.write(0x40);
				}
			}
			sendData(baos.toByteArray());
			keyboardLocked = true;
			updateStatusBar();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void sendReadBufferOld() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write(lastAID);
			int cPos = screenModel.getCursorPos();
			baos.write(encode3270Address(cPos)[0]);
			baos.write(encode3270Address(cPos)[1]);
			int size = screenModel.getSize();
			for (int i = 0; i < size; i++) {
				if (screenModel.isFieldStart(i)) {
					baos.write(ORDER_SF);
					baos.write(screenModel.getAttr(i));
				} else {
					char c = screenModel.getChar(i);
					if (c == '\0')
						baos.write(0x00);
					else if (c < 256 && ASCII_TO_EBCDIC[c] != 0)
						baos.write(ASCII_TO_EBCDIC[c]);
					else
						baos.write(0x40);
				}
			}
			sendData(baos.toByteArray());
			keyboardLocked = true;
			updateStatusBar();
		} catch (IOException e) {
		}
	}

	public void sendAID(byte aid) {
		lastAID = aid;
		int cPos = screenModel.getCursorPos();
		keyboardLocked = true;
		updateStatusBar();
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write(aid);
			baos.write(encode3270Address(cPos)[0]);
			baos.write(encode3270Address(cPos)[1]);

			if (aid == AID_CLEAR)
				resetReplyModeToDefault();

			boolean isReadMod = (aid == AID_ENTER || (aid >= AID_PF1 && aid <= AID_PF24) || aid == AID_PA1
					|| aid == AID_PA2 || aid == AID_PA3 || aid == AID_CLEAR);

			if (isReadMod) {
				if (aid == AID_ENTER || (aid >= AID_PF1 && aid <= AID_PF24)) {
					int screenSize = screenModel.getSize();
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

										// Handle Extended Attributes in Read Modified
										if (extended) {
											// Technically, Read Modified usually only sends Text,
											// unless Set Reply Mode specified Character Mode.
											// But if we are in Extended Field Mode, we might need SFE if we were
											// recreating the screen.
											// Standard Read Modified usually just sends text.
											// However, checking currentReplyMode prevents regression.
										}

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
				}
			}
			sendData(baos.toByteArray());
		} catch (IOException e) {
		}
		terminalPanel.repaint();
	}

	public void sendAIDOld(byte aid) {
		lastAID = aid;
		int cPos = screenModel.getCursorPos();
		keyboardLocked = true;
		updateStatusBar();
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write(aid);
			baos.write(encode3270Address(cPos)[0]);
			baos.write(encode3270Address(cPos)[1]);
			if (aid == AID_CLEAR)
				resetReplyModeToDefault();
			if (aid == AID_ENTER || (aid >= AID_PF1 && aid <= AID_PF24) || aid == AID_PA1 || aid == AID_PA2
					|| aid == AID_PA3 || aid == AID_CLEAR) {
				if (aid == AID_ENTER || (aid >= AID_PF1 && aid <= AID_PF24)) {
					int screenSize = screenModel.getSize();
					for (int i = 0; i < screenSize; i++) {
						if (screenModel.isFieldStart(i) && (screenModel.getAttr(i) & 0x01) != 0) {
							int fieldStart = i;
							int end = screenModel.findNextField(i);
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
			byte sfid = data[i + 2];
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
		if (sfBuf == null || len <= 0)
			return;
		int p = offset;
		int end = offset + len;
		int flags = 0;
		while (p + 1 < end) {
			int slen = sfBuf[p + 1] & 0xFF;
			if (p + slen > end)
				break;
			for (int i = 0; i < Math.min(4, slen); i++)
				flags = (flags << 8) | (sfBuf[p + i] & 0xFF);
			p += slen;
		}
		this.replyModeFlags = flags;
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
		fullPacket.write((byte) 0xEF);
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

	public void showFileTransferDialogOld(boolean isDownload) {
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
		hostTypeBox.addActionListener(e -> {
			boolean tso = (hostTypeBox.getSelectedIndex() == 0);
			datasetLabel.setText(tso ? "Host Dataset:" : "Host File:");
			spaceLabel.setVisible(tso);
			spaceField.setVisible(tso);
		});
		browseBtn.addActionListener(e -> {
			JFileChooser fc = new JFileChooser();
			if (isDownload ? fc.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION
					: fc.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION)
				localFileField.setText(fc.getSelectedFile().getAbsolutePath());
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
			} else {
				downloadStream = new FileOutputStream(currentFile);
				transferredBytes = 0;
				// updateProgressDialog("Receiving data...", "Bytes: 0");
				// FIX: Initialize indeterminate
				showProgressDialog("Downloading...", 0);
				updateProgressDialog("Receiving data...", "Bytes: 0", 0);
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

	private void sendDCOpenResponse(boolean success, int errorCode) {
		sendResp(DC_OPEN, success, errorCode);
	}

	private void sendDCCloseResponse(boolean success, int errorCode) {
		sendResp(DC_CLOSE, success, errorCode);
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

	private void showProgressDialogOld(String title) {
		if (progressDialog != null)
			progressDialog.dispose();
		progressDialog = new JDialog(getParentFrame(), title, false);
		progressDialog.setLayout(new BorderLayout());
		JPanel p = new JPanel(new GridLayout(0, 1));
		p.setBorder(new EmptyBorder(10, 10, 10, 10));
		progressLabel = new JLabel("Initializing...");
		transferProgressBar = new JProgressBar();
		transferProgressBar.setIndeterminate(true);
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

	private void updateProgressDialogOld(String msg, String status) {
		SwingUtilities.invokeLater(() -> {
			if (progressLabel != null)
				progressLabel.setText(msg);
			if (transferStatusLabel != null)
				transferStatusLabel.setText(status);
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
     * REGRESSION NOTE: LOOP PREVENTION & SCREEN BOUNDS
     * Used when the User changes Font Size manually. We resize the Container to fit the Font,
     * rather than resizing the Font to fit the Container.
     * 
     * Critical Logic:
     * 1. isProgrammaticResize: We set this flag to TRUE before calling window.pack().
     *    This prevents our 'componentResized' listener from firing and triggering the 
     *    Auto-Fit logic, which would calculate a new font and undo the user's selection.
     * 2. Screen Bounds: We clamp the new size to the user's screen resolution to ensure
     *    the window doesn't grow beyond the monitor edges.
     */
    public void snapWindow() {
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window == null || !window.isDisplayable()) return;

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
    
	public void snapWindowOld() {
		Window window = SwingUtilities.getWindowAncestor(this);
		if (window == null || !window.isDisplayable())
			return;

		isProgrammaticResize = true; // LOCK
		try {
			// 1. Ask the Layout Manager to calculate the perfect window size
			// based on the new Terminal PreferredSize + Ribbon + Keyboard + Borders
			window.pack();

			// 2. SAFETY: Don't let the window exceed the physical screen dimensions
			GraphicsConfiguration gc = window.getGraphicsConfiguration();
			Rectangle screenBounds = gc.getBounds();
			Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);

			int maxWidth = screenBounds.width - screenInsets.left - screenInsets.right;
			int maxHeight = screenBounds.height - screenInsets.top - screenInsets.bottom;

			int newW = Math.min(window.getWidth(), maxWidth);
			int newH = Math.min(window.getHeight(), maxHeight);

			// 3. Apply the constrained size
			// If the preferred size was larger than screen, this clamps it,
			// causing the JScrollPane to automatically show scrollbars.
			if (newW != window.getWidth() || newH != window.getHeight()) {
				window.setSize(newW, newH);
			}

			// 4. Center if it hasn't exceeded screen bounds (optional polish)
			if (window.getWidth() < maxWidth && window.getHeight() < maxHeight) {
				window.setLocationRelativeTo(null);
			}
			if (newW != window.getWidth() || newH != window.getHeight()) {
				window.setSize(newW, newH);
			}
		} finally {
			// Use invokeLater to unlock AFTER the resize events have fired
			SwingUtilities.invokeLater(() -> isProgrammaticResize = false);
		}
	}
	
	// --- SMART WINDOW RESIZE LOGIC ---
    private void changeFontSize(int delta) {
        if (terminalPanel == null) return;
        
        int currentSize = terminalPanel.getTerminalFont().getSize();
        int newSize = currentSize + delta;
        
        if (newSize < 6) newSize = 6;
        if (newSize > 72) newSize = 72;
        
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
                Window window = SwingUtilities.getWindowAncestor(this);
                if (window != null && window.isDisplayable()) {
                    window.pack();
                    
                    // Constrain to Screen
                    GraphicsConfiguration gc = window.getGraphicsConfiguration();
                    Rectangle bounds = gc.getBounds();
                    Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
                    int maxW = bounds.width - insets.left - insets.right;
                    int maxH = bounds.height - insets.top - insets.bottom;
                    
                    int w = Math.min(window.getWidth(), maxW);
                    int h = Math.min(window.getHeight(), maxH);
                    
                    if (w != window.getWidth() || h != window.getHeight()) {
                        window.setSize(w, h);
                    }
                }
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
 
/*
	// Update your existing changeFontSize method to call the new logic
	private void changeFontSize(int delta) {
		if (terminalPanel == null)
			return;

		int currentSize = terminalPanel.getTerminalFont().getSize();
		int newSize = currentSize + delta;

		if (newSize < 6)
			newSize = 6;
		if (newSize > 72)
			newSize = 72; // Allow large fonts for 4K screens

		if (newSize != currentSize) {
			// 1. Set the font (TerminalPanel calculates new PreferredSize)
			terminalPanel.setFont(new Font("Monospaced", Font.PLAIN, newSize));
			statusBar.setStatus("Font size set to " + newSize + "pt");

			// 2. Snap the window to the new size
			SwingUtilities.invokeLater(this::resizeWindowToFitContent);
		}
	}
*/
	/*
	 * private void changeFontSize(int delta) { if (terminalPanel == null) return;
	 * int currentSize = terminalPanel.getTerminalFont().getSize(); int newSize =
	 * currentSize + delta; if (newSize < 6) newSize = 6; if (newSize > 72) newSize
	 * = 72; if (newSize != currentSize) { terminalPanel.setFont(new
	 * Font("Monospaced", Font.PLAIN, newSize));
	 * statusBar.setStatus("Font size set to " + newSize + "pt"); } }
	 */
	// public void applyColorScheme(String schemeName) { ColorScheme scheme =
	// COLOR_SCHEMES.get(schemeName); if (scheme == null) return;
	// screenModel.setScreenBackground(scheme.background);
	// screenModel.setDefaultForeground(scheme.defaultFg);
	// screenModel.setCursorColor(scheme.cursor);
	// screenModel.setPalette(scheme.colors);
	// terminalPanel.setBackground(scheme.background); terminalPanel.repaint();
	// statusBar.setStatus("Color scheme: " + schemeName); }
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
		ok.addActionListener(e -> {
			int newSize = (Integer) spinner.getValue();
			int delta = newSize - terminalPanel.getTerminalFont().getSize();
			changeFontSize(delta);
			dialog.dispose();
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
		applyPreset.addActionListener(e -> {
			applyColorScheme((String) schemeBox.getSelectedItem());
			dialog.dispose();
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

	public void showColorSchemeDialogOld() {
		JDialog dialog = new JDialog(getParentFrame(), "Color Settings", true);
		dialog.setLayout(new BorderLayout());
		JPanel mainPanel = new JPanel(new GridBagLayout());
		mainPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.insets = new Insets(5, 5, 5, 5);
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
		applyPreset.addActionListener(e -> {
			applyColorScheme((String) schemeBox.getSelectedItem());
			dialog.dispose();
		});
		JPanel presetPanel = new JPanel(new BorderLayout(5, 0));
		presetPanel.add(schemeBox, BorderLayout.CENTER);
		presetPanel.add(applyPreset, BorderLayout.EAST);
		mainPanel.add(presetPanel, gbc);

		// Restore Custom Color Buttons
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

	/*
	 * public void setFontSize(int size) { terminalPanel.setFont(new
	 * Font("Monospaced", Font.PLAIN, size)); }
	 */
	// Also update setters/dialog callbacks if they set size directly
	/*
	public void setFontSize(int size) {
		terminalPanel.setFont(new Font("Monospaced", Font.PLAIN, size));
		SwingUtilities.invokeLater(this::resizeWindowToFitContent);
	}
*/
    public void setFontSize(int size) {
        if (terminalPanel == null) return;
        // Re-use logic to ensure safety
        changeFontSize(size - terminalPanel.getTerminalFont().getSize());
    }
    
    public void setFontSizeNoResize(int size) {
        if (terminalPanel == null) return;
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
}
