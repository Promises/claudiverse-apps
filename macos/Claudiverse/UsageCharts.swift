import Charts
import ClaudiverseCore
import SwiftUI

/**
 * How the pool's accounts use their weekly limits: a bar per account per day
 * for the last four weeks (points of the weekly limit used that day, a gap
 * for a day with no readings, the weekly resets marked), and this week's
 * running total per account against time, with 100% and each reset marked.
 * Read from GET /api/anthropic_auths/usage_history every five minutes while
 * shown.
 */
struct UsageCharts: View {
    @Environment(AppModel.self) private var model
    @State private var history: UsageHistory?
    @State private var error: String?
    @State private var showTable = false
    /// Drawn as given and never read from the server: the harness's preview.
    private let preset: UsageHistory?

    init(preset: UsageHistory? = nil) {
        self.preset = preset
        _history = State(initialValue: preset)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Weekly limit use").font(.headline)
            if let error {
                Text(error).font(.callout).foregroundStyle(.secondary)
            } else if let history {
                let accounts = Self.series(history)
                if accounts.isEmpty || accounts.allSatisfy({ $0.days.allSatisfy { $0.used == nil } }) {
                    Text("No readings yet: the server keeps a reading of each account's limits from when it learnt to.")
                        .font(.callout).foregroundStyle(.secondary)
                } else {
                    Legend(accounts: accounts)
                    DailyUseChart(accounts: accounts)
                    WeekBurnChart(accounts: accounts)
                    Toggle("Show as table", isOn: $showTable).toggleStyle(.checkbox).font(.callout)
                    if showTable { UsageTable(accounts: accounts) }
                }
            } else {
                ProgressView().controlSize(.small)
            }
        }
        .task {
            guard preset == nil else { return }
            while !Task.isCancelled {
                await load()
                try? await Task.sleep(for: .seconds(300))
            }
        }
    }

    private func load() async {
        do {
            history = try await model.client.api.usageHistory(days: 28, tz: TimeZone.current.identifier, raw: true)
            error = nil
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            self.error = error.httpStatus == 404
                ? "This server keeps no usage history yet."
                : "Usage history not read: \(error.message)"
        }
    }

    /// The accounts as the charts draw them: in id order, so each keeps its colour whatever the pool's order.
    static func series(_ h: UsageHistory) -> [UsageSeries] {
        let now = Date()
        return h.accounts.sorted { $0.id < $1.id }.enumerated().map { i, a in
            let week = currentWeek(account: a, nowMs: Int64(now.timeIntervalSince1970 * 1000))
            return UsageSeries(
                id: a.id, label: a.label, slot: i,
                days: a.days.compactMap { d in
                    Self.localDate(d.date).map {
                        UsageSeries.Day(date: $0, used: d.usedPct?.doubleValue, start: d.startPct?.doubleValue, end: d.endPct?.doubleValue,
                                        reset: d.resetInDay?.boolValue == true, samples: Int(d.samples), partial: d.partial)
                    }
                },
                resets: a.resetTimesMs.map { Date(timeIntervalSince1970: Double($0.int64Value) / 1000) },
                week: week.map { w in
                    UsageSeries.Week(
                        start: Date(timeIntervalSince1970: Double(w.startMs) / 1000),
                        reset: Date(timeIntervalSince1970: Double(w.resetMs) / 1000),
                        points: w.points.compactMap { p in
                            p.weeklyPct.map { (Date(timeIntervalSince1970: Double(p.atMs) / 1000), $0.doubleValue) }
                        })
                })
        }
    }

    /// "2026-10-11" as the start of that day here (the server was asked for this zone's days).
    static func localDate(_ s: String) -> Date? {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        f.dateFormat = "yyyy-MM-dd"
        return f.date(from: s)
    }

    /// "18.0%" with one decimal; whole numbers stay whole.
    static func pct(_ v: Double) -> String {
        v.rounded() == v ? "\(Int(v))%" : String(format: "%.1f%%", v)
    }
}

/// One account, ready to draw.
struct UsageSeries: Identifiable {
    struct Day {
        let date: Date
        /// Points of the weekly limit used that day; nil for a day with no readings.
        let used: Double?
        let start: Double?
        let end: Double?
        let reset: Bool
        let samples: Int
        let partial: Bool
    }

