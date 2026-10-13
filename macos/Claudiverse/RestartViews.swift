import ClaudiverseCore
import SwiftUI

/**
 * Restarting seats onto their machine's current Claude Code. The server plans
 * each seat first (restart in its tmux window, adopt it into one when it runs
 * outside tmux, or skip it, saying why), then restarts the chosen ones one at
 * a time; "seat_restart" events say how each went (core's Restarts).
 */

/// Where Restart idle seats was opened for: one machine, or every machine (nil).
struct RestartScope: Identifiable {
    let host: String?
    var id: String { host ?? "*" }
}

/// The fleets the operator left out of the last bulk restart, kept on this Mac.
enum RestartPreferences {
    private static let key = "restartExcludedFleets"

    static var excludedFleets: Set<String> {
        get { Set(UserDefaults.standard.stringArray(forKey: key) ?? []) }
        set { UserDefaults.standard.set(newValue.sorted(), forKey: key) }
    }
}

/// What a row's action reads as.
enum RestartWording {
    static func action(_ p: RestartPlan) -> String {
        switch p.action {
        case RestartPlan.companion.RESTART: "restart"
        case RestartPlan.companion.ADOPT: "adopt"
        default: "skip"
        }
    }

    /// "2.1.292 → 2.1.296", or one of them when the other is not known.
    static func versions(_ old: String?, _ new: String?) -> String? {
        switch (old, new) {
        case let (o?, n?) where o != n: "\(o) → \(n)"
        case let (o?, _): o
        case let (nil, n?): "→ \(n)"
        default: nil
        }
    }

    /// An outcome as a row says it.
    static func status(_ row: RestartRow) -> String {
        switch row.status {
        case "waiting": "waiting"
        case "started": "restarting…"
        case "restarted": "restarted"
        case "adopted": "adopted"
        case "skipped": "skipped"
        case "failed": "failed"
        default: row.status
        }
    }

    /// The seats a bulk restart starts with ticked: every one it would restart or adopt, except protected ones.
    static func defaultTicked(_ plan: [RestartPlan]) -> Set<Int64> {
        Set(plan.filter { !$0.skipped && !$0.isProtected }.map(\.sessionId))
    }

    /// Whether a row can be ticked: anything the server would restart or adopt, and a protected seat it skips only
    /// for being protected (it restarts one named in session_ids). Other skips (busy, up to date…) cannot be.
    static func tickable(_ p: RestartPlan) -> Bool { !p.skipped || p.isProtected }

    /// "Restart 3 seats", "Restart 1 seat".
    static func confirm(_ n: Int) -> String { n == 1 ? "Restart 1 seat" : "Restart \(n) seats" }
}

/// Restart idle seats…: a dry run first, each seat ticked or not, then the chosen ones restarted one at a time.
struct RestartIdleSheet: View {
    let scope: RestartScope
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var plan: [RestartPlan] = []
    @State private var ticked: Set<Int64> = []
    @State private var onlyOutdated = true
    @State private var excludedFleets = RestartPreferences.excludedFleets
    @State private var loading = true
    @State private var error: String?
    @State private var started: RestartBatchStarted?
    @State private var confirmingProtected = false

