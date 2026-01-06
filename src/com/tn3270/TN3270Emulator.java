package com.tn3270;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.ItemEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

import com.tn3270.ui.EnhancedRibbonToolbar;
import com.tn3270.ui.ModernKeyboardPanel;
import com.tn3270.util.LoggerSetup;

public class TN3270Emulator extends JFrame {
	private static final Logger logger = LoggerSetup.getLogger(TN3270Emulator.class);

	// =======================================================================
	// 1. STATE & ENUMS
	// =======================================================================

	public enum ViewMode {
		TABS, TILES
	}

	private ViewMode currentViewMode = ViewMode.TABS;
	private final List<TN3270Session> activeSessions = new ArrayList<>();
	private JPanel mainContentPanel;

	// UI References (Class-level to allow syncing)
	private JRadioButtonMenuItem menuViewTabs;
	private JRadioButtonMenuItem menuViewTiles;

	private EnhancedRibbonToolbar ribbon;
	private ModernKeyboardPanel keyboardPanel;

	private static final String PROFILES_FILE = System.getProperty("user.home") + File.separator + ".tn3270profiles";
	private static final Map<String, ConnectionProfile> savedProfiles = new HashMap<>();

	static {
		loadProfiles();
	}

	public static class ConnectionProfile {
		String name, hostname, model, luName;
		int port;
		boolean useTLS;

		public ConnectionProfile(String name, String hostname, int port, String model, String luName, boolean useTLS) {
			this.name = name;
			this.hostname = hostname;
			this.port = port;
			this.model = model;
			this.luName = luName;
			this.useTLS = useTLS;
		}

		@Override
		public String toString() {
			return name + " (" + hostname + ":" + port + ")";
		}
	}

	// =======================================================================
	// 2. CONSTRUCTOR & INIT
	// =======================================================================

	public TN3270Emulator(String initialModel) {
		super("TN3270 Emulator");
		setLayout(new BorderLayout());
		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

		mainContentPanel = new JPanel(new BorderLayout());
		mainContentPanel.setBackground(Color.DARK_GRAY);
		add(mainContentPanel, BorderLayout.CENTER);

		createMenuBar();
		ribbon = new EnhancedRibbonToolbar(this);
		add(ribbon, BorderLayout.NORTH);

		keyboardPanel = new ModernKeyboardPanel(this);
		add(keyboardPanel, BorderLayout.SOUTH);

		if (initialModel != null) {
			if (initialModel.trim().isEmpty())
				initialModel = "3279-3";
			openNewSession("New Session", null, 0, initialModel, "", false);
		}

		setupGlobalHandlers();

		pack();
		if (getWidth() < 1100)
			setSize(1100, 850);
		setLocationRelativeTo(null);
		setVisible(true);
	}

