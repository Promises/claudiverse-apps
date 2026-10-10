# Seat controls

Everything an operator can do to a seat from its screen besides reading it: type a message, queue one while the seat is busy, attach files, interrupt, stop or resume the seat, and change its model, effort, permission mode, Claudiverse tools and paging right. Reading the transcript is on [seat-view.md](seat-view.md); answering questions and permission prompts is on [questions-and-permissions.md](questions-and-permissions.md).

All of this lives in `SeatViewModel` (Android) with the shared `Outbox`, `Api` and `SessionChoices` from `core`, and the composables in `SeatScreen.kt` and `SessionControls.kt`.

## Sending a message

The composer is the text box at the bottom of the seat screen: an "Attach files" button (plus icon), the field, and a "Send" button. The field's placeholder is "Message the seat"; it changes to "Seat busy — your message will queue" when the seat is busy or has queued messages, and to "A note to go with the file (optional)" or "A note to go with the N files (optional)" when files are attached. "Send" is disabled when the text is blank and nothing is attached, or when an attached file is over the limit. While a send or upload is running a spinner replaces the Send button and the attach button is disabled. The text is cleared as soon as Send is tapped. An unsent draft survives rotation.

`SeatViewModel.send(text)`:

1. Blank text is ignored.
2. If the seat's live state says `idle == false`, or this seat already has queued messages, the text goes to the outbox (so order holds) and the notice "Seat busy — queued, sends when it goes idle" appears.
3. Otherwise it calls `POST /api/notify/send` on the IO dispatcher and reads the `status` of the answer:
   - `delivered`: nothing is shown; the reply streams back over the channel (the socket is checked to be alive).
   - `busy` (the seat went busy between the check and the call): the message is enqueued and the same "queued" notice is shown. It is never dropped.
   - anything else: notice "Not delivered: <hint or status>".
   - a thrown error: notice "Not delivered: <error message>".

Request and response:

```json
{ "session_id": 42, "content": "Run the tests again", "force": true }
```

`force` is only sent when true. The client reads `status` (`delivered`, `busy` or an error status) and the first present of `hint` and `error` as the hint. `SendResult.delivered` is `status == "delivered"`. A server that does not support `force` ignores it and answers `busy`.

Notices appear in a bar above the composer with an "OK" button that dismisses it. The same operator message later shows in the transcript as an "injected" bubble when the seat mirrors it back.

## The outbox (offline queue)

The server rejects a send to a busy seat instead of queueing it, so the app queues. `Outbox` (core) holds `QueuedMessage(id, seatId, text, queuedAt)` and exposes them as a flow; each seat screen filters it to its own seat.

How it behaves:

- **Persistence.** Every change is saved through `OutboxStore`; on Android that is `PrefsOutboxStore`, SharedPreferences file `claudiverse_outbox`, key `queue`. The stored form is a JSON array: `[{"id": 1, "seat": 42, "text": "...", "at": 1760000000000}]` (`at` is epoch milliseconds). Rows that cannot be read are dropped without losing the rest of the queue. Ids are the highest existing id plus one.
- **Draining.** While the queue is not empty a background loop runs every 4,000 ms (`POLL_MS`). Per tick, per seat, only the oldest message is considered: the loop fetches `GET /api/sessions/<id>`; if the seat's `status` is not `running` or `live.idle` is not true, it does nothing; otherwise it sends the message with `POST /api/notify/send` and removes it if the answer is `delivered`. One message per seat per idle: a delivered send makes the seat busy again, so the next waits for the following idle.
- **Starting.** The loop starts when something is enqueued, and at app start if the stored queue is not empty. It stops when the queue is empty.
- **Retry rules.** There is no retry limit or backoff beyond the 4 second tick. A failed fetch or send is logged ("outbox: seat <id> not drained (...)") and tried again at the next tick. A message sent to a seat that stopped is never sent (a stopped seat never goes idle) and stays queued until removed.
- **Needs the app running.** Delivery happens only while the app process is alive; the queue survives the app being killed and resumes the next time it starts.

