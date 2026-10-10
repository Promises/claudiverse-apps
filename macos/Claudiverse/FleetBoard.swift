import ClaudiverseCore
import SwiftUI

/**
 * A fleet laid out as rows of tiles: its members, and its runner spaces. A
 * member tile is a seat by title (a resumed seat keeps its title, not its
 * id); a runner tile is a slot ("ipsum-w5"), live from cv-runner's heartbeat:
 * busy with its seat, idle, halted, or missing. Slots seen only in old
 * runner titles ("ipsum-w5-t30450") are kept as spaces with no heartbeat yet.
 *
 * Each row has its own height and each tile its own width within its row,
 * so big member seats and small workers share a board; the gaps between
 * tiles and between rows are set separately. Kept per fleet on this Mac.
 */
struct BoardLayout: Codable, Equatable {
    enum Tile: Codable, Hashable {
        case member(String)
        case runner(String)

        var id: String {
            switch self {
            case .member(let title): "m:\(title)"
            case .runner(let slot): "r:\(slot)"
            }
        }
    }

    /// A tile and its width in its row, relative to the others there (1 is normal).
    struct Cell: Codable, Equatable, Identifiable {
        var id = UUID()
        var tile: Tile
        var width: Double = 1
    }

    struct Row: Codable, Equatable, Identifiable {
        var id = UUID()
        var height: Double
        var cells: [Cell]
    }

    var rows: [Row] = []
    /// Between tiles in a row.
    var columnGap: Double = 12
    /// Between rows.
    var rowGap: Double = 12

    static let small = 220.0, medium = 360.0, large = 560.0
    static let heights = 140.0...1000.0
    static let widths = 0.25...6.0

    var tiles: [Tile] { rows.flatMap { $0.cells.map(\.tile) } }

    func contains(_ tile: Tile) -> Bool { rows.contains { $0.cells.contains { $0.tile == tile } } }

    /// Where a tile is, as (row, cell) indexes.
    func position(of tile: Tile) -> (Int, Int)? {
        for (r, row) in rows.enumerated() {
            if let c = row.cells.firstIndex(where: { $0.tile == tile }) { return (r, c) }
        }
        return nil
    }

    /// Take a tile off the board; a row it leaves empty goes too.
    @discardableResult
    mutating func take(_ tile: Tile) -> Cell? {
        guard let (r, c) = position(of: tile) else { return nil }
        let cell = rows[r].cells.remove(at: c)
        if rows[r].cells.isEmpty { rows.remove(at: r) }
        return cell
    }

    /// Add to the last row, or a new row of [height] when the board is empty.
    mutating func append(_ tile: Tile, height: Double = BoardLayout.medium) {
        guard !contains(tile) else { return }
        if rows.isEmpty { rows.append(Row(height: height, cells: [])) }
        rows[rows.count - 1].cells.append(Cell(tile: tile))
    }

    /// Move [tile] to just before [target], in whatever row that is.
    mutating func move(_ tile: Tile, before target: Tile) {
        guard tile != target, contains(target), let cell = take(tile), let (r, c) = position(of: target) else { return }
        rows[r].cells.insert(cell, at: c)
    }

    /// Move [tile] to the end of the row [rowID].
    mutating func move(_ tile: Tile, toEndOf rowID: Row.ID) {
        guard rows.contains(where: { $0.id == rowID }), let cell = take(tile) else { return }
        // Taking the cell may have removed its own (now empty) row, so find the target row again.
        if let r = rows.firstIndex(where: { $0.id == rowID }) {
            rows[r].cells.append(cell)
        } else {
            rows.append(Row(height: Self.medium, cells: [cell]))
        }
    }

    /// Move [tile] to the end of the row [by] rows away; past either end, into a new row there.
    mutating func shift(_ tile: Tile, by: Int) {
        guard let (r, c) = position(of: tile) else { return }
        let height = rows[r].height
        let cell = rows[r].cells.remove(at: c)
        var target = r + by
        if rows[r].cells.isEmpty {
            rows.remove(at: r)
            if by > 0 { target -= 1 }
        }
        if target < 0 {
            rows.insert(Row(height: height, cells: [cell]), at: 0)
        } else if target >= rows.count {
            rows.append(Row(height: height, cells: [cell]))
        } else {
            rows[target].cells.append(cell)
        }
    }

