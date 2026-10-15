import ClaudiverseCore
import SwiftUI
import UniformTypeIdentifiers

/// One seat's live state, from core's SeatSession, for as long as its screen shows.
@MainActor
@Observable
final class SeatModel {
    let session: SeatSession
    private(set) var state: SeatState
    private var watching: Task<Void, Never>?
    private var pending: SeatState?
    private var flush: Task<Void, Never>?
    private var lastDraw = Date.distantPast

    /// Streaming text is redrawn at most this often; anything else shows at once.
    private static let streamInterval: TimeInterval = 0.15

    init(client: ClaudiverseClient, seatId: Int64) {
        session = client.seat(seatId: seatId)
        state = session.ui.value
        watching = Task { [weak self, session] in
            for await s in session.ui { self?.receive(s) }
        }
    }

    /**
     * A busy seat sends a state per streamed fragment, many a second. Only
     * streaming text changes in most of them; those are coalesced, and the
     * last one is always drawn.
     */
    private func receive(_ s: SeatState) {
        let onlyStreaming = s.transcriptVersion == state.transcriptVersion && !s.streaming.isEmpty
        if !onlyStreaming || Date().timeIntervalSince(lastDraw) >= Self.streamInterval {
            apply(s)
            return
        }
        pending = s
        guard flush == nil else { return }
        flush = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.streamInterval))
            guard let self, !Task.isCancelled else { return }
            self.flush = nil
            if let p = self.pending { self.apply(p) }
        }
    }

    private func apply(_ s: SeatState) {
        pending = nil
        state = s
        lastDraw = Date()
    }

    func close() {
        watching?.cancel()
        flush?.cancel()
        session.close()
    }
}

struct SeatDetailView: View {
    let seatId: Int64
    /// The main window's view: its seat stays loaded after switching away (AppModel.recentSeat), so
    /// switching back needs no fresh history from the server. A seat window owns its own.
    var keepLoaded = false
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var phase
    @State private var model: SeatModel?
    @State private var showInfo = false
    @State private var terminalError: String?
    /// The agent whose thread shows in place of main's; nil for main.
    @State private var agentId: String?
    /// The shown thread as plain text, while Show as Text is open.
    @State private var asText: ConversationText?
    @State private var restarting = false

    var body: some View {
        Group {
            if let model {
                content(model)
            } else {
                ProgressView()
            }
        }
        .onAppear {
            let m = keepLoaded ? app.recentSeat(seatId) : SeatModel(client: app.client, seatId: seatId)
            model = m
            m.session.setWatched(on: phase == .active)
        }
        // Watched only while its window is in front in the active app: that is what answers the seat's
        // request for the operator. Minimized, covered or in another app, the request and its notification stay.
        .onChange(of: phase) { _, now in model?.session.setWatched(on: now == .active) }
        .onDisappear {
            model?.session.setWatched(on: false)
            if !keepLoaded { model?.close() }
        }
    }

