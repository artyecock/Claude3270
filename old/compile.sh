#!/bin/bash

# TN3270 Emulator Compilation Script
# Compiles all refactored components in correct dependency order

echo "=========================================="
echo "TN3270 Emulator - Compilation Script"
echo "=========================================="
echo ""

# Create bin directory if it doesn't exist
mkdir -p bin

# Clean previous build
rm -rf bin/*

echo "Compiling Phase 1: Core Infrastructure..."
javac -d bin \
    src/Constants.java \
    src/TerminalModels.java \
    src/EbcdicConverter.java \
    src/AddressEncoder.java

if [ $? -ne 0 ]; then
    echo "ERROR: Phase 1 compilation failed"
    exit 1
fi
echo "✓ Phase 1 complete"
echo ""

echo "Compiling Phase 2: Configuration..."
javac -d bin -cp bin \
    src/config/ColorScheme.java \
    src/config/ConnectionProfile.java \
    src/config/KeyMapping.java

if [ $? -ne 0 ]; then
    echo "ERROR: Phase 2 compilation failed"
    exit 1
fi
echo "✓ Phase 2 complete"
echo ""

echo "Compiling Phase 3: Protocol Layer (Callbacks)..."
javac -d bin -cp bin \
    src/DataStreamListener.java \
    src/ProtocolCallback.java \
    src/TelnetCallback.java

if [ $? -ne 0 ]; then
    echo "ERROR: Phase 3 callbacks compilation failed"
    exit 1
fi
echo "✓ Phase 3 callbacks complete"
echo ""

echo "Compiling Phase 3: Protocol Layer (Implementations)..."
javac -d bin -cp bin \
    src/terminal/ScreenBuffer.java \
    src/protocol/TelnetProtocol.java \
    src/protocol/DataStreamReader.java \
    src/protocol/TN3270Protocol.java

if [ $? -ne 0 ]; then
    echo "ERROR: Phase 3 implementations compilation failed"
    exit 1
fi
echo "✓ Phase 3 implementations complete"
echo ""

echo "Compiling Phase 4: File Transfer..."
javac -d bin -cp bin \
    src/TransferCallback.java \
    src/protocol/FileTransferManager.java \
    src/protocol/FileTransferProtocol.java

if [ $? -ne 0 ]; then
    echo "ERROR: Phase 4 compilation failed"
    exit 1
fi
echo "✓ Phase 4 complete"
echo ""

echo "Compiling Phase 5: Terminal Logic..."
javac -d bin -cp bin \
    src/InputCallback.java \
    src/terminal/CursorManager.java \
    src/terminal/InputHandler.java

if [ $? -ne 0 ]; then
    echo "ERROR: Phase 5 compilation failed"
    exit 1
fi
echo "✓ Phase 5 complete"
echo ""

echo "Compiling Phase 6&7: Dialogs..."
#    src/ui/dialogs/VisualKeyboardDialog.java \
javac -d bin -cp bin \
    src/ui/dialogs/MessageDialog.java \
    src/ui/dialogs/ConnectionDialog.java \
    src/ui/dialogs/ManageProfilesDialog.java \
    src/ui/dialogs/ProgressDialog.java \
    src/ui/dialogs/ColorSchemeDialog.java \
    src/ui/dialogs/FontSizeDialog.java \
    src/ui/dialogs/TerminalSettingsDialog.java \
    src/ui/dialogs/FileTransferDialog.java \
    src/ui/dialogs/KeyboardMappingDialog.java

if [ $? -ne 0 ]; then
    echo "ERROR: Phase 6&7 compilation failed"
    exit 1
fi
echo "✓ Phase 6&7 complete"
echo ""

echo "Compiling Phase 7: UI..."
javac -d bin -cp bin \
    src/ui/EnhancedRibbonToolbar.java \
    src/ui/StatusBar.java \
    src/ModernKeyboardPanel.java \
    src/ui/TerminalCanvas.java

if [ $? -ne 0 ]; then
    echo "ERROR: Phase 7 compilation failed"
    exit 1
fi
echo "✓ Phase 7 complete"
echo ""

echo "Compiling Phase 8: Main Integration..."
javac -d bin -cp bin \
    src/TN3270Emulator.java

if [ $? -ne 0 ]; then
    echo "ERROR: Phase 8 (Main) compilation failed"
    exit 1
fi
echo "✓ Phase 8 complete"
echo ""

echo "=========================================="
echo "✓ ALL COMPILATION SUCCESSFUL"
echo "=========================================="
echo ""
echo "To run the emulator:"
echo "  java -cp bin TN3270Emulator <hostname> [port] [model] [--tls]"
echo ""
echo "Example:"
echo "  java -cp bin TN3270Emulator localhost 23 3279-3"
echo ""