    struct Week {
        let start: Date
        let reset: Date
        let points: [(at: Date, pct: Double)]
    }

    let id: Int64
    let label: String
    /// Its place in the categorical order: its colour.
    let slot: Int
    let days: [Day]
    let resets: [Date]
    let week: Week?

    var color: Color { UsagePalette.color(slot) }
}

/**
 * The categorical slots, light and dark, validated with the data-viz
 * validator against the window surface (all pairs: CVD and normal vision
 * pass in both modes). Two light slots are under 3:1 on the light surface,
 * so every series also has a text label (legend, line ends, table).
 */
enum UsagePalette {
    /// Grid and reference lines: a plain grey. `.secondary` here takes the chart's series colours.
    static let grid = Color.gray.opacity(0.22)
    static let reference = Color.gray.opacity(0.8)

    static let slots: [(light: UInt32, dark: UInt32)] = [(0x2A78D6, 0x3987E5), (0xEB6834, 0xD95926), (0x1BAF7A, 0x199E70)]

    static func color(_ slot: Int) -> Color {
        // Past the validated three, a series is drawn in the text colour: never a generated hue.
        guard slot < slots.count else { return .secondary }
        return Color(light: slots[slot].light, dark: slots[slot].dark)
    }
}

private struct Legend: View {
    let accounts: [UsageSeries]

    var body: some View {
        HStack(spacing: 14) {
            ForEach(accounts) { a in
                HStack(spacing: 5) {
                    RoundedRectangle(cornerRadius: 2).fill(a.color).frame(width: 12, height: 12)
                    Text(a.label).font(.callout)
                }
            }
            Spacer()
            Text("% of weekly limit").font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// A bar per account per day, side by side; a gap for no readings; a dashed tick at each weekly reset.
struct DailyUseChart: View {
    let accounts: [UsageSeries]
    @State private var hovered: Date?

    /// The tooltip sits on the side away from the pointer: right while it hovers the left half, left otherwise.
    static func tooltipSide(_ at: Date?, _ span: [Date]) -> Alignment {
        guard let at, let first = span.min(), let last = span.max(), last > first else { return .topLeading }
        return at.timeIntervalSince(first) < last.timeIntervalSince(first) / 2 ? .topTrailing : .topLeading
    }

    var body: some View {
        // A day with readings and no use gets a stub on the baseline, so it reads apart from a gap.
        let top = accounts.flatMap { $0.days.compactMap(\.used) }.max() ?? 0
        let stub = max(top, 10) * 0.015
        VStack(alignment: .leading, spacing: 16) {
            Text("Used each day, last 4 weeks").font(.callout).foregroundStyle(.secondary)
            Chart {
                ForEach(accounts) { a in
                    ForEach(a.days.filter { $0.used != nil }, id: \.date) { d in
                        let used = d.used ?? 0
                        BarMark(x: .value("Day", d.date, unit: .day), yStart: .value("% of weekly limit", 0),
                                yEnd: .value("% of weekly limit", max(used, stub)))
                            .foregroundStyle(a.color.opacity(used == 0 ? 0.45 : d.partial ? 0.55 : 1))
                            .position(by: .value("Account", a.label), axis: .horizontal, span: .ratio(0.85))
                            .clipShape(UnevenRoundedRectangle(topLeadingRadius: used == 0 ? 0 : 4, topTrailingRadius: used == 0 ? 0 : 4))
                    }
                    ForEach(a.resets, id: \.self) { r in
                        RuleMark(x: .value("Reset", r))
                            .foregroundStyle(a.color.opacity(0.7))
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                            .annotation(position: .top, alignment: .center, spacing: 2) {
                                Image(systemName: "arrow.counterclockwise").font(.system(size: 9)).foregroundStyle(.secondary)
                                    .help("\(a.label)'s weekly limit reset")
                            }
                    }
                }
                if let hovered {
                    RectangleMark(x: .value("Day", hovered, unit: .day)).foregroundStyle(Color.gray.opacity(0.12))
                }
            }
            .chartForegroundStyleScale(domain: accounts.map(\.label), range: accounts.map(\.color))
            .chartLegend(.hidden)
            .chartYAxis {
                AxisMarks(position: .leading) { v in
                    AxisGridLine().foregroundStyle(UsagePalette.grid)
                    AxisValueLabel { if let p = v.as(Double.self) { Text(UsageCharts.pct(p)) } }
                }
            }
            .chartXAxis {
                AxisMarks(values: .stride(by: .day, count: 7)) { _ in
                    AxisGridLine().foregroundStyle(UsagePalette.grid)
                    AxisValueLabel(format: .dateTime.day().month(.abbreviated))
                }
            }
            .chartOverlay { proxy in
                GeometryReader { geo in
                    Rectangle().fill(.clear).contentShape(Rectangle())
                        .onContinuousHover { phase in
                            switch phase {
                            case .active(let at):
                                guard let frame = proxy.plotFrame else { return }
                                let x = at.x - geo[frame].origin.x
                                hovered = proxy.value(atX: x, as: Date.self).map { Calendar.current.startOfDay(for: $0) }
                            case .ended: hovered = nil
                            }
                        }
                }
            }
            .overlay(alignment: Self.tooltipSide(hovered, accounts.first?.days.map(\.date) ?? [])) {
                if let hovered { DayTooltip(day: hovered, accounts: accounts).padding(6) }
            }
            .frame(height: 220)
        }
    }
}

/// The hovered day: each account's use that day, and how it got there.
struct DayTooltip: View {
    let day: Date
    let accounts: [UsageSeries]

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(day.formatted(.dateTime.weekday(.wide).day().month(.wide))).font(.caption.weight(.semibold))
            ForEach(accounts) { a in
                HStack(spacing: 5) {
                    RoundedRectangle(cornerRadius: 2).fill(a.color).frame(width: 8, height: 8)
                    Text(Self.line(a.days.first { Calendar.current.isDate($0.date, inSameDayAs: day) }, a.label)).font(.caption)
                }
            }
        }
        .padding(8)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
        .allowsHitTesting(false)
    }

    /// "Personal: 18% of weekly limit (7.5% → 25.5%), reset during the day"
    static func line(_ d: UsageSeries.Day?, _ label: String) -> String {
        guard let d, let used = d.used else { return "\(label): no readings" }
        var s = "\(label): \(UsageCharts.pct(used)) of weekly limit"
        if let start = d.start, let end = d.end { s += " (\(UsageCharts.pct(start)) → \(UsageCharts.pct(end)))" }
        if d.reset { s += ", reset during the day" }
        if d.partial { s += ", today so far" }
        return s
    }
}

/// This week's running total per account against time: 100% marked, and each account's reset.
private struct WeekBurnChart: View {
    let accounts: [UsageSeries]
    @State private var hovered: Date?