    @ViewBuilder
    private func content(_ model: SeatModel) -> some View {
        let s = model.state
        let agent = agentId.flatMap { id in s.agents.first { $0.id == id } }
        VStack(spacing: 0) {
            if !s.agents.isEmpty { AgentSwitcher(agents: s.agents, selection: $agentId) }
            let background = ClaudiverseCore.BackgroundTask.companion.shown(tasks: s.backgroundTasks)
            if !background.isEmpty { BackgroundTasksBar(tasks: background, startOpen: app.backgroundBarsOpen) }
            Group {
                if let agent {
                    TranscriptView(rows: s.agentThreads[agent.id]?.rows ?? [], version: s.transcriptVersion, streaming: "", session: model.session)
                } else {
                    TranscriptView(rows: s.rows, version: s.transcriptVersion, streaming: s.streaming, session: model.session)
                }
            }
            // Each thread keeps its own place: a fresh transcript view per thread.
            .id(agent?.id ?? "main")
            // A fork's thread starts with the call that spawned it: no link from a thread to itself.
            .environment(\.agentsByToolUse, Dictionary(s.agents.filter { $0.id != agent?.id }.compactMap { a in a.toolUseId.map { ($0, a) } },
                                                        uniquingKeysWith: { a, _ in a }))
            .environment(\.openAgentThread) { agentId = $0 }
            if let agent {
                Text("\(agent.label): its own thread. A message here goes to main; sending to an agent comes later.")
                    .font(.caption).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12).padding(.vertical, 4)
            }
            if let auto = RestartWording.latestAuto(app.restartEvents, seat: seatId) {
                Text(RestartWording.autoOutcome(auto))
                    .font(.caption).foregroundStyle(auto.outcome == "failed" ? Theme.bad : .secondary)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12).padding(.vertical, 4)
            }
            if !s.outbox.isEmpty { OutboxPanel(rows: s.outbox, session: model.session) }
            if let notice = s.notice {
                HStack {
                    Text(notice).font(.callout)
                    Spacer()
                    Button { model.session.dismissNotice() } label: { Image(systemName: "xmark") }.buttonStyle(.borderless)
                }
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(.thinMaterial)
            }
            Composer(seatId: seatId, state: s, session: model.session)
        }
        .navigationTitle(s.seat?.label ?? "Seat \(seatId)")
        .navigationSubtitle(subtitle(s))
        .toolbar { toolbar(model) }
        .inspector(isPresented: $showInfo) {
            SessionInfoView(state: s, session: model.session).inspectorColumnWidth(min: 260, ideal: 300, max: 420)
        }
        .sheet(item: $asText) { ConversationTextSheet(conversation: $0) }
        .sheet(isPresented: $restarting) { if let seat = s.seat { RestartSeatSheet(seat: seat).environment(app) } }
        .alert("Open terminal", isPresented: Binding(get: { terminalError != nil }, set: { if !$0 { terminalError = nil } })) {
            Button("OK") { terminalError = nil }
        } message: {
            Text(terminalError ?? "")
        }
    }

    private func subtitle(_ s: SeatState) -> String {
        var parts: [String] = []
        if let seat = s.seat {
            parts.append(seat.status == "running" ? (s.live?.idle?.boolValue == false ? "busy" : (seat.activity ?? "running")) : seat.status)
            if let host = seat.host { parts.append(host) }
            if let m = seat.model { parts.append(m) }
            if let e = s.effort { parts.append("effort \(e)") }
        }
        if !s.socketConnected { parts.append("offline") } else if !s.joined { parts.append("joining…") }
        if let error = s.joinError ?? s.loadError { parts.append(error) }
        return parts.joined(separator: " · ")
    }

    @ToolbarContentBuilder
    private func toolbar(_ model: SeatModel) -> some ToolbarContent {
        let s = model.state
        ToolbarItemGroup {
            if let seat = s.seat, seat.canResume {
                Button { model.session.resume() } label: { Label("Resume", systemImage: "play.fill") }.disabled(s.acting)
            } else {
                Button { _ = model.session.interrupt() } label: { Label("Interrupt", systemImage: "stop.circle") }
                    .help("Interrupt the current turn")
            }
            if let seat = s.seat, seat.status == "running", seat.host != nil {
                Button { openTerminal(seat) } label: { Label("Open terminal", systemImage: "terminal") }
                    .help("Attach to the seat's tmux session in iTerm")
            }
            SessionControlsMenu(state: s, session: model.session)
            Button {
                let items = agentId.flatMap { s.agentThreads[$0]?.items } ?? s.items
                let title = agentId.flatMap { id in s.agents.first { $0.id == id }?.label } ?? s.seat?.label ?? "Seat \(seatId)"
                asText = ConversationText(title: title, text: conversationText(items: items))
            } label: { Label("Show as Text", systemImage: "doc.plaintext") }
                .help("The conversation as one text, to select and copy across messages")
            if let seat = s.seat, seat.status == "running" {
                Button { restarting = true } label: { Label("Restart", systemImage: "arrow.triangle.2.circlepath") }
                    .help("Restart this seat onto its machine's current Claude Code, on the same conversation")
            }
            Button { showInfo.toggle() } label: { Label("Info", systemImage: "info.circle") }
        }
    }

    private func openTerminal(_ seat: Seat) {
        Task {
            do {
                try await TerminalOpener.open(seat)
            } catch {
                terminalError = error.localizedDescription
            }
        }
    }
}

