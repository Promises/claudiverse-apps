# Claudiverse apps

The Mac and Android apps for watching and steering your Claudiverse seats: the
Claude Code sessions running on your machines, with their conversations,
questions, permission requests, agents and background work.

They are optional. A Claudiverse server already has a web panel that does
this in any browser, with nothing extra to install. You need a running
Claudiverse server first: see the [Claudiverse quick start](https://github.com/Promises/claudiverse#quick-start).

**Quick start**: [install a release build](#install) (the Android APK or the Mac
app) or [build from source](#building), then [connect to your server](#connect-to-your-server).

What is here:

- **core/**: the shared Kotlin Multiplatform library: the server's models, its
  REST and channel clients, and the rules that turn them into screens
  (transcript, seat list, fleets, profiles). Android uses its JVM target; the
  Mac app uses it as a native framework.
- **macos/**: the macOS app (SwiftUI, macOS 26, Apple silicon).
- **android/**: the Android app (Jetpack Compose).
- **openvpn/**: the Android app is built on the base of
  [ics-openvpn](https://github.com/schwabe/ics-openvpn) (OpenVPN for Android),
  whose OpenVPN engine this folder holds. The app uses it for one optional
  feature: a built-in tunnel that reaches a server on a private network while
  the phone is away from it, carrying only the app's own traffic. A server you
  can reach directly needs no VPN at all. OpenVPN, OpenSSL and LZ4 are git
  submodules.

## What it looks like

![The Mac app: seats grouped by fleet, and a seat's conversation with a tool call and its answered permission request](docs/images/mac-app.png)

The Mac app: seats grouped by fleet, and one seat's conversation, tool calls and permission requests included.

![The Mac app: a seat's subagent in the agent switcher, and a shell it runs in the background with its command and running time](docs/images/mac-background.png)

A seat's agents and background work: its subagent beside the main conversation, and a shell running in the background.

<p>
  <img src="docs/images/android-seats.png" width="260" alt="The Android app's seat list, grouped by fleet">
  <img src="docs/images/android-background.png" width="260" alt="A seat on Android, with its subagent and a background shell">
  <img src="docs/images/android-board.png" width="260" alt="A forum channel of task cards on Android">
</p>

The Android app: the seat list, a seat with its subagent and background shell, and a channel of task cards.

## Install

Prebuilt apps are on the [Releases](https://github.com/Promises/claudiverse-apps/releases)
page, one release per version tag:

- **Mac**: `Claudiverse-macOS-<version>.zip` (Apple silicon, macOS 26). Unzip it
  and move `Claudiverse.app` to Applications. If the release notes say it is
  not notarized, open it the first time with right-click › **Open**, or run
  `xattr -dr com.apple.quarantine /Applications/Claudiverse.app`.
- **Android**: `claudiverse-android-<version>-universal.apk`, or the one for
  your phone's ABI (most phones: `arm64-v8a`). Open it on the phone and allow
  the install. It installs over an earlier build signed with the same key. A
  build attached as `…-unsigned.apk` cannot be installed as it is; the release
  notes say so. After that, your server can offer updates in the app; see
  [Builds and updates](docs/android/builds-and-updates.md).

`SHA256SUMS` lists every file's checksum. To build the apps yourself, see
[Building](#building).

## Connect to your server

You need a running Claudiverse server and an account on it. In the Android app,
open the Connection tab, enter the server URL and tap Save, then tap Sign in
under Account and enter your email and password; or paste an API token and tap
Save & test. The Mac app asks for the server and signs in when it starts.

## Building

Requirements: JDK 17 or later. For the Android app, the Android SDK with NDK
29.0.14206865 and CMake (from the SDK manager), and the submodules
(`git submodule update --init`). For the Mac app, Xcode 26 and
[XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```sh
./gradlew :core:jvmTest :core:macosArm64Test   # the core's tests, on the JVM and natively

macos/build.sh                                 # build the Mac app into macos/build/
macos/build.sh --install                       # and copy it into /Applications

./gradlew :android:testDebugUnitTest :openvpn:testDebugUnitTest
./gradlew :android:assembleDebug               # APKs per ABI, and a universal one
```

`android/publish.sh` uploads a build to the server, which then offers it to
the installed apps as an update. An update installs in place only when it is
signed with the same key as the installed app.

`macos/build.sh` builds the core as an XCFramework, generates the Xcode project
from `macos/project.yml`, then builds the app. The Mac app's tests run with
`xcodebuild test -project macos/Claudiverse.xcodeproj -scheme Claudiverse`
once the project has been generated.

Build numbers are `claudiverse.versionBase` in `gradle.properties` plus the
commit count.

### Development harness

`macos/build.sh --harness` compiles in a control socket (`Harness.swift`) that
`macos/harness.sh` talks to: open a seat or a board, list windows, and so on,
for testing the app without clicking through it. Normal builds leave it out.

## Licences

- **core/** and **macos/**: MIT; see [LICENSE](LICENSE).
- **openvpn/**: ics-openvpn's terms: the GNU GPL version 2 or later, with
  additional terms and OpenSSL and Apache 2 linking exceptions; see
  [openvpn/LICENSE.txt](openvpn/LICENSE.txt) and
  [openvpn/COPYING](openvpn/COPYING). The submodules and LZO carry their own
  licences. The module is upstream ics-openvpn file for file, apart from the
  files `tools/openvpn-vendor.json` lists as ours; `tools/vendorcheck.py`
  checks that against the recorded upstream commit, and must pass before a
  commit that touches `openvpn/` or updates it from upstream.
  `tools/vendorcheck.py --rev HEAD --list-identical` prints the files it proved
  upstream, as a skip list for a leak scan of the rest of the tree.
- **android/**: the GNU GPL version 2 or later, because it links the OpenVPN
  engine; see [android/LICENSE.txt](android/LICENSE.txt). The Android app as a
  whole, the APK, is distributed under the GPL.
