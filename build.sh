#!/bin/bash

# Root SmartSort directory
SMARTSORT_DIR="/Users/nicholas.park/Library/CloudStorage/Dropbox/PERSONAL/DEV BIN/smart_sort"
PLUGIN_DIR="$SMARTSORT_DIR/plugin"
TARGET_DIR="$PLUGIN_DIR/target"
VERSIONS_DIR="$SMARTSORT_DIR/versions"

# Create versions directory in root if it doesn't exist
mkdir -p "$VERSIONS_DIR"

# Print status information
echo "=== SmartSort Build Script ==="
echo "Building from: $PLUGIN_DIR"
echo "Storing versions in: $VERSIONS_DIR"
echo "Current time: $(date)"
echo "==============================="

# Preserve any existing JAR before cleaning
if [ -f "$TARGET_DIR"/smartsort-*.jar ]; then
  EXISTING_JAR=$(ls "$TARGET_DIR"/smartsort-*.jar)
  EXISTING_FILENAME=$(basename "$EXISTING_JAR")
  cp "$EXISTING_JAR" "$VERSIONS_DIR/$EXISTING_FILENAME"
  echo "Preserved existing JAR: $EXISTING_FILENAME"
fi

# Run Maven clean package
echo "Building new version..."
cd "$PLUGIN_DIR"
mvn clean package
BUILD_RESULT=$?

# Check if build was successful
if [ $BUILD_RESULT -ne 0 ]; then
  echo "Build failed! See Maven output above for details."
  exit 1
fi

# Copy the new JAR to the versions directory
if [ -f "$TARGET_DIR"/smartsort-*.jar ]; then
  NEW_JAR=$(ls "$TARGET_DIR"/smartsort-*.jar)
  NEW_FILENAME=$(basename "$NEW_JAR")
  cp "$NEW_JAR" "$VERSIONS_DIR/$NEW_FILENAME"
  echo "Build complete! New JAR copied to versions directory:"
  echo "$VERSIONS_DIR/$NEW_FILENAME"

  # Print file details
  echo "File details:"
  ls -lh "$VERSIONS_DIR/$NEW_FILENAME"
  shasum "$NEW_JAR" | cut -d ' ' -f 1
else
  echo "Warning: No JAR file found in target directory after build."
fi

# List versions directory
echo "Versions directory contents:"
ls -l "$VERSIONS_DIR"

echo "==============================="
echo "Build process complete!"
