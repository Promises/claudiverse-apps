import ClaudiverseCore
import SwiftUI

/**
 * The server's auto-restart of outdated idle seats: the global setting, and
 * each fleet's own override over it. Off unless the operator turns it on;
 * changing it takes the app token, so a seat can never turn it on.
 */
struct AutoRestartSettings: View {
    @Environment(AppModel.self) private var model
    @State private var global: AutoRestart?
    /// Read, and the server has no auto-restart (an older server: 404 on its settings).
    @State private var unsupported = false
    @State private var minutes = AutoRestartWording.minutes(AutoRestart.companion.DEFAULT_MIN_IDLE_S)
    @State private var saving: Task<Void, Never>?
    @State private var error: String?

    var body: some View {
        Form {
            if unsupported {
                Section("Auto-restart") {
                    Text("This server has no auto-restart yet.").foregroundStyle(.secondary)
                }
            } else {
                settings
            }
        }
        .formStyle(.grouped)
        .themedList()
        // Read again once signed in: a window opened while the app starts would otherwise keep the first failure.
        .task(id: model.phase == .signedIn) { await load() }
    }

    @ViewBuilder
    private var settings: some View {
        Section {
            Toggle("Restart outdated idle seats automatically", isOn: Binding(
                get: { global?.enabled ?? false },
                set: { on in save(enabled: on) }))
                .disabled(global == nil)
            Stepper(value: $minutes, in: AutoRestartWording.minuteRange) {
                Text("After \(AutoRestartWording.idle(minutes: minutes)) idle")
            }
            .disabled(global == nil)
            .onChange(of: minutes) { _, now in
                guard let global, now != AutoRestartWording.minutes(global.minIdleS) else { return }
                // A stepper held down sends one change, not one per step.
                saving?.cancel()
                saving = Task {
                    try? await Task.sleep(for: .milliseconds(600))
                    if !Task.isCancelled { save(minutes: now) }
                }
            }
        } header: {
            Text("Auto-restart")
        } footer: {
            Text(AutoRestartWording.explain).font(.caption).foregroundStyle(.secondary)
        }
        if let error { Text(error).foregroundStyle(Theme.bad).font(.callout) }
        if !model.fleets.isEmpty {
            Section("Fleets") {
                ForEach(model.fleets, id: \.fleet) { f in
                    FleetAutoRestartRow(fleet: f, global: global) { override in await saveFleet(f.fleet, override) }
                }
            }
        }
    }

    private func load() async {
        do {
            guard let a = try await model.client.api.autoRestart() else {
                unsupported = true
                return
            }
            unsupported = false
            global = a
            minutes = AutoRestartWording.minutes(a.minIdleS)
            error = nil
            // The fleets list is re-read only every minute: read it now, so a change made elsewhere shows on opening.
            try? await model.client.fleets.refresh()
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            self.error = "Auto-restart not read: \(error.message)"
        }
    }

    private func save(enabled: Bool? = nil, minutes: Int? = nil) {
        Task {
            do {
                let a = try await model.client.api.setAutoRestart(
                    enabled: enabled.map { KotlinBoolean(value: $0) },
                    minIdleS: minutes.map { KotlinInt(value: AutoRestartWording.seconds(minutes: $0)) })
                global = a
                self.minutes = AutoRestartWording.minutes(a.minIdleS)
                error = nil
                // Each fleet's effective setting follows the global one.
                try? await model.client.fleets.refresh()
            } catch {
                self.error = "Not changed: \(error.message)"
                await load()
            }
        }
    }

    private func saveFleet(_ fleet: String, _ override: AutoRestartOverride) async {
        do {
            _ = try await model.client.api.setFleetAutoRestart(fleet: fleet, override: override)
            error = nil
        } catch {
            self.error = "\(fleet) not changed: \(error.message)"
        }
        try? await model.client.fleets.refresh()
    }
}

/// One fleet: on, off or as the global setting, and its own idle minutes or the global ones.
private struct FleetAutoRestartRow: View {
    let fleet: Fleet
    let global: AutoRestart?
    let save: (AutoRestartOverride) async -> Void
    @State private var minutesText = ""

