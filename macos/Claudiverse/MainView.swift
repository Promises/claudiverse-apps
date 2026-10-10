import ClaudiverseCore
import SwiftUI

/// The section picker and its list on the left (seats, forum channels, task states, accounts), the chosen item on the right.
struct MainView: View {
    @Environment(AppModel.self) private var model
    @State private var newSeat = false
    @State private var channel: String?
    @State private var taskFilter = TaskFilter.active

    var body: some View {
        @Bindable var model = model
        NavigationSplitView {
            VStack(spacing: 0) {
                Picker("Section", selection: $model.section) {
                    ForEach(AppModel.Section.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .padding(.horizontal, 10).padding(.vertical, 6)
                switch model.section {
                case .seats: SeatsSidebar()
                case .profiles: ProfilesSidebar()
                case .forum: ChannelsSidebar(selection: $channel)
                case .tasks: TaskFiltersSidebar(selection: $taskFilter)
                case .pool: PoolSidebar()
                }
            }
            .navigationSplitViewColumnWidth(min: 260, ideal: 320, max: 460)
        } detail: {
            detail
                // A new identity per section: a thread pushed in the forum must not stay on screen after switching away.
                .id(model.section)
        }
        .overlay(alignment: .bottom) { SpawnBanner() }
        .toolbar {
            ToolbarItem(placement: .navigation) {
                Button { newSeat = true } label: { Label("New seat", systemImage: "plus") }
                    .keyboardShortcut("n", modifiers: .command)
                    .help("Start a seat on a machine (⌘N)")
            }
        }
        .sheet(isPresented: $newSeat) { NewSeatView().environment(model) }
        #if CLAUDIVERSE_HARNESS
        .sheet(item: $model.harnessAdopt) { AdoptSheet(seat: $0).environment(model) }
        .onChange(of: model.harnessChannel) { _, slug in if let slug { channel = slug } }
        #endif
    }

    @ViewBuilder
    private var detail: some View {
        switch model.section {
        case .seats:
            if let fleet = model.boardFleet {
                FleetBoardView(fleet: fleet).id(fleet)
            } else if let id = model.selectedSeat {
                SeatDetailView(seatId: id, keepLoaded: true).id(id)
            } else {
                ContentUnavailableView("No seat selected", systemImage: "rectangle.stack", description: Text("Pick a seat on the left."))
            }
        case .forum:
            if let channel {
                ForumChannelView(channel: channel).id(channel)
            } else {
                ContentUnavailableView("No channel selected", systemImage: "bubble.left.and.bubble.right", description: Text("Pick a channel on the left."))
            }
        case .profiles:
            if model.creatingProfile {
                ProfileEditor(profile: nil).id("new")
            } else if let id = model.selectedProfile, let profile = model.profiles.first(where: { $0.id == id }) {
                ProfileEditor(profile: profile).id(id)
            } else {
                ContentUnavailableView("No profile selected", systemImage: "person.text.rectangle", description: Text("Pick a profile on the left, or make a new one."))
            }
        case .tasks: TasksView(filter: taskFilter).id(taskFilter)
        case .pool: PoolView()
        }
    }
}

/// What the latest spawn or resume came to, until dismissed.
struct SpawnBanner: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let notice = model.spawnNotice {
            HStack(spacing: 12) {
                switch onEnum(of: notice) {
                case .pending(let p):
                    ProgressView().controlSize(.small)
                    Text("\(p.op.doing) \(p.title) on \(p.host)…")
                case .started(let s):
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.good)
                    Text("\(s.title) \(s.op.done) on \(s.host)")
                    if let id = s.sessionId?.int64Value ?? model.seats.first(where: { $0.title == s.title && $0.status == "running" })?.id {
                        Button("Open") {
                            model.open(seatId: id)
                            model.dismissSpawnNotice()
                        }
                    }
                case .failed(let f):
                    Image(systemName: "xmark.octagon.fill").foregroundStyle(Theme.bad)
                    Text("\(f.title) \(f.op.notDone) on \(f.host): \(f.error)")
                }
                Button { model.dismissSpawnNotice() } label: { Image(systemName: "xmark") }.buttonStyle(.borderless)
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
            .background(.regularMaterial, in: Capsule())
            .padding(.bottom, 16)
        }
    }
}
