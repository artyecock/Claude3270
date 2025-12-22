***

# TN3270 AI-Enhanced Emulator - Feature Guide

## Overview
This application is a modern, Java-based TN3270/TN3270E terminal emulator designed for connecting to IBM Mainframes (z/OS, z/VM, z/VSE). It features a unique multi-window interface, integrated IND$FILE transfer support, and a context-aware AI Assistant to help decipher legacy code and system messages.

---

## 1. Session Management & Views

The emulator moves away from the traditional "one window per session" model (though it supports that too), allowing for flexible workspace organization.

### Connection Profiles
*   **Save/Load:** Save frequently used hosts (Host, Port, LU, Model, TLS setting) into named profiles.
*   **Auto-Connect:** Selecting a profile automatically populates connection details.

### View Modes
You can toggle the entire window between two modes using the **View** menu or the Ribbon Toolbar:

1.  **Tabbed View (Default):**
    *   Standard browser-style tabs.
    *   **Double-click** a tab header to **Pop-out** that session into a new, separate window.
    *   Use the **`⊞`** button in the tab header to instantly switch to Tiled View.

2.  **Tiled View:**
    *   Displays all active sessions in a grid (2x2, 3x3, etc.).
    *   **Active Session:** The session with keyboard focus is highlighted with a **Green Border** and a Green "ACTIVE" badge in the header.
    *   **Single Click** a header to focus that session.
    *   **Double Click** a header to **Pop-out** that session.
    *   Use the **`≡`** button in the header to switch back to Tabbed View.

### Pop-Out & Pop-In
*   **Pop-Out:** Detach a session from the main window into its own window (via double-click on tab/header).
*   **Pop-In (Merge):** If multiple emulator windows are open, a **`↙` (Merge)** arrow appears in the tab/tile headers. Clicking this moves the session back into the main parent window.

---

## 2. AI Assistant Integration

The emulator includes a built-in AI chat interface capable of connecting to Local LLMs (Ollama) or Cloud APIs (OpenAI, Google).

### How to use it
1.  **Select Text** on the green screen (e.g., a JCL error message or a block of COBOL code).
2.  Press **`Ctrl+Shift+A`** (or click "Ask AI" in the toolbar/context menu).
3.  The AI Chat Window opens with your selection pre-filled as context.
4.  Ask questions like *"Explain this error"* or *"What does this JCL do?"*.

### Configuration (`.tn3270ai`)
The application looks for an `.tn3270ai` file in your home directory. It supports seamless switching between providers via the "Retry" button in the chat UI.

**Example `.tn3270ai`:**
```properties
# List your providers
ai.providers=local,cloud

# 1. Local Ollama (Free, Private)
local.type=ollama
local.endpoint=http://localhost:11434
# Syntax: RealModelName (Display Alias)
local.models=llama3 (Local Llama), mistral (Local Mistral)

# 2. OpenAI (Cloud, Smarter)
cloud.type=openai
cloud.apiKey=sk-your-api-key-here
cloud.models=gpt-4o (GPT-4 Omni)

ai.prompt.default=You are a mainframe expert. Be concise.
```

---

## 3. File Transfer (IND$FILE)

Built-in GUI for uploading and downloading datasets without leaving the emulator.

*   **Access:** `File > Upload...` or `File > Download...`
*   **Smart Syntax:** Automatically handles command syntax differences between **TSO (z/OS)** and **CMS (z/VM)**.
*   **Text Conversion:** Handles ASCII<->EBCDIC translation and CRLF handling automatically when "ASCII" mode is checked.
*   **Parameters:** Supports specific allocation parameters (LRECL, BLKSIZE, RECFM) for uploads.

---

## 4. Keyboard & Input

### Key Mapping
*   **Visual Mapper:** Go to `Settings > Keyboard Mapping`.
*   **Function Keys:** Click a visual 3270 key (e.g., PF1, RESET, CLEAR), then press the physical key you want to map it to.
*   **Character Translation:** Map specific PC keys to EBCDIC symbols (e.g., map `^` to `¬`).
*   **Special Character Palette:** Includes a built-in palette for selecting obscure IBM symbols or entering arbitrary Hex Unicode values.

### Cursor Movement
*   **wrapping:** The cursor wraps logically:
    *   Moving **Right** past the end of a line wraps to the start of the next line.
    *   Moving **Up** from the top line wraps to the bottom line (same column).
    *   Moving **Left** from the start of the screen wraps to the end.

### Shortcuts
| Shortcut | Action |
| :--- | :--- |
| **F1 - F12** | PF1 - PF12 |
| **Shift + F1 - F12** | PF13 - PF24 |
| **Ctrl + C** | Copy Selection |
| **Ctrl + V** | Paste from Clipboard |
| **Ctrl + A** | Select All |
| **Ctrl + Shift + A** | Ask AI (with current selection) |
| **Ctrl + +/-** | Increase / Decrease Font Size |
| **Esc** | Clear Screen |

---

## 5. Visual Customization

*   **Color Schemes:** Choose from presets (Green on Black, Amber, Solarized Dark, IBM Blue) or customize specific attributes (Background, Text, Cursor Color) via `View > Color Scheme`.
*   **Fonts:** Dynamic font resizing via `View > Font Size` or shortcuts.
*   **Cursor Style:** Choose between **Block**, **Underscore**, or **I-Beam**.

---

## 6. Technical Details

### Configuration Files
The emulator stores settings in your User Home directory:
1.  **`.tn3270profiles`**: Saved connection hosts and ports.
2.  **`.tn3270keymap`**: Custom keyboard mappings and character translations.
3.  **`.tn3270ai`**: (Located in app directory) AI Provider configuration.

### Connectivity
*   **TLS/SSL:** Full support for secure TN3270 connections.
*   **TN3270E:** Supports Extended negotiation (LU names, Device Type negotiation).
*   **OSA-ICC Support:** specific optimizations to prevent connection delays common with IBM OSA-ICC console controllers.