    private var api: Api { model.client.api }
    private var pausedFleets: Set<String> { Set(model.fleets.filter(\.paused).map(\.fleet)) }
    /// Fleets in the plan, for the toggles; seats in none are always offered.
    private var fleets: [String] { Array(Set(plan.compactMap(\.fleet))).sorted() }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(scope.host.map { "Restart idle seats on \($0)" } ?? "Restart idle seats on every machine").font(.headline)
            if let started {
                progress(started)
            } else {
                options
                planList
            }
            if let error { Text(error).foregroundStyle(Theme.bad).font(.callout) }
            HStack {
                Spacer()
                if started == nil {
                    Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                    Button(RestartWording.confirm(ticked.count)) {
                        if plan.contains(where: { $0.isProtected && ticked.contains($0.sessionId) }) {
                            confirmingProtected = true
                        } else {
                            Task { await start() }
                        }
                    }
                    .keyboardShortcut(.defaultAction)
                    .disabled(ticked.isEmpty || loading)
                } else {
                    Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(20)
        .frame(width: 620)
        .frame(minHeight: 360)
        .task { await dryRun() }
        .confirmationDialog("Restart protected seats?", isPresented: $confirmingProtected) {
            Button("Restart Them Too", role: .destructive) { Task { await start() } }
        } message: {
            Text("\(protectedTicked.map(\.title).joined(separator: ", ")) \(protectedTicked.count == 1 ? "is" : "are") protected: \(protectedTicked.count == 1 ? "it is" : "they are") left alone unless you mean it.")
        }
    }

    private var protectedTicked: [RestartPlan] { plan.filter { $0.isProtected && ticked.contains($0.sessionId) } }

    @ViewBuilder
    private var options: some View {
        Toggle("Only seats on an older Claude Code", isOn: $onlyOutdated)
            .onChange(of: onlyOutdated) { Task { await dryRun() } }
        if !fleets.isEmpty {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text("Fleets:").foregroundStyle(.secondary)
                ForEach(fleets, id: \.self) { fleet in
                    Toggle(isOn: Binding(
                        get: { !excluded(fleet) },
                        set: { on in
                            if on { excludedFleets.remove(fleet) } else { excludedFleets.insert(fleet) }
                            // A paused fleet the operator ticks is included on purpose.
                            if on && pausedFleets.contains(fleet) { includedPaused.insert(fleet) } else { includedPaused.remove(fleet) }
                            RestartPreferences.excludedFleets = excludedFleets
                            Task { await dryRun() }
                        })) {
                        Text(pausedFleets.contains(fleet) ? "\(fleet) (paused)" : fleet)
                    }
                    .toggleStyle(.checkbox)
                }
            }
            .font(.callout)
        }
    }

    /// Paused fleets ticked by hand this time: the only paused ones a restart takes.
    @State private var includedPaused: Set<String> = []

    private func excluded(_ fleet: String) -> Bool {
        excludedFleets.contains(fleet) || (pausedFleets.contains(fleet) && !includedPaused.contains(fleet))
    }