    var body: some View {
        let override = fleet.autoRestart
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            VStack(alignment: .leading, spacing: 1) {
                Text(fleet.fleet).fontWeight(.medium)
                Text(AutoRestartWording.effective(fleet)).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Picker("", selection: Binding(
                get: { AutoRestartWording.Choice(override?.enabled?.boolValue) },
                set: { c in Task { await save(AutoRestartWording.override(enabled: c, minIdleS: override?.minIdleS?.int32Value)) } })) {
                ForEach(AutoRestartWording.Choice.allCases, id: \.self) { c in
                    Text(c == .inherit ? "As global (\(global?.enabled == true ? "on" : "off"))" : c.label).tag(c)
                }
            }
            .labelsHidden()
            .frame(width: 150)
            TextField("", text: $minutesText,
                      prompt: Text(global.map { "\(AutoRestartWording.minutes($0.minIdleS))" } ?? ""))
                .frame(width: 56)
                .multilineTextAlignment(.trailing)
                .help("Idle minutes before a restart; empty follows the global setting")
                .onSubmit {
                    let choice = AutoRestartWording.Choice(override?.enabled?.boolValue)
                    let minutes = AutoRestartWording.parseMinutes(minutesText)
                    if minutes == nil && !minutesText.trimmingCharacters(in: .whitespaces).isEmpty {
                        minutesText = override?.minIdleS.map { "\(AutoRestartWording.minutes($0.int32Value))" } ?? ""
                        return
                    }
                    Task { await save(AutoRestartWording.override(enabled: choice, minIdleS: minutes.map { AutoRestartWording.seconds(minutes: $0) })) }
                }
            Text("min").foregroundStyle(.secondary)
        }
        .onAppear { minutesText = override?.minIdleS.map { "\(AutoRestartWording.minutes($0.int32Value))" } ?? "" }
        .onChange(of: override?.minIdleS?.int32Value) { _, s in minutesText = s.map { "\(AutoRestartWording.minutes($0))" } ?? "" }
    }
}

/// What the auto-restart settings say, and the minutes ↔ seconds the server takes.
enum AutoRestartWording {
    static let explain = "Every minute the server restarts, one at a time, seats on an older Claude Code or mod that have been idle this long. Never a protected, paused, runner or busy seat, and a seat at most once an hour."

    /// The server's bounds (60 s to a day), in whole minutes.
    static let minuteRange = minutes(AutoRestart.companion.MIN_IDLE_S)...minutes(AutoRestart.companion.MAX_IDLE_S)

    static func minutes(_ seconds: Int32) -> Int { Int(seconds) / 60 }

    static func seconds(minutes: Int) -> Int32 { Int32(min(max(minutes, minuteRange.lowerBound), minuteRange.upperBound) * 60) }

    /// "5 min", "1 h", "1 h 30 min".
    static func idle(minutes: Int) -> String {
        let (h, m) = (minutes / 60, minutes % 60)
        return switch (h, m) {
        case (0, _): "\(m) min"
        case (_, 0): "\(h) h"
        default: "\(h) h \(m) min"
        }
    }

    /// A typed minutes field: a whole number in range, or nil (empty, or not a number).
    static func parseMinutes(_ text: String) -> Int? {
        guard let n = Int(text.trimmingCharacters(in: .whitespaces)), minuteRange.contains(n) else { return nil }
        return n
    }

    enum Choice: CaseIterable, Hashable {
        case inherit, on, off

        init(_ enabled: Bool?) { self = enabled.map { $0 ? .on : .off } ?? .inherit }

        var label: String {
            switch self {
            case .inherit: "As global"
            case .on: "On"
            case .off: "Off"
            }
        }
    }

    /// The override a fleet's row sets as a whole: nil fields inherit, and both nil clears it.
    static func override(enabled: Choice, minIdleS seconds: Int32?) -> AutoRestartOverride {
        let on: KotlinBoolean? = switch enabled {
        case .inherit: nil
        case .on: KotlinBoolean(value: true)
        case .off: KotlinBoolean(value: false)
        }
        return AutoRestartOverride(enabled: on, minIdleS: seconds.map { KotlinInt(value: $0) })
    }

    /// What applies to a fleet's seats: "Now: on, after 5 min idle", "Now: off", "Paused: never auto-restarted".
    static func effective(_ f: Fleet) -> String {
        if f.paused { return "Paused: never auto-restarted" }
        guard let e = f.autoRestartEffective else { return "The server does not say" }
        let source = f.autoRestart == nil ? " (as global)" : ""
        return e.enabled ? "Now: on, after \(idle(minutes: minutes(e.minIdleS))) idle\(source)" : "Now: off\(source)"
    }
}
