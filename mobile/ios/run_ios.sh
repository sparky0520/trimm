#!/bin/bash
set -e

# Change directory to the location of this script
cd "$(dirname "$0")"

echo "🔨 Generating Xcode project..."
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodegen generate

echo "🚀 Building Trimm for iOS Simulator..."
xcodebuild -project Trimm.xcodeproj \
  -scheme Trimm \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=latest' \
  -derivedDataPath build \
  clean build

echo "📱 Starting Simulator..."
open -a Simulator

# Wait for simulator to be ready (it will boot if not already booted, or we can explicitly boot it)
xcrun simctl boot "iPhone 17 Pro" 2>/dev/null || true
echo "⏳ Waiting for Simulator to finish booting..."
xcrun simctl bootstatus "iPhone 17 Pro"

echo "📦 Installing Trimm app..."
APP_PATH=$(find build/Build/Products -name "Trimm.app" -type d | head -n 1)
xcrun simctl install "iPhone 17 Pro" "$APP_PATH"

echo "✨ Launching Trimm app..."
xcrun simctl launch "iPhone 17 Pro" ai.ctrlb.mobile.Trimm

echo "✅ Done!"