    @ViewBuilder
    private var planList: some View {
        if loading && plan.isEmpty {
            ProgressView().controlSize(.small)
        } else if plan.isEmpty {
            Text("No seats to look at.").foregroundStyle(.secondary)
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(plan, id: \.sessionId) { p in planRow(p) }
                }
            }
            .frame(maxHeight: 360)
        }
    }

    private func planRow(_ p: RestartPlan) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Toggle("", isOn: Binding(get: { ticked.contains(p.sessionId) }, set: { on in
                if on { ticked.insert(p.sessionId) } else { ticked.remove(p.sessionId) }
            }))
            .toggleStyle(.checkbox).labelsHidden()
            .disabled(!RestartWording.tickable(p))
            if p.isProtected { Image(systemName: "lock.fill").font(.caption).foregroundStyle(.secondary).help("Protected: left out unless ticked") }
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 6) {
                    Text(p.title).fontWeight(.medium)
                    if let fleet = p.fleet { Text(fleet).font(.caption).foregroundStyle(.secondary) }
                    if scope.host == nil, let host = p.host { Text(host).font(.caption).foregroundStyle(.secondary) }
                }
                if p.skipped, let reason = p.reason { Text(reason).font(.caption).foregroundStyle(.secondary) }
            }
            Spacer()
            if let v = RestartWording.versions(p.clientVersion, p.targetVersion) {
                Text(v).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            Pill(text: RestartWording.action(p), color: p.skipped ? Theme.muted : p.action == RestartPlan.companion.ADOPT ? Theme.info : Theme.good)
        }
        .opacity(RestartWording.tickable(p) ? 1 : 0.7)
    }

    private func progress(_ s: RestartBatchStarted) -> some View {
        let chosen = s.seats.filter { ticked.contains($0.sessionId) || !$0.skipped }
        let rows = foldRestart(plan: chosen, events: model.restartEvents, batchId: s.batchId)
        let batch = model.restartBatches[s.batchId]
        return VStack(alignment: .leading, spacing: 6) {
            Text(batch?.done == true ? Self.summary(batch) : "Restarting one at a time…").font(.callout)
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(rows, id: \.plan.sessionId) { row in RestartProgressRow(row: row) }
                }
            }
            .frame(maxHeight: 360)
        }
    }

    /// "Done: 3 restarted, 1 adopted, 1 failed".
    static func summary(_ b: RestartBatchEvent?) -> String {
        guard let b else { return "Done" }
        let parts = ["restarted", "adopted", "skipped", "failed"].compactMap { k in
            b.counts[k].flatMap { n in n.intValue > 0 ? "\(n.intValue) \(k)" : nil }
        }
        return "Done" + (parts.isEmpty ? "" : ": " + parts.joined(separator: ", "))
    }

    private var options_: RestartOptions {
        RestartOptions(fleets: nil, excludeFleets: fleets.filter(excluded), onlyOutdated: onlyOutdated, includeSelf: false, caller: nil,
                       sessionIds: nil, excludeSessionIds: nil, excludePaused: includedPaused.isEmpty)
    }

    private func dryRun() async {
        loading = true
        defer { loading = false }
        do {
            let previous = Set(plan.map(\.sessionId))
            plan = try await api.planIdleRestart(host: scope.host, options: options_)
            // Keep the operator's choices for seats still in the plan; newcomers start as the default says.
            let fresh = RestartWording.defaultTicked(plan)
            ticked = ticked.intersection(Set(plan.filter(RestartWording.tickable).map(\.sessionId)))
                .union(fresh.subtracting(previous))
            error = nil
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            self.error = error.httpStatus == 404 ? "This server cannot restart seats yet." : "Not planned: \(error.message)"
        }
    }

    private func start() async {
        let ids = plan.filter { ticked.contains($0.sessionId) }.map(\.sessionId)
        do {
            var o = options_
            o = RestartOptions(fleets: o.fleets, excludeFleets: o.excludeFleets, onlyOutdated: o.onlyOutdated, includeSelf: o.includeSelf,
                               caller: o.caller, sessionIds: ids.map { KotlinLong(value: $0) }, excludeSessionIds: nil, excludePaused: o.excludePaused)
            started = try await api.restartIdle(host: scope.host, options: o)
            error = nil
        } catch {
            self.error = "Not started: \(error.message)"
        }
    }
}

/// One seat in a running restart: its outcome, and the note in red when it failed or had to be killed.
struct RestartProgressRow: View {
    let row: RestartRow

    var body: some View {
        let e = row.event
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: Self.icon(row.status)).foregroundStyle(Self.color(row.status)).frame(width: 14)
            VStack(alignment: .leading, spacing: 1) {
                Text(row.plan.title).fontWeight(.medium)
                if let note = e?.note ?? (row.status == "skipped" ? (e?.reason ?? row.plan.reason) : nil) {
                    Text(note).font(.caption).foregroundStyle(e?.killed == true || row.status == "failed" ? Theme.bad : .secondary)
                }
                if e?.killed == true { Text("The old process had to be killed.").font(.caption).foregroundStyle(Theme.bad) }
            }
            Spacer()
            if let v = RestartWording.versions(e?.oldVersion ?? row.plan.clientVersion, e?.newVersion ?? row.plan.targetVersion) {
                Text(v).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            Text(RestartWording.status(row)).font(.callout).foregroundStyle(Self.color(row.status))
        }
    }

    static func icon(_ status: String) -> String {
        switch status {
        case "restarted", "adopted": "checkmark.circle.fill"
        case "failed": "xmark.octagon.fill"
        case "started": "arrow.triangle.2.circlepath"
        case "skipped": "minus.circle"
        default: "clock"
        }
    }

    static func color(_ status: String) -> Color {
        switch status {
        case "restarted", "adopted": Theme.good
        case "failed": Theme.bad
        case "started": Theme.busy
        default: Color.secondary
        }
    }
}

