TN3270 Emulator Refactoring Summary & Implementation Plan
Project Overview
Goal: Refactor monolithic TN3270Emulator.java (~3000 lines) into a modular, maintainable architecture with 25+ separate classes organized by responsibility.
Benefits:

Easier to discuss specific components without hitting message size limits
Better separation of concerns (UI, Protocol, Terminal Logic, Configuration)
Reusable components for future enhancements
Easier testing and debugging
GitHub-friendly structure for collaboration


Current Status
✅ Completed

Project structure designed - Full folder hierarchy defined
All class templates created - 25+ skeleton files with method signatures
Callback interfaces defined - 7 interfaces for component communication
Extraction map created - Detailed guide of what goes where
Modification requirements identified - Known changes for each refactored method

⏳ In Progress

Creating skeleton files with copy/paste placeholders
Waiting for actual code extraction from monolithic source

🔜 Not Started

Code extraction and placement
Testing individual components
Integration testing
GitHub repository setup


Final Project Structure
tn3270-emulator/
├── src/
│   ├── TN3270Emulator.java          # Main class (~500 lines)
│   ├── Constants.java                # Protocol constants
│   ├── TerminalModels.java          # Terminal model definitions
│   ├── EbcdicConverter.java         # Character set conversion
│   ├── AddressEncoder.java          # 3270 address encoding
│   │
│   ├── config/
│   │   ├── ColorScheme.java         # ✅ Created
│   │   ├── ConnectionProfile.java   # ✅ Created
│   │   └── KeyMapping.java          # Template ready
│   │
│   ├── terminal/
│   │   ├── ScreenBuffer.java        # ✅ Created (complete)
│   │   ├── CursorManager.java       # Template ready
│   │   └── InputHandler.java        # Template ready
│   │
│   ├── protocol/
│   │   ├── TelnetProtocol.java      # Template ready
│   │   ├── TN3270Protocol.java      # Template ready
│   │   ├── FileTransferProtocol.java # Template ready
│   │   ├── FileTransferManager.java  # Template ready
│   │   └── DataStreamReader.java     # Template ready
│   │
│   └── ui/
│       ├── TerminalCanvas.java       # Template ready
│       ├── StatusBar.java            # Template ready
│       ├── ModernKeyboardPanel.java  # Template ready
│       ├── EnhancedRibbonToolbar.java # Template ready
│       └── dialogs/
│           ├── ConnectionDialog.java          # Template ready
│           ├── ManageProfilesDialog.java      # Template ready
│           ├── FileTransferDialog.java        # Template ready
│           ├── ProgressDialog.java            # Template ready
│           ├── ColorSchemeDialog.java         # Template ready
│           ├── VisualKeyboardDialog.java      # Template ready
│           ├── FontSizeDialog.java            # Template ready
│           ├── TerminalSettingsDialog.java    # Template ready
│           └── MessageDialog.java             # Template ready
│
├── docs/
│   ├── INDFILE_PROTOCOL.md          # Protocol documentation
│   └── REFACTORING_GUIDE.md         # This document
│
└── README.md

Implementation Plan
Phase 1: Core Infrastructure (Priority 1)
Goal: Get basic terminal display working

Constants.java ✅ Ready

Copy all static final byte declarations
Copy ADDRESS_TABLE
No modifications needed


TerminalModels.java

Copy MODELS map initialization
No modifications needed


EbcdicConverter.java

Copy static initializer blocks for translation tables
No modifications needed


AddressEncoder.java

Copy decode3270Address() method
Copy encode3270Address() method
Change: Add bufferSize parameter to decode method


ScreenBuffer.java ✅ Complete

Already fully implemented
Ready to use




Phase 2: Configuration Classes (Priority 1)
Goal: Load/save user settings

ColorScheme.java ✅ Ready

Already complete
No modifications needed


ConnectionProfile.java ✅ Ready

Already complete
No modifications needed


KeyMapping.java

Copy constructors (lines ~672-685)
Copy saveKeyMappings() (lines ~645-655)
Copy loadKeyMappings() (lines ~657-670)
Copy initializeDefaultMappings() (lines ~700-710)
Copy getAidForName() (lines ~1265-1310)
Copy getNameForAid() (from VisualKeyboardDialog)
Change: Methods are now static, operating on passed Map




