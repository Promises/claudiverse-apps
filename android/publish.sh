#!/usr/bin/env bash
#
# publish.sh — build the Claudiverse app and publish it to the server, as the
# update every installed app is then offered (Connection → App updates). The
# server keeps only the newest build: publishing replaces the previous one.
#
#   CLAUDIVERSE_URL=http://host:4010 CLAUDIVERSE_APP_TOKEN=... android/publish.sh
#   NOTES="what changed" android/publish.sh   (default: the last commit's subject)
#
# Needs the APP token: the server refuses a publish with any other token, so
# a seat can never put a build on the phones.
set -euo pipefail

: "${CLAUDIVERSE_URL:?set CLAUDIVERSE_URL}"
: "${CLAUDIVERSE_APP_TOKEN:?set CLAUDIVERSE_APP_TOKEN (the app token; only it may publish)}"
cd "$(dirname "$0")/.."

# The build is labelled with HEAD's commit and count: a dirty tree would publish
# code that no commit holds.
if [ -n "$(git status --porcelain)" ]; then
    echo "publish: the working tree has uncommitted changes; commit them first" >&2
    exit 1
fi

# A plain build: not the test-only kind adb -Pandroid.injected.* produces,
# which a phone refuses to install from a file.
./gradlew :android:assembleDebug -q
APK=android/build/outputs/apk/debug/android-arm64-v8a-debug.apk

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AAPT2=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)aapt2
BADGING=$("$AAPT2" dump badging "$APK")
CODE=$(printf '%s\n' "$BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
NAME=$(printf '%s\n' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
COMMIT=$(git rev-parse --short=8 HEAD)
NOTES="${NOTES:-$(git log -1 --format=%s)}"
[ -n "$CODE" ] && [ -n "$NAME" ] || { echo "publish: could not read the version from $APK" >&2; exit 1; }

echo "publishing $NAME (code $CODE, $COMMIT), $(wc -c < "$APK" | tr -d ' ') bytes"
curl -fsS -H "Authorization: Bearer $CLAUDIVERSE_APP_TOKEN" \
    -F "apk=@$APK" -F "version_code=$CODE" -F "version_name=$NAME" -F "commit=$COMMIT" --form-string "notes=$NOTES" \
    "${CLAUDIVERSE_URL%/}/api/app/builds"
echo
