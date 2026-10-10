import ClaudiverseCore
import SwiftUI

/// The menu bar panel: seats asking for the operator, one click from opening.
struct MenuBarView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Claudiverse").font(.headline)
                Spacer()
                Circle().fill(model.socketConnected ? Theme.good : Theme.muted).frame(width: 8, height: 8)
                Text(model.socketConnected ? "live" : "offline").font(.caption).foregroundStyle(.secondary)
            }
            Divider()
            NotificationsOffNote(notifier: model.notifier)
            switch model.phase {
            case .signedIn:
                if model.needsYou.isEmpty {
                    Text("No seat is asking for you.").foregroundStyle(.secondary).font(.callout)
                } else {
                    ForEach(model.needsYou.values.sorted { ($0.updatedAt ?? "") > ($1.updatedAt ?? "") }, id: \.id) { r in
                        Button { model.open(seatId: r.sessionId) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack {
                                    Text(r.seatTitle).fontWeight(.semibold)
                                    Spacer()
                                    Text(relativeTime(iso: r.updatedAt ?? r.insertedAt)).font(.caption2).foregroundStyle(.secondary)
                                }
                                Text(r.summary).font(.callout).foregroundStyle(.secondary).lineLimit(3)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            case .signedOut:
                Text("Not signed in.").foregroundStyle(.secondary)
            case .starting:
                ProgressView().controlSize(.small)
            }
            Divider()
            HStack {
                Button("Open Claudiverse") {
                    openWindow(id: "main")
                    NSApp.activate()
                }
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
        }
        .padding(14)
        .frame(width: 340)
        .onAppear { if model.openMainWindow == nil { model.openMainWindow = { openWindow(id: "main") } } }
    }
}

/// Says so when macOS has this app's notifications switched off, with the way to switch them on.
struct NotificationsOffNote: View {
    let notifier: Notifier

    var body: some View {
        if notifier.allowed == false {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: "bell.slash").foregroundStyle(Theme.attention)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Notifications are off for Claudiverse: seats asking for you will not alert you.").font(.callout)
                    Button("Open Notification Settings") { Notifier.openSettings() }
                }
            }
            .onAppear { notifier.recheck() }
        }
    }
}
