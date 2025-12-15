***

# TN3270 Emulator - Developer Context & Architecture

**Project:** Java Swing-based TN3270 Emulator with AI Integration (Ollama/OpenAI).
**Goal:** Assist with feature enhancements and bug fixes while preventing regressions in rendering, protocol handling, and focus management.

## 1. Rules of Engagement (CRITICAL)
1.  **NO FULL FILE REPLACEMENTS:** Unless explicitly asked, never output the entire file. Output **only** the modified method(s), new variables, or changed inner classes.
2.  **Preserve Context:** Use comments like `// ... existing code ...` to indicate where the new code fits.
3.  **Swing Concurrency:** All UI updates must occur on the EDT (`SwingUtilities.invokeLater`).
4.  **Java Version:** Target Java 11+.

## 2. Component Architecture

### Core Hierarchy
*   **`TN3270Emulator.java` (JFrame):** The main window. Manages `ViewMode` (TABS vs TILES). Handles `jpackage` window snapping logic (`snapWindowToTiles`).
*   **`TN3270Session.java` (JPanel):** Represents a single connection.
    *   **Responsibilities:** Telnet/TN3270 state machine, socket handling, `snapWindow()` logic (for manual font resizing), and focus management.
    *   **Key State:** Has a `terminalPanel` and `screenModel`.
*   **`TerminalPanel.java` (JPanel):** The rendering canvas.
    *   **Responsibilities:** `paintComponent` (drawing chars/cursor), Font sizing logic (`fitToSize`), Crosshair rendering, and `Scrollable` interface implementation.
*   **`ScreenModel.java`:** The data buffer (rows/cols, attributes, EBCDIC/ASCII conversion). Pure logic, no UI.

### AI Integration (`com.tn3270.ai`)
*   **`AIManager`:** Singleton coordinator.
*   **`AIModelProvider`:** Interface implemented by `OllamaProvider` and `OpenAIProvider`.
*   **`AIStreamingClient`:** Handles asynchronous HTTP streaming (SSE) to prevent UI blocking during generation.
*   **`AIChatWindow`:** The UI dialog for interacting with the AI.

## 3. Critical Implementation Details (Do Not Break)

### A. Rendering & Sizing Strategy
We use a hybrid sizing model to prevent "Black Bands" and "Missing Scrollbars."
1.  **Manual Mode (Tabs):** Changing font size triggers `TN3270Session.snapWindow()`, which resizes the `JFrame` to wrap the content.
2.  **Auto-Fit Mode (Tiles):** Resizing the window triggers `TerminalPanel.fitToSize()`, which scales the font to fit the available space.
3.  **Smart Stretching:** `TerminalPanel.paintComponent` uses `Graphics2D.scale()` to eliminate fractional pixel gaps (black bands) when the window size isn't a perfect multiple of the character size.

### B. TN3270 Protocol
*   **Optimistic Negotiation:** The `process3270Data` method in `TN3270Session` explicitly checks `data[0]` to handle cases where the host sends standard 3270 data before TN3270E negotiation is fully acknowledged.
*   **TN3270E Header:** We must strictly differentiate between 3270-DATA (0x00 header) and standard command streams to prevent stripping valid commands.

### C. Input & Focus
*   **Tab Key:** `setFocusTraversalKeysEnabled(false)` is set in both `TN3270Session` and `TerminalPanel`. This prevents Swing from consuming the Tab key, allowing it to be sent to the host for field navigation.
*   **Tile Focus:** Tile headers contain a MouseListener on the button container to ensure clicking "dead space" in the header still focuses the session.

## 4. File Map
*   `./com/tn3270/TN3270Emulator.java` - Main Entry, Window Manager.
*   `./com/tn3270/TN3270Session.java` - Connection Logic, Protocol Parser.
*   `./com/tn3270/ui/TerminalPanel.java` - Rendering, Fonts, Mouse Input.
*   `./com/tn3270/model/ScreenModel.java` - 3270 Buffer State.
*   `./com/tn3270/ai/*` - AI Logic and UI.
*   `./com/tn3270/constants/*` - Telnet opcodes and Protocol bytes.

---

**Example Prompt:**
"I need to add a feature to `TerminalPanel` to change the cursor color when the keyboard is locked."

**Expected Output Format:**
"Here is the updated `paintComponent` method in `TerminalPanel.java`. No other methods require changes."
```java
@Override
protected void paintComponent(Graphics g) {
    // ... existing setup code ...

    // MODIFIED: Check for keyboard lock
    if (screenModel.isKeyboardLocked()) {
        g2d.setColor(Color.RED); // Visual indicator
    } else {
        g2d.setColor(screenModel.getCursorColor());
    }

    // ... rest of cursor drawing logic ...
}
```