/**
 * The message box: Return sends, Shift-Return starts a new line. Images and
 * files, pasted or picked with the paperclip, wait above the box and go with
 * the next send, in one message with its text. What is typed or attached is
 * the seat's draft (see Drafts): it stays until sent or cleared.
 */
struct Composer: View {
    let seatId: Int64
    let state: SeatState
    let session: SeatSession
    @Environment(AppModel.self) private var app
    @State private var height: CGFloat = 16
    @State private var picking = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !attachments.isEmpty { AttachmentStrip(files: attachmentsBinding) }
            HStack(alignment: .bottom, spacing: 8) {
                Button { picking = true } label: { Image(systemName: "paperclip") }
                    .buttonStyle(.borderless).help("Attach files (or paste them)").disabled(state.uploading)
                ComposerField(text: draft, height: $height, onSend: { send(now: false) }, onSendNow: { send(now: true) },
                              onPasteFiles: { addAttachments($0) })
                    .frame(height: height)
                    .overlay(alignment: .topLeading) {
                        if draft.wrappedValue.isEmpty {
                            Text(placeholder).foregroundStyle(Color(nsColor: .placeholderTextColor)).allowsHitTesting(false)
                        }
                    }
                if state.sending || state.uploading {
                    ProgressView().controlSize(.small)
                } else {
                    if isWorking {
                        Button { send(now: true) } label: { Image(systemName: "bolt.circle.fill").font(.title2) }
                            .buttonStyle(.borderless).disabled(!canSend || !attachments.isEmpty)
                            .help(attachments.isEmpty ? "Send now (⌘⇧Return): stop the running turn and start on this"
                                                      : "Send now takes text only; files go with Send")
                    }
                    Button { send(now: false) } label: { Image(systemName: "arrow.up.circle.fill").font(.title2) }
                        .buttonStyle(.borderless).disabled(!canSend)
                        .help(isWorking ? "Send (Return): runs when the current turn ends" : "Send (Return)")
                }
            }
        }
        .padding(10)
        .background(.bar)
        .fileImporter(isPresented: $picking, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { addAttachments(urls) }
        }
    }

    private var draft: Binding<String> {
        Binding(get: { app.drafts.text(seatId) }, set: { app.drafts.setText($0, for: seatId) })
    }

    private var attachments: [URL] { app.drafts.attachments(seatId) }

    private var attachmentsBinding: Binding<[URL]> {
        Binding(get: { app.drafts.attachments(seatId) }, set: { app.drafts.setAttachments($0, for: seatId) })
    }

    private func addAttachments(_ urls: [URL]) {
        app.drafts.setAttachments(attachments + urls, for: seatId)
    }

    private var text: String { draft.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canSend: Bool { !text.isEmpty || !attachments.isEmpty }

    /// Mid-turn: Send waits for the turn to end; Send now interrupts it.
    private var isWorking: Bool { state.live?.idle?.boolValue == false }

    private var placeholder: String {
        isWorking ? "Message (runs when this turn ends; ⌘⇧Return to interrupt)" : "Message"
    }

    private func send(now: Bool) {
        guard canSend, !state.uploading else { return }
        // Send now carries text only: files go with a plain send.
        if now && !attachments.isEmpty { return }
        if attachments.isEmpty {
            if now { session.sendNow(text: text) } else { session.send(text: text) }
        } else {
            let files = attachments.map { fileUpload(path: $0.path, contentType: UTType(filenameExtension: $0.pathExtension)?.preferredMIMEType) }
            session.sendFiles(files: files, note: text)
        }
        app.drafts.clear(seatId)
    }
}

