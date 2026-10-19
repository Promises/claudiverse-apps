import ClaudiverseCore
import SwiftUI

// MARK: tasks

/// Task states as the server names them; "active" is the ones still in play.
enum TaskFilter: String, CaseIterable, Identifiable {
    case active, RUNNING, READY, DRAFT, DONE, SUPERSEDED
    var id: String { rawValue }

    static let activeStates = ["RUNNING", "READY", "DRAFT"]

    /// The states the server is asked for: the list runs to thousands of rows, so it filters, not us.
    var states: [String] { self == .active ? Self.activeStates : [rawValue] }

    /// Finished states only grow; only the live ones are polled.
    var poll: Duration? { self == .active ? .seconds(30) : nil }
}

struct TaskFiltersSidebar: View {
    @Binding var selection: TaskFilter

    var body: some View {
        List(selection: Binding(get: { selection }, set: { if let s = $0 { selection = s } })) {
            ForEach(TaskFilter.allCases) { f in
                Label(f == .active ? "Active" : f.rawValue.capitalized, systemImage: icon(f)).tag(f)
            }
        }
        .listStyle(.sidebar)
        .themedList(sidebar: true)
    }

    private func icon(_ f: TaskFilter) -> String {
        switch f {
        case .active: "bolt"
        case .RUNNING: "play.circle"
        case .READY: "tray"
        case .DRAFT: "pencil"
        case .DONE: "checkmark.circle"
        case .SUPERSEDED: "arrow.uturn.right.circle"
        }
    }
}

/// Fleet tasks in one state (or all live ones), newest first within a state; a row opens to its body.
struct TasksView: View {
    let filter: TaskFilter
    @Environment(AppModel.self) private var model
    @State private var tasks: [FleetTask] = []
    @State private var error: String?
    @State private var loaded = false
    @State private var open: Set<Int64> = []

    var body: some View {
        List {
            if let error { Text(error).foregroundStyle(Theme.bad) }
            if loaded && tasks.isEmpty && error == nil { Text("No tasks.").foregroundStyle(.secondary) }
            ForEach(tasks, id: \.id) { t in
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Pill(text: t.state, color: color(t.state))
                        Text(verbatim: "#\(t.id)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        Text(t.title).lineLimit(open.contains(t.id) ? 6 : 2)
                    }
                    Text([t.fleet, t.lane, t.claimedBy.map { "→ \($0)" }, t.priority.map { "p\($0)" }, relativeTime(iso: t.insertedAt).nilIfEmpty]
                        .compactMap { $0 }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary)
                    if open.contains(t.id), let body = t.body { MarkdownText(body).padding(.top, 4).textSelection(.enabled) }
                }
                .contentShape(Rectangle())
                .onTapGesture { if open.contains(t.id) { open.remove(t.id) } else { open.insert(t.id) } }
                .padding(.vertical, 4)
            }
        }
        .navigationTitle(filter == .active ? "Active tasks" : "\(filter.rawValue.capitalized) tasks")
        .navigationSubtitle(loaded ? "\(tasks.count)" : "")
        .task {
            while !Task.isCancelled {
                do {
                    var all: [FleetTask] = []
                    for state in filter.states { all += try await model.client.api.tasks(state: state, fleet: nil, limit: 200) }
                    tasks = all.sorted {
                        let a = TaskFilter.activeStates.firstIndex(of: $0.state) ?? 99, b = TaskFilter.activeStates.firstIndex(of: $1.state) ?? 99
                        return a != b ? a < b : $0.id > $1.id
                    }
                    error = nil
                } catch {
                    // Cancelled: the filter changed or the view went; a newer load has the say.
                    if Task.isCancelled || error is CancellationError { return }
                    self.error = "Tasks not read: \(error.message)"
                }
                loaded = true
                guard let every = filter.poll else { return }
                try? await Task.sleep(for: every)
            }
        }
    }

    private func color(_ state: String) -> Color {
        switch state {
        case "RUNNING": Theme.busy
        case "READY": Theme.info
        case "DONE": Theme.good
        default: Theme.muted
        }
    }
}

// MARK: pool