    /// Move [tile] into a row of its own, just below the one it is in.
    mutating func ownRow(_ tile: Tile) {
        guard let (r, _) = position(of: tile), rows[r].cells.count > 1, let cell = take(tile) else { return }
        rows.insert(Row(height: rows[r].height, cells: [cell]), at: r + 1)
    }

    mutating func setWidth(_ tile: Tile, _ width: Double) {
        guard let (r, c) = position(of: tile) else { return }
        rows[r].cells[c].width = min(max(width, Self.widths.lowerBound), Self.widths.upperBound)
    }

    static func load(_ fleet: String) -> BoardLayout? {
        if let data = UserDefaults.standard.data(forKey: "board2.\(fleet)"), let layout = try? JSONDecoder().decode(BoardLayout.self, from: data) {
            return layout
        }
        return Legacy.load(fleet)
    }

    func save(_ fleet: String) {
        if let data = try? JSONEncoder().encode(self) { UserDefaults.standard.set(data, forKey: "board2.\(fleet)") }
    }

    /// The first boards: one list of tiles, a column count and one height for all. Read once into rows.
    private struct Legacy: Codable {
        var tiles: [Tile]
        var columns: Int
        var height: Double

        static func load(_ fleet: String) -> BoardLayout? {
            guard let data = UserDefaults.standard.data(forKey: "board.\(fleet)"),
                  let old = try? JSONDecoder().decode(Legacy.self, from: data), !old.tiles.isEmpty else { return nil }
            let n = max(1, old.columns)
            let rows = stride(from: 0, to: old.tiles.count, by: n).map { i in
                Row(height: old.height, cells: old.tiles[i..<min(i + n, old.tiles.count)].map { Cell(tile: $0) })
            }
            return BoardLayout(rows: rows)
        }
    }
}

/**
 * The fleet as the board sees it: live from GET /api/fleets where it has
 * data, the seat list otherwise. Members are the fleet's declared ones (its
 * profiles) and any other seat in the fleet that is not a runner's.
 */
struct FleetRoster {
    let members: [Seat]
    /// Declared members by name (the profile name is the seat title, fixed for life).
    let declared: [String: FleetMember]
    /// Every member's title: declared ones and seats, each once, sorted.
    let memberTitles: [String]
    /// Live runner spaces by slot, from the heartbeat.
    let live: [String: FleetRunner]
    /// Every slot, live ones first, then ones known only from runner titles.
    let slots: [String]
    private let seatsByTitle: [String: Seat]
    private let seatsById: [Int64: Seat]

    init(fleet: String, seats: [Seat], live fleetData: Fleet?) {
        // The newest row per title: a resumed seat registers as a new row under the same title.
        let latest = Dictionary(grouping: seats, by: \.title).compactMapValues { $0.max { $0.id < $1.id } }
        seatsByTitle = latest
        seatsById = Dictionary(seats.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        declared = Dictionary((fleetData?.members ?? []).map { ($0.name, $0) }, uniquingKeysWith: { a, _ in a })
        let liveMemberTitles = Set((fleetData?.seats ?? []).filter { $0.origin != "runner" }.map(\.title))
        members = latest.values
            .filter { s in !s.isRunner && Self.slot(of: s.title) == nil && (s.fleet == fleet || liveMemberTitles.contains(s.title)) }
            .sorted { $0.title < $1.title }
        let runners = fleetData?.runners ?? []
        live = Dictionary(runners.map { ($0.slot, $0) }, uniquingKeysWith: { a, _ in a })
        let fromTitles = Set(seats.filter { $0.fleet == fleet || $0.title.hasPrefix(fleet + "-w") }.compactMap { Self.slot(of: $0.title) })
        slots = runners.map(\.slot).sorted() + fromTitles.subtracting(live.keys).sorted()
        memberTitles = Set(declared.keys).union(members.map(\.title)).sorted()
    }

    /// "ipsum-w7-t30450" → "ipsum-w7": cv-runner names a runner's seat by its slot and task.
    static func slot(of title: String) -> String? {
        guard let match = title.firstMatch(of: /^(.+-w\d+)-t\d+$/) else { return nil }
        return String(match.1)
    }

    func member(_ title: String) -> Seat? { members.first { $0.title == title } }

    func seat(id: Int64) -> Seat? { seatsById[id] }

    /// The seat a busy runner is working in, when the seat list has it.
    func seat(titled title: String?) -> Seat? { title.flatMap { seatsByTitle[$0] } }
}

struct FleetBoardView: View {
    let fleet: String
    /// In a window of its own rather than the main window's detail: no "Open in New Window" then.
    var inOwnWindow = false
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openBoardWindow
    @State private var layout = BoardLayout()
    @State private var loaded = false
    /// Every seat, stopped ones too: the runner spaces are learnt from runner rows, which are mostly stopped.
    @State private var everySeat: [Seat] = []

