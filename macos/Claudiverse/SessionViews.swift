import AppKit
import ClaudiverseCore
import SwiftUI

/**
 * A seat's model, effort and permission mode. The marks show what the seat
 * REPORTS running, not what was last picked, so a change made at its terminal
 * shows here too. Changes go to the seat as commands and queue while it is busy.
 */
struct SessionControlsMenu: View {
    let state: SeatState
    let session: SeatSession
    @Environment(AppModel.self) private var app

    /// Only models the seat's Claude Code knows (Haiku 5.5 needs 2.1.293, say).
    private func known(_ models: [ModelChoice]) -> [ModelChoice] { models.filter { $0.knownBy(version: state.seat?.clientVersion) } }

    var body: some View {
        Menu {
            Section("Model: \(state.seat?.model ?? "unknown")") {
                ForEach(known(app.modelCatalog.models), id: \.id) { modelItem($0) }
                Menu("Older models") {
                    ForEach(known(app.modelCatalog.olderModels), id: \.id) { modelItem($0) }
                }
                ForEach(app.modelCatalog.aliases, id: \.id) { alias in
                    Button(alias.label) { session.setModel(model: alias.id) }
                }
            }
            Section("Effort: \(state.effort ?? "unknown until the next reply")") {
                ForEach(SessionChoices.shared.efforts, id: \.self) { level in
                    Button { session.setEffort(level: level) } label: { mark(level, level == state.effort) }
                }
            }
            Section("Permission mode: \(state.live?.permissionMode ?? "not reported")") {
                ForEach(SessionChoices.shared.modes, id: \.id) { mode in
                    Button { session.setPermissionMode(mode: mode.id) } label: {
                        mark(mode.label, mode.id == state.live?.permissionMode)
                    }
                }
            }
        } label: {
            Label("Session", systemImage: "slider.horizontal.3")
        }
        .help("Model, effort and permission mode")
    }

    @ViewBuilder
    private func modelItem(_ m: ModelChoice) -> some View {
        let running = SessionChoices.shared.isRunning(model: m, running: state.seat?.model)
        if m.oneM {
            Menu {
                Button("Standard") { session.setModel(model: m.id) }
                Button("1M context") { session.setModel(model: m.id + "[1m]") }
            } label: { mark(m.label, running) }
        } else {
            Button { session.setModel(model: m.id) } label: { mark(m.label, running) }
        }
    }

    @ViewBuilder
    private func mark(_ label: String, _ on: Bool) -> some View {
        if on { Label(label, systemImage: "checkmark") } else { Text(label) }
    }
}

/// Everything known about a seat, each value copyable; and Stop, where its machine can.
struct SessionInfoView: View {
    let state: SeatState
    let session: SeatSession
    @State private var confirmStop = false
    @State private var adopting = false
    /// The length of the next paging On or Off; nil until changed. One hour by default: a grant is usually temporary.
    @State private var pagingMinutes: Int? = 60

    var body: some View {
        Form {
            if let seat = state.seat {
                Section("Seat") {
                    row("Title", seat.title)
                    row("Display title", seat.displayTitle)
                    row("Id", String(seat.id))
                    row("Status", [seat.status, seat.activity].compactMap { $0 }.joined(separator: " · "))
                    row("Machine", seat.host)
                    row("Fleet", seat.fleet)
                    row("Started by", seat.origin)
                    // `mode` is how the seat connects (e.g. json for a sidecar), not its permission mode.
                    row("Type", [seat.type, seat.mode].compactMap { $0 }.joined(separator: " · ").nilIfEmpty)
                    row("Folder", seat.workingDirectory)
                }
                accessSection(seat)
                Section("Claude") {
                    row("Model", seat.model)
                    row("Effort", state.effort)
                    row("Permission mode", state.live?.permissionMode)
                    row("Session id", seat.claudeSessionId)
                    row("Conversation", seat.conversationId)
                    row("Client version", seat.clientVersion)
                }
                Section("Time") {
                    row("Started", seat.insertedAt.map { "\(localTime($0)) (\(relativeTime(iso: $0)))" })
                    row("Last activity", seat.lastActivityAt.map { "\(localTime($0)) (\(relativeTime(iso: $0)))" })
                }
                Section {
                    let blocked = adoptBlockedReason(seat: seat)
                    Button("Adopt…") { adopting = true }
                        .disabled(blocked != nil)
                        .help(blocked ?? "Run it from a profile under its machine's agent, so it can be stopped, started and restarted from here")
                    if let blocked, seat.origin != "profile" { Text(blocked).font(.caption).foregroundStyle(.secondary) }
                    if seat.canStop {
                        Button("Stop seat…", role: .destructive) { confirmStop = true }.disabled(state.acting)
                    }
                }
            } else if let error = state.loadError {
                Text(error).foregroundStyle(Theme.bad)
            } else {
                ProgressView()
            }
        }
        .formStyle(.grouped)
        .sheet(isPresented: $adopting) { if let seat = state.seat { AdoptSheet(seat: seat) } }
        .confirmationDialog("Stop \(state.seat?.label ?? "this seat")?", isPresented: $confirmStop) {
            Button("Stop on \(state.seat?.host ?? "its machine")", role: .destructive) { session.stop() }
        } message: {
            Text("Its terminal session closes on its machine. A stopped seat can be resumed.")
        }
    }