	private void setupGlobalHandlers() {
		addWindowFocusListener(new WindowAdapter() {
			public void windowGainedFocus(WindowEvent e) {
				TN3270Session s = getCurrentSession();
				if (s != null)
					s.requestFocusInWindow();
			}
		});

		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				closeAllSessionsAndExit();
			}
		});
	}

	// =======================================================================
	// 3. SESSION MANAGEMENT
	// =======================================================================

	private String generateUniqueTitle(String baseName) {
		String candidate = baseName;
		int counter = 1;
		boolean unique = false;

		while (!unique) {
			unique = true;
			for (TN3270Session s : activeSessions) {
				String existing = (String) s.getClientProperty("title");
				if (existing != null && existing.equals(candidate)) {
					unique = false;
					break;
				}
			}
			if (!unique) {
				candidate = baseName + " - " + counter++;
			}
		}
		return candidate;
	}

	public void handleConnectRequest(String title, String h, int p, String m, String l, boolean t) {
		TN3270Session current = getCurrentSession();

		// FIX: Aggressively detect if we are replacing the default placeholder.
		// If the current session is disconnected and named "New Session",
		// remove it immediately to force a clean build of the requested model.
		boolean isPlaceholder = (current != null && !current.isConnected()
				&& "New Session".equals(current.getClientProperty("title")));

		if (isPlaceholder) {
			activeSessions.remove(current);
			current = null; // Force new session creation below
		}

		// Smart Reuse Logic (Only if it's NOT a placeholder we just killed)
		if (current != null && !current.isConnected() && current.getModelName().equals(m)) {
			String oldTitle = (String) current.getClientProperty("title");
			if (!title.equals(oldTitle))
				title = generateUniqueTitle(title);
			current.setRequestedLuName(l);
			current.setUseTLS(t);
			current.putClientProperty("title", title);
			current.connect(h, p);
			updateViewLayout();
		} else {
			// Standard New Session
			if (current != null && !current.isConnected()) {
				activeSessions.remove(current);
			}
			openNewSession(title, h, p, m, l, t);
		}
	}

	public void openNewSession(String title, String h, int p, String m, String l, boolean t) {
		title = generateUniqueTitle(title);
		TN3270Session session = new TN3270Session(m, this);
		session.setUseTLS(t);
		session.setRequestedLuName(l);
		session.putClientProperty("title", title);
		session.setAutoFitOnResize(currentViewMode == ViewMode.TILES);

		activeSessions.add(session);
		if (h != null)
			session.connect(h, p);

		updateViewLayout();
		selectSession(session);

		// FIX: Auto-Snap Window Size
		if (activeSessions.size() == 1) {
			SwingUtilities.invokeLater(() -> {
				int defSize = 14;
				if (m.startsWith("3290"))
					defSize = 9;
				else if (m.startsWith("3278-5"))
					defSize = 11;

				// 1. Set the font
				session.setFontSizeNoResize(defSize);

				// 2. Force the TerminalPanel to recalculate its preferred size based on the new
				// font
				session.terminalPanel.updateSize();

				// 3. CRITICAL FIX: Validate the session container.
				// This forces the JScrollPane to detect the new preferred size of the
				// TerminalPanel.
				// Without this, the JScrollPane might report an old/default size to the Frame.
				session.validate();

				// 4. Use the session's robust snap logic (handles screen bounds & insets)
				session.snapWindow();

				// 5. Center
				this.setLocationRelativeTo(null);
			});
		}
	}

	public void addExistingSession(TN3270Session session) {
		String title = (String) session.getClientProperty("title");
		String newTitle = generateUniqueTitle(title);
		session.putClientProperty("title", newTitle);

		activeSessions.add(session);
		updateViewLayout();
		selectSession(session);

		session.requestFocusInWindow();
		toFront();
	}

	private void selectSession(TN3270Session session) {
		if (currentViewMode == ViewMode.TABS && mainContentPanel.getComponentCount() > 0) {
			Component c = mainContentPanel.getComponent(0);
			if (c instanceof JTabbedPane) {
				JTabbedPane tabs = (JTabbedPane) c;
				tabs.setSelectedComponent(session);
			}
		}
	}

	// --- WINDOW MANAGEMENT ---

	public void popOutSession(TN3270Session session) {
		if (session == null)
			return;

		activeSessions.remove(session);

		if (activeSessions.isEmpty()) {
			openNewSession("New Session", null, 0, "3279-3", "", false);
		} else {
			updateViewLayout();
		}

		TN3270Emulator newFrame = new TN3270Emulator(null);
		Point pt = getLocation();
		newFrame.setLocation(pt.x + 40, pt.y + 40);

		// FIX: Restore the "Smart Default" font for this specific model.
		// This prevents the "Tiny Window" effect if coming from Tiles,
		// but respects the density of 3290/Model-5 screens.
		String m = session.getModelName();
		int defSize = 14;
		if (m.startsWith("3290"))
			defSize = 9;
		else if (m.startsWith("3278-5"))
			defSize = 11;

		session.setFontSizeNoResize(defSize);

		newFrame.addExistingSession(session);
	}

	public void popInSession(TN3270Session session) {
		TN3270Emulator target = findBestTargetWindow();
		if (target == null)
			return;

		activeSessions.remove(session);
		target.addExistingSession(session);

		if (activeSessions.isEmpty()) {
			dispose();
		} else {
			updateViewLayout();
		}
	}

	public void popInAllSessions() {
		TN3270Emulator target = findBestTargetWindow();
		if (target == null) {
			JOptionPane.showMessageDialog(this, "No other Main Window found to merge into.", "Pop-in Failed",
					JOptionPane.WARNING_MESSAGE);
			return;
		}

		List<TN3270Session> sessionsToMove = new ArrayList<>(activeSessions);
		for (TN3270Session session : sessionsToMove) {
			activeSessions.remove(session);
			target.addExistingSession(session);
		}
		this.dispose();
	}

	private TN3270Emulator findBestTargetWindow() {
		Frame[] frames = Frame.getFrames();
		TN3270Emulator candidate = null;
		int maxSessions = -1;

		for (Frame f : frames) {
			if (f instanceof TN3270Emulator && f != this && f.isVisible()) {
				TN3270Emulator emu = (TN3270Emulator) f;
				int count = emu.activeSessions.size();
				if (count > maxSessions) {
					maxSessions = count;
					candidate = emu;
				}
			}
		}
		return candidate;
	}

	public void closeSession(TN3270Session session) {
		if (session == null)
			return;
		session.disconnect();
		activeSessions.remove(session);
		if (activeSessions.isEmpty()) {
			openNewSession("New Session", null, 0, "3279-3", "", false);
		} else {
			updateViewLayout();

			// FIX: Force the window to snap to the remaining session
			SwingUtilities.invokeLater(() -> {
				TN3270Session current = getCurrentSession();
				if (current != null)
					current.snapWindow();
			});
		}
	}

	private void closeAllSessionsAndExit() {
		for (TN3270Session s : activeSessions) {
			s.disconnect();
		}
		dispose();
		boolean anyVisible = false;
		for (Frame f : Frame.getFrames()) {
			if (f.isVisible()) {
				anyVisible = true;
				break;
			}
		}
		if (!anyVisible)
			System.exit(0);
	}

	public TN3270Session getCurrentSession() {
		if (activeSessions.isEmpty())
			return null;
		if (currentViewMode == ViewMode.TABS && mainContentPanel.getComponentCount() > 0) {
			Component c = mainContentPanel.getComponent(0);
			if (c instanceof JTabbedPane) {
				return (TN3270Session) ((JTabbedPane) c).getSelectedComponent();
			}
		}
		for (TN3270Session s : activeSessions) {
			if (s.isFocusOwner() || s.terminalPanel.isFocusOwner())
				return s;
		}
		return activeSessions.get(0);
	}

	// =======================================================================
	// 4. VIEW MANAGER (Tabs vs Tiles)
	// =======================================================================

	public void setViewMode(ViewMode mode) {
		this.currentViewMode = mode;

		// SYNC MENUS
		if (menuViewTabs != null && menuViewTiles != null) {
			if (mode == ViewMode.TABS)
				menuViewTabs.setSelected(true);
			else
				menuViewTiles.setSelected(true);
		}

		updateViewLayout();
		SwingUtilities.invokeLater(() -> {
			TN3270Session s = getCurrentSession();
			if (s != null)
				s.requestFocusInWindow();
		});
	}

	// -----------------------------------------------------------------------
	// NEW: Logic to Auto-Grow Window for Tiles
	// -----------------------------------------------------------------------

	/**
	 * Adjusts the window size to accommodate multiple tiled sessions.
	 * 
	 * REGRESSION NOTE: "CRUSHED" WINDOWS When switching from Tabs to Tiles, or
	 * adding a new Tile, the existing window size is often too small to show
	 * multiple sessions, resulting in tiny, unreadable fonts.
	 * 
	 * Strategy: 1. "Prime" all sessions by forcing them to a target font (e.g.,
	 * 14pt) via 'setFontSizeNoResize'. 2. Call 'pack()'. The Layout Manager
	 * calculates the total space needed for X tiles at 14pt. 3. The Window grows to
	 * this new size. 4. Finally, we re-enable 'AutoFitOnResize' so subsequent user
	 * drags scale the fonts dynamically.
	 */
	private void snapWindowToTiles() {
		if (activeSessions.isEmpty())
			return;

		// 1. Prime all sessions to a legible "Target Font" (e.g. 14pt)
		for (TN3270Session s : activeSessions) {
			s.setFontSizeNoResize(14);
		}

		// 2. Pack the Window
		pack();

		// 3. Clamp to Screen Size
		GraphicsConfiguration gc = getGraphicsConfiguration();
		Rectangle bounds = gc.getBounds();
		Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);

		int maxW = bounds.width - insets.left - insets.right;
		int maxH = bounds.height - insets.top - insets.bottom;

		int w = Math.min(getWidth(), maxW);
		int h = Math.min(getHeight(), maxH);

		if (w != getWidth() || h != getHeight()) {
			setSize(w, h);
		}

		// 4. Force Layout & Enable AutoFit (The Fix)
		// Pass 1: Request a re-layout of the container
		mainContentPanel.revalidate();

		// Pass 2: Wait for the re-layout to happen
		SwingUtilities.invokeLater(() -> {
			// Pass 3: Wait ONE MORE cycle to ensure the ScrollPanes inside the sessions
			// have actually updated their 'width' and 'height' fields.
			SwingUtilities.invokeLater(() -> {
				for (TN3270Session s : activeSessions) {
					s.setAutoFitOnResize(true); // This triggers the correct fitToSize()
				}
			});
		});
	}

	// -----------------------------------------------------------------------
	// UPDATED: updateViewLayout
	// -----------------------------------------------------------------------
	private void updateViewLayout() {
		mainContentPanel.removeAll();
		if (currentViewMode == ViewMode.TABS) {
			renderTabs();
			// TABS MODE: AutoFit = FALSE (Manual Grow allowed)
			for (TN3270Session s : activeSessions)
				s.setAutoFitOnResize(false);

			SwingUtilities.invokeLater(() -> {
				TN3270Session s = getCurrentSession();
				if (s != null)
					s.snapWindow();
			});
		} else {
			renderTiles();
			// TILES MODE:
			// 1. Temporarily Disable AutoFit on all sessions.
			// This prevents them from fighting the layout manager while we pack the window.
			for (TN3270Session s : activeSessions)
				s.setAutoFitOnResize(false);

			// 2. Schedule the Window Snap
			SwingUtilities.invokeLater(this::snapWindowToTiles);
		}
		mainContentPanel.revalidate();
		mainContentPanel.repaint();
	}

	private boolean hasOtherWindows() {
		int count = 0;
		for (Frame f : Frame.getFrames()) {
			if (f instanceof TN3270Emulator && f.isVisible())
				count++;
		}
		return count > 1;
	}

	private void renderTabs() {
		JTabbedPane tabbedPane = new JTabbedPane();

		// FIX: Add listener to update colors when tab is clicked/changed
		tabbedPane.addChangeListener(e -> {
			updateTabStyling(tabbedPane); // Update Colors
			TN3270Session s = (TN3270Session) tabbedPane.getSelectedComponent();
			// if (s != null) s.requestFocusInWindow();
			if (s != null) {
				s.requestFocusInWindow();
				// FIX: Snap window when switching tabs so bands don't persist
				SwingUtilities.invokeLater(s::snapWindow);
			}
		});

		boolean showPopIn = hasOtherWindows();

		for (TN3270Session session : activeSessions) {
			String title = (String) session.getClientProperty("title");
			tabbedPane.addTab(title, session);
			int index = tabbedPane.indexOfComponent(session);

			JPanel tabHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			tabHeader.setOpaque(false);

			// Note: We name the label component so we can find it easily later
			JLabel lblTitle = new JLabel(title + "  ");
			lblTitle.setName("tabTitle");

			// Pop-In Button
			if (showPopIn) {
				JButton btnPopIn = createHeaderButton("↙", "Merge into Main Window");
				btnPopIn.addActionListener(e -> popInSession(session));
				tabHeader.add(btnPopIn);
				tabHeader.add(Box.createHorizontalStrut(4));
			}

			// Tile View Button
			JButton btnTileView = createHeaderButton("⊞", "Switch to Tiled View");
			btnTileView.setFont(new Font("SansSerif", Font.BOLD, 14));
			btnTileView.addActionListener(e -> setViewMode(ViewMode.TILES));
			tabHeader.add(btnTileView);
			tabHeader.add(Box.createHorizontalStrut(2));

			// Close Button
			JButton btnClose = createHeaderButton("×", "Close Session");
			btnClose.addActionListener(e -> closeSession(session));
			btnClose.addMouseListener(new MouseAdapter() {
				public void mouseEntered(MouseEvent e) {
					btnClose.setForeground(Color.RED);
				}

				public void mouseExited(MouseEvent e) {
					btnClose.setForeground(Color.GRAY);
				}
			});

			tabHeader.add(lblTitle);
			tabHeader.add(btnClose);

			// Selection Logic
			MouseAdapter tabInteractionAdapter = new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					if (SwingUtilities.isLeftMouseButton(e)) {
						tabbedPane.setSelectedComponent(session);
					}
				}

				@Override
				public void mouseClicked(MouseEvent e) {
					if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
						popOutSession(session);
					}
				}
			};
			tabHeader.addMouseListener(tabInteractionAdapter);
			lblTitle.addMouseListener(tabInteractionAdapter);

			tabbedPane.setTabComponentAt(index, tabHeader);
		}

		// Initial Style Update
		updateTabStyling(tabbedPane);
		mainContentPanel.add(tabbedPane, BorderLayout.CENTER);
	}

	// FIX: Helper to Colorize Tabs
	private void updateTabStyling(JTabbedPane tabs) {
		int selected = tabs.getSelectedIndex();
		int count = tabs.getTabCount();

		for (int i = 0; i < count; i++) {
			Component c = tabs.getTabComponentAt(i);
			if (c instanceof JPanel) {
				JPanel header = (JPanel) c;
				// Find the label
				for (Component kid : header.getComponents()) {
					if (kid instanceof JLabel && "tabTitle".equals(kid.getName())) {
						JLabel lbl = (JLabel) kid;
						if (i == selected) {
							lbl.setForeground(Color.BLACK); // Or Color.WHITE if you prefer dark mode tabs
							lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
						} else {
							lbl.setForeground(Color.GRAY);
							lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN));
						}
						break;
					}
				}
			}
		}
	}

	private void renderTiles() {
		int count = activeSessions.size();
		if (count == 0)
			return;
		int cols = (int) Math.ceil(Math.sqrt(count));
		int rows = (int) Math.ceil((double) count / cols);

		JPanel gridPanel = new JPanel(new GridLayout(rows, cols, 5, 5));
		gridPanel.setBackground(Color.DARK_GRAY);
		gridPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

		boolean showPopIn = hasOtherWindows();

		for (TN3270Session session : activeSessions) {
			JPanel tileWrapper = new JPanel(new BorderLayout());
			Color inactiveBorder = Color.GRAY;
			Color activeBorder = new Color(0, 180, 60);
			Color headerBg = new Color(230, 230, 230);

			// tileWrapper.setBorder(BorderFactory.createLineBorder(inactiveBorder, 1));
			// REGRESSION FIX: Initialize with the 3px Compound Border.
			// Previously, this was a 1px LineBorder, which caused a size jump
			// (and black border shift) the first time the user clicked the tile.
			tileWrapper.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(inactiveBorder, 1),
					BorderFactory.createEmptyBorder(2, 2, 2, 2)));

			JPanel header = new JPanel(new BorderLayout());
			header.setBackground(headerBg);
			header.setBorder(new EmptyBorder(2, 5, 2, 5));

			String title = (String) session.getClientProperty("title");
			JLabel lblTitle = new JLabel(title);
			lblTitle.setFont(new Font("SansSerif", Font.BOLD, 11));

			// --- MOUSE LISTENER FOR CLICK-TO-FOCUS ---
			MouseAdapter tileHeaderAdapter = new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					if (SwingUtilities.isLeftMouseButton(e)) {
						session.requestFocusInWindow();
					}
				}

				@Override
				public void mouseClicked(MouseEvent e) {
					if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
						popOutSession(session);
					}
				}
			};

			// Add listener to the main header background and the title label
			header.addMouseListener(tileHeaderAdapter);
			lblTitle.addMouseListener(tileHeaderAdapter);

			// Button Container
			JPanel headerBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
			headerBtns.setOpaque(false);
			// CRITICAL FIX: Add listener to the button container so clicks BETWEEN buttons
			// work
			headerBtns.addMouseListener(tileHeaderAdapter);

			// --- Add Buttons ---
			JButton btnFocus = new JButton("Focus");
			btnFocus.setFont(new Font("SansSerif", Font.BOLD, 10));
			btnFocus.setBackground(headerBg);
			btnFocus.setForeground(Color.GRAY);
			btnFocus.setBorder(new LineBorder(Color.GRAY, 1));
			btnFocus.setFocusable(false);
			btnFocus.setPreferredSize(new Dimension(55, 18));
			btnFocus.addActionListener(e -> session.requestFocusInWindow());
			headerBtns.add(btnFocus);

			JButton btnTabify = createHeaderButton("≡", "Tabs");
			btnTabify.setFont(new Font("SansSerif", Font.BOLD, 14));
			btnTabify.addActionListener(e -> {
				setViewMode(ViewMode.TABS);
				selectSession(session);
			});
			headerBtns.add(btnTabify);

			if (showPopIn) {
				JButton btnPopIn = createHeaderButton("↙", "Merge");
				btnPopIn.addActionListener(e -> popInSession(session));
				headerBtns.add(btnPopIn);
			}

			JButton btnClose = createHeaderButton("×", "Close");
			btnClose.addActionListener(e -> closeSession(session));
			headerBtns.add(btnClose);

			header.add(lblTitle, BorderLayout.CENTER);
			header.add(headerBtns, BorderLayout.EAST);

			tileWrapper.add(header, BorderLayout.NORTH);
			tileWrapper.add(session, BorderLayout.CENTER);

			// Focus Highlighting Logic
			FocusListener focusHighlighter = new FocusAdapter() {
				public void focusGained(FocusEvent e) {
					tileWrapper.setBorder(BorderFactory.createLineBorder(activeBorder, 3));
					btnFocus.setText("ACTIVE");
					btnFocus.setForeground(new Color(0, 150, 0));
					btnFocus.setBorder(new LineBorder(new Color(0, 150, 0), 1));
				}

				public void focusLost(FocusEvent e) {
					// tileWrapper.setBorder(BorderFactory.createLineBorder(inactiveBorder, 1));
					// FIX: Maintain constant 3px width (1px Line + 2px Padding)
					// This prevents the terminal geometry from shifting/resizing on click
					tileWrapper.setBorder(
							BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(inactiveBorder, 1),
									BorderFactory.createEmptyBorder(2, 2, 2, 2)));
					btnFocus.setText("Focus");
					btnFocus.setForeground(Color.GRAY);
					btnFocus.setBorder(new LineBorder(Color.GRAY, 1));
				}
			};
			session.terminalPanel.addFocusListener(focusHighlighter);
			if (session.terminalPanel.isFocusOwner())
				focusHighlighter.focusGained(null);

			gridPanel.add(tileWrapper);
		}
		mainContentPanel.add(gridPanel, BorderLayout.CENTER);
	}

	private JButton createHeaderButton(String text, String tooltip) {
		JButton btn = new JButton(text);
		btn.setPreferredSize(new Dimension(20, 18));
		btn.setMargin(new Insets(0, 0, 0, 0));
		btn.setFont(new Font("SansSerif", Font.BOLD, 12));
		btn.setBorder(BorderFactory.createEmptyBorder());
		btn.setContentAreaFilled(false);
		btn.setForeground(Color.GRAY);
		btn.setToolTipText(tooltip);
		btn.setRolloverEnabled(true);
		btn.addMouseListener(new MouseAdapter() {
			public void mouseEntered(MouseEvent e) {
				btn.setForeground(Color.DARK_GRAY);
			}

			public void mouseExited(MouseEvent e) {
				btn.setForeground(Color.GRAY);
			}
		});
		return btn;
	}

	// =======================================================================
	// 5. DELEGATE METHODS
	// =======================================================================

	public void disconnect() {
		TN3270Session session = getCurrentSession();
		if (session != null)
			session.disconnect();
	}

	public void reconnect() {
		TN3270Session session = getCurrentSession();
		if (session != null)
			session.reconnect();
	}

	public void copySelection() {
		TN3270Session session = getCurrentSession();
		if (session != null)
			session.copySelection();
	}

	public void pasteFromClipboard() {
		TN3270Session session = getCurrentSession();
		if (session != null)
			session.pasteFromClipboard();
	}

	public void selectAll() {
		TN3270Session session = getCurrentSession();
		if (session != null)
			session.selectAll();
	}

	public void showFileTransferDialog(boolean isDownload) {
		TN3270Session session = getCurrentSession();
		if (session != null)
			session.showFileTransferDialog(isDownload);
	}

	public void showFontSizeDialog() {
		TN3270Session session = getCurrentSession();
		if (session != null)
			session.showFontSizeDialog();
	}

	public void showColorSchemeDialog() {
		TN3270Session session = getCurrentSession();
		if (session != null)
			session.showColorSchemeDialog();
	}

	public void showKeyboardMappingDialog() {
		TN3270Session session = getCurrentSession();
		if (session != null)
			session.showKeyboardMappingDialog();
	}

	public void showTerminalSettingsDialog() {
		TN3270Session session = getCurrentSession();
		if (session != null)
			session.showTerminalSettingsDialog();
	}

	public String getSelectedText() {
		TN3270Session session = getCurrentSession();
		return (session != null) ? session.getSelectedText() : "";
	}

	// =======================================================================
	// 6. MENUS
	// =======================================================================

	private void createMenuBar() {
		JMenuBar menuBar = new JMenuBar();
		int shortcutKey = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

		// --- FILE ---
		JMenu fileMenu = new JMenu("File");
		JMenuItem newItem = new JMenuItem("New Connection (Current Window)...");
		newItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, shortcutKey));
		newItem.addActionListener(e -> showConnectionDialog(this));
		fileMenu.add(newItem);
		JMenuItem newWinItem = new JMenuItem("New Window");
		newWinItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, shortcutKey | InputEvent.SHIFT_DOWN_MASK));
		newWinItem.addActionListener(e -> {
			TN3270Emulator newFrame = new TN3270Emulator("3279-3");
			showConnectionDialog(newFrame);
		});
		fileMenu.add(newWinItem);
		fileMenu.addSeparator();
		JMenuItem uploadItem = new JMenuItem("Upload to Host...");
		uploadItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_U, shortcutKey));
		uploadItem.addActionListener(e -> showFileTransferDialog(false));
		fileMenu.add(uploadItem);
		JMenuItem downloadItem = new JMenuItem("Download from Host...");
		downloadItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, shortcutKey));
		downloadItem.addActionListener(e -> showFileTransferDialog(true));
		fileMenu.add(downloadItem);
		/*
		 * // --- NEW: HOST SYSTEM TOGGLE --- fileMenu.addSeparator(); JMenu
		 * hostTypeMenu = new JMenu("Host System");
		 * 
		 * JRadioButtonMenuItem rbTSO = new JRadioButtonMenuItem("TSO (z/OS)");
		 * JRadioButtonMenuItem rbCMS = new JRadioButtonMenuItem("CMS (z/VM)");
		 * ButtonGroup hostGroup = new ButtonGroup(); hostGroup.add(rbTSO);
		 * hostGroup.add(rbCMS);
		 * 
		 * // Listener to update the CURRENT session when clicked ActionListener
		 * hostTypeListener = e -> { TN3270Session s = getCurrentSession(); if (s !=
		 * null) { if (rbTSO.isSelected()) s.setHostType(TN3270Session.HostType.TSO);
		 * else s.setHostType(TN3270Session.HostType.CMS); } };
		 * 
		 * rbTSO.addActionListener(hostTypeListener);
		 * rbCMS.addActionListener(hostTypeListener);
		 * 
		 * // Default selection (visual only, real state is in Session)
		 * rbCMS.setSelected(true);
		 * 
		 * hostTypeMenu.add(rbTSO); hostTypeMenu.add(rbCMS); fileMenu.add(hostTypeMenu);
		 */
		fileMenu.addSeparator();
		JMenuItem disconnectItem = new JMenuItem("Disconnect");
		disconnectItem.addActionListener(e -> disconnect());
		fileMenu.add(disconnectItem);
		JMenuItem reconnectItem = new JMenuItem("Reconnect");
		reconnectItem.addActionListener(e -> reconnect());
		fileMenu.add(reconnectItem);
		JMenuItem closeSessItem = new JMenuItem("Close Current Session");
		closeSessItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, shortcutKey));
		closeSessItem.addActionListener(e -> closeSession(getCurrentSession()));
		fileMenu.add(closeSessItem);
		fileMenu.addSeparator();
		JMenuItem exitItem = new JMenuItem("Exit");
		exitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, shortcutKey));
		exitItem.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));
		fileMenu.add(exitItem);
		menuBar.add(fileMenu);

		// --- EDIT ---
		JMenu editMenu = new JMenu("Edit");
		JMenuItem copyItem = new JMenuItem("Copy");
		copyItem.addActionListener(e -> copySelection());
		editMenu.add(copyItem);
		JMenuItem pasteItem = new JMenuItem("Paste");
		pasteItem.addActionListener(e -> pasteFromClipboard());
		editMenu.add(pasteItem);
		editMenu.addSeparator();
		JMenuItem selectAllItem = new JMenuItem("Select All");
		selectAllItem.addActionListener(e -> selectAll());
		editMenu.add(selectAllItem);
		editMenu.addSeparator();
		JMenuItem askAIItem = new JMenuItem("Ask AI");
		askAIItem.addActionListener(e -> {
			TN3270Session session = getCurrentSession();
			if (session != null)
				session.showAIChatDialog(this, session.getSelectedText());
		});
		editMenu.add(askAIItem);
		menuBar.add(editMenu);

		// --- VIEW ---
		JMenu viewMenu = new JMenu("View");
		menuViewTabs = new JRadioButtonMenuItem("Tabbed View", true);
		menuViewTabs.addActionListener(e -> setViewMode(ViewMode.TABS));
		menuViewTiles = new JRadioButtonMenuItem("Tiled View", false);
		menuViewTiles.addActionListener(e -> setViewMode(ViewMode.TILES));
		ButtonGroup bg = new ButtonGroup();
		bg.add(menuViewTabs);
		bg.add(menuViewTiles);
		viewMenu.add(menuViewTabs);
		viewMenu.add(menuViewTiles);
		viewMenu.addSeparator();
		JMenuItem popInItem = new JMenuItem("Pop-in / Merge to Main Window");
		popInItem.addActionListener(e -> popInAllSessions());
		viewMenu.add(popInItem);
		viewMenu.addSeparator();
		JCheckBoxMenuItem showKeyboardItem = new JCheckBoxMenuItem("Show Keyboard Panel", true);
		showKeyboardItem.addItemListener(e -> {
			keyboardPanel.setVisible(showKeyboardItem.getState());
			revalidate();
			repaint();
		});
		viewMenu.add(showKeyboardItem);
		viewMenu.addSeparator();
		JMenuItem fontSizeItem = new JMenuItem("Font Size...");
		fontSizeItem.addActionListener(e -> showFontSizeDialog());
		viewMenu.add(fontSizeItem);
		JMenuItem colorSchemeItem = new JMenuItem("Color Scheme...");
		colorSchemeItem.addActionListener(e -> showColorSchemeDialog());
		viewMenu.add(colorSchemeItem);
		menuBar.add(viewMenu);

		// --- SETTINGS ---
		JMenu settingsMenu = new JMenu("Settings");
		JMenuItem keyMapItem = new JMenuItem("Keyboard Mapping...");
		keyMapItem.addActionListener(e -> showKeyboardMappingDialog());
		settingsMenu.add(keyMapItem);
		JMenuItem termSetItem = new JMenuItem("Terminal Settings...");
		termSetItem.addActionListener(e -> showTerminalSettingsDialog());
		settingsMenu.add(termSetItem);
		menuBar.add(settingsMenu);

		// --- HELP ---
		JMenu helpMenu = new JMenu("Help");
		JMenuItem aboutItem = new JMenuItem("About");
		aboutItem.addActionListener(e -> showAboutDialog());
		helpMenu.add(aboutItem);
		JMenuItem keyboardHelpItem = new JMenuItem("Keyboard Reference");
		keyboardHelpItem.addActionListener(e -> showKeyboardReference());
		helpMenu.add(keyboardHelpItem);
		menuBar.add(helpMenu);

		setJMenuBar(menuBar);
	}

	// =======================================================================
	// 7. CONNECTION DIALOG
	// =======================================================================

	public static void showConnectionDialog(TN3270Emulator targetFrame) {
		JDialog dialog = new JDialog(targetFrame, "Connect to Host", true);
		dialog.setLayout(new BorderLayout(15, 15));

		JPanel mainContainer = new JPanel(new BorderLayout(10, 10));
		mainContainer.setBorder(new EmptyBorder(15, 15, 15, 15));

		JPanel topPanel = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 0;
		topPanel.add(new JLabel("Saved Profiles:"), gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;

		JComboBox<String> profileChoice = new JComboBox<>();
		profileChoice.addItem("(New Connection)");
		for (String profileName : savedProfiles.keySet())
			profileChoice.addItem(profileName);
		topPanel.add(profileChoice, gbc);
		mainContainer.add(topPanel, BorderLayout.NORTH);

		JPanel centerPanel = new JPanel(new GridBagLayout());
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 0;
		centerPanel.add(new JLabel("Hostname:", SwingConstants.RIGHT), gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;
		JTextField hostnameField = new JTextField("localhost", 20);
		centerPanel.add(hostnameField, gbc);

		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.weightx = 0;
		centerPanel.add(new JLabel("Port:", SwingConstants.RIGHT), gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;
		JTextField portField = new JTextField("23", 10);
		centerPanel.add(portField, gbc);

		gbc.gridx = 0;
		gbc.gridy = 2;
		gbc.weightx = 0;
		centerPanel.add(new JLabel("LU Name:", SwingConstants.RIGHT), gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;
		JTextField luNameField = new JTextField("", 10);
		centerPanel.add(luNameField, gbc);

		gbc.gridx = 0;
		gbc.gridy = 3;
		gbc.weightx = 0;
		centerPanel.add(new JLabel("Model:", SwingConstants.RIGHT), gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;
		JComboBox<String> modelChoice = new JComboBox<>();
		modelChoice.addItem("3278-2 (24x80)");
		modelChoice.addItem("3279-2 (24x80 Color)");
		modelChoice.addItem("3278-3 (32x80)");
		modelChoice.addItem("3279-3 (32x80 Color)");
		modelChoice.addItem("3278-4 (43x80)");
		modelChoice.addItem("3278-5 (27x132)");
		modelChoice.addItem("3290 (62x160 Large)"); // Added 3290 Support
		modelChoice.setSelectedIndex(3);
		centerPanel.add(modelChoice, gbc);

		gbc.gridx = 1;
		gbc.gridy = 4;
		gbc.weightx = 1.0;
		JCheckBox tlsCheckbox = new JCheckBox("Use TLS/SSL encryption");
		centerPanel.add(tlsCheckbox, gbc);

		mainContainer.add(centerPanel, BorderLayout.CENTER);
		dialog.add(mainContainer, BorderLayout.CENTER);

		JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
		JButton saveButton = new JButton("Save");
		JButton deleteButton = new JButton("Delete");
		JButton connectButton = new JButton("Connect");
		JButton cancelButton = new JButton("Cancel");
		deleteButton.setEnabled(false);

		bottomPanel.add(saveButton);
		bottomPanel.add(deleteButton);
		bottomPanel.add(Box.createHorizontalStrut(20));
		bottomPanel.add(connectButton);
		bottomPanel.add(cancelButton);
		dialog.add(bottomPanel, BorderLayout.SOUTH);
		dialog.getRootPane().setDefaultButton(connectButton);

		profileChoice.addItemListener(e -> {
			if (e.getStateChange() == ItemEvent.SELECTED) {
				String selected = (String) e.getItem();
				if (selected.equals("(New Connection)")) {
					hostnameField.setText("localhost");
					portField.setText("23");
					luNameField.setText("");
					modelChoice.setSelectedIndex(3);
					tlsCheckbox.setSelected(false);
					deleteButton.setEnabled(false);
				} else {
					ConnectionProfile profile = savedProfiles.get(selected);
					if (profile != null) {
						hostnameField.setText(profile.hostname);
						portField.setText(String.valueOf(profile.port));
						luNameField.setText(profile.luName != null ? profile.luName : "");
						tlsCheckbox.setSelected(profile.useTLS);
						for (int i = 0; i < modelChoice.getItemCount(); i++) {
							if (modelChoice.getItemAt(i).startsWith(profile.model)) {
								modelChoice.setSelectedIndex(i);
								break;
							}
						}
						deleteButton.setEnabled(true);
					}
				}
			}
		});

		saveButton.addActionListener(e -> {
			String current = (String) profileChoice.getSelectedItem();
			String defaultName = current.equals("(New Connection)") ? "" : current;
			String name = JOptionPane.showInputDialog(dialog, "Profile Name:", defaultName);
			if (name != null && !name.trim().isEmpty()) {
				name = name.replace(",", " "); // Prevent CSV breakage
				String host = hostnameField.getText().trim();
				int port = 23;
				try {
					port = Integer.parseInt(portField.getText().trim());
				} catch (Exception ex) {
				}
				String modStr = (String) modelChoice.getSelectedItem();
				String model = modStr.split(" ")[0];
				String lu = luNameField.getText().trim();
				boolean tls = tlsCheckbox.isSelected();
				savedProfiles.put(name, new ConnectionProfile(name, host, port, model, lu, tls));
				saveProfiles();
				if (((DefaultComboBoxModel) profileChoice.getModel()).getIndexOf(name) == -1)
					profileChoice.addItem(name);
				profileChoice.setSelectedItem(name);
			}
		});

		deleteButton.addActionListener(e -> {
			String selected = (String) profileChoice.getSelectedItem();
			if (!selected.equals("(New Connection)")) {
				if (JOptionPane.showConfirmDialog(dialog, "Delete profile '" + selected + "'?", "Confirm",
						JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
					savedProfiles.remove(selected);
					saveProfiles();
					profileChoice.removeItem(selected);
					profileChoice.setSelectedIndex(0);
				}
			}
		});

		connectButton.addActionListener(e -> {
			String host = hostnameField.getText().trim();
			if (host.isEmpty())
				host = "localhost";
			int port = 23;
			try {
				port = Integer.parseInt(portField.getText().trim());
			} catch (Exception ex) {
			}
			String modStr = (String) modelChoice.getSelectedItem();
			String model = modStr.split(" ")[0];
			String lu = luNameField.getText().trim();
			boolean tls = tlsCheckbox.isSelected();

			dialog.dispose();

			String selectedProfile = (String) profileChoice.getSelectedItem();
			String sessionTitle = (selectedProfile != null && !selectedProfile.equals("(New Connection)"))
					? selectedProfile
					: host;

			if (targetFrame != null) {
				targetFrame.handleConnectRequest(sessionTitle, host, port, model, lu, tls);
			} else {
				TN3270Emulator emu = new TN3270Emulator(model);
				emu.handleConnectRequest(sessionTitle, host, port, model, lu, tls);
			}
		});

		cancelButton.addActionListener(e -> dialog.dispose());
		dialog.pack();
		dialog.setLocationRelativeTo(targetFrame);
		dialog.setVisible(true);
	}

	private static void loadProfiles() {
		File file = new File(PROFILES_FILE);
		if (!file.exists()) {
			savedProfiles.put("Local z/VM", new ConnectionProfile("Local z/VM", "localhost", 23, "3279-3", "", false));
			return;
		}
		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");
				if (parts.length >= 5) {
					String name = parts[0].trim();
					String host = parts[1].trim();
					int port = 23;
					try {
						port = Integer.parseInt(parts[2].trim());
					} catch (Exception e) {
					}
					String model = parts[3].trim();
					String luName = "";
					boolean useTLS = false;
					if (parts.length == 5)
						useTLS = Boolean.parseBoolean(parts[4].trim());
					else if (parts.length >= 6) {
						luName = parts[4].trim();
						useTLS = Boolean.parseBoolean(parts[5].trim());
					}
					savedProfiles.put(name, new ConnectionProfile(name, host, port, model, luName, useTLS));
				}
			}
		} catch (IOException e) {
			//System.err.println("Error loading profiles: " + e.getMessage());
	        logger.severe("Error loading profiles: " + e.getMessage());
	        // Or with full stack trace:
	        // logger.log(Level.SEVERE, "Error loading profiles", e);
		}
	}

	public static void saveProfiles() {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(PROFILES_FILE))) {
			for (ConnectionProfile p : savedProfiles.values()) {
				writer.write(p.name + "," + p.hostname + "," + p.port + "," + p.model + ","
						+ (p.luName == null ? "" : p.luName) + "," + p.useTLS);
				writer.newLine();
			}
		} catch (IOException e) {
			//System.err.println("Error saving profiles: " + e.getMessage());
			logger.severe("Error saving profiles: " + e.getMessage());
		}
	}

	// =======================================================================
	// 8. MAIN
	// =======================================================================

	public static void main(String[] args) {
		String osName = System.getProperty("os.name").toLowerCase();
		if (osName.contains("mac")) {
			System.setProperty("apple.laf.useScreenMenuBar", "true");
			System.setProperty("apple.awt.application.name", "TN3270");
		}
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception e) {
		}

		if (args.length == 0) {
			TN3270Emulator emu = new TN3270Emulator("3279-3");
			showConnectionDialog(emu);
		} else {
			new TN3270Emulator("3279-3").openNewSession(args[0], args[0], 23, "3279-3", "", false);
		}
	}

	private void showAboutDialog() {
		JOptionPane.showMessageDialog(this,
				"TN3270 Emulator\nVersion 2.3 (Tabbed/Tiled/Pop-out/Pop-in)\n\n"
						+ "Features:\n- Multiple View Modes\n- Session Management\n- AI Assistant\n- IND$FILE\n\n"
						+ "Design by: Arty Ecock\n" + "Coding by: Claude, ChatGPT and Gemnini",
				"About", JOptionPane.INFORMATION_MESSAGE);
	}

	private void showKeyboardReference() {
		JDialog dialog = new JDialog(this, "Keyboard Reference", false);
		dialog.setLayout(new BorderLayout());
		JTextArea textArea = new JTextArea(15, 50);
		textArea.setEditable(false);
		textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
		textArea.setMargin(new Insets(10, 10, 10, 10));
		textArea.setText(
				"F1-F12       PF1-PF12\nShift+F1-12  PF13-PF24\nEnter        Send AID (Enter)\nCtrl+C       Copy\nCtrl+V       Paste\nCtrl+A       Select All\nCtrl+Shift+A Ask AI");
		dialog.add(new JScrollPane(textArea), BorderLayout.CENTER);
		JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton b = new JButton("Close");
		b.addActionListener(e -> dialog.dispose());
		p.add(b);
		dialog.add(p, BorderLayout.SOUTH);
		dialog.pack();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}
}
