import ClaudiverseCore
import SwiftUI

/**
 * Seat profiles: one server-side record per managed seat, which the
 * machine's host agent starts it from (docs/design/seat-profiles.md in the
 * claudiverse repo). The list shows each one's derived status; the editor
 * changes it, starts, stops and restarts its seat.
 */
struct ProfilesSidebar: View {
    @Environment(AppModel.self) private var model
    /// Folded fleets, by name ("" for no fleet).
    @State private var collapsed: Set<String> = []
    @State private var renaming: RenameTarget?

    var body: some View {
        @Bindable var model = model
        VStack(spacing: 0) {
            // Grouped by fleet as the seat list is: fleets in name order, profiles with none last.
            List(selection: $model.selectedProfile) {
                ForEach(profileGroups(profiles: model.profiles), id: \.fleet) { group in
                    let key = group.fleet ?? ""
                    let folded = collapsed.contains(key)
                    Section {
                        if !folded {
                            ForEach(group.profiles, id: \.id) { profile in
                                ProfileRow(profile: profile).tag(profile.id)
                            }
                        }
                    } header: {
                        HStack(spacing: 6) {
                            Button {
                                if folded { collapsed.remove(key) } else { collapsed.insert(key) }
                            } label: {
                                Image(systemName: folded ? "chevron.right" : "chevron.down").frame(width: 12)
                            }
                            .accessibilityLabel(folded ? "Show \(group.fleet ?? "no fleet")" : "Hide \(group.fleet ?? "no fleet")")
                            .buttonStyle(.plain)
                            .help(folded ? "Show" : "Hide")
                            Text(group.fleet ?? "No fleet")
                            Spacer()
                            Text(folded ? "\(group.profiles.count)" : "\(group.running) running").foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .listStyle(.sidebar)
            .contextMenu(forSelectionType: Int64.self) { ids in
                if let id = ids.first, let profile = model.profiles.first(where: { $0.id == id }) {
                    Button(profile.isRunning ? "Rename and Restart…" : "Rename…") { renaming = RenameTarget(id: id) }
                }
            }
            .sheet(item: $renaming) { RenameProfileSheet(profileId: $0.id) }
            .overlay {
                if model.profiles.isEmpty {
                    ContentUnavailableView {
                        Label("No profiles", systemImage: "person.text.rectangle")
                    } description: {
                        Text("A profile is a seat the server starts on a machine: its folder, fleet, model and MCP servers.")
                    }
                }
            }
            Divider()
            HStack {
                Button {
                    model.selectedProfile = nil
                    model.creatingProfile = true
                } label: { Label("New profile", systemImage: "plus") }
                    .buttonStyle(.borderless)
                Spacer()
            }
            .padding(8)
        }
        .onChange(of: model.selectedProfile) { _, id in if id != nil { model.creatingProfile = false } }
    }
}

struct ProfileRow: View {
    let profile: Profile

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(ProfileStatus.color(profile.status)).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(profile.name).fontWeight(.medium)
                // Its fleet is the section it is in.
                Text([profile.fields.machine, profile.fields.role].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            if profile.restartNeeded {
                Image(systemName: "arrow.clockwise.circle.fill").foregroundStyle(Theme.attention).help("Edited since its seat started: restart to apply")
            }
        }
        .padding(.vertical, 2)
    }
}

enum ProfileStatus {
    static func color(_ status: String) -> Color {
        switch status {
        case "running": Theme.good
        case "starting": Theme.busy
        case "unregistered": Theme.attention
        case "failed": Theme.bad
        default: Theme.muted
        }
    }

    /**
     * The last start as history, with its time, since it stays "started" after
     * the seat stops: "Last start: started 16:04:16 (took 7 s)". The time taken
     * shows once the server sends when the start was asked for.
     */
    static func lastStart(_ last: ProfileStart) -> String {
        var line = "Last start: \(last.outcome)"
        if let at = date(last.at) {
            line += " " + (Calendar.current.isDateInToday(at)
                ? at.formatted(date: .omitted, time: .standard)
                : at.formatted(date: .abbreviated, time: .standard))
            if last.outcome != "starting", let asked = date(last.requestedAt), asked < at {
                line += " (took \(Int(at.timeIntervalSince(asked).rounded())) s)"
            }
        }
        if let reason = last.reason { line += " — \(reason)" }
        if let stopped = date(last.stoppedAt) {
            line += " · stopped " + (Calendar.current.isDateInToday(stopped)
                ? stopped.formatted(date: .omitted, time: .standard)
                : stopped.formatted(date: .abbreviated, time: .standard))
        }
        return line
    }

    private static func date(_ iso: String?) -> Date? {
        guard let iso else { return nil }
        let plain = ISO8601DateFormatter()
        if let d = plain.date(from: iso) { return d }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: iso)
    }

    /// "running", "machine offline", "start refused: outside_roots: …"
    static func label(_ profile: Profile) -> String {
        switch profile.status {
        case "offline": return "machine offline"
        case "unregistered": return "started, but never registered"
        case "failed":
            guard let last = profile.lastStart else { return "failed" }
            return "start \(last.outcome)" + (last.reason.map { ": \($0)" } ?? "")
        default: return profile.status
        }
    }
}

/// The editor's working copy of a profile's fields: plain Swift values for the controls, "" for an unset optional.
struct ProfileDraft: Equatable {
    var machine: String
    var folder: String
    var fleet: String
    var role: String
    var canNotify: Bool
    var runtime: String
    var model: String
    var effort: String
    var permissionMode: String
    var mcp: [String]
    var pooled: Bool
    var autostart: Bool
    var resume: String
    var debug: Bool

    init(_ f: ProfileFields) {
        machine = f.machine
        folder = f.folder
        fleet = f.fleet ?? ""
        role = f.role
        canNotify = f.canNotify
        runtime = f.runtime
        model = f.model ?? ""
        effort = f.effort ?? ""
        permissionMode = f.permissionMode
        mcp = f.mcp
        pooled = f.pooled
        autostart = f.autostart
        resume = f.resume
        debug = f.debug
    }

    var fields: ProfileFields {
        func optional(_ s: String) -> String? {
            let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? nil : t
        }
        return ProfileFields(
            machine: machine, folder: folder.trimmingCharacters(in: .whitespacesAndNewlines), fleet: optional(fleet), role: role,
            canNotify: canNotify, runtime: runtime, model: optional(model), effort: optional(effort), permissionMode: permissionMode,
            mcp: mcp, pooled: pooled && !Self.failsOverItself(runtime), autostart: autostart, resume: resume.trimmingCharacters(in: .whitespacesAndNewlines), debug: debug
        )
    }

    /// The patched runtime leases from the pool and fails over itself; it has never run through the proxy, so it is never pooled.
    static func failsOverItself(_ runtime: String) -> Bool { runtime == "patched" }

    /// The draft as the form shows it, where a patched profile reads unpooled.
    var showsPooled: Bool { pooled && !Self.failsOverItself(runtime) }

    /// A new profile: the server's defaults (auto mode, as for a new seat), on the first online machine.
    static func new(machine: String) -> ProfileFields {
        ProfileFields(machine: machine, folder: "", fleet: nil, role: "member", canNotify: false, runtime: "stock+mod", model: nil, effort: nil,
                      permissionMode: "auto", mcp: [], pooled: true, autostart: false, resume: "latest", debug: false)
    }
}

/**
 * A profile's fields as a form: the Seat, Launch and Starting sections. The
 * profile editor and the Adopt sheet both use it. [name] is editable for a
 * new profile, [fixedName] shown for one whose name is set; [machineFixed]
 * holds the machine (an adopted seat stays on its own).
 */
struct ProfileFieldsForm: View {
    @Binding var draft: ProfileDraft
    var name: Binding<String>?
    var fixedName: String? = nil
    var machineFixed = false
    let hosts: [ClaudiverseCore.Host]
    let errors: [String: [String]]
    @Environment(AppModel.self) private var model

    private var host: ClaudiverseCore.Host? { hosts.first { $0.name == draft.machine } }

    var body: some View {
        seatSection
        launchSection
        startingSection
    }

    private var seatSection: some View {
        Section("Seat") {
            if let name {
                TextField("Name", text: name, prompt: Text("ipsum-admin"))
                hint("The seat's title and tmux session. Letters, digits, . _ -; it cannot be changed later.")
                fieldErrors("name")
            } else if let fixedName {
                LabeledContent("Name", value: fixedName)
            }
            if machineFixed {
                LabeledContent("Machine", value: draft.machine)
            } else {
                Picker("Machine", selection: $draft.machine) {
                    if draft.machine.isEmpty { Text("Choose…").tag("") }
                    ForEach(hosts, id: \.name) { h in
                        Text(h.online ? h.name : "\(h.name) (offline)").tag(h.name)
                    }
                    if !draft.machine.isEmpty, host == nil { Text("\(draft.machine) (no agent)").tag(draft.machine) }
                }
            }
            fieldErrors("machine")
            HStack {
                TextField("Folder", text: $draft.folder, prompt: Text("/Users/…/project"))
                if let roots = host?.roots, !roots.isEmpty {
                    Menu("Allowed") {
                        ForEach(roots, id: \.self) { root in Button(root) { draft.folder = root } }
                    }
                    .fixedSize()
                    .help("This machine's allowed folders; the seat's folder must be inside one")
                }
            }
            fieldErrors("folder")
            HStack {
                TextField("Fleet", text: $draft.fleet, prompt: Text("none"))
                let fleets = model.fleets.map(\.fleet)
                if !fleets.isEmpty {
                    Menu("Fleets") {
                        ForEach(fleets, id: \.self) { f in Button(f) { draft.fleet = f } }
                        Divider()
                        Button("None") { draft.fleet = "" }
                    }
                    .fixedSize()
                }
            }
            fieldErrors("fleet")
            Picker("Role", selection: $draft.role) {
                ForEach(ProfileFields.companion.ROLES, id: \.self) { Text($0.capitalized).tag($0) }
            }
            .pickerStyle(.segmented)
            Toggle("May page the operator", isOn: $draft.canNotify)
        }
    }

    private var launchSection: some View {
        Section("Launch") {
            machineChoice("Runtime", selection: $draft.runtime, options: host?.runtimes.map { r in (r.name, r.version.map { "\(r.name) (\($0))" } ?? r.name) } ?? [], what: "runtimes")
            fieldErrors("runtime")
            HStack {
                TextField("Model", text: $draft.model, prompt: Text("Claude Code's default"))
                Menu("Models") {
                    ForEach(SessionChoices.shared.models, id: \.id) { m in
                        Button(m.label) { draft.model = m.oneM ? "\(m.id)[1m]" : m.id }
                    }
                    Divider()
                    Button("Claude Code's default") { draft.model = "" }
                }
                .fixedSize()
            }
            fieldErrors("model")
            Picker("Effort", selection: $draft.effort) {
                Text("Claude Code's default").tag("")
                ForEach(ProfileFields.companion.EFFORTS, id: \.self) { Text($0).tag($0) }
            }
            fieldErrors("effort")
            machineChoice("Permission mode", selection: $draft.permissionMode, options: host?.modes.map { ($0, ProfileEditor.modeLabel($0)) } ?? [], what: "permission modes")
            fieldErrors("permission_mode")
            mcpChoice
            fieldErrors("mcp")
        }
    }

    private var startingSection: some View {
        Section("Starting") {
            Picker("Conversation", selection: resumeKind) {
                Text("Continue the latest").tag("latest")
                Text("Start new").tag("new")
                Text("Continue a chosen one").tag("chosen")
            }
            if resumeKind.wrappedValue == "chosen" {
                TextField("Conversation id", text: $draft.resume, prompt: Text("a Claude Code session id"))
            }
            fieldErrors("resume")
            Toggle("Start when its machine comes online", isOn: $draft.autostart)
            Toggle("Pooled login (proxy failover)", isOn: Binding(get: { draft.showsPooled }, set: { draft.pooled = $0 }))
                .disabled(ProfileDraft.failsOverItself(draft.runtime))
            if ProfileDraft.failsOverItself(draft.runtime) {
                hint("Off: the patched runtime does its own pool failover.")
            }
            Toggle("Debug log", isOn: $draft.debug)
        }
    }

    /// A choice the machine's agent lists: only what it offers, plus the profile's current value marked when the machine lacks it.
    @ViewBuilder
    private func machineChoice(_ title: String, selection: Binding<String>, options: [(id: String, label: String)], what: String) -> some View {
        if options.isEmpty {
            LabeledContent(title, value: selection.wrappedValue)
            hint(draft.machine.isEmpty ? "Choose a machine to see its \(what)."
                 : "\(draft.machine)'s agent\(host?.version.map { " (\($0))" } ?? "") lists no \(what) yet; the profile keeps this one. Agent 0.7.0 lists them.")
        } else {
            Picker(title, selection: selection) {
                ForEach(options, id: \.id) { Text($0.label).tag($0.id) }
                if !options.contains(where: { $0.id == selection.wrappedValue }) {
                    Text("\(selection.wrappedValue) (not on this machine)").tag(selection.wrappedValue)
                }
            }
        }
    }

    @ViewBuilder
    private var mcpChoice: some View {
        let catalog = host?.mcp ?? []
        let unknown = draft.mcp.filter { !catalog.contains($0) }
        LabeledContent("MCP servers") {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(catalog, id: \.self) { name in
                    Toggle(name, isOn: Binding(
                        get: { draft.mcp.contains(name) },
                        set: { on in if on { draft.mcp.append(name) } else { draft.mcp.removeAll { $0 == name } } }
                    ))
                }
                ForEach(unknown, id: \.self) { name in
                    HStack {
                        Label("\(name): not in this machine's catalog", systemImage: "exclamationmark.triangle").foregroundStyle(Theme.attention)
                        Button("Remove") { draft.mcp.removeAll { $0 == name } }.buttonStyle(.borderless)
                    }
                }
                if catalog.isEmpty && unknown.isEmpty {
                    Text(draft.machine.isEmpty ? "Choose a machine first." : "This machine's agent lists no MCP catalog yet.").foregroundStyle(.secondary)
                }
            }
        }
    }

    private var resumeKind: Binding<String> {
        Binding(
            get: { ["latest", "new"].contains(draft.resume) ? draft.resume : "chosen" },
            set: { kind in draft.resume = kind == "chosen" ? (["latest", "new"].contains(draft.resume) ? "" : draft.resume) : kind }
        )
    }

    private func hint(_ text: String) -> some View {
        Text(text).font(.caption).foregroundStyle(.secondary)
    }

    @ViewBuilder
    private func fieldErrors(_ field: String) -> some View {
        if let messages = errors[field], !messages.isEmpty {
            Text(messages.joined(separator: "; ")).font(.caption).foregroundStyle(Theme.bad)
        }
    }

}

/**
 * One profile's settings and its seat's controls. A save sends the revision
 * the editor read: if someone else saved in between, their changes are merged
 * in, the fields both changed are named, and the operator saves again.
 * Edits apply at the seat's next start, so a running seat shows "restart".
 */
struct ProfileEditor: View {
    /// Nil for a new profile.
    let profile: Profile?
    @Environment(AppModel.self) private var model
    @State private var name = ""
    /// What the draft started from: the profile as read, or as merged after a conflict.
    @State private var base: ProfileFields
    @State private var revision: Int64
    @State private var draft: ProfileDraft
    @State private var hosts: [ClaudiverseCore.Host] = []
    @State private var errors: [String: [String]] = [:]
    @State private var notice: (text: String, bad: Bool)?
    /// Fields both this editor and the other save changed, and the other save's values for them.
    @State private var conflicts: [String] = []
    @State private var theirs: ProfileFields?
    @State private var busy = false
    @State private var confirmingDelete = false
    @State private var renaming: RenameTarget?

