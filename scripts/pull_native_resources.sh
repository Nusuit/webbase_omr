#!/bin/bash
# Pull all resources_*.csv files written by ResourceMonitor.kt from the
# device's app external files dir, into a local folder.
#
# Usage:
#   bash scripts/pull_native_resources.sh                 # → runs/resources/native
#   bash scripts/pull_native_resources.sh some/other/dir

set -eu

DEST="${1:-runs/resources/native}"
mkdir -p "$DEST"

SRC_DIR='/sdcard/Android/data/com.gradesnap.omr/files/Documents'

echo "─── On device: $SRC_DIR ───"
MSYS_NO_PATHCONV=1 adb shell ls -lt "$SRC_DIR" 2>/dev/null | tr -d '\r' | head -10

echo
echo "─── Pulling resources_*.csv → $DEST ───"

# `adb shell ls` on Windows emits CRLF line endings; strip the CR before
# substituting into the pull command. Use a here-string to keep the loop
# in the current shell.
files=$(MSYS_NO_PATHCONV=1 adb shell ls "$SRC_DIR" 2>/dev/null | tr -d '\r' | grep -E '^resources_.*\.csv$' || true)

if [ -z "$files" ]; then
  echo "  (no resources_*.csv on device — did you run YOLO/CV benchmark?)"
  exit 0
fi

while IFS= read -r fname; do
  [ -z "$fname" ] && continue
  remote="$SRC_DIR/$fname"
  local_path="$DEST/$fname"
  MSYS_NO_PATHCONV=1 adb pull "$remote" "$local_path" >/dev/null && \
    echo "  pulled $fname"
done <<< "$files"

echo
echo "─── Files in $DEST ───"
ls -lt "$DEST"

echo
echo "Next: python scripts/plot_resources.py $DEST"
