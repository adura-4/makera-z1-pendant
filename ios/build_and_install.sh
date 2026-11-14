#!/bin/bash
# Build Z1 Pendant for a connected iPhone, sign it with your Apple ID's team, install it, and
# (with --run) launch it with its log in this terminal.
#
#   ios/build_and_install.sh <TEAM_ID> [--run]
#
# TEAM_ID: your 10-character Apple team ID (Xcode > Settings > Accounts, or the "(XXXXXXXXXX)"
# shown by `security find-identity -v -p codesigning`). The first time, open
# ios/iosApp.xcodeproj in Xcode and pick your team under Signing & Capabilities once.
# With a free Apple ID the app stops opening after 7 days: run this again to reinstall
# (your settings and hotkeys are kept).
set -euo pipefail
TEAM_ID="${1:?usage: $0 <TEAM_ID> [--run]}"
RUN="${2:-}"
cd "$(dirname "$0")"

# First connected iPhone (or set DEVICE=<udid>).
DEVICE="${DEVICE:-$(xcrun devicectl list devices 2>/dev/null | awk '/connected/ && /iPhone/ {for (i=1;i<=NF;i++) if ($i ~ /^[0-9A-F]{8}-[0-9A-F]{16}$/) print $i}' | head -1)}"
[ -n "$DEVICE" ] || { echo "No connected iPhone found (plug it in, unlock it, trust this Mac)."; exit 1; }
echo "iPhone: $DEVICE"

DD=/tmp/z1pendant-dd
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination "id=$DEVICE" -derivedDataPath "$DD" \
  -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
  DEVELOPMENT_TEAM="$TEAM_ID" TEAM_ID="$TEAM_ID" build | grep -E "error:|warning: .*signing|BUILD" || true

APP="$DD/Build/Products/Debug-iphoneos/Z1 Pendant.app"
[ -d "$APP" ] || { echo "Build failed (see above)."; exit 1; }
BUNDLE=$(/usr/libexec/PlistBuddy -c "Print CFBundleIdentifier" "$APP/Info.plist")
xcrun devicectl device install app --device "$DEVICE" "$APP" | tail -1
echo "Installed $BUNDLE"
if [ "$RUN" = "--run" ]; then
  xcrun devicectl device process launch --console --terminate-existing --device "$DEVICE" "$BUNDLE"
fi