Phase 3: Protocol Layer (Priority 2)
Goal: Handle Telnet and 3270 communication

TelnetProtocol.java

Copy handleTelnetCommand() (lines ~1450-1490)
Copy handleSubnegotiation() (lines ~1492-1500)
Copy handleTN3270ESubneg() (lines ~1502-1580)
Copy sendTelnet() (lines ~1650-1653)
Copy sendTerminalType() (lines ~1655-1665)
Copy all TN3270E helper methods
Change: Remove direct field access, use instance variables


DataStreamReader.java

Copy readLoop() (lines ~1370-1448)
Change: Replace direct method calls with listener.on*() callbacks
Change: Pass data through interface instead of calling directly


TN3270Protocol.java

Copy process3270Data() (lines ~1667-1730)
Copy processWCC() (lines ~2118-2128)
Copy processOrders() (lines ~2130-2290)
Copy processWSF() (lines ~2292-2330)
Copy sendQueryResponse() (lines ~2287-2450)
Copy sendReadBuffer() (lines ~2460-2490)
Copy sendAID() (lines ~2492-2555)
Copy sendData() (lines ~2557-2585)
Change: Access screenBuffer through instance variable
Change: Signal UI updates through ProtocolCallback interface




Phase 4: File Transfer (Priority 2)
Goal: IND$FILE upload/download

FileTransferManager.java

Copy buildIndFileCommand() (lines ~1700-1765)
Extract file opening logic from initiateFileTransfer()
Extract state management fields
Change: Pure state management, no I/O operations


FileTransferProtocol.java

Copy handleDataChain() (lines ~2332-2360)
Copy handleDCOpen() (lines ~2362-2450)
Copy handleDCClose() (lines ~2452-2495)
Copy handleDCGet() (lines ~2503-2600)
Copy handleDCInsert() (lines ~2602-2750)
Copy all sendDC*Response() methods
Change: Use FileTransferManager for state
Change: Report progress through TransferCallback interface




Phase 5: Terminal Logic (Priority 2)
Goal: Cursor movement and field navigation

CursorManager.java

Copy moveCursor() (lines ~2730-2734)
Copy tabToNextField() (lines ~2736-2763)
Copy tabToPreviousField() (lines ~2765-2792)
Copy eraseToEndOfField() (lines ~2794-2810)
Copy eraseToEndOfLine() (lines ~2812-2828)
Change: Minimal - already designed for independence


InputHandler.java

Copy keyPressed() (lines ~2587-2670)
Copy keyTyped() (lines ~2672-2720)
Copy copySelection() (lines ~850-888)
Copy pasteFromClipboard() (lines ~890-940)
Change: Replace direct method calls with callback.on*() calls
Change: Pass state through constructor/setters




Phase 6: UI Components (Priority 3)
Goal: Display and user interaction

TerminalCanvas.java

Copy constructor (lines ~2855-2895)
Copy paint() (lines ~2903-2915)
Copy paintScreen() (lines ~2917-2978)
Copy updateSize() (lines ~2980-2987)
Change: Add parameters to paintScreen() - cursorPos, keyboardLocked
Change: Access buffer through screenBuffer reference
Change: Remove direct emulator field access


StatusBar.java

Copy constructor (lines ~2990-3010)
Copy setStatus() (lines ~3012-3014)
Copy update() (lines ~3016-3024)
Change: Modify update() to accept parameters instead of accessing fields


ModernKeyboardPanel.java

Copy constructor (lines ~2555-2650)
Copy createFunctionKeyPanel() (lines ~2652-2675)
Copy createActionKeyPanel() (lines ~2677-2745)
Copy createStatusPanel() (lines ~2747-2815)
Copy createStyledButton() (lines ~2817-2850)
Change: Replace emulator.* calls with listener.* calls


EnhancedRibbonToolbar.java

Copy constructor (lines ~2390-2460)
Copy createGroupLabel() (lines ~2462-2467)
Copy createSeparator() (lines ~2469-2479)
Copy createIconButton() (lines ~2481-2530)
Copy createEnhancedIcon() (lines ~2532-2650)
Change: Replace direct method calls with listener.* calls




Phase 7: Dialogs (Priority 3)
Goal: User configuration and file transfer

