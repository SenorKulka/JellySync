#!/usr/bin/env bash
# Build Jellysync (release-optimized, signed with the Jellysync key) and optionally install it.
#
#   jellysync/build.sh                 # test + build
#   TV_HOST=192.168.1.167 jellysync/build.sh   # ...and install on that TV over ADB
#
# Bump jellysync/VERSION before every release you install: Android only accepts updates with a higher version code.
# Needs JDK 21, Android SDK platform android-37.0 + build-tools 36.0.0, and the jellysync.* signing properties in
# ~/.gradle/gradle.properties (see README).
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=$(tr -d '[:space:]' < jellysync/VERSION)
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"

./gradlew -Pjellyfin.version="$VERSION" test assembleJellysync

APK=$(ls app/build/outputs/apk/jellysync/*.apk | head -n 1)
apksigner=$(ls -d "${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"/build-tools/*/apksigner | tail -n 1)
"$apksigner" verify --print-certs "$APK" | grep -q 'CN=JellySync' || { echo "not signed with the Jellysync key: $APK" >&2; exit 1; }
echo "Built $APK ($VERSION)"

if [ -n "${TV_HOST:-}" ]; then
  adb connect "$TV_HOST:5555" >/dev/null
  adb -s "$TV_HOST:5555" install -r "$APK"
fi