In the seat screen a panel "Queued — sends when the seat goes idle" lists this seat's queued messages (two lines each, ellipsised), each with:

- "Send now": force-sends that message (`POST /api/notify/send` with `"force": true`). On `delivered` the message is removed from the queue and the notice reads "Sent now — the seat takes it mid-turn". If the server answers `busy`, the notice is "Not sent: this server cannot force a send yet — still queued". Other statuses give "Not sent: <hint or status> — still queued"; errors give "Not sent: <message> — still queued". If the message is no longer queued the result is status `error`, hint "no longer queued".
- A close icon ("Remove") that deletes the message from the queue.

## File attachments

The plus button opens the system document picker (`OpenMultipleDocuments`, any type). Picked files appear as chips above the composer; a file already picked is not added twice. Each chip shows a thumbnail for images (Android 10 and later, 96 by 96 px) or a document icon, the name, the size ("<n> B", "<n> KB", "<n.n> MB", or "size unknown"), and an "Remove <name>" button.

**Size limit.** The client refuses files over 25 MiB (`MAX_UPLOAD_BYTES`, 25 * 1024 * 1024) up front, because the server answers 413 only after the whole upload. Such a chip gets an error-coloured border and the text "over the 25 MiB limit", and Send stays disabled until it is removed. A file whose size the provider does not report is not checked.

**Sending.** With attachments, Send calls `sendFiles(files, note)` instead of `send`, and the typed text becomes the note. The attachments row is cleared at once.

1. `uploading` is set (the spinner shows) and the files are uploaded one after another, in the order picked, each streamed from the content resolver without holding the file in memory: `POST /api/sessions/<id>/files`, multipart form with one part named `file`, carrying the file's content type (when known) and a `Content-Disposition` filename. The answer is parsed as `{"file_id": "...", "name": "...", "size": 123, "url": "/api/files/..."}`; `name` defaults to the local name and `size` to 0.
2. Only if all uploads succeeded, one message is sent through `send` (so it queues while the seat is busy). Its text, from `SeatSession.filesMessage`, is:

```text
The operator sent you a file: report.pdf (1234 bytes). Fetch it with:
curl -fsS -H "Authorization: Bearer $CLAUDIVERSE_TOKEN" "$CLAUDIVERSE_URL/api/files/<id>" -o report.pdf

<note>
```

   With several files the first line is "The operator sent you N files:" and each entry is numbered ("1. name (size bytes). Fetch it with:" and so on, blank line between entries). The local file name in `-o` has characters outside `A-Za-z0-9._-` replaced by `_` (`file` if empty). The note, trimmed, comes last, only if not blank.

The seat runs on another machine and fetches the file itself with its own token, which is why the message carries a `curl` command. If any upload fails nothing is sent: the notice is "Not sent: this server cannot take files yet" for HTTP 404, else "Not sent: <error message>". Note that the attachments have already been cleared from the composer by then.

## The session settings sheet

The gear icon ("Session settings") in the header opens a bottom sheet "Session settings" ("Changes go to the seat as commands, and queue if it is busy."). In fact only model and effort are sent as commands (and queue); the other sections are direct server calls. Every value shown is what the seat reports, not what was last tapped, so a change made at the terminal shows too.

| Section | Chips | What it does |
|---|---|---|
| Model | Per model: "Standard" and, for 1M-capable models, "1M"; a collapsible "Older models" list (open by itself when the running model is in it); aliases "Default" and "Opus plan" | Sends the message `/model <id>` (1M adds the suffix `[1m]`; aliases send `default` or `opusplan`) through `send`, so it queues when the seat is busy. A dot marks the running model (matched exactly, or with a dated `-2...` suffix). |
| Effort | `low`, `medium`, `high`, `xhigh`, `max`, `auto` | Sends `/effort <level>` through `send`. "Running: <effort>" comes from the latest reply, "unknown until the next reply" before that. |
| Permission mode | Default, Accept edits, Plan, Auto | `POST /api/sessions/<id>/permission_mode`. |
| Claudiverse tools | Basic, Full, Default | `POST /api/sessions/<id>/access`. |
| May page the operator | On, Off, Default; with "For": 1 h, 4 h, 1 day, Until changed | `POST /api/sessions/<id>/notify`. |

