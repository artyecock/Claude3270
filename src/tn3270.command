#!/bin/bash
cd "$(dirname "$0")"

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo "Java is not installed. Please install Java 11+."
    exit 1
fi

# Run the app with the dock name and icon
java -Xdock:name="TN3270" -Xdock:icon="icon.icns" -jar TN3270.jar