    var body: some View {
        let roster = FleetRoster(fleet: fleet, seats: everySeat.isEmpty ? model.seats : everySeat, live: model.fleets.first { $0.fleet == fleet })
        ScrollView {
            if layout.rows.isEmpty {
                ContentUnavailableView {
                    Label("Nothing on the board", systemImage: "square.grid.2x2")
                } description: {
                    Text("Add the fleet's members and runner spaces from the toolbar, or place them all at once: members large, runner spaces small.")
                } actions: {
                    Button("Place all") { placeAll(roster) }.disabled(roster.memberTitles.isEmpty && roster.slots.isEmpty)
                }
                .padding(.top, 80)
            } else {
                VStack(spacing: 0) {
                    ForEach($layout.rows) { $row in
                        BoardRowView(row: $row, layout: $layout) { tile in tileView(tile, roster) }
                        RowResizeHandle(row: $row, gap: layout.rowGap, onMenu: { action in rowAction(action, row.id) })
                    }
                }
                .padding(12)
            }
        }
        .navigationTitle("Fleet \(fleet)")
        .navigationSubtitle("\(roster.memberTitles.count) members · \(roster.live.values.filter { $0.status == "busy" }.count) of \(roster.slots.count) runner spaces busy" + (roster.live.isEmpty ? " · no heartbeat yet" : ""))
        .toolbar { toolbar(roster) }
        .onAppear {
            if !loaded {
                layout = BoardLayout.load(fleet) ?? BoardLayout()
                loaded = true
            }
        }
        .onChange(of: layout) { _, new in new.save(fleet) }
        // The same board open in two windows: an edit in one shows in the other, so neither saves over it.
        .onReceive(NotificationCenter.default.publisher(for: UserDefaults.didChangeNotification)) { _ in
            if loaded, let saved = BoardLayout.load(fleet), saved != layout { layout = saved }
        }
        .task {
            while !Task.isCancelled {
                if let seats = try? await model.client.api.seats(includeStopped: true) { everySeat = seats }
                try? await Task.sleep(for: .seconds(10))
            }
        }
    }

    @ViewBuilder
    private func tileView(_ tile: BoardLayout.Tile, _ roster: FleetRoster) -> some View {
        let remove = { layout.take(tile); return () }
        switch tile {
        case .member(let title):
            if let declared = roster.declared[title] {
                if let id = declared.seat?.int64Value, let seat = roster.seat(id: id) ?? model.seats.first(where: { $0.id == id }) {
                    SeatTile(seat: seat, caption: declared.role, onRemove: remove).id(seat.id)
                } else {
                    MemberTile(member: declared, onRemove: remove)
                }
            } else if let seat = roster.member(title) {
                SeatTile(seat: seat, caption: nil, onRemove: remove).id(seat.id)
            } else {
                EmptyTile(title: title, detail: "Not in the seat list now (stopped? Turn Running only off to see stopped seats).", onRemove: remove)
            }
        case .runner(let slot):
            if let r = roster.live[slot] {
                if r.status == "busy", let seat = roster.seat(titled: r.seat) {
                    SeatTile(seat: seat, caption: Self.caption(r), onRemove: remove).id(seat.id)
                } else {
                    RunnerSpaceTile(runner: r, onRemove: remove)
                }
            } else {
                EmptyTile(title: slot, detail: "No heartbeat yet: its runner is not on the new cv-runner, or has not started.", onRemove: remove)
            }
        }
    }