ConnectionDialog.java

Copy/convert showConnectionDialog() (lines ~1070-1240)
Change: Convert from static method to instance dialog
Change: Return ConnectionProfile object instead of connecting directly


ManageProfilesDialog.java

Copy/convert showManageProfilesDialog() (lines ~1242-1285)
Change: Convert from static method to instance dialog


FileTransferDialog.java

Copy/convert showFileTransferDialog() (lines ~1430-1650)
Change: Convert to instance dialog returning TransferConfig
Change: Remove direct file transfer initiation


ProgressDialog.java

Copy/convert showProgressDialog() (lines ~1840-1875)
Copy updateProgressDialog() (lines ~1877-1886)
Change: Convert to instance dialog with update methods


ColorSchemeDialog.java

Copy/convert showColorSchemeDialog() (lines ~1020-1068)
Copy showCustomColorDialog() (lines ~1140-1195)
Copy showColorChooser() (lines ~1197-1280)
Change: Convert to instance dialog returning ColorScheme


VisualKeyboardDialog.java

Copy constructor and methods (lines ~2050-2330)
Change: Return Map<Integer, KeyMapping> instead of modifying emulator


FontSizeDialog.java

Copy/convert showFontSizeDialog() (lines ~970-1018)
Change: Return int font size instead of applying directly


TerminalSettingsDialog.java

Copy/convert showTerminalSettingsDialog() (lines ~1148-1220)
Change: Return Settings object instead of applying directly


MessageDialog.java

Copy showMessageDialog() (lines ~1773-1790)
Copy showMultilineMessageDialog() (lines ~1802-1820)
Copy showInputDialog() (lines ~1287-1330)
Copy showConfirmDialog() (lines ~1333-1368)
Change: Convert to static utility methods (no changes needed)




Phase 8: Main Integration (Priority 4)
Goal: Wire everything together

TN3270Emulator.java

Copy constructor framework
Copy connect() method (lines ~1360-1390)
Copy disconnect() (lines ~1392-1402)
Copy main() (lines ~3040-3080)
Implement all callback interfaces:

InputHandler.InputCallback
DataStreamReader.DataStreamListener
ModernKeyboardPanel.KeyboardActionListener
EnhancedRibbonToolbar.ToolbarActionListener
TN3270Protocol.ProtocolCallback
FileTransferProtocol.TransferCallback
TerminalCanvas.SelectionListener


Major changes:

Initialize all components in correct dependency order
Wire callbacks between components
Manage component lifecycle
Coordinate state across components

Critical Modifications Required
1. Constructor Initialization Order
// Correct order (dependencies first):
screenBuffer = new ScreenBuffer(rows, cols);
cursorManager = new CursorManager(screenBuffer);
keyMap = new HashMap<>();
KeyMapping.loadKeyMappings(keyMap);

// Then UI components:
canvas = new TerminalCanvas(cols, rows);
canvas.setScreenBuffer(screenBuffer);
canvas.setColorScheme(currentColorScheme);

// Then protocols (need output stream from socket):
telnetProtocol = new TelnetProtocol(output, model);
tn3270Protocol = new TN3270Protocol(output, screenBuffer, telnetProtocol);
fileTransferManager = new FileTransferManager();
fileTransferProtocol = new FileTransferProtocol(output, telnetProtocol, fileTransferManager);

// Finally input handlers:
inputHandler = new InputHandler(this, screenBuffer, cursorManager, keyMap);

2. Replace Direct Field Access
// BEFORE (monolithic):
buffer[pos] = 'A';
if (keyboardLocked) { ... }

// AFTER (modular):
screenBuffer.setChar(pos, 'A');
if (tn3270Protocol.isKeyboardLocked()) { ... }

3. Convert Static Dialogs to Instance-Based
// BEFORE:
private static void showConnectionDialog() {
    Dialog d = new Dialog(...);
    // ... build and show inline ...
}

// AFTER:
private void showConnectionDialog() {
    ConnectionDialog dialog = new ConnectionDialog(this);
    ConnectionProfile profile = dialog.showDialog();
    if (profile != null) {
        // Use profile...
    }
}

4. Use Callbacks Instead of Direct Calls
// BEFORE:
canvas.repaint();
statusBar.update();

