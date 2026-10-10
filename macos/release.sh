#!/bin/sh
# Build the Mac app for a release and package it: macos/release.sh <version> <out dir>
#
# Writes <out>/Claudiverse-macOS-<version>.zip, and <out>/release-notes.md
# when the app is not signed and notarized (how to open it anyway).
#
# Signing is used only when its secrets are in the environment:
#   MACOS_CERT_P12_BASE64, MACOS_CERT_PASSWORD   a Developer ID Application certificate (.p12, base64)
#   APPLE_TEAM_ID                                 its team
#   APPLE_ID, APPLE_APP_PASSWORD                  for notarization (an app-specific password)
# Without them the app keeps the ad hoc signature build.sh gives it.
# Nothing here prints a secret.
set -eu

version=${1:?usage: release.sh <version> <out dir>}
out=${2:?usage: release.sh <version> <out dir>}
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"

CLAUDIVERSE_VERSION="$version" "$here/build.sh"
app="$here/build/derived/Build/Products/Release/Claudiverse.app"
zip="$out/Claudiverse-macOS-$version.zip"

signed=""
notarized=""
if [ -n "${MACOS_CERT_P12_BASE64:-}" ] && [ -n "${MACOS_CERT_PASSWORD:-}" ] && [ -n "${APPLE_TEAM_ID:-}" ]; then
  echo "== signing with the Developer ID certificate"
  keychain="$RUNNER_TEMP/release.keychain-db"
  keychain_password=$(uuidgen)
  p12="$RUNNER_TEMP/cert.p12"
  printf '%s' "$MACOS_CERT_P12_BASE64" | base64 --decode > "$p12"
  security create-keychain -p "$keychain_password" "$keychain"
  security set-keychain-settings -lut 3600 "$keychain"
  security unlock-keychain -p "$keychain_password" "$keychain"
  security import "$p12" -k "$keychain" -P "$MACOS_CERT_PASSWORD" -T /usr/bin/codesign >/dev/null
  security set-key-partition-list -S apple-tool:,apple: -s -k "$keychain_password" "$keychain" >/dev/null
  security list-keychains -d user -s "$keychain" $(security list-keychains -d user | tr -d '"')
  rm -f "$p12"
  identity=$(security find-identity -v -p codesigning "$keychain" | sed -n "s/.*\"\(Developer ID Application: .*($APPLE_TEAM_ID)\)\"/\1/p" | head -1)
  [ -n "$identity" ] || { echo "no Developer ID Application identity for team $APPLE_TEAM_ID in the certificate" >&2; exit 1; }
  # The hardened runtime is what notarization requires; the entitlement keeps "open in iTerm" (Apple events) working under it.
  codesign --force --deep --timestamp --options runtime \
    --entitlements "$here/Claudiverse.entitlements" --sign "$identity" "$app"
  codesign --verify --strict "$app"
  signed=1

  if [ -n "${APPLE_ID:-}" ] && [ -n "${APPLE_APP_PASSWORD:-}" ]; then
    echo "== notarizing"
    ditto -c -k --keepParent "$app" "$out/notarize.zip"
    xcrun notarytool submit "$out/notarize.zip" --apple-id "$APPLE_ID" --password "$APPLE_APP_PASSWORD" \
      --team-id "$APPLE_TEAM_ID" --wait
    rm -f "$out/notarize.zip"
    xcrun stapler staple "$app"
    notarized=1
  fi
fi

ditto -c -k --keepParent "$app" "$zip"
echo "packaged: $zip"

if [ -z "$notarized" ]; then
  cat > "$out/release-notes.md" <<EOF
## Mac

\`Claudiverse-macOS-$version.zip\` holds \`Claudiverse.app\` for Apple silicon Macs with macOS 26.
$( [ -n "$signed" ] && echo "It is signed with a Developer ID but not notarized," || echo "It is not signed with a Developer ID (ad hoc signature only)," ) so macOS refuses to open it the first time. Either:

- right-click (or Control-click) \`Claudiverse.app\` in Finder, choose **Open**, then **Open** again; or
- remove the quarantine flag in Terminal: \`xattr -dr com.apple.quarantine /Applications/Claudiverse.app\`

Then open it as usual.
EOF
  echo "notes: $out/release-notes.md"
fi
