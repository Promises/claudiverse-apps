import ClaudiverseCore
import SwiftUI

@main
struct ClaudiverseApp: App {
    @State private var model: AppModel

    init() {
        // Before the model reads them: the preferences an install under the old bundle id kept.
        Preferences.adoptOldBundleId()
        _model = State(initialValue: AppModel())
    }

    var body: some Scene {
        Window("Claudiverse", id: "main") {
            RootView()
                .background(ListedInWindowsMenu())
                .imageViewer()
                .environment(model)
                .themed()
        }
        .defaultSize(width: 1180, height: 780)

        // One window per seat, opened by double-clicking it in the list; opening it again brings that window forward.
        WindowGroup("Seat", id: "seat", for: Int64.self) { $seatId in
            if let seatId {
                SeatDetailView(seatId: seatId)
                    .imageViewer()
                    .environment(model)
                    .themed()
                    .frame(minWidth: 520, minHeight: 420)
            }
        }
        .defaultSize(width: 760, height: 820)

        // A fleet's board in a window of its own, opened from the board's toolbar or its fleet in the seat list.
        WindowGroup("Fleet", id: "board", for: String.self) { $fleet in
            if let fleet {
                FleetBoardView(fleet: fleet, inOwnWindow: true)
                    .imageViewer()
                    .environment(model)
                    .themed()
                    .frame(minWidth: 640, minHeight: 480)
            }
        }
        .defaultSize(width: 1280, height: 900)

        // Keeps the app, and its watch on seats asking for the operator, running with the window closed.
        MenuBarExtra {
            MenuBarView().environment(model).themed()
        } label: {
            Image(systemName: model.needsYou.isEmpty ? "sparkles" : "exclamationmark.bubble.fill")
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView().environment(model).themed()
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Group {
            switch model.phase {
            case .starting: ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .signedOut: SignInView()
            case .signedIn: MainView()
            }
        }
        .onAppear {
            model.openMainWindow = { openWindow(id: "main") }
            model.openSeatWindow = { openWindow(id: "seat", value: $0) }
        }
    }
}

/**
 * Lists its window in the Window menu and the Dock icon's menu. SwiftUI
 * leaves a single-instance Window scene (the main window) out of both, so
 * the Dock showed seat and board windows but not the main one.
 */
private struct ListedInWindowsMenu: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView { Probe() }
    func updateNSView(_ view: NSView, context: Context) {}

    private final class Probe: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard let window else { return }
            window.isExcludedFromWindowsMenu = false
            // SwiftUI may set it again as it sets the window up; once more after that.
            DispatchQueue.main.async { window.isExcludedFromWindowsMenu = false }
        }
    }
}