    /// "ipsum-w5 · task 30450 · ipsum-tasks"
    static func caption(_ r: FleetRunner) -> String {
        [r.slot, r.task.map { "task \($0)" }, r.lane ?? r.channel].compactMap { $0 }.joined(separator: " · ")
    }

    enum RowAction { case height(Double), up, down, remove }

    private func rowAction(_ action: RowAction, _ id: BoardLayout.Row.ID) {
        guard let r = layout.rows.firstIndex(where: { $0.id == id }) else { return }
        switch action {
        case .height(let h): layout.rows[r].height = h
        case .up: if r > 0 { layout.rows.swapAt(r, r - 1) }
        case .down: if r < layout.rows.count - 1 { layout.rows.swapAt(r, r + 1) }
        case .remove: layout.rows.remove(at: r)
        }
    }

    @ToolbarContentBuilder
    private func toolbar(_ roster: FleetRoster) -> some ToolbarContent {
        ToolbarItemGroup {
            if !inOwnWindow {
                Button { openBoardWindow(id: "board", value: fleet) } label: { Label("Open in New Window", systemImage: "macwindow.on.rectangle") }
                    .help("Pop the \(fleet) board out into a window of its own")
            }
            Menu {
                Section("Members") {
                    ForEach(roster.memberTitles, id: \.self) { title in
                        Button(roster.member(title)?.label ?? title) { layout.append(.member(title), height: BoardLayout.large) }.disabled(layout.contains(.member(title)))
                    }
                }
                Section("Runner spaces") {
                    ForEach(roster.slots, id: \.self) { slot in
                        Button("\(slot) (\(roster.live[slot]?.status ?? "no heartbeat"))") { layout.append(.runner(slot), height: BoardLayout.small) }
                            .disabled(layout.contains(.runner(slot)))
                    }
                }
                Divider()
                Button("New Row") { layout.rows.append(.init(height: BoardLayout.medium, cells: [])) }
                Button("Place all") { placeAll(roster) }
                Button("Clear the board", role: .destructive) { layout.rows = [] }.disabled(layout.rows.isEmpty)
            } label: {
                Label("Add", systemImage: "plus.rectangle.on.rectangle")
            }
            .accessibilityLabel("Add to Board")
            Menu {
                Section("Between tiles: \(Int(layout.columnGap)) pt") {
                    ForEach([0.0, 4, 8, 12, 20, 32], id: \.self) { g in Button("\(Int(g)) pt") { layout.columnGap = g } }
                }
                Section("Between rows: \(Int(layout.rowGap)) pt") {
                    ForEach([0.0, 4, 8, 12, 20, 32], id: \.self) { g in Button("\(Int(g)) pt") { layout.rowGap = g } }
                }
            } label: {
                Label("Gaps", systemImage: "rectangle.split.3x3")
            }
            .accessibilityLabel("Gaps")
            .help("Gaps between tiles and between rows")
        }
    }

    /// Members in large rows of two, then runner spaces in small rows of four; what is placed stays where it is.
    private func placeAll(_ roster: FleetRoster) {
        let members = roster.memberTitles.map { BoardLayout.Tile.member($0) }.filter { !layout.contains($0) }
        let runners = roster.slots.map { BoardLayout.Tile.runner($0) }.filter { !layout.contains($0) }
        for chunk in stride(from: 0, to: members.count, by: 2).map({ Array(members[$0..<min($0 + 2, members.count)]) }) {
            layout.rows.append(.init(height: BoardLayout.large, cells: chunk.map { .init(tile: $0) }))
        }
        for chunk in stride(from: 0, to: runners.count, by: 4).map({ Array(runners[$0..<min($0 + 4, runners.count)]) }) {
            layout.rows.append(.init(height: BoardLayout.small, cells: chunk.map { .init(tile: $0) }))
        }
    }
}

/**
 * One row: its tiles side by side at their widths, with a handle in each gap
 * to drag the width between two tiles. A tile dropped on the row's empty
 * space goes to its end.
 */
struct BoardRowView<Tile: View>: View {
    @Binding var row: BoardLayout.Row
    @Binding var layout: BoardLayout
    @ViewBuilder let tile: (BoardLayout.Tile) -> Tile
    /// The widths when a drag between two tiles began.
    @State private var dragStart: (Double, Double)?

