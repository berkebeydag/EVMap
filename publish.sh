#!/usr/bin/env bash
# Builds the app and publishes it where the phone can find it.
#
# The phone polls latest.json, so this is the whole release process: run it after a
# change and the update appears on the device. versionCode comes from the commit
# count, so commit first — otherwise the build carries the same version as the last
# one and the phone correctly decides there is nothing new.
#
# Publishes the *release* build: 2 MB against the debug build's 20 MB, and both are
# signed with the same debug key, so it installs over an existing debug build
# without a signature conflict.
#
# The APK goes to a `dist` branch holding exactly two files, rewritten to a single
# parentless commit every time. Binaries never enter the source history, and the
# repository does not grow by 2 MB per build forever. `dist` is disposable by
# design — nothing but the current build is ever meant to be on it.
#
# Usage:  ./publish.sh [release-notes]
#         ./publish.sh --local [dir] [release-notes]   # copy to a folder instead
set -euo pipefail

cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot}"

APK_OUT="app/build/outputs/apk/release/app-release.apk"
DIST_BRANCH="dist"

LOCAL_DIR=""
if [[ "${1:-}" == "--local" ]]; then
    LOCAL_DIR="${2:-$HOME/OneDrive/IoniqScope}"
    shift 2 2>/dev/null || shift
fi

echo "building…"
./gradlew --quiet assembleRelease

AAPT=$(find "$LOCALAPPDATA/Android/Sdk/build-tools" -name "aapt2.exe" | sort -r | head -1)
BADGING=$("$AAPT" dump badging "$APK_OUT" | head -1)
VERSION_CODE=$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING")
VERSION_NAME=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING")
SIZE=$(stat -c%s "$APK_OUT")
NOTES="${1:-$(git log -1 --pretty=%s)}"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# The key is `url`, which is what UpdateChecker reads; it may be relative to
# latest.json, as here, or absolute. It was written as `apk` before, which the app
# never looked at — it only kept working because the fallback filename matched.
cat > "$WORK/latest.json" <<EOF
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION_NAME",
  "url": "IoniqScope.apk",
  "sizeBytes": $SIZE,
  "notes": "$(sed 's/"/\\"/g' <<<"$NOTES")"
}
EOF

if [[ -n "$LOCAL_DIR" ]]; then
    mkdir -p "$LOCAL_DIR"
    cp "$APK_OUT" "$LOCAL_DIR/IoniqScope.apk"
    cp "$WORK/latest.json" "$LOCAL_DIR/latest.json"
    TARGET="$LOCAL_DIR"
else
    # Built with plumbing rather than by checking the branch out: this leaves the
    # working tree and the current branch completely untouched, which matters when
    # publishing is something you do in the middle of working on something else.
    APK_BLOB=$(git hash-object -w "$APK_OUT")
    JSON_BLOB=$(git hash-object -w "$WORK/latest.json")
    TREE=$(printf '100644 blob %s\tIoniqScope.apk\n100644 blob %s\tlatest.json\n' \
        "$APK_BLOB" "$JSON_BLOB" | git mktree)
    # No parent: each publish replaces the branch outright.
    COMMIT=$(git commit-tree "$TREE" -m "build $VERSION_CODE ($VERSION_NAME)")
    git push --force --quiet origin "$COMMIT:refs/heads/$DIST_BRANCH"
    TARGET=$(git remote get-url origin | sed 's|\.git$||')/tree/$DIST_BRANCH
fi

printf 'published %s (build %s, %s MB)\n  -> %s\n' \
    "$VERSION_NAME" "$VERSION_CODE" \
    "$(awk "BEGIN{printf \"%.1f\", $SIZE/1048576}")" "$TARGET"
