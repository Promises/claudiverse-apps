package dev.claudiverse.core

import kotlinx.serialization.json.JsonObject

/**
 * How each Anthropic account used its weekly limit, day by day, from
 * GET /api/anthropic_auths/usage_history. Dates are local to [tz]; every
 * account has one [UsageDay] per date from [from] to [to], oldest first.
 */
data class UsageHistory(
    val tz: String,
    val from: String,
    val to: String,
    val accounts: List<AccountUsage>,
) {
    companion object {
        /** From the response's text: for a client that has the JSON already (a saved response, a test). */
        fun parse(text: String): UsageHistory = from(kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject)

        fun from(o: JsonObject) = UsageHistory(
            tz = o.text("tz"),
            from = o.text("from"),
            to = o.text("to"),
            accounts = o.array("accounts").objects().map(AccountUsage::from),
        )
    }
}

/** One account's days, its raw readings (when asked for), and its weekly resets. */
data class AccountUsage(
    val id: Long,
    val label: String,
    val days: List<UsageDay>,
    /** Readings, oldest first; empty unless asked for (raw=1). */
    val samples: List<UsageSample>,
    /** The next weekly reset as last seen, ms; null if never seen. */
    val weeklyResetsAtMs: Long?,
    /** Every weekly reset inside the window, ms, oldest first. */
    val resetTimesMs: List<Long>,
) {
    companion object {
        fun from(o: JsonObject): AccountUsage {
            val samples = o.array("samples").objects().map(UsageSample::from)
            return AccountUsage(
                id = o.requireLong("id"),
                label = o.text("label"),
                days = o.array("days").objects().map(UsageDay::from),
                samples = samples,
                // The account's own field; an older server leaves it out, and the newest reading's then says it.
                weeklyResetsAtMs = parseServerTime(o.str("weekly_resets_at"))
                    ?: samples.lastOrNull { it.weeklyResetsAtMs != null }?.weeklyResetsAtMs,
                resetTimesMs = o.array("reset_times").strings().mapNotNull(::parseServerTime).sorted(),
            )
        }
    }
}

/**
 * One local day of an account's weekly limit. [usedPct] is percentage points
 * of the weekly limit used that day: 0 for a day with readings and no use,
 * null for a day with no readings (a gap, not a zero). Across a reset it
 * counts the new period from zero, so it never goes negative.
 */
data class UsageDay(
    val date: String,
    val usedPct: Double?,
    val startPct: Double?,
    val endPct: Double?,
    val resetInDay: Boolean?,
    val samples: Int,
    /** Today: still counting. */
    val partial: Boolean,
) {
    val hasData: Boolean get() = usedPct != null

    companion object {
        fun from(o: JsonObject) = UsageDay(
            date = o.text("date"),
            usedPct = o.double("weekly_used_pct")?.coerceAtLeast(0.0),
            startPct = o.double("weekly_pct_start_of_day"),
            endPct = o.double("weekly_pct_end_of_day"),
            resetInDay = o.bool("reset_in_day"),
            samples = o.int("samples") ?: 0,
            partial = o.flag("partial"),
        )
    }
}

/** One reading of an account's limits. */
data class UsageSample(val atMs: Long, val weeklyPct: Double?, val weeklyResetsAtMs: Long?) {
    companion object {
        fun from(o: JsonObject) = UsageSample(
            atMs = parseServerTime(o.str("at")) ?: 0,
            weeklyPct = o.double("weekly_pct"),
            weeklyResetsAtMs = parseServerTime(o.str("weekly_resets_at")),
        )
    }
}

/**
 * The current weekly period of one account, for a burn-down line: from
 * [startMs] (a week before [resetMs]) to the reset, with the period's
 * readings in order. [points] holds only readings inside the period that
 * carry a weekly value.
 */
data class WeekLine(val startMs: Long, val resetMs: Long, val points: List<UsageSample>) {
    /** The latest weekly %, or null with no reading yet. */
    val latestPct: Double? get() = points.lastOrNull()?.weeklyPct
}

const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L

/**
 * The week [nowMs] falls in, for [account]: null when no weekly reset was
 * ever seen. A reset that has already passed (the last reading before it is
 * the newest one) rolls forward a week at a time, since the limit resets
 * weekly whether or not a new reading has said so.
 */
fun currentWeek(account: AccountUsage, nowMs: Long): WeekLine? {
    var reset = account.weeklyResetsAtMs ?: return null
    while (reset <= nowMs) reset += WEEK_MS
    val start = reset - WEEK_MS
    val points = account.samples.filter { it.weeklyPct != null && it.atMs >= start && it.atMs <= nowMs }.sortedBy { it.atMs }
    return WeekLine(start, reset, points)
}
