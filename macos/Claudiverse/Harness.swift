#if CLAUDIVERSE_HARNESS
import AppKit
import ClaudiverseCore
import Foundation
import SwiftUI
import UserNotifications

/**
 * A control socket for driving the app while developing it, compiled in only
 * by `build.sh --harness` (CLAUDIVERSE_HARNESS); a normal build has none of it.
 *
 * Listens on harness.sock in the app's folder (AppFolder: ~/Library/
 * Application Support/Claudiverse, or "Claudiverse <name>" for a copy) (in the
 * 0700 folder, so only the operator's account can connect). One JSON command
 * per connection, one JSON reply, e.g. with macos/harness.sh:
 *
 *   harness.sh '{"cmd":"state"}'
 *   harness.sh '{"cmd":"select","seat":204013}'
 *
 * Commands: state, windows {onscreen?}, section {name}, select {seat}, board {fleet},
 * profile {id} (open it in the Profiles section; {"new": true} for a new one),
 * adopt {seat} (open the Adopt sheet for it),
 * window {seat}, send {seat, text, now?}, reveal {text} (scroll open transcripts to
 * the first message containing it), preview {text, image?} (a window drawing that
 * Markdown as Claude's and the operator's bubbles; image: a server file path,
 * drawn in the operator's bubble as a stored image), notify {title, body}, log (core's last
 * 200 log lines), probe (the socket's heartbeat check), restarts (Settings → Restarts
 * in a window), autorestart {minutes} (the global idle minutes; never the switch), snapshot {path, window?} (a window drawn into a PNG by the app), quit.
 * Window ids from "windows" work with `screencapture -l <id>`, and with snapshot.
 */
@MainActor
enum Harness {
    /// Posted by "reveal", its object the text to find; open transcripts scroll to it.
    static let reveal = Notification.Name("ClaudiverseHarnessReveal")
    private static var model: AppModel?
    /// The "preview" window, reused by the next preview.
    private static var previewWindow: NSWindow?
    /// Seat sessions opened by "send", kept until their send has gone (closing at once would cancel it).
    private static var sending: [SeatSession] = []
    /// Seats joined in the background by "seatagents", to report what their replay holds.
    private static var inspecting: [Int64: SeatSession] = [:]
    /// Core's latest log lines, newest last: the socket's own account of why it dropped, say.
    nonisolated(unsafe) private static var lines: [String] = []
    private static let linesLock = NSLock()

    /// A line in the harness's "log", beside core's: the app's own measurements.
    nonisolated static func note(_ line: String) {
        let stamped = "\(Date().formatted(.iso8601.time(includingFractionalSeconds: true))) \(line)"
        linesLock.withLock {
            lines.append(stamped)
            if lines.count > 200 { lines.removeFirst(lines.count - 200) }
        }
    }

    /// When the main window's seat last changed, for "switch" timings: time to first rows, and until it settles.
    static var switchStarted: (seat: Int64, at: Date)?

    static func start(_ app: AppModel) {
        model = app
        let previous = CoreLog.shared.sink
        CoreLog.shared.sink = { line in
            previous(line)
            let stamped = "\(Date().formatted(.iso8601.time(includingFractionalSeconds: true))) \(line)"
            linesLock.withLock {
                lines.append(stamped)
                if lines.count > 200 { lines.removeFirst(lines.count - 200) }
            }
        }
        let folder = AppFolder.support
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let path = folder.appendingPathComponent("harness.sock").path
        Thread.detachNewThread { serve(path) }
        log.notice("harness listening on \(path, privacy: .public)")
    }

    // MARK: socket