    private var weeks: [(UsageSeries, UsageSeries.Week)] { accounts.compactMap { a in a.week.map { (a, $0) } } }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("This week so far").font(.callout).foregroundStyle(.secondary)
            if weeks.allSatisfy({ $0.1.points.isEmpty }) {
                Text("No readings this week yet.").font(.callout).foregroundStyle(.secondary).frame(height: 60)
            } else {
                Chart {
                    RuleMark(y: .value("Limit", 100))
                        .foregroundStyle(UsagePalette.reference)
                        .lineStyle(StrokeStyle(lineWidth: 1))
                    ForEach(weeks, id: \.0.id) { a, w in
                        ForEach(Array(w.points.enumerated()), id: \.offset) { _, p in
                            LineMark(x: .value("Time", p.at), y: .value("% of weekly limit", p.pct), series: .value("Account", a.label))
                                .foregroundStyle(a.color)
                                .lineStyle(StrokeStyle(lineWidth: 2))
                                .interpolationMethod(.stepEnd)
                        }
                        if let last = w.points.last {
                            PointMark(x: .value("Time", last.at), y: .value("% of weekly limit", last.pct))
                                .foregroundStyle(a.color).symbolSize(48)
                                .annotation(position: .trailing, spacing: 4) {
                                    Text("\(a.label) \(UsageCharts.pct(last.pct))").font(.caption).foregroundStyle(.primary)
                                }
                        }
                        RuleMark(x: .value("Reset", w.reset))
                            .foregroundStyle(a.color.opacity(0.8))
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                            .annotation(position: .top, alignment: .center, spacing: 2) {
                                Text("\(a.label) resets").font(.caption2).foregroundStyle(.secondary)
                            }
                    }
                    if let hovered { RuleMark(x: .value("Time", hovered)).foregroundStyle(UsagePalette.reference) }
                }
                .chartForegroundStyleScale(domain: accounts.map(\.label), range: accounts.map(\.color))
                .chartLegend(.hidden)
                .chartYScale(domain: 0...105)
                .chartXScale(domain: (weeks.map(\.1.start).min() ?? .now)...(weeks.map(\.1.reset).max() ?? .now))
                .chartYAxis {
                    AxisMarks(position: .leading, values: [0, 25, 50, 75, 100]) { v in
                        AxisGridLine().foregroundStyle(UsagePalette.grid)
                        AxisValueLabel { if let p = v.as(Double.self) { Text(UsageCharts.pct(p)) } }
                    }
                }
                .chartXAxis {
                    AxisMarks(values: .stride(by: .day)) { _ in
                        AxisGridLine().foregroundStyle(UsagePalette.grid)
                        AxisValueLabel(format: .dateTime.weekday(.abbreviated))
                    }
                }
                .chartOverlay { proxy in
                    GeometryReader { geo in
                        Rectangle().fill(.clear).contentShape(Rectangle())
                            .onContinuousHover { phase in
                                switch phase {
                                case .active(let at):
                                    guard let frame = proxy.plotFrame else { return }
                                    hovered = proxy.value(atX: at.x - geo[frame].origin.x, as: Date.self)
                                case .ended: hovered = nil
                                }
                            }
                    }
                }
                .overlay(alignment: DailyUseChart.tooltipSide(hovered, weeks.flatMap { [$0.1.start, $0.1.reset] })) {
                    if let hovered { TimeTooltip(at: hovered, weeks: weeks).padding(6) }
                }
                .padding(.trailing, 90)
                .frame(height: 200)
            }
        }
    }
}