The model list offered is in `SessionChoices` (Opus 5.5, Opus 5, Sonnet 5.5, Sonnet 5, Fable 5.1, Fable 5, Mythos 5.1, Mythos 5, Haiku 4.5; older: Opus 4.8, 4.7, 4.6, 4.5, 4.1, Sonnet 4.6, 4.5, 4). The sheet notes that a seat reports no 1M suffix, so the window it has is not shown.

### Permission mode

`bypassPermissions` and `dontAsk` are deliberately not offered, so a mis-tap cannot switch off a seat's permission checks. Request: `{"mode": "acceptEdits"}`; the answer's `mode` is used as the applied mode (falling back to the one sent). The notice is "Mode set to <mode>". Some seats (mod seats) do not report their mode, so the app remembers the one it last set and the sheet then says "Set to <mode> from this app (this seat does not report its mode)"; with neither, "Not reported by this seat; pick one to set it"; otherwise "Running: <mode>". About 2.5 seconds after a change the seat is re-fetched to show what it reports. Errors: HTTP 409, 503 and 504 give "Mode not changed: <server's sentence after the first ': '>" (the server says why, for instance mid-turn or the agent offline); others "Mode not changed: <message>".

### Claudiverse tools (access)

Request `{"level": "basic"}` or `"full"`; `{"level": null}` puts it back to the default for how the seat was started. The answer's `session` replaces the seat row. The line shows "Basic: reports and shared tools" or "Full: orchestrator tools too", with "(default)" when the operator did not set it, and "Applies within a minute."; "Not reported: this server has no access levels yet" when the seat row has none. Notices: "Tools set to <level>" and "Tools back to their default". Errors: 403 "Not changed: this needs the app's own token", 404 "Not changed: this server has no access settings yet", else "Not changed: <message>".

### Paging (may page the operator)

Whether the seat may page the operator (see [notifications.md](notifications.md)). Pick a duration under "For" first, then tap On or Off; Default clears the switch so the seat's own launch setting applies. Request: `{"allow": true, "minutes": 60}` (`minutes` is sent only with a non-null `allow` and a non-null duration; the API documents 1 to 10080). `{"allow": null}` clears it. The answer's `session` carries `notifyGrant` and `notifyUntil`. The line reads "On until 14:30: it may page you.", "Off ...: it may not page you." or "Default: it may page you." / "Default: it may not page you.". Notices: "May page you for 1 h", "May not page you for 4 h" (durations that are whole days read "1 d"), "May page you" with no duration, or "Paging back to its default". Errors reuse the access ones above (403 and 404 texts are the same).

### Stop, Resume and Interrupt

- **Interrupt** (header button, with confirmation) sends the `interrupt` channel event; the notice is "Interrupt sent", or "Not connected — interrupt not sent" when the socket is down.
- **Stop seat…** (menu "More", shown when the seat is running and reports its machine) asks "Stop <label>?" ("Closes its terminal session on <host>, ending any turn in progress. Its conversation is kept: resume it from the seat list with Running turned off.") and calls `POST /api/sessions/<id>/stop`. Notices: "Stopping on <host>…", then "Stopped on <host>". Errors: 403 "Not stopped: its folder is outside <host>'s allowed folders, or this app may not stop seats", 404 "Not stopped: no seat by this title runs on <host> (renamed since it started?)", 422 "Not stopped: the seat never reported its machine", 504 "Not stopped: <host> did not answer (offline?)", else "Not stopped: <message>". The seat is re-fetched afterwards.
- **Resume** (header button, shown when the seat is not running, reports a host and has a conversation id) starts the same conversation again on its machine; the server answers "accepted" and the outcome arrives as a spawn event shown in the app's snackbar (see [new-seat.md](new-seat.md)). Errors: 400 "Not resumed: <server's sentence>", 409 "Not resumed: the seat is still running", 422 "Not resumed: the seat never reported its Claude session or folder", 429 "Not resumed: <host> is rate-limited, try again in a few seconds", 504 "Not resumed: <host> did not answer (offline?)", else "Not resumed: <message>".

