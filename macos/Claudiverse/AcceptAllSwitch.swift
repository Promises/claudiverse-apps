import ClaudiverseCore
import SwiftUI

/**
 * A seat's accept-all switch, as the web has it: on, the server answers every
 * permission prompt the seat relays with yes and puts the session in
 * accept-edits mode (plan approvals and questions still come here). Turning it
 * on takes a second click, on a red button that says what it does, in a
 * popover; a native dialog is easy to click through. When on, everyone sees it
 * in red; only an app or super sign-in can turn it off. [compact] (a board
 * tile) shows a small red mark alone. Nothing shows for a server without
 * seat settings.
 */
struct AcceptAllSwitch: View {
    let title: String
    var compact = false
    @Environment(AppModel.self) private var model
    /// nil until read, or when the server has no seat settings.
    @State private var on: Bool?
    @State private var confirming = false
    @State private var busy = false
    @State private var error: String?

    static let explain = "The server answers every permission prompt from this seat with yes, and puts it in accept-edits mode. Plan approvals and questions still come to you; deny rules still apply."

    var body: some View {
        content
            // Read on opening and every 30 s: it may be changed from the web or the phone.
            .task(id: title) {
                confirming = false
                while !Task.isCancelled {
                    await load()
                    try? await Task.sleep(for: .seconds(30))
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch AcceptAllSwitch.shows(on: on, canChange: model.canChangeSettings, compact: compact) {
        case .nothing:
            EmptyView()
        case .badge(let turnOff) where compact || !turnOff:
            // A tile, or a sign-in that cannot turn it off: the mark alone.
            Label("Accept all on", systemImage: "exclamationmark.shield.fill")
                .labelStyle(.titleAndIcon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Theme.bad)
                .help(Self.explain)
        case .badge:
            Menu {
                Button("Turn Off Accept All") { change(false) }.disabled(busy)
            } label: {
                Label("Accept all on", systemImage: "exclamationmark.shield.fill").labelStyle(.titleAndIcon)
            }
            .menuIndicator(.hidden)
            .buttonStyle(.borderedProminent)
            .tint(Theme.bad)
            .fixedSize()
            .help(error ?? Self.explain)
        case .arm:
            Button { confirming = true } label: { Label("Accept All…", systemImage: "shield") }
                .help("Accept all: have the server answer this seat's permission prompts with yes")
                .popover(isPresented: $confirming, arrowEdge: .bottom) { confirm }
        }
    }

    /// The second click: what it does, said on the button itself.
    private var confirm: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Accept all", systemImage: "exclamationmark.shield.fill").font(.headline).foregroundStyle(Theme.bad)
            Text(Self.explain).font(.callout).fixedSize(horizontal: false, vertical: true)
            if let error { Text(error).font(.caption).foregroundStyle(Theme.bad) }
            HStack {
                Button("Cancel") { confirming = false }.keyboardShortcut(.cancelAction)
                Spacer()
                Button("Yes to every prompt from this seat") { change(true) }
                    .buttonStyle(.borderedProminent).tint(Theme.bad)
            }
            .disabled(busy)
        }
        .padding(16)
        .frame(width: 340)
    }

    enum Shows: Equatable {
        case nothing
        /// On: the mark, and a way to turn it off when this sign-in may change it.
        case badge(turnOff: Bool)
        /// Off, and this sign-in may turn it on.
        case arm
    }

    /// What the switch shows: the mark to everyone when on; the way to turn it on only to an app or super sign-in.
    static func shows(on: Bool?, canChange: Bool, compact: Bool) -> Shows {
        switch on {
        case true?: .badge(turnOff: canChange && !compact)
        case false? where canChange && !compact: .arm
        default: .nothing
        }
    }

    private func load() async {
        do {
            on = try await model.client.api.seatSetting(title: title)?.acceptAll
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            // Unreadable now (offline, say): keep what was shown.
        }
    }

    private func change(_ next: Bool) {
        busy = true
        error = nil
        Task {
            do {
                on = try await model.client.api.setAcceptAll(title: title, on: next).acceptAll
                confirming = false
            } catch {
                self.error = error.message
            }
            busy = false
        }
    }
}
