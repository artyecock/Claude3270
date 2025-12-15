#!/bin/bash

# Compile
javac -d . com/tn3270/**/*.java

echo "Main-Class: com.tn3270.TN3270Emulator" > manifest.txt

# Create the JAR
# c=create, v=verbose, f=file, m=manifest
jar cvfm TN3270.jar manifest.txt com

# Create a package
jpackage --name "Claude3270" \
  --input . \
  --main-jar TN3270.jar \
  --main-class com.tn3270.TN3270Emulator \
  --type dmg \
  --icon Claude3270.icns \
  --vendor "OpenSource"

echo ""
echo "To run, issue: java -jar TN3270.jar"
echo ""

exit