    private nonisolated static func serve(_ path: String) {
        unlink(path)
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { return }
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let bytes = Array(path.utf8.prefix(MemoryLayout.size(ofValue: addr.sun_path) - 1))
        withUnsafeMutableBytes(of: &addr.sun_path) { raw in
            for (i, b) in bytes.enumerated() { raw[i] = b }
        }
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
        }
        guard bound == 0, listen(fd, 8) == 0 else { close(fd); return }
        chmod(path, 0o600)
        while true {
            let client = accept(fd, nil, nil)
            if client < 0 { continue }
            let request = readLine(client)
            let semaphore = DispatchSemaphore(value: 0)
            var reply = "{}"
            Task { @MainActor in
                reply = handle(request)
                semaphore.signal()
            }
            semaphore.wait()
            let out = Array((reply + "\n").utf8)
            _ = out.withUnsafeBytes { write(client, $0.baseAddress, out.count) }
            close(client)
        }
    }

    private nonisolated static func readLine(_ fd: Int32) -> String {
        var data = [UInt8]()
        var byte: UInt8 = 0
        while read(fd, &byte, 1) == 1, byte != UInt8(ascii: "\n") { data.append(byte) }
        return String(decoding: data, as: UTF8.self)
    }

    // MARK: commands

    private static func handle(_ request: String) -> String {
        guard let model,
              let json = try? JSONSerialization.jsonObject(with: Data(request.utf8)) as? [String: Any],
              let cmd = json["cmd"] as? String else { return reply(["error": "send one JSON object with a \"cmd\""]) }
        func seatArg() -> Int64? { (json["seat"] as? NSNumber)?.int64Value }

        switch cmd {
        case "state":
            return reply([
                "phase": "\(model.phase)",
                "section": model.section.rawValue,
                "selectedSeat": model.selectedSeat as Any,
                "profiles": model.profiles.map { ["id": $0.id, "name": $0.name, "revision": $0.revision, "status": $0.status] },
                "boardFleet": model.boardFleet as Any,
                "socketConnected": model.socketConnected,
                "seats": model.seats.count,
                "needsYou": model.needsYou.values.map { ["seat": $0.sessionId, "title": $0.seatTitle, "summary": $0.summary] },
                "fleets": model.fleets.map { f in ["fleet": f.fleet, "runners": f.runners.map { ["slot": $0.slot, "status": $0.status, "seat": $0.seat as Any] }, "seats": f.seats.map(\.title)] },
                "notificationsAllowed": model.notifier.allowed as Any,
                "queued": model.queued.count,
            ])
        case "windows":
            // onscreen: true first moves a window left on a display that is gone back onto the main screen.
            if json["onscreen"] as? Bool == true, let screen = NSScreen.main?.visibleFrame {
                for w in NSApp.windows where w.isVisible && w.level == .normal && !screen.intersects(w.frame) {
                    w.setFrameOrigin(NSPoint(x: screen.minX, y: screen.maxY - w.frame.height))
                }
            }
            return reply(["windows": NSApp.windows.filter { $0.isVisible && $0.level == .normal }.map { ["id": $0.windowNumber, "title": $0.title, "key": $0.isKeyWindow, "frame": NSStringFromRect($0.frame), "occluded": !$0.occlusionState.contains(.visible), "inWindowsMenu": !$0.isExcludedFromWindowsMenu, "class": String(describing: type(of: $0)), "tabbing": $0.tabbingIdentifier] }])
        case "section":
            guard let name = json["name"] as? String, let section = AppModel.Section.allCases.first(where: { $0.rawValue.lowercased() == name.lowercased() }) else {
                return reply(["error": "name: " + AppModel.Section.allCases.map { $0.rawValue.lowercased() }.joined(separator: ", ")])
            }
            model.section = section
            return reply(["ok": true])
        case "select":
            guard let seat = seatArg() else { return reply(["error": "seat: an id"]) }
            model.open(seatId: seat)
            return reply(["ok": true])
        case "profile":
            if json["new"] as? Bool == true {
                model.section = .profiles
                model.selectedProfile = nil
                model.creatingProfile = true
                return reply(["ok": true])
            }
            guard let id = (json["id"] as? NSNumber)?.int64Value else { return reply(["error": "id: a profile id, or new: true"]) }
            model.openProfile(id)
            return reply(["ok": true])
        case "adopt":
            guard let id = seatArg(), let seat = model.seats.first(where: { $0.id == id }) else { return reply(["error": "seat: a running seat's id"]) }
            model.harnessAdopt = seat
            return reply(["ok": true])
        case "board":
            guard let fleet = json["fleet"] as? String else { return reply(["error": "fleet: a name"]) }
            model.section = .seats
            model.openBoard(fleet)
            return reply(["ok": true])
        case "window":
            guard let seat = seatArg(), let open = model.openSeatWindow else { return reply(["error": "seat: an id (and a window open to open it from)"]) }
            open(seat)
            return reply(["ok": true])
        case "send":
            guard let seat = seatArg(), let text = json["text"] as? String, !text.isEmpty else { return reply(["error": "seat and text"]) }
            // The app's own send: into Claude's queue, or with now: true, interrupting the running turn.
            let now = json["now"] as? Bool == true
            let session = model.client.seat(seatId: seat)
            sending.append(session)
            Task {
                try? await session.refreshSeat()
                if now { session.sendNow(text: text) } else { session.send(text: text) }
                try? await Task.sleep(for: .seconds(30))
                session.close()
                sending.removeAll { $0 === session }
            }
            return reply(["ok": true, "note": now ? "sent now: it interrupts a running turn" : "sent: it runs when the current turn ends"])
        case "reveal":
            guard let text = json["text"] as? String, !text.isEmpty else { return reply(["error": "text: what the message contains"]) }
            NotificationCenter.default.post(name: reveal, object: text)
            return reply(["ok": true])
        case "preview":
            guard let text = json["text"] as? String, !text.isEmpty else { return reply(["error": "text: the Markdown to draw"]) }
            let paths = (json["images"] as? [String]) ?? (json["image"] as? String).map { [$0] } ?? []
            let images = paths.map { TranscriptImage(mediaType: "image/png", base64: nil, url: $0, size: nil) }
            let content = HarnessPreview(text: text, images: images).imageViewer().environment(model)
            let window = previewWindow ?? NSWindow(contentRect: NSRect(x: 0, y: 0, width: 720, height: 640),
                                                   styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false
            window.title = "Markdown preview"
            window.contentView = NSHostingView(rootView: content)
            window.orderFront(nil)
            previewWindow = window
            return reply(["ok": true, "window": window.windowNumber])
        case "usage":
            // The Pool view's usage charts, drawn from a saved usage_history response (path), never the server.
            guard let path = json["path"] as? String, let text = try? String(contentsOfFile: path, encoding: .utf8) else {
                return reply(["error": "path: a usage_history response saved as JSON"])
            }
            let history = UsageHistory.companion.parse(text: text)
            let window = previewWindow ?? NSWindow(contentRect: NSRect(x: 0, y: 0, width: 900, height: 820),
                                                   styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false
            window.title = "Usage preview"
            window.contentView = NSHostingView(rootView: ScrollView {
                UsageCharts(preset: history).padding(20).environment(model)
            }.frame(width: 900, height: 820))
            if let appearance = json["appearance"] as? String {
                window.appearance = NSAppearance(named: appearance == "dark" ? .darkAqua : .aqua)
            }
            window.orderFront(nil)
            previewWindow = window
            return reply(["ok": true, "window": window.windowNumber])
        case "agents":
            // The agent switcher with sample agents (one per status), as a seat with a fork and subagents shows it.
            let window = previewWindow ?? NSWindow(contentRect: NSRect(x: 0, y: 0, width: 720, height: 640),
                                                   styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false
            window.title = "Agents preview"
            window.contentView = NSHostingView(rootView: HarnessAgents())
            window.orderFront(nil)
            previewWindow = window
            return reply(["ok": true, "window": window.windowNumber])
        case "seatagents":
            // A seat's agents as the app's own feed sees them: the first call joins it in the background, a later one
            // reports what its replay held. close: true leaves it.
            guard let seat = seatArg() else { return reply(["error": "seat: an id"]) }
            if json["close"] as? Bool == true {
                inspecting.removeValue(forKey: seat)?.close()
                return reply(["ok": true])
            }
            guard let session = inspecting[seat] else {
                let session = model.client.seat(seatId: seat)
                // Polled as an open seat screen polls it, so "working" follows the seat.
                session.setWatched(on: true)
                inspecting[seat] = session
                return reply(["joining": true])
            }
            let s = session.ui.value
            // find: which of main's items mention a text (an agent's description, say), by kind.
            let found = (json["find"] as? String).map { needle in
                s.items.compactMap { item -> String? in
                    guard let text = clipboardText(item: item), text.localizedCaseInsensitiveContains(needle) else { return nil }
                    return "\(type(of: item)): " + String(text.prefix(120))
                }
            } ?? []
            return reply([
                "found": found,
                "joined": s.joined,
                "mainItems": s.items.count,
                "working": s.live?.idle?.boolValue == false,
                // The Outbox as OutboxPanel draws it: Claude's queue, sent, held.
                "outbox": s.outbox.map { r in
                    ["kind": "\(r.kind)", "text": String(r.text.prefix(80)), "label": r.label, "cancellable": r.cancellable,
                     "interrupting": r.interrupting] as [String: Any]
                },
                "backgroundRunning": s.seat?.backgroundRunning ?? 0,
                "background": s.backgroundTasks.map { t in
                    ["id": t.id, "type": t.type, "status": t.status, "label": t.label, "command": t.command ?? "",
                     "elapsed": t.startedAt.map { elapsedLabel(fromMs: $0.int64Value, nowMs: Int64(Date().timeIntervalSince1970 * 1000)) } ?? ""] as [String: Any]
                },
                "agents": s.agents.map { a in
                    ["id": a.id, "type": a.type, "label": a.label, "status": a.status, "fork": a.fork,
                     "items": s.agentThreads[a.id]?.items.count ?? 0] as [String: Any]
                },
            ])
        case "channel":
            guard let slug = json["slug"] as? String else { return reply(["error": "slug: a forum channel"]) }
            model.section = .forum
            model.harnessChannel = slug
            return reply(["ok": true])
        case "unfold":
            // Background-task bars open unfolded from now on (for screenshots).
            model.backgroundBarsOpen = json["open"] as? Bool ?? true
            return reply(["ok": true])
        case "present":
            // Ready a window for a screenshot (screencapture -l <window>): its size, its appearance ("light", "dark",
            // or "system"), and in front and active so nothing draws dimmed. Returns its window number.
            let window = (json["window"] as? NSNumber).flatMap { n in NSApp.windows.first { $0.windowNumber == n.intValue } }
                ?? NSApp.windows.first { $0.isVisible && $0.level == .normal && $0.title != "Agents preview" }
            guard let window else { return reply(["error": "no window"]) }
            switch json["appearance"] as? String {
            case "light": window.appearance = NSAppearance(named: .aqua)
            case "dark": window.appearance = NSAppearance(named: .darkAqua)
            case "system": window.appearance = nil
            default: break
            }
            if let w = (json["width"] as? NSNumber)?.doubleValue, let h = (json["height"] as? NSNumber)?.doubleValue {
                window.setContentSize(NSSize(width: w, height: h))
            }
            NSApp.activate()
            window.makeKeyAndOrderFront(nil)
            return reply(["ok": true, "window": window.windowNumber])
        case "restarts":
            // Settings → Restarts (auto-restart, global and per fleet) in a window of its own; it reads and writes
            // the server's real setting, so only look unless a change is meant.
            let window = previewWindow ?? NSWindow(contentRect: NSRect(x: 0, y: 0, width: 640, height: 520),
                                                   styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false
            window.title = "Restarts preview"
            window.contentView = NSHostingView(rootView: AutoRestartSettings().frame(width: 640, height: 520).environment(model))
            window.orderFront(nil)
            previewWindow = window
            return reply(["ok": true, "window": window.windowNumber])
        case "autorestart":
            // The global auto-restart's idle minutes, set as the Restarts stepper sets them (never its switch: the
            // operator turns auto-restart on). The server's answer lands in "log".
            guard let minutes = (json["minutes"] as? NSNumber)?.intValue else { return reply(["error": "minutes: whole minutes, 1 to 1440"]) }
            Task {
                do {
                    let a = try await model.client.api.setAutoRestart(enabled: nil, minIdleS: KotlinInt(value: AutoRestartWording.seconds(minutes: minutes)))
                    note("autorestart: enabled \(a.enabled), min_idle_s \(a.minIdleS)")
                } catch {
                    note("autorestart: not changed: \(error.message)")
                }
            }
            return reply(["ok": true, "note": "sent; the server's answer is in log"])
        case "snapshot":
            // A window drawn into a PNG at path, by the app itself: works where screencapture cannot (a locked screen,
            // a window on another Space). window: a number from "windows"; the frontmost normal window when left out.
            guard let path = json["path"] as? String else { return reply(["error": "path: where to write the PNG"]) }
            let window = (json["window"] as? NSNumber).flatMap { n in NSApp.windows.first { $0.windowNumber == n.intValue } }
                ?? NSApp.windows.first { $0.isVisible && $0.level == .normal }
            guard let view = window?.contentView, let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else {
                return reply(["error": "no window"])
            }
            view.cacheDisplay(in: view.bounds, to: rep)
            guard let png = rep.representation(using: .png, properties: [:]), (try? png.write(to: URL(fileURLWithPath: path))) != nil else {
                return reply(["error": "not written: \(path)"])
            }
            return reply(["ok": true, "path": path, "width": rep.pixelsWide, "height": rep.pixelsHigh])
        case "restartsheet":
            // Restart idle seats, for one machine (host) or every machine; close: true closes it. The sheet only plans
            // (a dry run) until someone confirms in it.
            if json["close"] as? Bool == true {
                model.restartScope = nil
                return reply(["ok": true])
            }
            model.section = .seats
            model.restartScope = RestartScope(host: json["host"] as? String)
            return reply(["ok": true])
        case "notify":
            let content = UNMutableNotificationContent()
            content.title = json["title"] as? String ?? "Claudiverse harness"
            content.body = json["body"] as? String ?? "A test notification from the harness."
            content.sound = Notifier.sound
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "harness-\(UUID())", content: content, trigger: nil))
            return reply(["ok": true])
        case "log":
            return reply(["lines": linesLock.withLock { lines }])
        case "probe":
            // What a send does after delivery: a heartbeat the server must answer in time, or the socket is torn down.
            model.client.socket.checkAlive()
            return reply(["ok": true])
        case "quit":
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { NSApp.terminate(nil) }
            return reply(["ok": true])
        default:
            return reply(["error": "unknown cmd \(cmd)"])
        }
    }

    private static func reply(_ object: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else { return "{\"error\":\"unencodable reply\"}" }
        return String(decoding: data, as: UTF8.self)
    }
}
/// The "preview" window's content: Markdown as both bubbles, and stored images in the operator's, opening the window's viewer.
private struct HarnessAgents: View {
    @State private var selection: String?
    private let agents = [
        SeatAgent(id: "a1", type: "fork", description: "Screenshotting Storm Spire viewer page", name: nil, fork: true, background: false, status: "running", toolUseId: nil, parentAgentId: nil),
        SeatAgent(id: "a2", type: "Explore", description: "Find where tiles are laid out", name: nil, fork: false, background: false, status: "completed", toolUseId: "toolu_1", parentAgentId: nil),
        SeatAgent(id: "a3", type: "general-purpose", description: "", name: "reviewer", fork: false, background: true, status: "waiting", toolUseId: nil, parentAgentId: nil),
        SeatAgent(id: "a4", type: "Explore", description: "Search the logs", name: nil, fork: false, background: false, status: "failed", toolUseId: nil, parentAgentId: nil),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            AgentSwitcher(agents: agents, selection: $selection)
            Text("Selected: \(selection ?? "main")").padding(.horizontal, 12)
            AgentThreadLink(toolUseId: "toolu_1")
                .environment(\.agentsByToolUse, ["toolu_1": agents[1]])
                .environment(\.openAgentThread) { selection = $0 }
            Spacer()
        }
        .frame(width: 720, height: 240)
    }
}

private struct HarnessPreview: View {
    let text: String
    let images: [TranscriptImage]
    @Environment(ImageViewerModel.self) private var viewer

    var body: some View {
        let items = images.map { ImageViewerModel.Item(key: $0.url ?? "", image: $0, caption: "You · preview") }
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                ChatBubble(fromClaude: true) { MarkdownText(text, fill: false) }
                ChatBubble(fromClaude: false) {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(Array(images.enumerated()), id: \.offset) { i, image in TranscriptImageView(image: image, id: "preview#\(i)") }
                        MarkdownText(text, fill: false)
                    }
                }
                // As a transcript row wraps it: the row's click must not take the image's.
                .contentShape(Rectangle())
                .onTapGesture { log.notice("harness preview: the row took the click") }
            }
            .padding(16)
        }
        .environment(\.showImage) { key in viewer.open(items, at: key) }
    }
}
#endif
