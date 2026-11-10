import ClaudiverseCore
import SwiftUI

/**
 * Z.ai (GLM) Coding Plan keys on the Pool screen: each key's plan, each quota
 * window (credits used of the limit, and when it resets, or "spent until …"),
 * and why the last check failed if it did. The server reads them every
 * 5 minutes; the key itself never reaches the app. Adding, checking and
 * removing a key are for an app or super sign-in.
 */
struct ZaiSection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let keys = model.pool.zai
        VStack(alignment: .leading, spacing: 10) {
            Text("Z.ai Coding Plan").font(.headline)
            if let keys {
                if keys.isEmpty { Text("No Z.ai key yet.").foregroundStyle(.secondary) }
                ForEach(keys, id: \.id) { key in ZaiCard(key: key) }
                if model.canChangeSettings { ZaiAddForm() }
            } else if let error = model.pool.zaiError {
                Text(error).foregroundStyle(Theme.bad)
            } else {
                Text("This server shows no Z.ai keys yet.").foregroundStyle(.secondary)
            }
        }
    }
}

/// One key: its plan, status, each window, when it was read, and Check usage and Remove.
struct ZaiCard: View {
    let key: ZaiKey
    @Environment(AppModel.self) private var model
    @State private var busy = false
    @State private var error: String?
    @State private var confirmingRemove = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(key.label).font(.title3.weight(.semibold))
                if let level = key.level { Text("\(level) plan").foregroundStyle(.secondary) }
                Spacer()
                Pill(text: key.status, color: key.ok ? Theme.good : Theme.bad)
                if model.canChangeSettings {
                    Button(busy ? "Checking…" : "Check Usage") { act { _ = try await model.client.api.checkZaiKey(id: key.id) } }.disabled(busy)
                    Button("Remove…", role: .destructive) { confirmingRemove = true }.disabled(busy)
                }
            }
            if let reason = key.lastError {
                Text(key.ok ? "Last error: \(reason)" : "\(reason). The numbers below are the last good reading.")
                    .font(.callout).foregroundStyle(key.ok ? .secondary : Theme.bad)
            }
            if key.windows.isEmpty { Text("No quota read yet.").font(.callout).foregroundStyle(.secondary) }
            ForEach(key.windows, id: \ZaiWindow.window) { (w: ZaiWindow) in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(zaiWindowLabel(w: w)).font(.callout.weight(.medium))
                        Spacer()
                        Text(zaiWindowLine(w: w, now: nowMillis))
                            .font(.caption).foregroundStyle(w.spent ? Theme.bad : .secondary)
                    }
                    if let pct = w.percent?.doubleValue {
                        ProgressView(value: min(pct, 100), total: 100).tint(ZaiCard.tint(percent: pct))
                    }
                }
            }
            Text("Checked \(relativeTime(iso: key.checkedAt))").font(.caption).foregroundStyle(.secondary)
            if let error { Text(error).font(.callout).foregroundStyle(Theme.bad) }
        }
        .padding(14)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
        .confirmationDialog("Remove the Z.ai key “\(key.label)”?", isPresented: $confirmingRemove) {
            Button("Remove Key", role: .destructive) { act { try await model.client.api.deleteZaiKey(id: key.id) } }
        } message: {
            Text("Its seats keep running; only the quota display goes.")
        }
    }

    /// Green, then yellow from 75%, red from 90%: as the Anthropic accounts' bars.
    static func tint(percent: Double) -> Color {
        percent >= 90 ? Theme.bad : percent >= 75 ? Theme.busy : Theme.good
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

/// Add a key: the server checks it with Z.ai first, then stores it encrypted; the field is cleared after.
struct ZaiAddForm: View {
    @Environment(AppModel.self) private var model
    @State private var label = ""
    @State private var key = ""
    @State private var busy = false
    @State private var error: String?
    @FocusState private var keyFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Add a Z.ai key").font(.callout.weight(.medium))
            HStack {
                TextField("Name", text: $label, prompt: Text("coding plan")).frame(maxWidth: 200)
                SecureField("API key", text: $key)
                    .focused($keyFocused)
                Button(busy ? "Checking…" : "Add Key") { add() }
                    .disabled(busy || label.trimmingCharacters(in: .whitespaces).isEmpty || key.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            if let error { Text(error).font(.callout).foregroundStyle(Theme.bad) }
        }
        .padding(14)
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 12))
        #if CLAUDIVERSE_HARNESS
        .onReceive(NotificationCenter.default.publisher(for: Harness.focusSecret)) { _ in keyFocused = true }
        #endif
    }

    private func add() {
        busy = true
        error = nil
        let name = label.trimmingCharacters(in: .whitespaces), secret = key.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                _ = try await model.client.api.addZaiKey(label: name, key: secret)
                label = ""
                key = ""
                await model.pool.loadZai(model.client.api)
            } catch {
                // The server's sentence ("Z.ai refused the key (401)"); never the key.
                self.error = error.message
            }
            busy = false
        }
    }
}
