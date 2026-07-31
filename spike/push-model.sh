#!/usr/bin/env bash
# Sideload the embedding model onto the device for the Phase 0 spike.
#
# The model is pushed rather than bundled in the APK: it keeps a 22MB blob out
# of git, and it mirrors how the real app will treat weights (downloaded once,
# stored in app files) instead of baking them into the build.
set -euo pipefail

PKG="dev.loam.spike"
MODELS_DIR="$(cd "$(dirname "$0")/../models/all-MiniLM-L6-v2" && pwd)"
DEST="/sdcard/Android/data/$PKG/files"

if ! command -v adb >/dev/null; then
    echo "adb not found. It ships in the Android SDK at:"
    echo "  ~/Android/Sdk/platform-tools/adb"
    echo "Add that to PATH, or run this script with it prepended."
    exit 1
fi

if [ "$(adb devices | grep -c 'device$')" -eq 0 ]; then
    echo "No device detected. Enable USB debugging and check 'adb devices'."
    exit 1
fi

echo "Creating $DEST ..."
adb shell mkdir -p "$DEST"

for f in model_qint8_arm64.onnx vocab.txt; do
    if [ ! -f "$MODELS_DIR/$f" ]; then
        echo "Missing $MODELS_DIR/$f — see the roadmap's model prerequisites."
        exit 1
    fi
    echo "Pushing $f ..."
    adb push "$MODELS_DIR/$f" "$DEST/$f"
done

echo
echo "Done. Launch the app and hit 'Test embedding'."
echo "To compare against fp32, push model.onnx too and change MODEL_NAME."