    var body: some View {
        GeometryReader { geo in
            let gap = layout.columnGap
            let total = max(row.cells.reduce(0) { $0 + $1.width }, 0.0001)
            let usable = max(geo.size.width - gap * Double(max(row.cells.count - 1, 0)), 1)
            HStack(spacing: 0) {
                ForEach(Array(row.cells.enumerated()), id: \.element.id) { index, cell in
                    tile(cell.tile)
                        .frame(width: usable * cell.width / total, height: row.height)
                        .contextMenu { cellMenu(cell) }
                        .draggable(cell.tile.id)
                        .dropDestination(for: String.self) { ids, _ in
                            guard let id = ids.first, let from = layout.tiles.first(where: { $0.id == id }) else { return false }
                            layout.move(from, before: cell.tile)
                            return true
                        }
                    if index < row.cells.count - 1 {
                        WidthHandle(gap: gap) { dx in
                            resize(index, by: dx, pointsPerUnit: usable / total)
                        } onEnd: {
                            dragStart = nil
                        }
                    }
                }
                if row.cells.isEmpty {
                    Text("Empty row: drop a tile here, or remove it from the row's menu.").foregroundStyle(.secondary).frame(maxWidth: .infinity)
                }
                Spacer(minLength: 0)
            }
        }
        .frame(height: row.height)
        .contentShape(Rectangle())
        .dropDestination(for: String.self) { ids, _ in
            guard let id = ids.first, let from = layout.tiles.first(where: { $0.id == id }) else { return false }
            layout.move(from, toEndOf: row.id)
            return true
        }
    }

    /// Drag the boundary between tile [index] and the next: one grows as the other shrinks.
    private func resize(_ index: Int, by dx: Double, pointsPerUnit: Double) {
        let start = dragStart ?? (row.cells[index].width, row.cells[index + 1].width)
        dragStart = start
        let sum = start.0 + start.1
        let min = BoardLayout.widths.lowerBound
        let left = Swift.min(Swift.max(start.0 + dx / pointsPerUnit, min), sum - min)
        row.cells[index].width = left
        row.cells[index + 1].width = sum - left
    }

    @ViewBuilder
    private func cellMenu(_ cell: BoardLayout.Cell) -> some View {
        Section("Width") {
            Button("Narrow") { layout.setWidth(cell.tile, 0.5) }
            Button("Normal") { layout.setWidth(cell.tile, 1) }
            Button("Wide") { layout.setWidth(cell.tile, 2) }
        }
        Section("Row") {
            Button("Move to Row Above") { layout.shift(cell.tile, by: -1) }
            Button("Move to Row Below") { layout.shift(cell.tile, by: 1) }
            Button("Own Row") { layout.ownRow(cell.tile) }.disabled(row.cells.count < 2)
        }
        Button("Remove from Board", role: .destructive) { layout.take(cell.tile) }
    }
}

/// The gap between two tiles in a row, draggable to move the width from one to the other.
struct WidthHandle: View {
    let gap: Double
    let onDrag: (Double) -> Void
    let onEnd: () -> Void
    @State private var hovering = false

