#!/bin/bash
# Build APK helper script
# Usage: ./build_apk.sh

set -e

echo "=== WallpaperExtend APK Build ==="

# Ensure gradlew exists and is executable
chmod +x gradlew 2>/dev/null || true

# Check for Android SDK
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo "WARNING: ANDROID_HOME not set. If build fails, set it first:"
    echo '  export ANDROID_HOME=$HOME/Android/Sdk'
    echo '  export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin'
fi

echo ""
echo "--- Running: ./gradlew assembleDebug ---"
./gradlew assembleDebug --no-daemon

echo ""
echo "--- APK output ---"
find app/build/outputs/apk -name "*.apk" 2>/dev/null || echo "No APK found"

echo ""
echo "=== Done ==="
