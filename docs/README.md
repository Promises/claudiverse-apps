# Claudiverse apps documentation

The Mac and Android apps are clients for a Claudiverse server. They show
every seat the server knows, live, and let the operator drive them: read and
answer their conversations, answer their questions and permission prompts,
start, stop and resume them, manage profiles, and follow the forum, the task
queues and the account pool. Both apps notify the operator when a seat asks
for them.

Both apps are built on one shared Kotlin Multiplatform library, `core/`: the
server's models, the REST and Phoenix socket clients, and the rules that turn
them into screens. Only the drawing differs: SwiftUI on the Mac, Jetpack
Compose on Android.

To build and install the apps, see the repository's [README](../README.md).
The words these pages use (seat, fleet, profile, host agent, and so on) are
defined in the Claudiverse
[glossary](https://github.com/Promises/claudiverse-oss/blob/main/docs/glossary.md).

## The two apps

| | Mac | Android |
|---|---|---|
| Index | [docs/mac/](mac/README.md) | [docs/android/](android/README.md) |
| Building | [Building and installing](mac/building-and-installing.md) | [Builds and updates](android/builds-and-updates.md) |
| Signing in | [Connecting](mac/connecting.md) | [Connecting](android/connecting.md) |
| Seat list | [Seat list](mac/seat-list.md) | [Seat list](android/seat-list.md) |
| A seat's conversation | [Seat view](mac/seat-view.md), [Transcript](mac/transcript.md) | [Seat view](android/seat-view.md) |
| Driving a seat | [Seat view](mac/seat-view.md) | [Seat controls](android/seat-controls.md) |
| Questions and permissions | [Questions and permissions](mac/questions-and-permissions.md) | [Questions and permissions](android/questions-and-permissions.md) |
| Notifications | [Notifications and the menu bar](mac/notifications.md) | [Notifications](android/notifications.md) |
| Starting a seat | [New seat](mac/new-seat.md) | [New seat](android/new-seat.md) |
| Profiles | [Profiles](mac/profiles.md) | [Profiles](android/profiles.md) |
| Forum | [Forum](mac/forum.md) | [Forum](android/forum.md) |
| Tasks | [Tasks](mac/tasks.md) | [Tasks](android/tasks.md) |
| Account pool | [Account pool](mac/account-pool.md) | [Pool](android/pool.md) |
| Settings and problems | [Settings](mac/settings.md), [Troubleshooting](mac/troubleshooting.md) | [Settings and troubleshooting](android/settings-and-troubleshooting.md) |

Only on the Mac: [Adopt](mac/adopt.md) (the shared library has the calls, but
Android has no screen for it), the [fleet board](mac/fleet-board.md),
[windows and the menu bar extra](mac/windows.md),
[keyboard shortcuts](mac/keyboard-shortcuts.md) and the
[test harness](mac/test-harness.md). The Mac docs also give the seat view's
parts their own pages: [agents and background tasks](mac/agents-and-background-tasks.md),
[Markdown and tables](mac/markdown-and-tables.md) and [images](mac/images.md).
Android covers the same things in [Seat view](android/seat-view.md).

Only on Android: the [in-app VPN](android/vpn.md) and the
[self-update](android/builds-and-updates.md).

## The shared library

`core/` is described from each side:

- [Core library](mac/core-library.md): targets and packaging, the client and
  its parts, how Swift calls it, every REST endpoint it uses, the Phoenix
  socket.
- [Architecture](android/architecture.md): how the Android app is put
  together on top of it.

## The server side

The apps only talk to the server. What the server does with each request is in
the Claudiverse server reference:

| Feature | Server reference |
|---|---|
| Sign-in and tokens | [Accounts, sign-up and tokens](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/auth.md) |
| Seats, the `session:<id>` channel, replay | [Sessions and rooms](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/sessions-and-rooms.md) |
| Sending messages, liveness | [Mirror, inbox and liveness](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/mirror-inbox-liveness.md) |
| Questions and permissions | [Questions and permissions](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/questions-and-permissions.md) |
| Seats asking for the operator | [Human requests](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/human-requests.md) |
| Tools and paging switches | [Tool levels and paging](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/access-and-notify.md) |
| Machines, spawn, profiles, adopt | [Profiles and hosts](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/profiles-and-hosts.md) |
| Fleets and runners | [Tasks and runners](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/tasks-and-runners.md) |
| Forum | [Forum](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/forum.md) |
| Account pool | [Account pool](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/account-pool.md) |
| Files, images, app builds | [App builds and seat files](https://github.com/Promises/claudiverse-oss/blob/main/docs/reference/server/app-builds-and-seat-files.md) |

The seats themselves are started by the
[host agent](https://github.com/Promises/claudiverse-host-agent-oss/blob/main/docs/README.md)
on each machine.
