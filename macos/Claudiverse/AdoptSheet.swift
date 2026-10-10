import ClaudiverseCore
import SwiftUI

/**
 * Adopt a seat someone started by hand: its machine stops exactly the Claude
 * Code process running it and starts the same conversation again from a
 * profile, so the app can stop, start and restart it from then on.
 *
 * The sheet asks the server what adopting would do (the process found, and
 * a stopped profile of the seat's name or suggested fields for a new one),
 * says it plainly, and lets the operator edit the profile before confirming.
 */
struct AdoptSheet: View {
    let seat: Seat
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var preview: AdoptPreview?
    @State private var draft = ProfileDraft(ProfileDraft.new(machine: ""))
    @State private var hosts: [ClaudiverseCore.Host] = []
    @State private var errors: [String: [String]] = [:]
    @State private var failure: String?
    @State private var loading = true
    @State private var adopting = false
    /// The operator confirmed a match by tmux name and folder, not by conversation.
    @State private var confirmedMatch = false
    /// Stop it even mid-turn.
    @State private var force = false
    /// The machine looked on for a seat whose row has none: this Mac, as the server names its agent.
    @State private var guessedHost: String?

    var body: some View {
        VStack(spacing: 0) {
            Form {
                if seat.host == nil, !loading {
                    Section {
                        Label(guessedHost.map { "Its machine isn't recorded, so this Mac (\($0)) was looked on." }
                              ?? "Its machine isn't recorded, and this Mac has no agent to look on.", systemImage: "questionmark.circle")
                            .foregroundStyle(.secondary)
                    }
                }
                if loading {
                    ProgressView("Asking \(seat.host ?? "this Mac") for the seat's process…").frame(maxWidth: .infinity)
                } else if let preview {
                    happens(preview)
                    if preview.canAdopt {
                        ProfileFieldsForm(draft: $draft, name: nil, fixedName: preview.existing?.name ?? seat.title,
                                          machineFixed: true, hosts: hosts, errors: errors)
                    }
                }
            }
            .formStyle(.grouped)
            Divider()
            HStack {
                // Here, beside the buttons, where the eye is after pressing Adopt: below the form it was out of view.
                if let failure {
                    Text(failure).foregroundStyle(Theme.bad).font(.callout).lineLimit(3).textSelection(.enabled)
                }
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }.keyboardShortcut(.cancelAction)
                Button(adopting ? "Adopting…" : "Adopt") { Task { await adopt() } }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!ready)
            }
            .padding(12)
        }
        // Fits a small window: the form scrolls, the buttons stay in view.
        .frame(minWidth: 520, idealWidth: 620, maxWidth: 720, minHeight: 320, idealHeight: 680, maxHeight: 900)
        .task { await load() }
    }

    private var ready: Bool {
        guard let preview, preview.canAdopt, let process = preview.process, !adopting else { return false }
        if process.byTmuxName && !confirmedMatch { return false }
        if process.isBusy && !force { return false }
        return true
    }

    // MARK: what will happen

    @ViewBuilder
    private func happens(_ p: AdoptPreview) -> some View {
        Section("What will happen") {
            if let blocked = p.blocked {
                Label(blocked, systemImage: "nosign").foregroundStyle(Theme.bad)
                // A seat started by hand has no folder in its row: the server sends its process's.
                if let host = targetHost, let folder = p.folder ?? seat.workingDirectory, !Self.inside(folder, host.roots) {
                    // The agent re-joins with its new roots within a second or two; ask again after.
                    AllowFolderView(host: host, folder: folder) { Task { try? await Task.sleep(for: .seconds(2)); await load() } }
                }
            } else if let process = p.process {
                Text(Self.processLine(process, host: seat.host ?? guessedHost))
                if process.byTmuxName {
                    Toggle(isOn: $confirmedMatch) {
                        Text("Matched by its tmux session's name and folder, not by its conversation: this is the right process")
                    }
                    .foregroundStyle(Theme.attention)
                }
                if process.isBusy {
                    Toggle("It is mid-turn: stop it anyway (the turn is lost)", isOn: $force).foregroundStyle(Theme.attention)
                }
                Text(Self.stopLine(process)).font(.callout)
                if let existing = p.existing {
                    if existing.canStop {
                        Label("Profile \(existing.name) is running already: stop it first.", systemImage: "nosign").foregroundStyle(Theme.bad)
                    } else {
                        Text("It is started from the existing profile \(existing.name) (revision \(existing.revision)), with the settings below.").font(.callout)
                    }
                } else {
                    Text("A new profile named \(seat.title) is made with the settings below.").font(.callout)
                    // A seat does not report its permission mode, so the suggestion cannot know it.
                    Label("Check the permission mode below: a seat doesn't report its own, so it is suggested as \(ProfileEditor.modeLabel(p.suggested?.permissionMode ?? "default")).",
                          systemImage: "exclamationmark.triangle").foregroundStyle(Theme.attention)
                }
                if let change = runtimeChange(p, process) {
                    Label(change, systemImage: "arrow.triangle.2.circlepath").foregroundStyle(Theme.attention)
                }
            } else {
                Label("No Claude Code process for this seat was found on \(seat.host ?? "its machine").", systemImage: "nosign").foregroundStyle(Theme.bad)
            }
        }
    }

    /// The machine the seat would start on: its own, or this Mac when its row has none.
    private var targetHost: ClaudiverseCore.Host? {
        let name = seat.host ?? guessedHost
        return hosts.first { $0.name == name }
    }

    /// Whether [folder] is one of [roots] or inside one.
    static func inside(_ folder: String, _ roots: [String]) -> Bool {
        roots.contains { ClaudiverseCore.isInside(folder: normalFolder(path: folder), root: normalFolder(path: $0)) }
    }

    /// "Claude Code 2.1.286, process 87920, in tmux session ipsum-admin; idle"
    static func processLine(_ p: AdoptProcess, host: String?) -> String {
        let place = p.tmux.map { "in tmux session \($0.split(separator: ":").first.map(String.init) ?? $0)" } ?? "in a terminal tab (not tmux)"
        let version = p.version.map { "Claude Code \($0), " } ?? ""
        return "\(version)process \(p.pid) on \(host ?? "its machine"), \(place); \(p.isBusy ? "mid-turn" : "idle")."
    }

    static func stopLine(_ p: AdoptProcess) -> String {
        if p.tmux == nil {
            return "That process is stopped and its conversation continues in tmux under the machine's agent. The terminal tab gets its shell back: don't resume this conversation there again, or two processes will write to it."
        }
        return "That process is stopped and its conversation continues in tmux under the machine's agent."
    }

    /// Said when the profile starts it on another runtime than it runs on now.
    private func runtimeChange(_ p: AdoptPreview, _ process: AdoptProcess) -> String? {
        let inferred = p.existing?.fields.runtime ?? p.suggested?.runtime
        guard let inferred, draft.runtime != inferred else { return nil }
        return "It runs on \(inferred) now and will start on \(draft.runtime)."
    }

    // MARK: actions

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            hosts = (try? await model.client.api.hosts()) ?? []
            // A seat started by hand may not report its machine: it most likely runs on this Mac.
            let thisMac = ProcessInfo.processInfo.hostName.lowercased()
            guessedHost = seat.host == nil ? hosts.first { $0.name.lowercased() == thisMac }?.name : nil
            if seat.host == nil, guessedHost == nil {
                preview = AdoptPreview(process: nil, suggested: nil, existing: nil, blocked: "Its machine is not known, and this Mac (\(thisMac)) has no agent.")
                failure = nil
                return
            }
            let p = try await model.client.api.adoptPreview(seatId: seat.id, host: guessedHost)
            self.preview = p
            if let fields = p.existing?.fields ?? p.suggested { draft = ProfileDraft(fields) }
            failure = nil
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            failure = error.httpStatus == 404
                ? "This server cannot adopt seats yet."
                : "Not asked: \(error.message)"
        }
    }

    private func adopt() async {
        guard !adopting, let preview, let process = preview.process else { return }
        adopting = true
        defer { adopting = false }
        do {
            let profile = try await model.client.api.adopt(seatId: seat.id, fields: draft.fields, process: process,
                                                           profileId: preview.existing.map { KotlinLong(value: $0.id) }, force: force, host: guessedHost)
            model.client.profiles.put(profile: profile)
            dismiss()
            model.openProfile(profile.id)
        } catch {
            log.error("adopt \(seat.id, privacy: .public) failed: \(error.message, privacy: .public)")
            errors = error.apiException?.fieldErrors ?? [:]
            switch error.apiException?.code {
            case "process_changed":
                failure = "The process changed since this was shown (it restarted or ended). Asked again:"
                await load()
            case "busy":
                failure = "It went mid-turn meanwhile. Stop it anyway, or wait and try again."
                await load()
            default:
                failure = errors.isEmpty ? "Not adopted: \(error.message)" : "Not adopted: see the fields marked below."
            }
        }
    }
}

/// A seat by its row id: the seat list's Adopt sheet is opened for one.
extension Seat: @retroactive Identifiable {}
