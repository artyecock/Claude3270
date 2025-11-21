# TN3270 Terminal Emulator

A modern Java-based TN3270/TN3270E terminal emulator with full IND$FILE support.

## Features

- Multiple terminal models (3278-2 through 3278-5, 3279-2/3)
- TLS/SSL encryption support
- IND$FILE file transfer (TSO and CMS)
- Extended colors and highlighting
- Customizable keyboard mappings
- Multiple color schemes
- Modern UI with ribbon toolbar

## Building
```bash
javac -d bin src/**/*.java
```

## Running
```bash
java -cp bin TN3270Emulator [hostname] [port] [model] [--tls]
```

Or run without arguments to show connection dialog.

## References

- IBM 3270 Data Stream Programmer's Reference (GA23-0059)
- CICS External Interfaces Guide (SC33-0208)
