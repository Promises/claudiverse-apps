import ClaudiverseCore
@testable import Claudiverse
import XCTest

/// The usage charts' series, labels and tooltips, from a usage_history response.
final class UsageChartsTests: XCTestCase {
    private let response = """
    {"tz":"Europe/Oslo","from":"2026-10-11","to":"2026-10-12","generated_at":"2026-10-12T10:00:00Z","accounts":[
     {"id":5,"label":"Work","weekly_resets_at":null,"reset_times":[],"days":[
       {"date":"2026-10-11","weekly_used_pct":null,"weekly_pct_start_of_day":null,"weekly_pct_end_of_day":null,"reset_in_day":null,"samples":0,"partial":false},
       {"date":"2026-10-12","weekly_used_pct":0,"weekly_pct_start_of_day":40,"weekly_pct_end_of_day":40,"reset_in_day":false,"samples":12,"partial":true}]},
     {"id":3,"label":"Personal","weekly_resets_at":"2026-10-16T07:00:00Z","reset_times":["2026-10-09T07:00:00Z"],"days":[
       {"date":"2026-10-11","weekly_used_pct":18.25,"weekly_pct_start_of_day":95,"weekly_pct_end_of_day":10,"reset_in_day":true,"samples":288,"partial":false},
       {"date":"2026-10-12","weekly_used_pct":5,"weekly_pct_start_of_day":10,"weekly_pct_end_of_day":15,"reset_in_day":false,"samples":40,"partial":true}]}]}
    """

    private var series: [UsageSeries] { UsageCharts.series(UsageHistory.companion.parse(text: response)) }

    /// Colour follows the account, not the server's order (preferred first): sorted by id.
    func testEachAccountKeepsItsColourWhateverTheOrder() {
        XCTAssertEqual(series.map(\.label), ["Personal", "Work"])
        XCTAssertEqual(series.map(\.slot), [0, 1])
    }

    func testAGapIsNoReadingsAndAZeroIsAZero() {
        let work = series[1]
        XCTAssertNil(work.days[0].used)
        XCTAssertEqual(work.days[1].used, 0)
        XCTAssertEqual(DayTooltip.line(work.days[0], "Work"), "Work: no readings")
        XCTAssertEqual(DayTooltip.line(work.days[1], "Work"), "Work: 0% of weekly limit (40% → 40%), today so far")
        XCTAssertEqual(DayTooltip.line(nil, "Work"), "Work: no readings")
    }

    func testAResetDaySaysSoAndKeepsItsTrueSum() {
        let personal = series[0]
        XCTAssertTrue(personal.days[0].reset)
        XCTAssertEqual(DayTooltip.line(personal.days[0], "Personal"), "Personal: 18.2% of weekly limit (95% → 10%), reset during the day")
        XCTAssertEqual(personal.resets.count, 1)
        XCTAssertNotNil(personal.week, "a reset was seen, so there is a week to draw")
        XCTAssertNil(series[1].week, "never saw a reset: no week")
    }

    /// One day of data still spans the whole window, on a scale of at least 0-10%.
    func testTheDailyChartSpansTheWindowAndAtLeastTenPercent() {
        let range = DailyUseChart.dayRange(series)
        XCTAssertEqual(range.upperBound.timeIntervalSince(range.lowerBound), 2 * 86_400, accuracy: 3600)
        XCTAssertEqual(DailyUseChart.yTop(0), 10)
        XCTAssertEqual(DailyUseChart.yTop(18.25), 21)
    }

    func testPercentagesAndTheTooltipSide() {
        XCTAssertEqual(UsageCharts.pct(18), "18%")
        XCTAssertEqual(UsageCharts.pct(18.25), "18.2%")
        let a = Date(timeIntervalSince1970: 0), b = Date(timeIntervalSince1970: 100)
        XCTAssertEqual(DailyUseChart.tooltipSide(Date(timeIntervalSince1970: 10), [a, b]), .topTrailing)
        XCTAssertEqual(DailyUseChart.tooltipSide(Date(timeIntervalSince1970: 90), [a, b]), .topLeading)
        XCTAssertEqual(DailyUseChart.tooltipSide(nil, [a, b]), .topLeading)
    }
}