    var body: some View {
        Rectangle()
            .fill(hovering ? Theme.accent.opacity(0.35) : Color.clear)
            .frame(width: max(gap, 6))
            .contentShape(Rectangle())
            .onHover { inside in
                hovering = inside
                if inside { NSCursor.resizeLeftRight.push() } else { NSCursor.pop() }
            }
            .gesture(DragGesture(minimumDistance: 1).onChanged { onDrag($0.translation.width) }.onEnded { _ in onEnd() })
            .help("Drag to change the widths")
    }
}

/// The gap below a row: drag it to change the row's height. Its menu has height presets and the row's moves.
struct RowResizeHandle: View {
    @Binding var row: BoardLayout.Row
    let gap: Double
    let onMenu: (FleetBoardView.RowAction) -> Void
    @State private var start: Double?
    @State private var hovering = false

    var body: some View {
        ZStack(alignment: .trailing) {
            Rectangle()
                .fill(hovering ? Theme.accent.opacity(0.35) : Color.clear)
                .frame(height: max(gap, 6))
                .contentShape(Rectangle())
                .onHover { inside in
                    hovering = inside
                    if inside { NSCursor.resizeUpDown.push() } else { NSCursor.pop() }
                }
                .gesture(DragGesture(minimumDistance: 1)
                    .onChanged { value in
                        let from = start ?? row.height
                        start = from
                        row.height = min(max(from + value.translation.height, BoardLayout.heights.lowerBound), BoardLayout.heights.upperBound)
                    }
                    .onEnded { _ in start = nil })
                .help("Drag to change this row's height (\(Int(row.height)) pt)")
            Menu {
                Button("Small (\(Int(BoardLayout.small)))") { onMenu(.height(BoardLayout.small)) }
                Button("Medium (\(Int(BoardLayout.medium)))") { onMenu(.height(BoardLayout.medium)) }
                Button("Large (\(Int(BoardLayout.large)))") { onMenu(.height(BoardLayout.large)) }
                Divider()
                Button("Move Row Up") { onMenu(.up) }
                Button("Move Row Down") { onMenu(.down) }
                Button("Remove Row", role: .destructive) { onMenu(.remove) }
            } label: {
                Image(systemName: "ellipsis").font(.caption2)
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
            .opacity(hovering ? 1 : 0.35)
            .help("Row height and order")
        }
        .frame(height: max(gap, 10))
    }
}

/// A seat on the board: its status, its live transcript and a message box, in a tile.
struct SeatTile: View {
    let seat: Seat
    let caption: String?
    let onRemove: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.openWindow) private var openWindow
    @Environment(\.scenePhase) private var phase
    @State private var model: SeatModel?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Circle().fill(color).frame(width: 8, height: 8)
                VStack(alignment: .leading, spacing: 0) {
                    Text(seat.label).font(.callout.weight(.semibold)).lineLimit(1)
                    Text([caption, seat.host, seat.model].compactMap { $0 }.joined(separator: " · ")).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                if let needs = app.needsYou[seat.id] {
                    Image(systemName: "exclamationmark.bubble.fill").foregroundStyle(Theme.attention).help(needs.summary)
                }
                Menu {
                    Button("Open in New Window") { openWindow(id: "seat", value: seat.id) }
                    Button("Show in Seats") { app.open(seatId: seat.id) }
                    Divider()
                    Button("Remove from Board", action: onRemove)
                } label: { Image(systemName: "ellipsis.circle") }
                    .menuStyle(.borderlessButton).fixedSize()
            }
            .padding(8)
            .background(.bar)
            if let model {
                TranscriptView(rows: model.state.rows, version: model.state.transcriptVersion, streaming: model.state.streaming, session: model.session)
                Composer(seatId: seat.id, state: model.state, session: model.session)
            } else {
                Spacer()
            }
        }
        .background(.background, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(.separator))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .onAppear {
            let m = SeatModel(client: app.client, seatId: seat.id)
            model = m
            m.session.setWatched(on: phase == .active)
        }
        .onChange(of: phase) { _, now in model?.session.setWatched(on: now == .active) }
        .onDisappear {
            model?.session.setWatched(on: false)
            model?.close()
        }
    }

    private var color: Color {
        if app.needsYou[seat.id] != nil { return Theme.attention }
        if seat.status != "running" { return Theme.muted }
        return seat.activity == "busy" ? Theme.busy : Theme.good
    }

}