/// Server health, the Anthropic account pool and the incident log, read every 30 s while shown.
@MainActor
@Observable
final class PoolModel {
    private(set) var health: Int?
    private(set) var pool: Pool?
    private(set) var incidents: Incidents?
    private(set) var error: String?
    /// The newest "pool" event since the app started: what changed, and who changed it.
    var lastChange: PoolChange?

    func load(_ api: Api) async {
        health = try? await Int(api.health())
        do {
            pool = try await api.pool()
            error = nil
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            self.error = "Pool not read: \(error.message)"
        }
        incidents = try? await api.incidents()
    }

    /** Make [account] the pool's first choice; the answer's order shows at once. */
    func makePreferred(_ api: Api, _ account: AnthropicAccount) async {
        do {
            let order = try await api.makePreferred(accountId: account.id)
            pool = pool?.withOrder(order: order) ?? Pool(accounts: order.accounts, seats: [], currentId: order.currentId)
            error = nil
        } catch {
            // A 404 is either this account gone or a server without the call yet: say both rather than guess.
            self.error = error.httpStatus == 404
                ? "\(account.label) not made preferred: the server does not know this account, or cannot set a preferred account yet (HTTP 404)."
                : "\(account.label) not made preferred: \(error.message)"
        }
    }
}

struct PoolSidebar: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List {
            Section("Accounts") {
                ForEach(model.pool.pool?.ordered ?? [], id: \.id) { a in
                    HStack {
                        Circle().fill(a.status == "available" ? Theme.good : Theme.bad).frame(width: 8, height: 8)
                        Text(a.label)
                        if a.id == model.pool.pool?.preferredId?.int64Value {
                            Image(systemName: "star.fill").font(.caption).foregroundStyle(Theme.accent).help("Preferred")
                        }
                        Spacer()
                        if let session = a.limits.first(where: { $0.kind == "session" })?.percent {
                            Text("session \(session)%").foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .themedList(sidebar: true)
    }
}

struct PoolView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let p = model.pool
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 8) {
                    Text("Server").font(.headline)
                    switch p.health {
                    case 200: Pill(text: "health 200", color: Theme.good)
                    case nil: Pill(text: "health unknown", color: Theme.muted)
                    case let code?: Pill(text: "health \(code)", color: Theme.bad)
                    }
                    if let inc = p.incidents { Pill(text: "\(inc.total) incidents", color: inc.total == 0 ? Theme.good : Theme.bad) }
                }
                if let change = p.lastChange { Text(Self.describe(change, p.pool)).font(.callout).foregroundStyle(.secondary) }
                if let error = p.error { Text(error).foregroundStyle(Theme.bad) }
                if let pool = p.pool {
                    ForEach(pool.ordered, id: \.id) { a in
                        AccountCard(account: a, pool: pool) { Task { await p.makePreferred(model.client.api, a) } }
                    }
                }
                if let pool = p.pool { AssignmentSummary(pool: pool) }
                UsageCharts()
                if let inc = p.incidents, !inc.latest.isEmpty {
                    Text("Latest incidents" + (inc.since.map { " since \(relativeTime(iso: $0))" } ?? "")).font(.headline)
                    ForEach(Array(inc.latest.reversed().enumerated()), id: \.offset) { _, i in
                        Text(i.summary).font(.caption.monospaced()).textSelection(.enabled)
                    }
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Pool")
        .task {
            while !Task.isCancelled {
                await p.load(model.client.api)
                try? await Task.sleep(for: .seconds(30))
            }
        }
    }
}

/**
 * One account of the pool: its status, cooldown and usage, and its place in
 * the order. The preferred one is the pool's first choice; any other can be
 * made preferred, which moves pooled seats to it on their next request.
 */
extension PoolView {
    /// "Preferred account changed to Work by op@example.com · 2m ago"
    static func describe(_ c: PoolChange, _ pool: Pool?) -> String {
        let name = { (id: KotlinLong?) in id.flatMap { id in pool?.accounts.first { $0.id == id.int64Value }?.label } }
        let what: String
        switch c.change {
        case "preferred": what = "Preferred account changed" + (name(c.preferredId).map { " to \($0)" } ?? "") + (c.by.map { " by \($0)" } ?? "")
        case "upgrade": what = "Seats moved back to " + (name(c.currentId) ?? "a better account")
        case "degrade": what = "Seats moved to " + (name(c.currentId) ?? "a fallback account")
        case "cooling": what = "An account cooled down after a rate limit"
        default: what = "The pool changed (\(c.change))"
        }
        let when = relativeTime(iso: c.at)
        return when.isEmpty ? what : "\(what) · \(when)"
    }
}

struct AccountCard: View {
    let account: AnthropicAccount
    let pool: Pool
    let onMakePreferred: () -> Void
    @State private var confirming = false