/// One seat: what restarting it would do, then doing it and following it.
struct RestartSeatSheet: View {
    let seat: Seat
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var plan: RestartPlan?
    @State private var error: String?
    @State private var restartId: String?
    @State private var confirmingProtected = false

    private var api: Api { model.client.api }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Restart \(seat.label)").font(.headline)
            if let restartId {
                let events = model.restartEvents.filter { $0.restartId == restartId }
                if let plan { RestartProgressRow(row: RestartRow(plan: plan, event: events.last)) }
            } else if let plan {
                Text(Self.describe(plan)).font(.callout)
                if !plan.argvNames.isEmpty || !plan.envNames.isEmpty {
                    Text("Carries over: " + (plan.argvNames + plan.envNames).joined(separator: ", "))
                        .font(.caption.monospaced()).foregroundStyle(.secondary).textSelection(.enabled)
                }
            } else if error == nil {
                ProgressView().controlSize(.small)
            }
            if let error { Text(error).foregroundStyle(Theme.bad).font(.callout) }
            HStack {
                Spacer()
                if restartId == nil {
                    Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                    Button(plan.map(Self.button) ?? "Restart") {
                        if plan?.isProtected == true { confirmingProtected = true } else { Task { await restart(confirmProtected: false) } }
                    }
                    .keyboardShortcut(.defaultAction)
                    .disabled(plan == nil || plan?.skipped == true)
                } else {
                    Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(20)
        .frame(width: 460)
        .task { await dryRun() }
        .confirmationDialog("\(seat.label) is protected. Restart it anyway?", isPresented: $confirmingProtected) {
            Button("Restart", role: .destructive) { Task { await restart(confirmProtected: true) } }
        }
    }

    /// The button: "Restart (resume on 2.1.296)", or "Adopt into a managed seat".
    static func button(_ p: RestartPlan) -> String {
        switch p.action {
        case RestartPlan.companion.ADOPT: "Adopt into a managed seat"
        case RestartPlan.companion.RESTART: p.targetVersion.map { "Restart (resume on \($0))" } ?? "Restart"
        default: "Restart"
        }
    }

    /// What the plan says, for people.
    static func describe(_ p: RestartPlan) -> String {
        switch p.action {
        case RestartPlan.companion.RESTART:
            "It stops, and starts again in its tmux window on \(p.targetVersion.map { "Claude Code \($0)" } ?? "the machine's current Claude Code"), resuming the same conversation."
        case RestartPlan.companion.ADOPT:
            "It runs outside tmux. It stops, and starts again as a managed tmux seat on the same conversation; its old terminal window is left as it is."
        default:
            "Not now: \(p.reason ?? "the server says it cannot be restarted").".replacingOccurrences(of: "..", with: ".")
        }
    }

    private func dryRun() async {
        do {
            plan = try await api.planSeatRestart(seatId: seat.id, includeSelf: false, caller: nil, confirmProtected: false)
            error = nil
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            self.error = error.httpStatus == 404 ? "This server cannot restart seats yet." : "Not planned: \(error.message)"
        }
    }

    private func restart(confirmProtected: Bool) async {
        do {
            restartId = try await api.restartSeat(seatId: seat.id, includeSelf: false, caller: nil, confirmProtected: confirmProtected)
            error = nil
        } catch {
            // A refusal carries the server's sentence (busy, asking a question…): said as it is.
            self.error = "Not restarted: \(error.message)"
        }
    }
}