/// Files waiting to go with the next message, each removable.
private struct AttachmentStrip: View {
    @Binding var files: [URL]

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 6) {
                ForEach(Array(files.enumerated()), id: \.offset) { index, url in
                    HStack(spacing: 6) {
                        Image(nsImage: Self.thumbnail(url)).resizable().aspectRatio(contentMode: .fill)
                            .frame(width: 32, height: 32).clipShape(RoundedRectangle(cornerRadius: 4))
                        Text(url.lastPathComponent).font(.caption).lineLimit(1).truncationMode(.middle).frame(maxWidth: 180, alignment: .leading)
                        Button { files.remove(at: index) } label: { Image(systemName: "xmark.circle.fill") }
                            .buttonStyle(.borderless).foregroundStyle(.secondary).help("Remove")
                    }
                    .padding(4).padding(.trailing, 2)
                    .background(Theme.codeBackground, in: RoundedRectangle(cornerRadius: 6))
                }
            }
        }
        .scrollIndicators(.never)
    }

    private static func thumbnail(_ url: URL) -> NSImage {
        if UTType(filenameExtension: url.pathExtension)?.conforms(to: .image) == true, let image = NSImage(contentsOf: url) { return image }
        return NSWorkspace.shared.icon(forFile: url.path)
    }
}

/// This seat's messages waiting for it to go idle.
/**
 * The seat's Outbox: what Claude will run next, in run order (core's
 * outbox). Claude's own queue first ("in Claude's queue", or "interrupting…"
 * while a Send now stops the turn), which cannot be taken back; then
 * messages sent but not yet listed by the seat; then messages held in this
 * app (the seat offline, or a send that failed), which can be sent now or
 * cancelled.
 */
struct OutboxPanel: View {
    let rows: [OutboxRow]
    let session: SeatSession

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(rows, id: \.key) { row in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Image(systemName: Self.icon(row)).foregroundStyle(Self.color(row)).frame(width: 14)
                    Text(row.text).lineLimit(2).font(.callout)
                    Spacer(minLength: 8)
                    Text(row.label).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    if let id = row.heldId?.int64Value {
                        // Send: into Claude's queue (one held as Send now still interrupts); Send Now: interrupts the turn.
                        Button("Send") { session.forceSend(id: id, interrupt: false) }
                            .controlSize(.small).help("Send it now, to run when the current turn ends")
                        Button("Send Now") { session.forceSend(id: id, interrupt: true) }
                            .controlSize(.small).help("Send it now, interrupting the running turn")
                        Button { session.unqueue(id: id) } label: { Image(systemName: "trash") }
                            .buttonStyle(.borderless).help("Cancel it")
                    }
                }
                .help(row.cancellable ? "" : "Claude Code cannot take back a message in its queue: it runs when its turn comes")
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Theme.busy.opacity(0.12))
    }

    static func icon(_ row: OutboxRow) -> String {
        if row.interrupting { return "bolt.fill" }
        switch row.kind {
        case .queued: return "text.line.first.and.arrowtriangle.forward"
        case .sent: return "paperplane"
        default: return "clock"
        }
    }

    static func color(_ row: OutboxRow) -> Color {
        if row.interrupting { return Theme.attention }
        return row.kind == .held ? Theme.busy : .secondary
    }
}

/**
 * Main and the seat's agents, as Claude Code's own agent switcher lists them:
 * the selected one's thread shows. Each agent with its type, what it was asked
 * to do, and a dot for running, waiting, idle or ended.
 */
struct AgentSwitcher: View {
    let agents: [SeatAgent]
    @Binding var selection: String?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                chip(id: nil, dot: Theme.good, badge: nil, label: "main")
                ForEach(agents, id: \.id) { a in
                    chip(id: a.id, dot: Self.color(a), badge: a.fork ? "fork" : a.type, label: a.label)
                        .help("\(a.type) · \(a.status)\(a.background ? " · background" : "")")
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
        }
        .background(.bar)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Agents")
    }

    private func chip(id: String?, dot: Color, badge: String?, label: String) -> some View {
        let selected = selection == id
        return Button { selection = id } label: {
            HStack(spacing: 5) {
                Circle().fill(dot).frame(width: 7, height: 7)
                if let badge { Text(badge).font(.caption2.weight(.semibold)).foregroundStyle(Theme.accent) }
                Text(label).lineLimit(1).truncationMode(.tail).frame(maxWidth: 260, alignment: .leading)
            }
            .font(.callout)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(selected ? Theme.accent.opacity(0.18) : .clear, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    static func color(_ a: SeatAgent) -> Color {
        if a.isBusy { return Theme.attention }
        switch a.status {
        case "failed", "killed": return Theme.bad
        case "completed": return .secondary
        default: return Theme.good
        }
    }
}

/// A thread as plain text, for Show as Text.
struct ConversationText: Identifiable {
    let id = UUID()
    let title: String
    let text: String
}

/// The conversation as one document: select any part of it, across messages, and copy it.
struct ConversationTextSheet: View {
    let conversation: ConversationText
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(conversation.title).font(.headline).lineLimit(1)
                Spacer()
                Button("Copy All") { TranscriptRows.copy(conversation.text) }
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(12)
            Divider()
            SelectableText(text: conversation.text)
        }
        .frame(minWidth: 560, idealWidth: 760, minHeight: 400, idealHeight: 700)
    }
}