### Adopting a seat into a profile

The core `Api` has `adoptPreview` and `adopt` calls (`GET /api/sessions/<id>/adopt_preview`, `POST /api/sessions/<id>/adopt`), but the Android seat screen and its sheets do not offer adoption; nothing in the app's UI calls them. Profiles are covered in [profiles.md](profiles.md).

## Interface summary

| Action | Call |
|---|---|
| Send, queue drain, force | `POST /api/notify/send` `{"session_id", "content", "force"?}` |
| Upload a file | `POST /api/sessions/<id>/files` (multipart `file`) |
| Permission mode | `POST /api/sessions/<id>/permission_mode` `{"mode"}` |
| Tools | `POST /api/sessions/<id>/access` `{"level"}` |
| Paging | `POST /api/sessions/<id>/notify` `{"allow", "minutes"?}` |
| Stop | `POST /api/sessions/<id>/stop` |
| Resume | `POST /api/sessions/<id>/resume` (answers `spawn_id`) |
| Interrupt | channel `session:<id>` cast `interrupt` |

Access and paging need an app or super token (the `Api` documents both as "App or super token"); a seat token gets 403, shown as "Not changed: this needs the app's own token". A stop the server refuses with 403 reads "... or this app may not stop seats".

## Failure modes and troubleshooting

| Message | Meaning |
|---|---|
| "Not delivered: <hint>" | The server refused the send; the hint is its own words. |
| "Not delivered: HTTP ..." | The request failed (offline, server error). The text is not queued; retype or resend. |
| "Seat busy — queued, sends when it goes idle" | Queued in the outbox; it sends when the seat is idle and the app is running. |
| A queued message that never leaves | The seat is stopped, or never reports idle, or the app was not running. Use "Send now" or remove it. |
| "Not sent: this server cannot force a send yet — still queued" | The server ignores `force` and answered `busy`. |
| "over the 25 MiB limit" | Remove the file; the server would refuse it with 413. |
| "Not sent: this server cannot take files yet" | `POST /api/sessions/<id>/files` returned 404. |
| "Mode not changed: ..." | The seat refused or is not connected; the text after it is the server's reason. |
| "Not changed: this needs the app's own token" | You are signed in with a seat or api token without the app's powers. |

## Related pages

- [Seat view](seat-view.md)
- [Questions and permissions](questions-and-permissions.md)
- [Notifications: seats that need you](notifications.md)
- [Seat profiles](profiles.md)
- [New seat (spawning a seat)](new-seat.md)
- [Settings, permissions and troubleshooting](settings-and-troubleshooting.md)
- Server: [Tool levels and paging](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/access-and-notify.md), [App builds and seat files](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/app-builds-and-seat-files.md), [HTTP mirror, inbox long-poll and liveness](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/mirror-inbox-liveness.md), [Sessions and Rooms](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/sessions-and-rooms.md)
- Mod: [The inbox: remote input and inbox commands](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/services/mod/inbox.md)
- Web panel: [The seat view](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/services/web/seat-view.md)
- Runners and tools: [cvctl: the orchestrator CLI](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/runners/cvctl.md), [cv-mcp: the claudiverse MCP server (overview)](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/runners/mcp.md), [cv-mcp: seat, ping and question tools](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/runners/mcp-seats.md)
- Host agent: [Seat control](https://github.com/Promises/claudiverse-host-agent-oss/blob/main/docs/reference/seat-control.md)
- Mac: [Images](../mac/images.md), [Seat view](../mac/seat-view.md)

## Source

- `android/src/main/java/dev/claudiverse/app/seat/SeatViewModel.kt`, `PrefsOutboxStore.kt`
- `android/src/main/java/dev/claudiverse/app/ui/SeatScreen.kt`, `SessionControls.kt`
- `core/src/commonMain/kotlin/dev/claudiverse/core/Outbox.kt`, `Api.kt`, `SessionChoices.kt`, `SeatSession.kt`, `Models.kt`
