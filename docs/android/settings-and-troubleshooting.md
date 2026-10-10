# Settings, permissions and troubleshooting

This page collects in one place every setting the Android app keeps, every Android permission it holds, and the problems an operator is most likely to hit, with what the app says and what to do. Each entry links to the page that explains the feature in full.

## Settings

The app has no separate settings screen: everything an operator sets is on the **Connection** tab ([connecting.md](connecting.md)), apart from the per-seat controls in a seat's sheet ([seat-controls.md](seat-controls.md)).

### Stored settings

`Settings` keeps them in the SharedPreferences file `claudiverse` (private to the app, separate from the OpenVPN engine's own preferences). A saved change reaches the API client, the socket and the screens at once through a `StateFlow`, without a restart.

| Key | Set on | Meaning | Default |
|---|---|---|---|
| `server_url` | Claudiverse server card, `Server URL` | Base URL, e.g. `http://claudiverse.example:4000`. Trimmed, trailing `/` removed. | empty |
| `api_token` | `API token` field, or `Sign in` | Bearer token for every call. Trimmed. | empty |
| `forum_author` | `Post to the forum as` | The `self` name forum posts go out under ([forum.md](forum.md)). Blank saves as `operator`. | `operator` |
| `auto_connect` | `Use the VPN only when the server is out of reach …` switch | Lets `AutoVpn` bring the tunnel up away from the server's network ([vpn.md](vpn.md)). | `true` |
| `notify_requests` | `Notify me when a seat asks for me …` switch | Runs the watcher service that posts "needs you" notifications ([notifications.md](notifications.md)). | `true` |
| `vpn_profile_uuid` | importing an `.ovpn` | Which OpenVPN profile in the engine's profile store is the app's. | absent |

The configuration is **complete** when both `server_url` and `api_token` are non-blank. Until then the app starts on the Connection tab, the API refuses calls with `Set the server URL and token in Settings`, and neither the watcher nor the VPN automation runs.

### Other app state

| Where | What | Notes |
|---|---|---|
| SharedPreferences `claudiverse_outbox`, key `queue` | Messages queued for busy seats ([seat-controls.md](seat-controls.md)) | JSON array; survives the app being killed. |
| Engine profile store | The imported OpenVPN profile, its saved username and password | Owned by the vendored engine; the app reaches it through `ProfileManager`. |
| `cacheDir/updates/claudiverse.apk` | The last downloaded update ([builds-and-updates.md](builds-and-updates.md)) | Overwritten by each download. |
| In memory only | The seat list's Running filter, the transcript image cache (48 MiB), filter chips | Lost when the process ends. |

The manifest sets `android:allowBackup="false"`, so none of this, the token included, goes into Android's cloud or device-transfer backups. Moving to a new phone means entering the server and signing in again, and re-importing the VPN profile.

### Per-seat settings

Model, effort, permission mode, Claudiverse tools (access level) and whether a seat may page the operator are properties of the seat on the server, not of the app. They are changed from the seat's settings sheet; see [seat-controls.md](seat-controls.md).

## Android permissions

The app's own manifest asks for two permissions; the vendored OpenVPN library's manifest is merged in and adds the rest.

| Permission | Declared by | Why | Granted how |
|---|---|---|---|
| `INTERNET` | `openvpn` | All server traffic and the tunnel. | At install. |
| `ACCESS_NETWORK_STATE` | `openvpn` | Network callbacks: `AutoVpn` re-deciding the tunnel, and the socket reconnecting when a network returns. | At install. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | `openvpn` | The VPN service (subtype `vpn`) and the app's `WatchService` (subtype `operator-requests`) run as foreground services of type `specialUse`. | At install. |
| `POST_NOTIFICATIONS` | `openvpn` | The watcher's notifications and the VPN's status notification. | Runtime prompt on Android 13+, asked when the watcher turns on. |
| `RECEIVE_BOOT_COMPLETED` | `openvpn` | The engine's comment: its `keepVPNAlive` job is persisted, and JobScheduler refuses a persisted job without this permission. The app declares no boot receiver of its own. | At install. |
| `REQUEST_INSTALL_PACKAGES` | `android` | Installing an update downloaded from the server. | The operator allows "Install unknown apps" for Claudiverse in Android settings; the update card says when it is missing. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | `android` | The `Allow background use` button on the `Seats that need you` card asks for the exemption directly. | System dialog. |
| VPN consent | (not a manifest permission) | Android's per-app "connection request" dialog, needed once before the app may start a VPN. | Shown by `MainActivity` when you tap Connect; never from the background. |

## Logs

The app logs to logcat under the tag `claudiverse`, including everything the shared `core` library logs (`CoreLog.sink` points there). Useful lines:

| Line | From |
|---|---|
| `socket open`, `socket lost (<why>)` | The Phoenix socket ([architecture.md](architecture.md)). |
| `auto-vpn: reachable=<bool> ours=<bool> other=<bool> -> <Connect\|Disconnect\|None>` | Each `AutoVpn` decision ([vpn.md](vpn.md)). |
| `auto-vpn: connect failed: <message>` | An automatic connect that threw. |
| `outbox: seat <id> not drained (<message>)` | A queued message that could not be sent this round. |
| `profiles not read: <message>` | A failed profile list refresh. |
| `update failed: <message>`, `update installed` | Self-update ([builds-and-updates.md](builds-and-updates.md)). |
| `updated to <version>: restarting VPN check and watcher`, `watcher not restarted: <message>` | After an update installs. |

```sh
adb logcat -s claudiverse
```

## Troubleshooting

### The app cannot reach the server

| Symptom | Cause and fix |
|---|---|
| `Set the server URL and token in Settings` | URL or token is empty. Fill both on the Connection tab. |
| `Not a valid server URL: <text>` | The URL is not `http://` or `https://` with a host. |
| Lists show a red banner with a network error, VPN pill not `VPN up` | The phone is off the server's network and the tunnel is down. Check the `In-app VPN` card: is a profile imported, are credentials saved, is `Use the VPN only when the server is out of reach` on? If another VPN is up, the app never replaces it automatically: connect by hand or turn the other VPN off. See [vpn.md](vpn.md). |
| `Server sent something that is not JSON` | The URL points at something that is not the Claudiverse API (wrong port, a proxy or captive portal page). |
| `HTTP 401: …` / `This token is no longer valid: sign in again.` | The token was revoked or is wrong. Tap `Sign in` on the Account card. |
| Live views say `offline` or `joining` for long | The socket is down or the channel join fails. It reconnects by itself with backoff up to 30 s; coming back to the screen forces a check. A `Live view: <reason>` banner names a refused join. |

### Controls are refused

| Symptom | Cause and fix |
|---|---|
| `Not changed: this needs the app's own token` | The token's scope cannot change seat settings. The Account card shows the scope; sign in for a `super` token. |
| `Not saved: this needs a signed-in token (Connection → Account → Sign in)` (and `Not started`, `Not deleted` …) | Profiles need a signed-in token. See [profiles.md](profiles.md). |
| `Not spawned: … or this app may not spawn` | The folder is outside the machine's allowed folders, or the token may not spawn. See [new-seat.md](new-seat.md). |
| `Not answered: the server refused (…)` on a permission prompt | The server refuses an allow from a token without the app's powers. See [questions-and-permissions.md](questions-and-permissions.md). |

### Notifications

| Symptom | Cause and fix |
|---|---|
| No "needs you" notifications and no `needs you` pills in the seat list | The watcher is off (`notify_requests`), or the configuration is incomplete. The pills come from the same watcher. |
| Toast `Without notifications you will not hear when a seat needs you` | `POST_NOTIFICATIONS` was refused. Allow notifications for the app in Android settings. |
| Notifications stop after the phone has been idle | Android paused the app. Tap `Allow background use` on the `Seats that need you` card. |
| `This server does not offer seat requests yet.` | The server answered 404 for `GET /api/human_requests`. |

See [notifications.md](notifications.md).

### Updates

| Symptom | Cause and fix |
|---|---|
| The update card asks for permission to install | Allow "Install unknown apps" for the app, then tap install again. |
| `Not installed: …` | Android's installer refused, most often because the published build is signed with a different key from the installed app. An update installs in place only when both are signed with the same key. |
| `Download corrupt: sha256 …` | The downloaded file does not match the server's checksum; try again. |

See [builds-and-updates.md](builds-and-updates.md).

### Messages

| Symptom | Cause and fix |
|---|---|
| A queued message never sends | The outbox drains only while the app runs, and only to a running seat that reports idle. A stopped seat's messages stay queued: use `Send now` or remove them. |
| `Not sent: this server cannot take files yet` | The server has no file upload endpoint. |
| A file chip says `over the 25 MiB limit` | Remove it; the server would refuse it. |

## Related pages

- [Claudiverse for Android](README.md)
- [Connecting to a server](connecting.md)
- [In-app VPN](vpn.md)
- [Notifications: seats that need you](notifications.md)
- [Builds and updates](builds-and-updates.md)
- [Architecture](architecture.md)
- Mac: [Settings](../mac/settings.md), [Troubleshooting](../mac/troubleshooting.md)

## Source

- `android/src/main/java/dev/claudiverse/app/Settings.kt`
- `android/src/main/java/dev/claudiverse/app/seat/PrefsOutboxStore.kt`
- `android/src/main/java/dev/claudiverse/app/ui/ConnectionScreen.kt`
- `android/src/main/AndroidManifest.xml`
- `openvpn/src/main/AndroidManifest.xml`
- `android/src/main/java/dev/claudiverse/app/AppContainer.kt`
- `core/src/commonMain/kotlin/dev/claudiverse/core/CoreLog.kt`
