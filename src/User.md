***

# TN3270 Emulator with AI Integration - User Guide

## 1. Introduction
Welcome to the TN3270 Emulator. This is a modern, Java-based terminal emulator designed for connecting to IBM Mainframe systems (z/OS, z/VM, VSE). It features advanced session management (Tabs and Tiling), secure TLS connections, IND$FILE transfer, and integrated AI assistance.

## 2. Getting Started

### System Requirements
*   **Java Runtime Environment (JRE):** Version 11 or higher.
*   **Display:** Supports HiDPI and standard monitors.

### Launching the Application
Run the emulator via the command line or by double-clicking the JAR file:
```bash
java -jar TN3270Emulator.jar
```
*Optional:* You can pass a hostname as an argument to quick-connect:
```bash
java -jar TN3270Emulator.jar towel.blinkenlights.nl
```

---

## 3. Connecting to a Host

Upon launch, the **Connection Dialog** appears.

### Connection Parameters
*   **Hostname:** The IP address or DNS name of the mainframe.
*   **Port:** Usually `23` for unencrypted Telnet or `992` for TLS/SSL.
*   **LU Name:** (Optional) Specific Logical Unit name if required by your host.
*   **Model:** Select the screen size:
    *   **3278-2 / 3279-2:** Standard (24 Rows x 80 Cols)
    *   **3278-3 / 3279-3:** Extended (32 Rows x 80 Cols)
    *   **3278-4:** Large (43 Rows x 80 Cols)
    *   **3278-5:** Wide (27 Rows x 132 Cols)
    *   **3290:** Ultra-Large Plasma (62 Rows x 160 Cols) - *Auto-applies Amber Theme*
*   **Use TLS/SSL:** Check this for secure connections (HTTPS-style encryption).

### Managing Profiles
*   **Save:** After entering details, click **Save**, enter a name, and the profile will be stored for future use.
*   **Delete:** Select a profile from the dropdown and click **Delete** to remove it.

---

## 4. Session Management & View Modes

This emulator supports managing multiple connections simultaneously using two distinct view modes. You can switch modes via the **View** menu or the toolbar buttons.

### Tabbed View (Default)
Displays one session at a time with tabs at the top.
*   **Window Behavior:** In this mode, the window acts like "Notepad".
    *   **Changing Font Size:** The window automatically snaps/resizes to wrap tightly around the terminal content.
    *   **Resizing Window:** If you shrink the window manually, scrollbars will appear. If you expand it, black borders may appear.
*   **Closing Tabs:** Closing a tab automatically snaps the window size to fit the remaining session.

### Tiled View (Grid)
Displays all active sessions simultaneously in a grid layout.
*   **Window Behavior:** In this mode, the window acts like a "Dashboard".
    *   **Auto-Fit:** Resizing the main window automatically scales the font size of **all** tiles to fit the new area.
    *   **Auto-Grow:** Adding a new session automatically expands the window width to accommodate the new tile without crushing existing ones.
*   **Focusing:** Click anywhere on a tile's header (or the terminal itself) to make it active. The active tile has a green border.

### Multi-Window (Pop-Out / Pop-In)
*   **Pop-Out:** Double-click a tab or a tile header (or use the **Pop-Out** icon) to detach that session into its own separate window.
*   **Pop-In (Merge):** Click the **Merge / Pop-In (↙)** button in the header of a detached window to pull that session back into the main application window.

---

## 5. Display & Appearance

### Font Sizing
*   **Shortcuts:** Press `Ctrl +` to increase size, `Ctrl -` to decrease.
*   **Menu:** Go to **View > Font Size...** to set a specific point size (6pt - 72pt).
*   **Smart Scaling:** The emulator uses a "Stretch-to-Fit" algorithm. If the calculated font size leaves small black gaps at the edge of the screen, the display is slightly stretched to fill the viewport perfectly.

### Color Schemes
Go to **View > Color Scheme...** to select presets:
*   **Green on Black:** Classic phosphor look.
*   **IBM 3270 Blue:** Standard corporate theme.
*   **Solarized Dark:** Modern, low-contrast theme for reduced eye strain.
*   **Amber:** Automatically applied for Model 3290 sessions.
*   **Custom:** You can manually customize Background, Foreground, and Cursor colors.

### Cursor Styles
Go to **Settings > Terminal Settings** to choose:
*   **Block:** Standard 3270 rectangle.
*   **Underscore:** Thin line at the bottom.
*   **I-Beam:** Vertical bar (like a text editor).
*   **Crosshair:** Enables ultra-light horizontal and vertical lines spanning the entire screen to help align fields on complex forms.

---

## 6. Keyboard & Navigation

The emulator maps PC keys to IBM 3270 keys.

| PC Key | 3270 Function |
| :--- | :--- |
| **Enter** | Enter (Send Data) |
| **Tab** | Next Field |
| **Shift + Tab** | Previous Field |
| **F1 - F12** | PF1 - PF12 |
| **Shift + F1 - F12** | PF13 - PF24 |
| **Esc** | Clear |
| **Insert** | Toggle Insert/Overwrite Mode |
| **Home** | Cursor to First Field |
| **Backtick (`)** | Logical NOT (¬) |

### Copy / Paste
*   **Select:** Click and drag with the mouse to select text (Standard rectangle selection).
*   **Copy:** `Ctrl + C`
*   **Paste:** `Ctrl + V` (Pastes text starting at the cursor position; handles field skipping automatically).
*   **Select All:** `Ctrl + A`

---

## 7. File Transfer (IND$FILE)

Transfer files between your PC and the Mainframe using the **File > Upload/Download** menu.

### Configuration
1.  **Host System:** Choose **TSO** (z/OS) or **CMS** (z/VM).
2.  **Local File:** Browse for the file on your computer.
3.  **Host Dataset:**
    *   *TSO:* Format like `USER.DATA.TEXT`
    *   *CMS:* Format like `FILENAME FILETYPE A`
4.  **Transfer Mode:**
    *   **ASCII (Text):** Converts line endings (CRLF). Use for source code, JCL, text.
    *   **Binary:** No conversion. Use for XMIT files, zip files.
5.  **Allocations (Upload only):** Set RECFM (Fixed/Variable), LRECL, and BLKSIZE if creating a new dataset.

---

## 8. AI Integration

The emulator features an "Ask AI" capability.

1.  **Select Text:** Highlight an error message, code snippet, or screen content on the terminal.
2.  **Invoke:** Right-click and choose **Ask AI**, or use the menu **Edit > Ask AI** (`Ctrl + Shift + A`).
3.  **Result:** An AI dialog will appear to explain the error code or suggest fixes for the selected JCL/Code.

---

## 9. Troubleshooting

**Q: I see black bars on the left/right of the screen.**
A: This happens if the window size doesn't mathematically match the font size.
*   *Fix:* Use `Ctrl +` or `Ctrl -`. In Tabbed mode, this will snap the window to the perfect size. In Tiled mode, resize the main window slightly to trigger the auto-fit scaling.

**Q: My connection hangs at the login screen.**
A: This may be an issue with TN3270E negotiation. The emulator includes specific fixes for "Optimistic Negotiation." Try disconnecting and reconnecting.

**Q: The Tab key changes focus to buttons instead of moving the cursor.**
A: This issue has been resolved in the latest version. Focus traversal keys are disabled, so `TAB` is sent directly to the host for field navigation.

**Q: I can't resize the window in Tabbed mode.**
A: You can, but scrollbars will appear if you shrink it too much. To resize the actual text, use the Font Size controls.
