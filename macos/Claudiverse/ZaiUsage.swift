import ClaudiverseCore
import SwiftUI

/**
 * Other plans' quota on the Pool screen, under each plan's own name: the Z.ai
 * (GLM) Coding Plan first, where a key is added, then OpenCode Go and any
 * other the server reads. Each key: its level, each quota window (credits
 * used of the limit where the plan gives them, and when it resets, or
 * "spent until …"), and why the last check failed if it did. A Mistral
 * account's API key and Vibe session are one card (core's zaiEntries): its two
 * allowances once, then a row per credential. The server reads them every
 * 5 minutes; the key itself never reaches the app. Adding, checking and
 * removing a key are for an app or super sign-in.
 */
struct ZaiSection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let keys = model.pool.zai
        VStack(alignment: .leading, spacing: 10) {
            if let keys {
                ForEach(ZaiSection.plans(keys), id: \.name) { plan in
                    Text(plan.name).font(.headline)
                    ForEach(ZaiSection.entries(plan.keys), id: \.id) { entry in
                        switch onEnum(of: entry.entry) {
                        case .single(let one): ZaiCard(key: one.key)
                        case .account(let account): ZaiAccountCard(account: account)
                        }
                    }
                    if plan.name == ZaiKey.companion.ZAI_PLAN {
                        if plan.keys.isEmpty { Text("No Z.ai key yet.").foregroundStyle(.secondary) }
                        if model.canChangeSettings { ZaiAddForm() }
                    }
                }
            } else if let error = model.pool.zaiError {
                Text("Z.ai Coding Plan").font(.headline)
                Text(error).foregroundStyle(Theme.bad)
            } else {
                Text("Z.ai Coding Plan").font(.headline)
                Text("This server shows no Z.ai keys yet.").foregroundStyle(.secondary)
            }
        }
    }

    /// The keys by plan: the Z.ai Coding Plan first, even with no key (a key is added there), then the others as served.
    static func plans(_ keys: [ZaiKey]) -> [(name: String, keys: [ZaiKey])] {
        let zai = ZaiKey.companion.ZAI_PLAN
        var names = [zai]
        for k in keys where !names.contains(k.providerName) { names.append(k.providerName) }
        return names.map { name in (name, keys.filter { $0.providerName == name }) }
    }

    /// A plan's cards (core's zaiEntries: one Mistral account's credentials together), each with a stable id.
    static func entries(_ keys: [ZaiKey]) -> [(id: String, entry: ZaiEntry)] {
        zaiEntries(keys: keys).map { e in
            switch onEnum(of: e) {
            case .single(let one): ("key:\(one.key.id)", e)
            case .account(let account): ("account:\(account.account)", e)
            }
        }
    }
}

/// One key: its plan, status, each window, when it was read, and Check usage and Remove.
struct ZaiCard: View {
    let key: ZaiKey

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(key.label).font(.title3.weight(.semibold))
                if let level = key.level { Text("\(level) plan").foregroundStyle(.secondary) }
                Spacer()
                ZaiKeyActions(key: key)
            }
            ZaiLastError(key: key)
            ZaiWindows(windows: key.windows) { zaiWindowLabel(w: $0) }
            Text("Checked \(relativeTime(iso: key.checkedAt))").font(.caption).foregroundStyle(.secondary)
        }
        .padding(14)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
    }

    /// Green, then yellow from 75%, red from 90%: as the Anthropic accounts' bars.
    static func tint(percent: Double) -> Color {
        percent >= 90 ? Theme.bad : percent >= 75 ? Theme.busy : Theme.good
    }
}

/**
 * One Mistral account: its API and Vibe allowances once (every credential of
 * it reads the same two), then each credential (an API key or a Vibe session)
 * as a row with its status, priority, when it was read, and its own actions.
 */
struct ZaiAccountCard: View {
    let account: ZaiEntryAccount

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(account.account).font(.title3.weight(.semibold))
            ZaiWindows(windows: account.windows) { zaiAccountWindowLabel(w: $0) }
            ForEach(account.keys, id: \.id) { key in
                Divider()
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(key.label).fontWeight(.medium)
                        Text(ZaiAccountCard.detail(key)).foregroundStyle(.secondary)
                        Spacer()
                        ZaiKeyActions(key: key)
                    }
                    ZaiLastError(key: key)
                    Text("Checked \(relativeTime(iso: key.checkedAt))").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .padding(14)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
    }

    /// "Vibe session · priority 5": what the credential is and the order the proxy tries it in.
    static func detail(_ key: ZaiKey) -> String {
        [key.kind, key.priority.map { "priority \($0)" }].compactMap { $0 }.joined(separator: " · ")
    }
}

/// Each quota window as a bar: its name, credits used of the limit where the plan gives them, and when it resets.
struct ZaiWindows: View {
    let windows: [ZaiWindow]
    let label: (ZaiWindow) -> String