// AFTER (in protocol classes):
callback.requestRepaint();
callback.updateStatus("Ready");

Testing Strategy
Unit Testing (Per Component)

ScreenBuffer - Test field attributes, modified flags, cursor movement
AddressEncoder - Test 12-bit and 14-bit encoding/decoding
EbcdicConverter - Test ASCII/EBCDIC/APL conversions
KeyMapping - Test save/load persistence
ColorScheme - Test all predefined schemes

Integration Testing (Component Pairs)

TN3270Protocol + ScreenBuffer - Test order processing
TelnetProtocol + TN3270Protocol - Test negotiation
InputHandler + CursorManager - Test field navigation
FileTransferProtocol + FileTransferManager - Test upload/download

System Testing (Full Application)

Basic Connection - Connect to mainframe, verify screen display
Keyboard Input - Test typing, field navigation, AID keys
File Transfer - Test IND$FILE upload and download
Configuration - Test profile save/load, color schemes, key mappings


Validation Checklist
Code Quality

 All files compile without errors
 No direct field access across component boundaries
 All callbacks properly implemented
 Constants imported from Constants.java
 No hardcoded magic numbers
 Proper exception handling

Functionality

 Terminal displays correctly
 Keyboard input works
 Field navigation works
 AID keys send correct codes
 File transfer completes successfully
 Configuration persists

Protocol Compliance (per IBM manuals)

 Query Reply structure correct (GA23-0059 Ch 5)
 Address encoding correct (GA23-0059 Ch 4)
 Field attributes correct (GA23-0059 Ch 3)
 Orders processed correctly (GA23-0059 Appendix A)
 IND$FILE operations correct (implementation notes)


Next Steps for Implementation
Immediate Actions:

Create GitHub repository

Initialize with folder structure
Add README.md and LICENSE
Create initial commit with templates


Start with Phase 1 (Core Infrastructure)

Extract and test Constants.java
Extract and test TerminalModels.java
Extract and test EbcdicConverter.java
Extract and test AddressEncoder.java
Verify ScreenBuffer.java compiles


Continue with Phase 2 (Configuration)

Complete KeyMapping.java
Test save/load functionality


Build and test incrementally

Don't move to next phase until current phase works
Test each component in isolation first
Then test component integration

GitHub Workflow:
# Initial setup
git init
git add .
git commit -m "Initial refactored structure"
git branch -M main
git remote add origin <your-repo-url>
git push -u origin main

# For each component:
git checkout -b feature/component-name
# ... implement component ...
git add src/path/to/Component.java
git commit -m "Implement Component.java"
git push origin feature/component-name
# ... create PR, review, merge ...

Reference Materials
IBM Manuals (for protocol validation):

GA23-0059: IBM 3270 Data Stream Programmer's Reference
SC33-0208: CICS External Interfaces Guide

Key Sections to Reference:

Address Encoding: GA23-0059, Chapter 4
Structured Fields: GA23-0059, Chapter 5, Table 5-1
Field Attributes: GA23-0059, Chapter 3, Table 3-8
Orders: GA23-0059, Appendix A
Query Reply Structure: GA23-0059, Chapter 5.21


Questions for Next Session

Repository setup: Create GitHub repo together or you create it first?
Phase priority: Start with Phase 1 or different order based on your needs?
Testing approach: Want to set up JUnit tests from the start?
Documentation: Extract IND$FILE protocol doc to separate file now or later?
Build system: Keep javac or use Maven/Gradle?


Estimated Effort

Phase 1-2: 2-3 hours (Core + Config)
Phase 3-4: 4-5 hours (Protocol + File Transfer)
Phase 5-6: 3-4 hours (Terminal + UI)
Phase 7: 4-5 hours (Dialogs - lots of conversion)
Phase 8: 3-4 hours (Main integration + callback wiring)
Testing: 4-6 hours (Unit + Integration + System)
Documentation: 2-3 hours (Comments, README, protocol doc)

Total: 22-34 hours of work

Success Criteria
✅ Refactoring Complete When:

All 29 files exist and compile
Application connects to mainframe
Can type and navigate fields
Can send AID keys and receive responses
IND$FILE upload/download works
Configuration saves and loads
No regressions from original functionality
Code is more maintainable and testable