    init(profile: Profile?) {
        self.profile = profile
        let fields = profile?.fields ?? ProfileDraft.new(machine: "")
        _base = State(initialValue: fields)
        _revision = State(initialValue: profile?.revision ?? 0)
        _draft = State(initialValue: ProfileDraft(fields))
    }

    private var edited: Bool { draft != ProfileDraft(base) }
    private var host: ClaudiverseCore.Host? { hosts.first { $0.name == draft.machine } }
    private var api: Api { model.client.api }

    var body: some View {
        Form {
            if let profile { status(profile) }
            if let notice {
                Text(notice.text).foregroundStyle(notice.bad ? Theme.bad : .secondary)
            }
            if !conflicts.isEmpty { conflictSection }
            ProfileFieldsForm(draft: $draft, name: profile == nil ? $name : nil, hosts: hosts, errors: errors)
            if let profile {
                Section {
                    Button("Delete Profile…", role: .destructive) { confirmingDelete = true }
                        .disabled(busy || profile.canStop)
                        .help(profile.canStop ? "Stop its seat first" : "")
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle(profile?.name ?? "New profile")
        .navigationSubtitle(profile.map { "revision \($0.revision)" } ?? "")
        .toolbar {
            ToolbarItemGroup {
                if profile != nil {
                    Button("Revert") { revert() }.disabled(!edited || busy)
                }
                Button(profile == nil ? "Create" : "Save") { Task { await save() } }
                    .keyboardShortcut("s", modifiers: .command)
                    .disabled(busy || (profile != nil && !edited) || (profile == nil && (name.isEmpty || draft.machine.isEmpty)))
            }
        }
        .task { await loadHosts() }
        // Saved elsewhere while this editor has no edits of its own: show it.
        .onChange(of: profile?.revision) { _, _ in
            if let profile, !edited, conflicts.isEmpty, profile.revision != revision { adopt(profile) }
        }
        .sheet(item: $renaming) { RenameProfileSheet(profileId: $0.id) }
        .confirmationDialog("Delete \(profile?.name ?? "this profile")?", isPresented: $confirmingDelete) {
            Button("Delete", role: .destructive) { Task { await delete() } }
        } message: {
            Text("The server forgets its settings. Its seats' history stays.")
        }
    }

    // MARK: sections

    @ViewBuilder
    private func status(_ profile: Profile) -> some View {
        Section {
            HStack(spacing: 8) {
                Circle().fill(ProfileStatus.color(profile.status)).frame(width: 10, height: 10)
                Text(ProfileStatus.label(profile)).fontWeight(.medium)
                Spacer()
                Button(profile.isRunning ? "Rename and Restart…" : "Rename…") { renaming = RenameTarget(id: profile.id) }
                    .disabled(busy)
                if let seat = profile.seat?.int64Value {
                    Button("Open Seat") { model.open(seatId: seat) }
                }
                if profile.isRunning {
                    Button("Restart") { control(.restart) }.disabled(busy)
                    Button("Stop") { control(.stop) }.disabled(busy)
                } else if profile.canStop {
                    Button("Stop") { control(.stop) }.disabled(busy)
                } else {
                    Button("Start") { control(.start) }.disabled(busy || profile.status == "starting")
                }
            }
            if profile.status == "unregistered" {
                Text("Its machine started the seat, but it never registered with the server (it may be waiting at a prompt in its terminal). Open its terminal, or stop it.")
                    .font(.callout).foregroundStyle(Theme.attention)
            }
            if profile.restartNeeded {
                Label("Edited since its seat started: restart to apply.", systemImage: "arrow.clockwise.circle.fill")
                    .foregroundStyle(Theme.attention)
            }
            if let last = profile.lastStart {
                Text(ProfileStatus.lastStart(last)).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var conflictSection: some View {
        Section("Changed elsewhere too") {
            Text("Someone saved this profile after you opened it. Their changes are in and yours are kept; these fields both of you changed show your value. Keep it, or take theirs, then save.")
                .font(.callout).foregroundStyle(.secondary)
            ForEach(conflicts, id: \.self) { field in
                HStack {
                    Text(Self.label(field)).fontWeight(.medium)
                    Text("theirs: \(theirValue(field))").foregroundStyle(.secondary).lineLimit(1)
                    Spacer()
                    Button("Take Theirs") { takeTheirs(field) }
                }
            }
        }
    }

    // MARK: names

    static func modeLabel(_ id: String) -> String {
        SessionChoices.shared.modes.first { $0.id == id }?.label ?? id
    }

    /// A server field name as the form labels it.
    static func label(_ field: String) -> String {
        [
            "machine": "Machine", "folder": "Folder", "fleet": "Fleet", "role": "Role", "can_notify": "May page the operator",
            "runtime": "Runtime", "model": "Model", "effort": "Effort", "permission_mode": "Permission mode", "mcp": "MCP servers",
            "pooled": "Pooled login", "autostart": "Autostart", "resume": "Conversation", "debug": "Debug log",
        ][field] ?? field
    }

    private func theirValue(_ field: String) -> String {
        guard let theirs else { return "" }
        let d = ProfileDraft(theirs)
        switch field {
        case "machine": return d.machine
        case "folder": return d.folder
        case "fleet": return d.fleet.isEmpty ? "none" : d.fleet
        case "role": return d.role
        case "can_notify": return d.canNotify ? "on" : "off"
        case "runtime": return d.runtime
        case "model": return d.model.isEmpty ? "default" : d.model
        case "effort": return d.effort.isEmpty ? "default" : d.effort
        case "permission_mode": return d.permissionMode
        case "mcp": return d.mcp.isEmpty ? "none" : d.mcp.joined(separator: ", ")
        case "pooled": return d.pooled ? "on" : "off"
        case "autostart": return d.autostart ? "on" : "off"
        case "resume": return d.resume
        case "debug": return d.debug ? "on" : "off"
        default: return ""
        }
    }

    private func takeTheirs(_ field: String) {
        guard let theirs else { return }
        let t = ProfileDraft(theirs)
        switch field {
        case "machine": draft.machine = t.machine
        case "folder": draft.folder = t.folder
        case "fleet": draft.fleet = t.fleet
        case "role": draft.role = t.role
        case "can_notify": draft.canNotify = t.canNotify
        case "runtime": draft.runtime = t.runtime
        case "model": draft.model = t.model
        case "effort": draft.effort = t.effort
        case "permission_mode": draft.permissionMode = t.permissionMode
        case "mcp": draft.mcp = t.mcp
        case "pooled": draft.pooled = t.pooled
        case "autostart": draft.autostart = t.autostart
        case "resume": draft.resume = t.resume
        case "debug": draft.debug = t.debug
        default: break
        }
        conflicts.removeAll { $0 == field }
    }

    // MARK: actions

    private func adopt(_ p: Profile) {
        base = p.fields
        revision = p.revision
        draft = ProfileDraft(p.fields)
        conflicts = []
        theirs = nil
    }

    private func revert() {
        draft = ProfileDraft(base)
        errors = [:]
        conflicts = []
        notice = nil
    }

    private func loadHosts() async {
        do {
            hosts = try await api.hosts()
            if profile == nil, draft.machine.isEmpty, let first = hosts.first(where: \.online) ?? hosts.first {
                draft.machine = first.name
                base = draft.fields
            }
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            notice = ("Machines not read: \(error.message)", true)
        }
    }

    private func save() async {
        busy = true
        defer { busy = false }
        do {
            let result: ProfileSave
            if let profile {
                result = try await api.updateProfile(id: profile.id, revision: revision, fields: draft.fields)
            } else {
                result = try await api.createProfile(name: name.trimmingCharacters(in: .whitespaces), fields: draft.fields)
            }
            switch onEnum(of: result) {
            case .saved(let s):
                errors = [:]
                model.client.profiles.put(profile: s.profile)
                if profile == nil {
                    model.creatingProfile = false
                    model.selectedProfile = s.profile.id
                } else {
                    adopt(s.profile)
                    notice = (s.profile.restartNeeded ? "Saved. Its seat runs the old settings until you restart it." : "Saved.", false)
                }
            case .invalid(let i):
                errors = i.errors
                notice = ("Not saved: see the fields marked below.", true)
            case .conflict(let c):
                let merged = ProfileFields.companion.merge(base: base, mine: draft.fields, theirs: c.current.fields)
                theirs = c.current.fields
                base = c.current.fields
                revision = c.current.revision
                draft = ProfileDraft(merged.fields)
                conflicts = merged.conflicts
                errors = [:]
                notice = merged.conflicts.isEmpty
                    ? ("Someone saved this profile after you opened it. Their changes are merged in and yours are kept: save again.", true)
                    : ("Not saved yet: see below.", true)
            }
        } catch {
            notice = ("Not saved: \(error.message)", true)
        }
    }

    enum Control { case start, stop, restart }

    private func control(_ op: Control) {
        guard let profile else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                switch op {
                case .start: model.client.profiles.put(profile: try await api.startProfile(id: profile.id))
                case .restart: model.client.profiles.put(profile: try await api.restartProfile(id: profile.id))
                case .stop: try await api.stopProfile(id: profile.id)
                }
                notice = nil
            } catch {
                notice = ("\(op == .stop ? "Not stopped" : "Not started"): \(error.message)", true)
            }
        }
    }

    private func delete() async {
        guard let profile else { return }
        busy = true
        defer { busy = false }
        do {
            try await api.deleteProfile(id: profile.id)
            model.selectedProfile = nil
            // The "deleted" event removes it too; this is in case the socket missed it.
            try? await model.client.profiles.refresh()
        } catch {
            notice = ("Not deleted: \(error.message)", true)
        }
    }
}

/// The profile a Rename sheet is open for.
struct RenameTarget: Identifiable { let id: Int64 }

/**
 * Rename a profile, and its seat with it. A running seat is stopped and
 * started again under the new name on the same conversation (about ten
 * seconds): its title is read once at launch by everything that uses it.
 * The server moves the seat's history, watch lists, handoffs and open tasks
 * to the new name; what names the old one elsewhere is not followed.
 */
struct RenameProfileSheet: View {
    let profileId: Int64
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var busy = false
    @State private var error: String?
    /// After the rename: what moved, and whether the seat is restarting.
    @State private var done: (moved: String, restarting: Bool)?
    /// The name before the rename, for the note after it.
    @State private var profileOldName: String?

    private var profile: Profile? { model.profiles.first { $0.id == profileId } }

    /// The server's rule, so the button is only on for a name it can take.
    static func validName(_ s: String) -> Bool {
        s.range(of: "^[A-Za-z0-9._-]{1,64}$", options: .regularExpression) != nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let profile {
                Text("Rename \(profile.name)").font(.headline)
                if let done {
                    Label(done.restarting ? "Renamed. Its seat is restarting as \(name) on the same conversation." : "Renamed to \(name).",
                          systemImage: "checkmark.circle").foregroundStyle(Theme.good)
                    if !done.moved.isEmpty { Text("Moved to the new name: \(done.moved).").font(.callout) }
                    oldNameNote(old: profileOldName ?? "")
                } else {
                    TextField("New name", text: $name).textFieldStyle(.roundedBorder).onSubmit { if canRename { Task { await rename(profile) } } }
                    Text("1-64 letters, digits, dots, dashes or underscores.").font(.caption).foregroundStyle(.secondary)
                    if profile.isRunning {
                        Text("Its seat is running: it stops, and starts again under the new name on the same conversation, in about ten seconds. A seat mid-turn can't be renamed.")
                            .font(.callout)
                    }
                    Text("Its past seats, watch lists, handoffs and open tasks move to the new name.").font(.callout).foregroundStyle(.secondary)
                    oldNameNote(old: profile.name)
                }
                if let error { Text(error).foregroundStyle(Theme.bad).font(.callout) }
            } else {
                Text("This profile is gone.").foregroundStyle(.secondary)
            }
            HStack {
                Spacer()
                if done != nil || profile == nil {
                    Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
                } else {
                    Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                    Button(busy ? "Renaming…" : profile?.isRunning == true ? "Rename and Restart" : "Rename") {
                        if let profile { Task { await rename(profile) } }
                    }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!canRename)
                }
            }
        }
        .padding(20)
        .frame(width: 460)
        .onAppear { if name.isEmpty { name = profile?.name ?? "" } }
    }

    private var canRename: Bool { !busy && Self.validName(name) && name != profile?.name }

    private func oldNameNote(old: String) -> some View {
        Text("Not followed: messages sent to \"\(old)\" fail (another seat may take that name later), and scripts, scheduled jobs, prompts and memories that name it are not changed. Search for it after renaming.")
            .font(.callout).foregroundStyle(Theme.attention)
    }

    private func rename(_ profile: Profile) async {
        busy = true
        defer { busy = false }
        let old = profile.name
        do {
            switch onEnum(of: try await model.client.api.renameProfile(id: profile.id, revision: profile.revision, name: name)) {
            case .renamed(let r):
                profileOldName = old
                error = nil
                done = (renameMoved(moved: r.moved), r.restarting)
            case .refused(let r):
                error = renameRefusal(r: r)
            case .conflict:
                error = "The profile was changed meanwhile. Look at it again, then rename."
            case .invalid(let r):
                error = r.errors["name"]?.joined(separator: "; ") ?? r.errors.values.flatMap { $0 }.joined(separator: "; ")
            }
        } catch {
            self.error = switch error.httpStatus {
            case 504: "Not renamed: its machine is offline."
            case 404: "Not renamed: this server can't rename profiles yet (it needs an update)."
            default: "Not renamed: \(error.message)"
            }
        }
    }
}
