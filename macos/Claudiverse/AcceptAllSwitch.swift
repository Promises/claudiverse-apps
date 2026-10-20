import ClaudiverseCore
import SwiftUI

/**
 * A seat's accept-all switch, as the web has it: on, the server answers every
 * permission prompt the seat relays with yes and puts the session in
 * accept-edits mode (plan approvals and questions still come here). Turning it
 * on takes a second click, on a red button that says what it does; a native
 * dialog is easy to click through. When on, everyone sees a red badge; only an
 * app or super sign-in gets "Turn off". [compact] (a board tile) shows the
 * badge alone. Nothing shows for a server without seat settings.
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

    var body: some View {
        HStack(spacing: 6) {
            switch AcceptAllSwitch.shows(on: on, canChange: model.canChangeSettings, compact: compact) {
            case .nothing:
                EmptyView()
            case .badge(let turnOff):
                Text("Accept all: ON")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 7).padding(.vertical, 2)
                    .background(Theme.bad, in: RoundedRectangle(cornerRadius: 4))
                    .help("The server answers every permission prompt from this seat with yes. Plan approvals and questions still come to you.")
                if turnOff {
                    Button("Turn off") { change(false) }.disabled(busy)
                }
            case .arm:
                if confirming {
                    Button("Yes to every prompt from this seat") { change(true) }
                        .buttonStyle(.borderedProminent).tint(Theme.bad)
                        .disabled(busy)
                    Button("Cancel") { confirming = false }.disabled(busy)
                } else {
                    Button("Accept all…") { confirming = true }
                        .help("Have the server answer this seat's permission prompts with yes")
                }
            }
            if let error { Text(error).font(.caption).foregroundStyle(Theme.bad).lineLimit(1) }
        }
        .controlSize(.small)
        // Read on opening and every 30 s: it may be changed from the web or the phone.
        .task(id: title) {
            confirming = false
            while !Task.isCancelled {
                await load()
                try? await Task.sleep(for: .seconds(30))
            }
        }
    }

    enum Shows: Equatable {
        case nothing
        /// On: the badge, and "Turn off" when this sign-in may change it.
        case badge(turnOff: Bool)
        /// Off, and this sign-in may turn it on.
        case arm
    }

    /// What the switch shows: the badge to everyone when on; the way to turn it on only to an app or super sign-in.
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
