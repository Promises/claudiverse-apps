import ClaudiverseCore
import SwiftUI

struct SettingsView: View {
    /// The open tab, kept: picking a theme redraws the window from scratch, which would open the first tab again.
    @AppStorage("settingsTab") private var tab = "account"

    var body: some View {
        TabView(selection: $tab) {
            AccountSettings().tabItem { Label("Account", systemImage: "person.crop.circle") }.tag("account")
            MachinesSettings().tabItem { Label("Machines", systemImage: "desktopcomputer") }.tag("machines")
            AutoRestartSettings().tabItem { Label("Restarts", systemImage: "arrow.clockwise") }.tag("restarts")
            AppearanceSettings().tabItem { Label("Appearance", systemImage: "paintpalette") }.tag("appearance")
            TokenSettings().tabItem { Label("Tokens", systemImage: "key") }.tag("tokens")
        }
        .frame(width: 640, height: 520)
    }
}

/// The app's look: the system's (Automatic), Light, Dark, or Nord. Kept on this Mac.
struct AppearanceSettings: View {
    @AppStorage(ThemeChoice.key) private var theme = ThemeChoice.system.rawValue

    var body: some View {
        Form {
            Picker("Theme", selection: $theme) {
                ForEach(ThemeChoice.allCases) { Text($0.label).tag($0.rawValue) }
            }
            .pickerStyle(.radioGroup)
            Text("Automatic follows macOS, light or dark. Nord is dark, in the Nord palette.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .formStyle(.grouped)
        .themedList()
    }
}

/// Who is signed in, where, and signing out (which revokes this Mac's token).
struct AccountSettings: View {
    @Environment(AppModel.self) private var model
    @State private var signingOut = false

    var body: some View {
        Form {
            LabeledContent("Server", value: model.serverURL.isEmpty ? "—" : model.serverURL)
            LabeledContent("Signed in as", value: model.email ?? "—")
            LabeledContent("This Mac", value: AppModel.clientName())
            NotificationsOffNote(notifier: model.notifier)
            if model.phase == .signedIn {
                Button("Sign out", role: .destructive) {
                    signingOut = true
                    Task {
                        await model.signOut()
                        signingOut = false
                    }
                }
                .disabled(signingOut)
            }
        }
        .formStyle(.grouped)
        .themedList()
    }
}

/**
 * Every token on the server, without values. Revoke one (a lost phone's, a
 * retired machine's); the fleet's shared seat token can only be revoked on the
 * server, which says so.
 */
struct TokenSettings: View {
    @Environment(AppModel.self) private var model
    @State private var tokens: [TokenInfo] = []
    @State private var error: String?
    @State private var revoking: TokenInfo?

    var body: some View {
        VStack(alignment: .leading) {
            if let error { Text(error).foregroundStyle(Theme.bad).font(.callout) }
            Table(tokens) {
                TableColumn("Kind") { t in Text(t.host.map { "host · \($0)" } ?? t.context) }
                TableColumn("Client") { t in Text(t.label ?? "minted on the server").foregroundStyle(t.label == nil ? .secondary : .primary) }
                TableColumn("Since") { t in Text(relativeTime(iso: t.insertedAt)) }
                TableColumn("") { t in
                    if t.current {
                        Text("this Mac").foregroundStyle(.secondary)
                    } else {
                        Button("Revoke…") { revoking = t }
                    }
                }
                .width(90)
            }
        }
        .padding()
        .task { await load() }
        .confirmationDialog("Revoke \(revoking.map(Self.describe) ?? "this token")?", isPresented: Binding(get: { revoking != nil }, set: { if !$0 { revoking = nil } })) {
            Button("Revoke", role: .destructive) {
                if let t = revoking { Task { await revoke(t) } }
            }
        } message: {
            Text("Whatever uses it is signed out at once.")
        }
    }

    /// "the host token for linux-box", "the super token for Sams MacBook Pro": the dialog names what it revokes.
    static func describe(_ t: TokenInfo) -> String {
        let kind = t.host.map { _ in "host" } ?? t.context
        let owner = t.host ?? t.label
        return owner.map { "the \(kind) token for \($0)" } ?? "the \(kind) token minted on the server \(relativeTime(iso: t.insertedAt))"
    }

    private func load() async {
        do {
            tokens = try await model.client.api.tokens()
            error = nil
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            self.error = error.httpStatus == 403 ? "Only a super token can list tokens." : "Tokens not read: \(error.message)"
        }
    }

    private func revoke(_ t: TokenInfo) async {
        do {
            try await model.client.api.revokeToken(id: t.id)
            await load()
        } catch {
            self.error = error.httpStatus == 409
                ? "That one may be the fleet's shared seat token: it can only be revoked on the server."
                : "Not revoked: \(error.message)"
        }
    }
}

extension TokenInfo: @retroactive Identifiable {}