    /**
     * The seat's claudiverse tools (Basic or Full), and, apart from them,
     * whether it may page the operator: the operator's switch, on or off for
     * a while or until changed, or the seat's own default. Each shows
     * "(default)" when the operator has not set it. A seat applies a tools
     * change within a minute.
     */
    @ViewBuilder
    private func accessSection(_ seat: Seat) -> some View {
        Section {
            Picker("Tools", selection: Binding(
                get: { seat.accessExplicit ? (seat.access ?? "") : "default" },
                set: { choice in session.setAccess(level: choice == "default" ? nil : choice) }
            )) {
                ForEach(SessionChoices.shared.accessLevels, id: \.id) { Text($0.label).tag($0.id) }
                Text("Default").tag("default")
            }
            .pickerStyle(.segmented)
            Text(Self.toolsMeaning(seat)).font(.caption).foregroundStyle(.secondary)
        } header: {
            HStack(spacing: 4) {
                Text("Tools")
                if seat.access != nil, !seat.accessExplicit { Text("(default)").foregroundStyle(.secondary) }
            }
        }
        Section {
            Picker("Switch", selection: Binding(
                get: { seat.notifyGrant.map { $0.boolValue ? "on" : "off" } ?? "default" },
                set: { choice in
                    switch choice {
                    case "on": session.setNotify(allow: true, minutes: pagingMinutes.map { KotlinInt(int: Int32($0)) })
                    case "off": session.setNotify(allow: false, minutes: pagingMinutes.map { KotlinInt(int: Int32($0)) })
                    default: session.setNotify(allow: nil, minutes: nil)
                    }
                }
            )) {
                Text("On").tag("on")
                Text("Off").tag("off")
                Text("Default").tag("default")
            }
            .pickerStyle(.segmented)
            Picker("For", selection: Binding(
                get: { pagingMinutes ?? 0 },
                set: { minutes in
                    pagingMinutes = minutes == 0 ? nil : minutes
                    // A switch in force takes the new length now.
                    if let grant = seat.notifyGrant { session.setNotify(allow: grant, minutes: pagingMinutes.map { KotlinInt(int: Int32($0)) }) }
                }
            )) {
                ForEach(Self.pagingDurations, id: \.minutes) { Text($0.label).tag($0.minutes) }
            }
            LabeledContent("Now", value: Self.pagingNow(seat))
        } header: {
            HStack(spacing: 4) {
                Text("May page the operator")
                if seat.notifyGrant == nil { Text("(default)").foregroundStyle(.secondary) }
            }
        }
    }

    /// How long an On or Off lasts; 0 is until changed.
    static let pagingDurations: [(minutes: Int, label: String)] = [(60, "1 hour"), (240, "4 hours"), (1440, "1 day"), (0, "Until changed")]

    static func toolsMeaning(_ seat: Seat) -> String {
        switch seat.access {
        case "basic": "Reporting and the shared tools."
        case "full": "The orchestrator's full tool set."
        default: "This server does not report a seat's tools."
        }
    }

    /// "yes, until 14:30", "no (the seat's default)"
    static func pagingNow(_ seat: Seat) -> String {
        var text = seat.canNotify ? "yes" : "no"
        if seat.notifyGrant == nil {
            text += " (the seat's default)"
        } else if let until = seat.notifyUntil, let ms = parseServerTime(iso: until)?.int64Value {
            let date = Date(timeIntervalSince1970: Double(ms) / 1000)
            text += ", until " + (Calendar.current.isDateInToday(date) ? date.formatted(date: .omitted, time: .shortened) : date.formatted(date: .abbreviated, time: .shortened))
        } else {
            text += ", until you change it"
        }
        return text
    }

    private func row(_ label: String, _ value: String?) -> some View {
        LabeledContent(label) {
            HStack {
                Text(value ?? "—").textSelection(.enabled).lineLimit(3).multilineTextAlignment(.trailing)
                if let value {
                    Button {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(value, forType: .string)
                    } label: { Image(systemName: "doc.on.doc") }.buttonStyle(.borderless).help("Copy")
                }
            }
        }
    }

    private func localTime(_ iso: String) -> String {
        guard let ms = parseServerTime(iso: iso)?.int64Value else { return iso }
        return Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .abbreviated, time: .shortened)
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
