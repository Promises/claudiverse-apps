#!/bin/sh
# Build the Claudiverse macOS app: the Kotlin core as a native framework, the
# Xcode project from project.yml, then the app.
#
# Usage:  macos/build.sh                     build into macos/build/
#         macos/build.sh --install           also copy it into /Applications (replacing an older copy)
#         macos/build.sh --harness [--install]  with the development control socket (Harness.swift)
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)

harness=""
install=""
for arg in "$@"; do
  case "$arg" in
    --harness) harness=1 ;;
    --install) install=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done


echo "== core (Kotlin/Native framework)"
"$root/gradlew" -p "$root" :core:assembleClaudiverseCoreReleaseXCFramework -q

echo "== Xcode project"
xcodegen generate --spec "$here/project.yml" --project "$here" --quiet

# The version is the base in gradle.properties plus the commit count, as the Android app's is, plus the short commit.
base=$(sed -n 's/^claudiverse.versionBase=//p' "$root/gradle.properties")
count=$((base + $(git -C "$root" rev-list --count HEAD 2>/dev/null || echo 0)))
commit=$(git -C "$root" rev-parse --short HEAD 2>/dev/null || echo unknown)

echo "== app (1.0.$count, $commit)${harness:+, with the harness}"
# Only --harness compiles Harness.swift in; a normal build has no control socket.
conditions='$(inherited)'
[ -n "$harness" ] && conditions='$(inherited) CLAUDIVERSE_HARNESS'
xcodebuild -project "$here/Claudiverse.xcodeproj" -scheme Claudiverse -configuration Release \
  -derivedDataPath "$here/build/derived" -quiet \
  MARKETING_VERSION="1.0.$count" CURRENT_PROJECT_VERSION="$count" GIT_COMMIT="$commit" \
  SWIFT_ACTIVE_COMPILATION_CONDITIONS="$conditions" \
  build

app="$here/build/derived/Build/Products/Release/Claudiverse.app"

echo "built: $app"

if [ -n "$install" ]; then
  # Quit a running copy first: replacing a running app's bundle leaves it half-updated.
  # A copy too busy to answer the quit within 5 s is stopped.
  osascript -e 'with timeout of 5 seconds' -e 'tell application id "dev.claudiverse.desktop" to quit' -e 'end timeout' 2>/dev/null || true
  pkill -f '/Claudiverse.app/Contents/MacOS/Claudiverse' 2>/dev/null || true
  rm -rf /Applications/Claudiverse.app
  cp -R "$app" /Applications/
  echo "installed: /Applications/Claudiverse.app"
fi
