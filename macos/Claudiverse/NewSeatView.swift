import ClaudiverseCore
import SwiftUI

/// The New seat sheet's state, from core's NewSeatSession.
@MainActor
@Observable
final class NewSeatModel {
    let session: NewSeatSession
    private(set) var state: NewSeatState
    private var watching: Task<Void, Never>?

    init(client: ClaudiverseClient) {
        session = client.openNewSeat()
        state = session.ui.value
        watching = Task { [weak self, session] in
            for await s in session.ui { self?.state = s }
        }
    }

    func close() {
        watching?.cancel()
        session.close()
    }
}

/**
 * Start a seat on a machine: pick the machine, a folder (a recent one, or
 * browse inside its allowed folders), the title (the folder's name until
 * edited), the permission mode and an optional first prompt. Once the server
 * accepts, the sheet closes and the banner follows the outcome.
 */
struct NewSeatView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var model: NewSeatModel?

    var body: some View {
        Group {
            if let model { content(model) } else { ProgressView() }
        }
        .frame(width: 640, height: 640)
        .onAppear { model = NewSeatModel(client: app.client) }
        .onDisappear { model?.close() }
    }

    @ViewBuilder
    private func content(_ model: NewSeatModel) -> some View {
        let s = model.state
        let session = model.session
        VStack(alignment: .leading, spacing: 0) {
            Form {
                Section("Machine") {
                    if s.hostsLoading {
                        ProgressView().controlSize(.small)
                    } else if let error = s.hostsError {
                        Text(error).foregroundStyle(Theme.bad)
                    } else if s.hosts.isEmpty {
                        Text("No machine has a host agent yet.").foregroundStyle(.secondary)
                    } else {
                        Picker("Machine", selection: Binding(get: { s.host ?? "" }, set: { session.pickHost(name: $0) })) {
                            ForEach(s.hosts, id: \.name) { h in
                                Text("\(h.name)\(h.online ? "" : " (offline)") · \(h.seats) seats").tag(h.name)
                                    .disabled(!h.online)
                            }
                        }
                        .labelsHidden()
                    }
                }

                if s.host != nil {
                    Section {
                        Picker("Folder", selection: Binding(get: { s.tab }, set: { session.setTab(tab: $0) })) {
                            Text("Recent").tag(FolderTab.recent)
                            Text("Browse").tag(FolderTab.browse)
                        }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                        if s.roots.isEmpty {
                            Text("This machine allows no folders yet. Add one in Settings › Machines, or in the machine's own agent config.")
                                .font(.callout).foregroundStyle(.secondary)
                        }
                        switch s.tab {
                        case .recent: RecentFolders(state: s, session: session)
                        case .browse: FolderBrowser(state: s, session: session)
                        }
                    } header: {
                        Text("Folder")
                    } footer: {
                        Text(s.path.map { "Starts in \($0)" } ?? "Pick a folder inside the machine's allowed folders.")
                    }

                    Section("Seat") {
                        TextField("Title", text: Binding(get: { s.title }, set: { session.setTitle(title: $0) }))
                        Picker("Permission mode", selection: Binding(get: { s.mode }, set: { session.setMode(mode: $0) })) {
                            ForEach(SessionChoices.shared.modes, id: \.id) { Text($0.label).tag($0.id) }
                        }
                        TextField("First prompt (optional)", text: Binding(get: { s.prompt }, set: { session.setPrompt(prompt: $0) }), axis: .vertical)
                            .lineLimit(2...6)
                    }
                }
            }
            .formStyle(.grouped)
            .themedList()

            HStack {
                if let error = s.spawnError { Text(error).foregroundStyle(Theme.bad).font(.callout).lineLimit(2) }
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                Button {
                    Task { if try await session.spawn().boolValue { dismiss() } }
                } label: {
                    if s.spawning { ProgressView().controlSize(.small) } else { Text("Start seat") }
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(!s.canSpawn)
            }
            .padding(16)
        }
    }
}

/// Folders past seats on this machine worked in; ones outside its allowed folders are shown but cannot be picked.
struct RecentFolders: View {
    let state: NewSeatState
    let session: NewSeatSession

    var body: some View {
        if state.recents.isEmpty {
            Text("No recent folders on this machine.").foregroundStyle(.secondary)
        } else {
            ForEach(Array(state.recents.enumerated()), id: \.offset) { index, r in
                let allowed = state.allowed(path: r.path)
                Button { session.pickRecent(index: Int32(index)) } label: {
                    HStack {
                        Image(systemName: index == Int(state.recentIndex) && allowed ? "largecircle.fill.circle" : "circle")
                            .foregroundStyle(allowed ? Theme.accent : .secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(r.path).lineLimit(1).truncationMode(.head)
                            Text(allowed
                                ? [relativeTime(iso: r.lastUsedAt), r.seatsRunning > 0 ? "\(r.seatsRunning) running" : nil].compactMap { $0?.nilIfEmpty }.joined(separator: " · ")
                                : "Outside this machine's allowed folders")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!allowed)
                .opacity(allowed ? 1 : 0.5)
            }
        }
    }
}

/// Inside the machine's allowed folders: breadcrumbs from the root holding the folder, then its subfolders.
struct FolderBrowser: View {
    let state: NewSeatState
    let session: NewSeatSession

    var body: some View {
        let path = state.listing?.path ?? ""
        HStack(spacing: 4) {
            Button("Allowed folders") { session.browse(path: "") }.buttonStyle(.link)
            ForEach(crumbs(path: path, roots: state.roots), id: \.path) { c in
                Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.secondary)
                Button(c.label) { session.browse(path: c.path) }.buttonStyle(.link).lineLimit(1)
            }
        }
        if let error = state.listingError {
            Text(error).foregroundStyle(Theme.bad)
        } else if let listing = state.listing {
            // At a root the parent is the list of allowed folders (""); that list has no Up.
            if !path.isEmpty {
                Button { session.browse(path: listing.parent ?? "") } label: {
                    Label("Up", systemImage: "arrow.up").frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            if listing.dirs.isEmpty {
                Text(path.isEmpty ? "No allowed folders." : "No subfolders.").foregroundStyle(.secondary)
            }
            ForEach(listing.dirs, id: \.self) { dir in
                // The roots list holds full paths; elsewhere a name inside the folder.
                let target = path.isEmpty ? dir : (path.hasSuffix("/") ? path + dir : path + "/" + dir)
                Button { session.browse(path: target) } label: {
                    Label(dir, systemImage: "folder").frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            if listing.truncated { Text("More folders than shown.").font(.caption).foregroundStyle(.secondary) }
        } else {
            ProgressView().controlSize(.small)
        }
    }
}