    var body: some View {
        if windows.isEmpty { Text("No quota read yet.").font(.callout).foregroundStyle(.secondary) }
        ForEach(windows, id: \ZaiWindow.window) { (w: ZaiWindow) in
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(label(w)).font(.callout.weight(.medium))
                    Spacer()
                    Text(zaiWindowLine(w: w, now: nowMillis))
                        .font(.caption).foregroundStyle(w.spent ? Theme.bad : .secondary)
                }
                if let pct = w.percent?.doubleValue {
                    ProgressView(value: min(pct, 100), total: 100).tint(ZaiCard.tint(percent: pct))
                }
            }
        }
    }
}

/// Why a key's last check failed, if it did.
struct ZaiLastError: View {
    let key: ZaiKey

    var body: some View {
        if let reason = key.lastError {
            Text(key.ok ? "Last error: \(reason)" : "\(reason). The numbers shown are the last good reading.")
                .font(.callout).foregroundStyle(key.ok ? .secondary : Theme.bad)
        }
    }
}

/// A key's status, and for an app or super sign-in its Check Usage and Remove (confirmed); what went wrong under them.
struct ZaiKeyActions: View {
    let key: ZaiKey
    @Environment(AppModel.self) private var model
    @State private var busy = false
    @State private var error: String?
    @State private var confirmingRemove = false

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            HStack {
                Pill(text: key.status, color: key.ok ? Theme.good : Theme.bad)
                if model.canChangeSettings {
                    Button(busy ? "Checking…" : "Check Usage") { act { _ = try await model.client.api.checkZaiKey(id: key.id) } }.disabled(busy)
                    Button("Remove…", role: .destructive) { confirmingRemove = true }.disabled(busy)
                }
            }
            if let error { Text(error).font(.callout).foregroundStyle(Theme.bad) }
        }
        .confirmationDialog(ZaiKeyActions.removeTitle(key), isPresented: $confirmingRemove) {
            Button("Remove", role: .destructive) { act { try await model.client.api.deleteZaiKey(id: key.id) } }
        } message: {
            Text("Its seats keep running; only the quota display goes.")
        }
    }

    /// "Remove the Vibe session “vibe-2”?"; a key of another plan by its plan's name.
    static func removeTitle(_ key: ZaiKey) -> String {
        "Remove the \(key.kind ?? "\(key.providerName) key") “\(key.label)”?"
    }

    private func act(_ run: @escaping () async throws -> Void) {
        busy = true
        error = nil
        Task {
            do {
                try await run()
                await model.pool.loadZai(model.client.api)
            } catch {
                self.error = error.message
            }
            busy = false
        }
    }
}

/**
 * Add a key: the server checks it with Z.ai first, then stores it encrypted.
 * The key's field exists only while adding (a button opens it, Cancel or a
 * stored key closes it and forgets what was typed): a secure field on screen
 * can turn on Secure Keyboard Entry for the whole Mac, which stops other
 * apps' hotkeys.
 */
struct ZaiAddForm: View {
    @Environment(AppModel.self) private var model
    @State private var adding = false
    @State private var label = ""
    @State private var key = ""
    @State private var busy = false
    @State private var error: String?
    @FocusState private var keyFocused: Bool

    var body: some View {
        Group {
            if adding {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Add a Z.ai key").font(.callout.weight(.medium))
                    HStack {
                        TextField("Name", text: $label, prompt: Text("coding plan")).frame(maxWidth: 200)
                        SecureField("API key", text: $key)
                            .focused($keyFocused)
                        Button("Cancel") { close() }.disabled(busy)
                        Button(busy ? "Checking…" : "Add Key") { add() }
                            .disabled(busy || label.trimmingCharacters(in: .whitespaces).isEmpty || key.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                    if let error { Text(error).font(.callout).foregroundStyle(Theme.bad) }
                }
                .padding(14)
                .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 12))
            } else {
                Button("Add Z.ai Key…") { adding = true }
            }
        }
        #if CLAUDIVERSE_HARNESS
        .onReceive(NotificationCenter.default.publisher(for: Harness.focusSecret)) { _ in
            adding = true
            DispatchQueue.main.async { keyFocused = true }
        }
        #endif
    }

    /// The form goes, and what was typed with it.
    private func close() {
        keyFocused = false
        label = ""
        key = ""
        error = nil
        adding = false
    }

    private func add() {
        busy = true
        error = nil
        let name = label.trimmingCharacters(in: .whitespaces), secret = key.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                _ = try await model.client.api.addZaiKey(label: name, key: secret)
                close()
                await model.pool.loadZai(model.client.api)
            } catch {
                // The server's sentence ("Z.ai refused the key (401)"); never the key.
                self.error = error.message
            }
            busy = false
        }
    }
}
