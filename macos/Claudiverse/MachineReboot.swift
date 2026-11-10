import ClaudiverseCore
import SwiftUI

/**
 * A machine's safe reboot (host agent 0.23+; nothing shows before, or
 * offline, or for a sign-in that may not): Prepare Reboot writes down every
 * seat and runner it runs, which the machine reopens by itself after its
 * reboot. After a reboot nobody prepared, the list it kept is offered as
 * Reopen Seats, reopened only once confirmed here. The words are core's
 * (RebootWording), as Android's.
 */
struct MachineReboot: View {
    let host: ClaudiverseCore.Host
    @Environment(AppModel.self) private var model
    @State private var summary: RebootSummary?
    @State private var confirming = false
    @State private var reopenId: String?
    /// The reopen's done, once the app has seen it (it may come before the reply that names it).
    @State private var done: ReopenDone?
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        if host.online, model.canChangeSettings, let reboot = host.reboot {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Button("Prepare Reboot") { prepare() }.disabled(busy)
                        .help("Write down every seat and runner here; after the reboot \(host.name) reopens them by itself")
                    if let r = reboot.recoverable, reopenId == nil, !confirming {
                        Button(RebootWording.shared.reopenButton(brief: r)) { confirming = true }.disabled(busy)
                    }
                    if reboot.reopening != nil, reopenId == nil { Text("Reopening…").foregroundStyle(.secondary) }
                }
                if let prepared = summary?.brief ?? reboot.prepared {
                    Text(RebootWording.shared.prepared(host: host.name, brief: prepared, whenLabel: relativeTime(iso: prepared.takenAt)))
                        .font(.callout)
                    if let summary {
                        ForEach(RebootWording.shared.preparedNotes(s: summary), id: \.self) { Text($0).font(.caption).foregroundStyle(.secondary) }
                    }
                }
                if confirming, let r = reboot.recoverable { confirm(r) }
                if let reopenId {
                    if let done {
                        result(done)
                    } else {
                        Text("Reopening \(host.name)'s seats…").foregroundStyle(.secondary).font(.callout)
                            .task(id: reopenId) { await waitForDone(reopenId) }
                    }
                } else if let last = reboot.lastReopen {
                    DisclosureGroup(RebootWording.shared.lastReopen(r: last, whenLabel: relativeTime(iso: last.reopenedAt))) {
                        outcomes(last)
                    }
                    .font(.callout)
                }
                if let error { Text(error).font(.callout).foregroundStyle(Theme.bad) }
            }
            .padding(.vertical, 4)
        }
    }

    /// The confirm, in the app: what is reopened (mid-turn seats marked), then the button that does it.
    private func confirm(_ r: RebootBrief) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(RebootWording.shared.reopenConfirm(host: host.name, brief: r, whenLabel: relativeTime(iso: r.checkedAt))).font(.callout)
            ForEach(r.seats, id: \.title) { s in
                HStack(spacing: 6) {
                    Text(s.title)
                    if s.busy { Pill(text: "mid-turn", color: Theme.busy) }
                }
                .font(.callout)
            }
            ForEach(r.runners, id: \.self) { Text("runner \($0)").font(.callout) }
            HStack {
                Button("Cancel") { confirming = false }.disabled(busy)
                Button(RebootWording.shared.reopenAction(brief: r)) { reopen() }.buttonStyle(.borderedProminent).disabled(busy)
            }
        }
        .padding(10)
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private func result(_ d: ReopenDone) -> some View {
        if !d.ok {
            Text(RebootWording.shared.failed(d: d)).font(.callout).foregroundStyle(Theme.bad)
        } else if let r = d.result {
            Text(RebootWording.shared.reopened(r: r)).font(.callout)
            outcomes(r)
        }
    }

    private func outcomes(_ r: ReopenReport) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(Array(r.outcomes.enumerated()), id: \.offset) { _, o in
                Text(RebootWording.shared.outcome(o: o)).font(.caption)
                    .foregroundStyle(o.outcome == "failed" ? Theme.bad : o.outcome == "skipped" ? .secondary : .primary)
            }
        }
        .textSelection(.enabled)
    }

    private func prepare() {
        busy = true
        error = nil
        Task {
            do { summary = try await model.client.api.prepareReboot(host: host.name) } catch { self.error = error.message }
            busy = false
        }
    }

    private func reopen() {
        busy = true
        error = nil
        Task {
            do {
                reopenId = try await model.client.api.reopenSeats(host: host.name)
                confirming = false
            } catch {
                let k = (error as NSError).userInfo["KotlinException"]
                self.error = k is AlreadyReopening ? "A reopen of \(host.name) is running already." : k is NothingToReopen ? "\(host.name) has nothing to reopen now." : error.message
                confirming = false
            }
            busy = false
        }
    }

    /// The done event for [id]: already seen (a fast machine), or when it comes.
    private func waitForDone(_ id: String) async {
        for await all in model.client.reboots.done {
            if let d = all[id] {
                done = d
                return
            }
        }
    }
}
