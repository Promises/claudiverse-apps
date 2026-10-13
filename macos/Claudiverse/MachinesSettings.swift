import AppKit
import ClaudiverseCore
import SwiftUI

/**
 * The machines with a host agent, and the folders each allows seats in.
 * Removing a folder is always allowed (it only narrows). Adding one is the
 * machine's call: its agent accepts it only when its own config says
 * "allow_remote_roots": true, so Add shows only there. A new machine gets a
 * host token here for its agent config.
 */
struct MachinesSettings: View {
    @Environment(AppModel.self) private var model
    @State private var hosts: [ClaudiverseCore.Host] = []
    @State private var error: String?
    @State private var loading = true
    @State private var addingMachine = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let error { Text(error).foregroundStyle(Theme.bad).font(.callout) }
            List {
                ForEach(hosts, id: \.name) { host in
                    MachineSection(host: host, onChange: { Task { await load() } })
                }
            }
            .overlay { if loading && hosts.isEmpty { ProgressView() } }
            HStack {
                Button("Add a machine…") { addingMachine = true }
                Spacer()
                Button { Task { await load() } } label: { Label("Refresh", systemImage: "arrow.clockwise") }
            }
        }
        .padding()
        // Re-read while shown: after a folder changes, the machine's agent re-joins
        // and is briefly missing from the list; the next read has it back.
        .task {
            while !Task.isCancelled {
                await load()
                try? await Task.sleep(for: .seconds(5))
            }
        }
        .sheet(isPresented: $addingMachine) { AddMachineSheet(onDone: { Task { await load() } }) }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            hosts = try await model.client.api.hosts()
            error = nil
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            self.error = "Machines not read: \(error.message)"
        }
    }
}

struct MachineSection: View {
    let host: ClaudiverseCore.Host
    let onChange: () -> Void
    @Environment(AppModel.self) private var model
    @State private var newRoot = ""
    @State private var busy = false
    @State private var error: String?
    @State private var removing: String?
    @State private var sshTarget = ""
    @State private var restarting: RestartScope?

    var body: some View {
        Section {
            if host.roots.isEmpty { Text("No allowed folders: seats cannot be started here.").foregroundStyle(.secondary) }
            ForEach(host.roots, id: \.self) { root in
                HStack {
                    Image(systemName: "folder")
                    Text(root).textSelection(.enabled)
                    Spacer()
                    Button { removing = root } label: { Image(systemName: "minus.circle") }.buttonStyle(.borderless).help("Remove").disabled(busy)
                }
            }
            if host.remoteRoots {
                HStack {
                    TextField("Absolute path on \(host.name)", text: $newRoot).onSubmit(add)
                    if isThisMac {
                        Button("Choose…", action: choose)
                    }
                    Button("Add", action: add).disabled(busy || !newRoot.hasPrefix("/"))
                }
            } else {
                Text("Folders can be added from here only once this machine allows it: set \"allow_remote_roots\": true in its host agent's config. Removing is always possible.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if !TerminalOpener.isThisMac(host.name) {
                TextField("ssh", text: Binding(get: { sshTarget }, set: { sshTarget = $0; TerminalOpener.setSshTarget($0, for: host.name) }),
                          prompt: Text("user@address, for Open terminal from this Mac"))
            }
            if let error { Text(error).font(.callout).foregroundStyle(Theme.bad) }
        } header: {
            HStack {
                Circle().fill(host.online ? Theme.good : Theme.muted).frame(width: 8, height: 8)
                Text(host.name).font(.headline)
                Spacer()
                Text([host.online ? "online" : "offline", "\(host.seats) seats", host.spawn?.version.map { "Claude Code \($0)" },
                      host.version.map { "agent \($0)" }].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)
                if host.online && host.seats > 0 {
                    Button("Restart Idle Seats…") { restarting = RestartScope(host: host.name) }
                        .controlSize(.small)
                        .help("Restart this machine's idle seats onto its current Claude Code")
                }
            }
        }
        .onAppear { sshTarget = TerminalOpener.sshTarget(for: host.name) ?? "" }
        .sheet(item: $restarting) { RestartIdleSheet(scope: $0).environment(model) }
        .confirmationDialog("Remove \(removing ?? "") from \(host.name)?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
            Button("Remove", role: .destructive) { if let r = removing { remove(r) } }
        } message: {
            Text("New seats can no longer start there. Seats already running are not touched.")
        }
    }

    /// The machine is this Mac: its folders can be picked from a panel.
    private var isThisMac: Bool { TerminalOpener.isThisMac(host.name) }

    private func choose() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.prompt = "Allow"
        if panel.runModal() == .OK, let url = panel.url { newRoot = url.path }
    }

    private func add() {
        let path = newRoot.trimmingCharacters(in: .whitespaces)
        guard path.hasPrefix("/") else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                // Without duplicates: covered already adds nothing; folders inside it are removed.
                _ = try await model.client.api.allowFolder(host: host.name, path: path, current: host.roots)
                newRoot = ""
                error = nil
                // The agent re-joins with its new roots within a second or two.
                try? await Task.sleep(for: .seconds(2))
                onChange()
            } catch {
                self.error = switch error.httpStatus {
                case 403: "\(host.name) refused: it does not allow folders to be added remotely."
                case 404: "No such folder on \(host.name)."
                case 504: "\(host.name) did not answer (offline?)."
                case 400: "The path must be absolute."
                default: "Not added: \(error.message)"
                }
            }
        }
    }

    private func remove(_ root: String) {
        busy = true
        Task {
            defer { busy = false }
            do {
                _ = try await model.client.api.removeRoot(host: host.name, path: root)
                error = nil
                try? await Task.sleep(for: .seconds(2))
                onChange()
            } catch {
                self.error = error.httpStatus == 504 ? "\(host.name) did not answer (offline?)." : "Not removed: \(error.message)"
            }
        }
    }
}

/// A host token for a new machine's agent config, shown once: the server never returns it again.
struct AddMachineSheet: View {
    let onDone: () -> Void
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var token: HostToken?
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Add a machine").font(.title3.bold())
            if let token {
                Text("The host token for \(token.name). Put it in that machine's host agent config now: it is shown only this once.")
                HStack {
                    Text(token.token).font(.body.monospaced()).textSelection(.enabled).lineLimit(2)
                    Button {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(token.token, forType: .string)
                    } label: { Label("Copy", systemImage: "doc.on.doc") }
                }
                .padding(10)
                .background(Theme.codeBackground, in: RoundedRectangle(cornerRadius: 6))
                HStack {
                    Spacer()
                    Button("Done") {
                        onDone()
                        dismiss()
                    }
                    .keyboardShortcut(.defaultAction)
                }
            } else {
                Text("Its hostname, as the machine reports it (e.g. laptop.local).").font(.callout).foregroundStyle(.secondary)
                TextField("Hostname", text: $name).onSubmit(mint)
                if let error { Text(error).foregroundStyle(Theme.bad).font(.callout) }
                HStack {
                    Spacer()
                    Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                    Button("Create token", action: mint).keyboardShortcut(.defaultAction).disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .padding(20)
        .frame(width: 480)
    }

    private func mint() {
        let host = name.trimmingCharacters(in: .whitespaces)
        guard !host.isEmpty else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                token = try await model.client.api.hostToken(host: host)
            } catch {
                self.error = error.httpStatus == 400 ? "Not a valid machine name." : "No token: \(error.message)"
            }
        }
    }
}
