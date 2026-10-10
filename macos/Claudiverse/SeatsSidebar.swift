import ClaudiverseCore
import SwiftUI

/// The seat list: searched, filtered and grouped by core's seatList, as on the phone.
struct SeatsSidebar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow
    @State private var text = ""
    /// The seat whose Adopt sheet is open.
    @State private var adopting: Seat?
    @State private var hideRunner = true
    @State private var host: String?
    @State private var groupByFleet = true
    @State private var collapsed: Set<String> = []

    private var filters: SeatFilters {
        SeatFilters(text: text, runningOnly: !model.includeStopped, hideRunner: hideRunner, host: host, groupByFleet: groupByFleet)
    }

    var body: some View {
        @Bindable var model = model
        let list = seatList(all: model.seats, f: filters)
        List(selection: $model.selectedSeat) {
            if let error = model.seatsError {
                Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(Theme.bad).font(.callout)
            }
            ForEach(list.groups, id: \.fleet) { group in
                let key = group.fleet ?? ""
                let folded = collapsed.contains(key)
                Section {
                    if !folded {
                        ForEach(group.seats, id: \.id) { seat in
                            SeatRow(seat: seat, needsYou: model.needsYou[seat.id], queued: queuedCount(seat.id)).tag(seat.id)
                        }
                    }
                } header: {
                    if groupByFleet {
                        HStack(spacing: 6) {
                            // The chevron folds the group; a fleet's name opens its board.
                            Button {
                                if folded { collapsed.remove(key) } else { collapsed.insert(key) }
                            } label: {
                                Image(systemName: folded ? "chevron.right" : "chevron.down").frame(width: 12)
                            }
                            .accessibilityLabel(folded ? "Show \(group.fleet ?? "no fleet")" : "Hide \(group.fleet ?? "no fleet")")
                            .buttonStyle(.plain)
                            .help(folded ? "Show" : "Hide")
                            if let fleet = group.fleet {
                                Button { model.openBoard(fleet) } label: {
                                    Label(fleet, systemImage: "square.grid.2x2").labelStyle(.titleAndIcon)
                                }
                                .buttonStyle(.plain)
                                .foregroundStyle(model.boardFleet == fleet ? Theme.accent : .secondary)
                                .help("Open the \(fleet) board")
                                .contextMenu {
                                    Button("Open Board") { model.openBoard(fleet) }
                                    Button("Open Board in New Window") { openWindow(id: "board", value: fleet) }
                                }
                            } else {
                                Text("No fleet")
                            }
                            Spacer()
                            if group.busy > 0 { Text("\(group.busy) busy").foregroundStyle(.secondary) }
                        }
                        // Fold and board as two controls, not one merged header.
                        .accessibilityElement(children: .contain)
                    }
                }
            }
        }
        .listStyle(.sidebar)
        // Double-click (or Return) opens the seat in a window of its own.
        .contextMenu(forSelectionType: Int64.self) { ids in
            if let id = ids.first {
                Button("Open in New Window") { openWindow(id: "seat", value: id) }
                if let fleet = model.seats.first(where: { $0.id == id })?.fleet {
                    Button("Open the \(fleet) Board") { model.openBoard(fleet) }
                }
                if let seat = model.seats.first(where: { $0.id == id }) {
                    Divider()
                    Button("Adopt…") { adopting = seat }.disabled(adoptBlockedReason(seat: seat) != nil)
                }
            }
        } primaryAction: { ids in
            ids.forEach { openWindow(id: "seat", value: $0) }
        }
        .searchable(text: $text, placement: .sidebar, prompt: "Search seats")
        .sheet(item: $adopting) { AdoptSheet(seat: $0) }
        .safeAreaInset(edge: .bottom) {
            Text("\(list.shown) of \(list.total) · \(list.busy) busy" + (list.runnersHidden > 0 ? " · \(list.runnersHidden) runners hidden" : ""))
                .font(.caption).foregroundStyle(.secondary).padding(8)
                .frame(maxWidth: .infinity)
                .background(.bar)
        }
        .toolbar {
            ToolbarItem {
                Menu {
                    Toggle("Running only", isOn: Binding(get: { !model.includeStopped }, set: { model.includeStopped = !$0 }))
                    Toggle("Hide runner seats", isOn: $hideRunner)
                    Toggle("Group by fleet", isOn: $groupByFleet)
                    if !list.hosts.isEmpty {
                        Picker("Machine", selection: $host) {
                            ForEach(list.hosts, id: \.host) { h in
                                Text("\(h.host ?? "All machines") (\(h.visible))").tag(h.host)
                            }
                        }
                    }
                } label: {
                    Label("Filters", systemImage: filtersChanged ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
                }
            }
        }
    }

    private var filtersChanged: Bool { model.includeStopped || !hideRunner || host != nil || !groupByFleet }

    private func queuedCount(_ seat: Int64) -> Int { model.queued.filter { $0.seatId == seat }.count }
}

struct SeatRow: View {
    let seat: Seat
    let needsYou: HumanRequest?
    let queued: Int

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Circle().fill(statusColor).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(seat.label).lineLimit(1).fontWeight(needsYou == nil ? .regular : .semibold)
                    if seat.canNotify { Image(systemName: "bell").font(.caption2).foregroundStyle(.secondary) }
                }
                if let needsYou {
                    Text(needsYou.summary).font(.caption).foregroundStyle(Theme.attention).lineLimit(2)
                } else {
                    Text([seat.host, seat.model].compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 2) {
                Text(relativeTime(iso: seat.lastActivityAt)).font(.caption2).foregroundStyle(.secondary)
                if queued > 0 { Text("\(queued) queued").font(.caption2).foregroundStyle(Theme.busy) }
                if seat.backgroundRunning > 0 {
                    Label("\(seat.backgroundRunning)", systemImage: "gearshape.2")
                        .font(.caption2).foregroundStyle(Theme.busy).labelStyle(.titleAndIcon)
                        .help("\(seat.backgroundRunning) running in the background (shells, monitors, workflows, subagents)")
                }
            }
        }
        .padding(.vertical, 2)
    }

    private var statusColor: Color {
        if needsYou != nil { return Theme.attention }
        if seat.status != "running" { return Theme.muted }
        return seat.activity == "busy" ? Theme.busy : Theme.good
    }
}