    var body: some View {
        let a = account
        let preferred = a.id == pool.preferredId?.int64Value
        let inUse = pool.inUseId?.int64Value
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(a.label).font(.title3.weight(.semibold))
                if preferred { Pill(text: "Preferred", color: Theme.accent) }
                if inUse == a.id { Pill(text: "In use", color: Theme.good) }
                Spacer()
                Pill(text: a.status.replacingOccurrences(of: "_", with: " "), color: a.status == "available" ? Theme.good : Theme.bad)
                if a.tokenValid?.boolValue == false { Pill(text: "token invalid", color: Theme.bad) }
                if !preferred {
                    Button("Make Preferred…") { confirming = true }
                }
            }
            if preferred, let inUse, inUse != a.id, let other = pool.accounts.first(where: { $0.id == inUse }) {
                Text("Preferred, but seats use \(other.label) now: the pool comes back here when this account recovers.")
                    .font(.callout).foregroundStyle(Theme.attention)
            }
            if let until = a.cooldownUntil { Text("Cooling down until \(until) (\(relativeTime(iso: until)))").font(.callout) }
            // The reason outlives the cooldown on the server; it is history once the account is available.
            if let reason = a.cooldownReason { Text((a.cooldownUntil == nil ? "Last cooldown: " : "Reason: ") + reason).font(.callout).foregroundStyle(.secondary) }
            ForEach(Array(a.limits.enumerated()), id: \.offset) { _, l in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(l.kind.replacingOccurrences(of: "_", with: " ")).font(.callout.weight(.medium))
                        Spacer()
                        Text((l.percent.map { "\($0)%" } ?? "—") + (l.resetsAt.map { " · resets \(relativeTime(iso: $0))" } ?? ""))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    if let pct = l.percent?.doubleValue {
                        ProgressView(value: min(pct, 100), total: 100).tint(l.severity == "critical" ? Theme.bad : l.severity == "warning" ? Theme.busy : Theme.good)
                    }
                }
            }
            Text("Usage read \(relativeTime(iso: a.usageCheckedAt)) · priority \(a.priority.map { "\($0)" } ?? "—")").font(.caption).foregroundStyle(.secondary)
        }
        .padding(14)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
        .confirmationDialog("Make \(account.label) the preferred account?", isPresented: $confirming) {
            Button("Make Preferred") { onMakePreferred() }
        } message: {
            Text("Pooled seats move to \(account.label) on their next request. While it is cooling down or needs re-auth, the pool keeps using the next account and comes back to it when it recovers.")
        }
    }
}

/// Which account seats were last handed: assignment records, not a live map (a seat stays on its last account until it asks again).
struct AssignmentSummary: View {
    let pool: Pool

    var body: some View {
        let dayAgo = nowMillis - 24 * 3_600_000
        let latest = Dictionary(grouping: pool.seats, by: \.title).compactMapValues { rows in
            rows.max { (parseServerTime(iso: $0.assignedAt)?.int64Value ?? 0) < (parseServerTime(iso: $1.assignedAt)?.int64Value ?? 0) }
        }.values.filter { (parseServerTime(iso: $0.assignedAt)?.int64Value ?? 0) > dayAgo }
        let byAccount = Dictionary(grouping: latest, by: { $0.label ?? "?" }).mapValues(\.count).sorted { $0.key < $1.key }
        VStack(alignment: .leading, spacing: 4) {
            Text("Last assignment per seat, past 24 h (\(latest.count) seats)").font(.headline)
            ForEach(byAccount, id: \.key) { label, n in Text("\(label): \(n)") }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
    }
}