/// Read-only text that selects like a document: an NSTextView, which SwiftUI's Text is not.
struct SelectableText: NSViewRepresentable {
    let text: String

    func makeNSView(context: Context) -> NSScrollView {
        let scroll = NSTextView.scrollableTextView()
        let view = scroll.documentView as! NSTextView
        view.isEditable = false
        view.isSelectable = true
        view.drawsBackground = false
        view.font = .systemFont(ofSize: NSFont.systemFontSize)
        view.textColor = .labelColor
        view.textContainerInset = NSSize(width: 12, height: 12)
        view.string = text
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        let view = scroll.documentView as! NSTextView
        if view.string != text { view.string = text }
    }
}

/**
 * What the seat runs in the background (shells, monitors, workflows), as
 * Claude Code's own task list has it: folded to a count, unfolded one line
 * each with how long it has run. Subagents are left to the agent switcher.
 */
struct BackgroundTasksBar: View {
    let tasks: [ClaudiverseCore.BackgroundTask]
    @State private var open: Bool

    init(tasks: [ClaudiverseCore.BackgroundTask], startOpen: Bool = false) {
        self.tasks = tasks
        _open = State(initialValue: startOpen)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button { open.toggle() } label: {
                HStack(spacing: 6) {
                    Image(systemName: open ? "chevron.down" : "chevron.right").frame(width: 10)
                    Image(systemName: "gearshape.2").foregroundStyle(Theme.busy)
                    Text(Self.summary(tasks))
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .font(.callout)
            if open {
                // Elapsed times tick on their own; nothing else redraws for it.
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(tasks, id: \.id) { task in row(task, now: context.date) }
                    }
                }
                .padding(.leading, 16)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(.bar)
    }

    private func row(_ task: ClaudiverseCore.BackgroundTask, now: Date) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: Self.icon(task.type)).foregroundStyle(.secondary).frame(width: 14)
            VStack(alignment: .leading, spacing: 1) {
                Text(task.label).lineLimit(1)
                if let command = task.command, command != task.label {
                    Text(command).font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle)
                        .help(command)
                        .textSelection(.enabled)
                }
            }
            Spacer(minLength: 8)
            Text(task.isRunning ? (task.startedAt.map { elapsedLabel(fromMs: $0.int64Value, nowMs: Int64(now.timeIntervalSince1970 * 1000)) } ?? task.status) : task.status)
                .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
        }
        .font(.callout)
        .contextMenu {
            if let command = task.command { Button("Copy Command") { TranscriptRows.copy(command) } }
        }
    }

    /// "2 shells running", "1 shell, 1 monitor running".
    static func summary(_ tasks: [ClaudiverseCore.BackgroundTask]) -> String {
        var counts: [(String, Int)] = []
        for t in tasks {
            if let i = counts.firstIndex(where: { $0.0 == t.type }) { counts[i].1 += 1 } else { counts.append((t.type, 1)) }
        }
        let parts = counts.map { type, n in "\(n) \(n == 1 ? type : type + "s")" }
        return parts.joined(separator: ", ") + (tasks.contains { $0.isRunning } ? " running" : "")
    }

    static func icon(_ type: String) -> String {
        switch type {
        case "shell": "terminal"
        case "monitor": "waveform.path.ecg"
        case "workflow": "flowchart"
        default: "gearshape"
        }
    }
}
