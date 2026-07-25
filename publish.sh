#!/usr/bin/env bash
# Builds the app and publishes it to the shared update folder.
#
# The phone polls latest.json, so this is the whole release process: run it after
# a change and the update appears on the device. versionCode comes from the commit
# count, so commit first — otherwise the build carries the same version as the last
# one and the phone correctly decides there is nothing new.
#
# Publishes the *release* build: 2 MB against the debug build's 20 MB, and both are
# signed with the same debug key, so it installs over an existing debug build
# without a signature conflict.
set -euo pipefail

cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot}"

SHARE_DIR="${1:-$HOME/OneDrive/IoniqScope}"
APK_OUT="app/build/outputs/apk/release/app-release.apk"

echo "building…"
./gradlew --quiet assembleRelease

AAPT=$(find "$LOCALAPPDATA/Android/Sdk/build-tools" -name "aapt2.exe" | sort -r | head -1)
BADGING=$("$AAPT" dump badging "$APK_OUT" | head -1)
VERSION_CODE=$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING")
VERSION_NAME=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING")
SIZE=$(stat -c%s "$APK_OUT")

NOTES="${2:-$(git log -1 --pretty=%s)}"

mkdir -p "$SHARE_DIR"
cp "$APK_OUT" "$SHARE_DIR/IoniqScope.apk"

# The key is `url`, which is what UpdateChecker actually reads. It was written as
# `apk` before, which the app never looked at — it only kept working because the
# fallback filename happened to match.
cat > "$SHARE_DIR/latest.json" <<EOF
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION_NAME",
  "url": "IoniqScope.apk",
  "sizeBytes": $SIZE,
  "notes": "$(sed 's/"/\\"/g' <<<"$NOTES")"
}
EOF

printf 'published %s (build %s, %s MB)\n  -> %s\n' \
  "$VERSION_NAME" "$VERSION_CODE" "$(awk "BEGIN{printf \"%.1f\", $SIZE/1048576}")" "$SHARE_DIR"