struct EmptyTile: View {
    let title: String
    let detail: String
    let onRemove: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Text(title).font(.callout.weight(.semibold))
                Spacer()
                Button(action: onRemove) { Image(systemName: "xmark") }.buttonStyle(.borderless).help("Remove from Board")
            }
            Spacer()
            Text(detail).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Spacer()
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).strokeBorder(style: StrokeStyle(lineWidth: 1, dash: [5, 4])).foregroundStyle(.secondary))
    }
}

/// A runner space with no seat to show: idle, halted, or missing (no heartbeat for 75 s, drawn as a hole).
/// A declared member with no running seat: its profile's status, and Start.
struct MemberTile: View {
    let member: FleetMember
    let onRemove: () -> Void
    @Environment(AppModel.self) private var model
    @State private var starting = false
    @State private var error: String?

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Circle().fill(ProfileStatus.color(member.status)).frame(width: 8, height: 8)
                Text(member.name).font(.callout.weight(.semibold))
                Spacer()
                Button(action: onRemove) { Image(systemName: "xmark") }.buttonStyle(.borderless).help("Remove from Board")
            }
            Spacer()
            Text(Self.statusText(member.status)).font(.title3.weight(.medium)).foregroundStyle(member.status == "unregistered" ? Theme.attention : .secondary)
            Text([member.role, member.machine].compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
            if let error { Text(error).font(.caption).foregroundStyle(Theme.bad).multilineTextAlignment(.center) }
            HStack {
                if member.status == "unregistered" {
                    Button("Stop") { act(stop: true) }.disabled(starting)
                } else {
                    Button("Start") { act(stop: false) }.disabled(starting || member.status == "starting" || member.status == "running")
                }
                Button("Edit Profile") { model.openProfile(member.id) }
            }
            Spacer()
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).strokeBorder(style: StrokeStyle(lineWidth: 1, dash: [5, 4])).foregroundStyle(.secondary))
    }

    static func statusText(_ status: String) -> String {
        switch status {
        case "offline": "machine offline"
        case "unregistered": "started, never registered"
        default: status
        }
    }

    private func act(stop: Bool) {
        starting = true
        Task {
            defer { starting = false }
            do {
                if stop {
                    try await model.client.api.stopProfile(id: member.id)
                } else {
                    model.client.profiles.put(profile: try await model.client.api.startProfile(id: member.id))
                }
                error = nil
            } catch {
                self.error = "\(stop ? "Not stopped" : "Not started"): \(error.message)"
            }
        }
    }
}

struct RunnerSpaceTile: View {
    let runner: FleetRunner
    let onRemove: () -> Void

    var body: some View {
        let missing = runner.status == "missing"
        VStack(spacing: 8) {
            HStack {
                Circle().fill(color).frame(width: 8, height: 8)
                Text(runner.slot).font(.callout.weight(.semibold))
                Spacer()
                Button(action: onRemove) { Image(systemName: "xmark") }.buttonStyle(.borderless).help("Remove from Board")
            }
            Spacer()
            Text(label).font(.title3.weight(.medium)).foregroundStyle(missing ? Theme.bad : .secondary)
            Text([runner.host, runner.lane ?? runner.channel, runner.model, runner.since.map { "since \(relativeTime(iso: $0))" }]
                .compactMap { $0 }.joined(separator: " · "))
                .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Spacer()
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).strokeBorder(style: StrokeStyle(lineWidth: missing ? 2 : 1, dash: [5, 4]))
            .foregroundStyle(missing ? Theme.bad : .secondary))
    }

    private var label: String {
        switch runner.status {
        case "idle": "Idle"
        case "halted": "Halted"
        case "missing": "Missing: no heartbeat"
        case "busy": "Busy" + (runner.seat.map { " in \($0)" } ?? "")
        default: runner.status.capitalized
        }
    }

    private var color: Color {
        switch runner.status {
        case "busy": Theme.busy
        case "idle": Theme.good
        case "missing": Theme.bad
        default: Theme.muted
        }
    }
}