/// The hovered time: each account's total then, the latest reading at or before it.
private struct TimeTooltip: View {
    let at: Date
    let weeks: [(UsageSeries, UsageSeries.Week)]

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(at.formatted(.dateTime.weekday(.abbreviated).hour().minute())).font(.caption.weight(.semibold))
            ForEach(weeks, id: \.0.id) { a, w in
                HStack(spacing: 5) {
                    RoundedRectangle(cornerRadius: 2).fill(a.color).frame(width: 8, height: 8)
                    Text(Self.line(a.label, w, at)).font(.caption)
                }
            }
        }
        .padding(8)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
        .allowsHitTesting(false)
    }

    static func line(_ label: String, _ w: UsageSeries.Week, _ at: Date) -> String {
        if at < w.start { return "\(label): previous week" }
        if at > (w.points.last?.at ?? w.start) { return "\(label): no reading yet" }
        guard let p = w.points.last(where: { $0.at <= at }) else { return "\(label): no reading yet" }
        return "\(label): \(UsageCharts.pct(p.pct)) of weekly limit"
    }
}

/// The same days as a table: one row per day, one column per account.
private struct UsageTable: View {
    let accounts: [UsageSeries]

    var body: some View {
        let dates = (accounts.first?.days.map(\.date) ?? []).reversed()
        Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 4) {
            GridRow {
                Text("Day").fontWeight(.semibold)
                ForEach(accounts) { a in Text(a.label).fontWeight(.semibold).gridColumnAlignment(.trailing) }
            }
            ForEach(Array(dates), id: \.self) { date in
                GridRow {
                    Text(date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)))
                    ForEach(accounts) { a in
                        let d = a.days.first { $0.date == date }
                        Text(Self.cell(d)).monospacedDigit().foregroundStyle(d?.used == nil ? .secondary : .primary)
                    }
                }
            }
        }
        .font(.callout)
        .textSelection(.enabled)
    }

    static func cell(_ d: UsageSeries.Day?) -> String {
        guard let used = d?.used else { return "–" }
        return UsageCharts.pct(used) + (d?.reset == true ? " ↻" : "")
    }
}
