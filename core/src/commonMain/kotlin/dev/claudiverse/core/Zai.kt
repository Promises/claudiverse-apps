package dev.claudiverse.core

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A Z.ai (GLM) Coding Plan key on the server: its plan and quota windows as
 * the server last read them (every 5 minutes). The key itself never leaves
 * the server. On [status] "error" the windows keep the last good numbers and
 * [lastError] says why the last check failed.
 */
data class ZaiKey(
    val id: Long,
    val label: String,
    /** "pro", "lite", …; null when not read yet. */
    val level: String?,
    val status: String,
    val lastError: String?,
    /** The server's last check, ISO. */
    val checkedAt: String?,
    val windows: List<ZaiWindow>,
    /** Whose plan it is: "zai" (Z.ai Coding Plan) or "opencode-go"; null from a server that does not say (Z.ai). */
    val provider: String? = null,
    /** The provider account as the operator names it ("someone@example.com"); credentials of one Mistral account share their allowances. */
    val account: String? = null,
    /** The server's order of use, lowest first; null when it does not say. */
    val priority: Int? = null,
) {
    val ok: Boolean get() = status == "ok"

    /** What kind of Mistral credential it is: "API key" or "Vibe session"; null for any other provider. */
    val kind: String? get() = when (provider) {
        MISTRAL -> "API key"
        VIBE -> "Vibe session"
        else -> null
    }

    /** The plan's name, for a heading: "Z.ai Coding Plan", "OpenCode Go", or the server's name for one this app does not know. */
    val providerName: String get() = when (provider) {
        null, ZAI -> ZAI_PLAN
        OPENCODE_GO -> "OpenCode Go"
        MISTRAL, VIBE -> "Mistral (Vibe Pro)"
        else -> provider
    }

    companion object {
        fun from(o: JsonObject) = ZaiKey(
            id = o.requireLong("id"),
            label = o.str("label") ?: "Z.ai key",
            level = o.str("level"),
            status = o.str("status") ?: "unknown",
            lastError = o.str("last_error"),
            checkedAt = o.str("checked_at"),
            windows = o.array("windows").objects().map(ZaiWindow::from),
            provider = o.str("provider"),
            account = o.str("account"),
            priority = o.int("priority"),
        )

        const val ZAI = "zai"
        /** The Z.ai plan's name: where a key is added. */
        const val ZAI_PLAN = "Z.ai Coding Plan"
        const val OPENCODE_GO = "opencode-go"
        /** Mistral's hosted GLM and DeepSeek on the Vibe Pro plan; its quota is one monthly allowance. */
        const val MISTRAL = "mistral"
        /** A signed-in Mistral Vibe session: the same account's allowances as its API key. */
        const val VIBE = "vibe"
    }
}

/** What the Pool shows for keys: one key as it always was, or one Mistral account's credentials together. */
sealed interface ZaiEntry {
    data class Single(val key: ZaiKey) : ZaiEntry

    /**
     * One Mistral account: its [windows] once (both credentials read the same
     * allowances), then each credential in [keys], by priority.
     */
    data class Account(val account: String, val keys: List<ZaiKey>, val windows: List<ZaiWindow>) : ZaiEntry
}

/**
 * The Pool's entries in the server's order: Mistral credentials (API key or
 * Vibe session) that name the same account become one [ZaiEntry.Account],
 * where its first credential was; every other key stays a [ZaiEntry.Single].
 * The account's windows are those of the credential read last that has any.
 */
fun zaiEntries(keys: List<ZaiKey>): List<ZaiEntry> {
    fun accountOf(k: ZaiKey) = k.account?.trim()?.takeIf { it.isNotEmpty() && (k.provider == ZaiKey.MISTRAL || k.provider == ZaiKey.VIBE) }
    val groups = keys.filter { accountOf(it) != null }.groupBy { accountOf(it)!! }
    val placed = mutableSetOf<String>()
    return keys.mapNotNull { k ->
        val account = accountOf(k) ?: return@mapNotNull ZaiEntry.Single(k)
        if (!placed.add(account)) return@mapNotNull null
        val members = groups.getValue(account)
        val windows = members.filter { it.windows.isNotEmpty() }
            .maxByOrNull { parseServerTime(it.checkedAt) ?: Long.MIN_VALUE }?.windows.orEmpty()
        ZaiEntry.Account(account, members.sortedWith(compareBy<ZaiKey>({ it.priority ?: Int.MAX_VALUE }, { it.id })), windows)
    }
}

/** A Mistral account's window: "API ($30)" (month) or "Vibe ($300)" (vibe); any other as [zaiWindowLabel]. */
fun zaiAccountWindowLabel(w: ZaiWindow): String = when (w.window) {
    "month" -> "API ($30)"
    "vibe" -> "Vibe ($300)"
    else -> zaiWindowLabel(w)
}

/** One quota window of a key: credits used of the limit, and when it resets (null on an unused window). */
data class ZaiWindow(
    val window: String,
    val used: Long?,
    val limit: Long?,
    val remaining: Long?,
    val percent: Int?,
    val resetsAt: String?,
) {
    /** Used up: the quota is gone until it resets. */
    val spent: Boolean get() = (percent ?: 0) >= 100 || (remaining == 0L && (limit ?: 0) > 0)

    companion object {
        fun from(o: JsonObject) = ZaiWindow(
            window = o.str("window") ?: "?",
            used = o.long("used"),
            limit = o.long("limit"),
            remaining = o.long("remaining"),
            percent = o.int("percent"),
            resetsAt = o.str("resets_at"),
        )
    }
}

/** A window's name for people, as the web has it: "5h" → "5 hours", "week" → "Week", any other as is. */
fun zaiWindowLabel(w: ZaiWindow): String = when (w.window) {
    "5h" -> "5 hours"
    "week" -> "Week"
    "month" -> "Month"
    else -> w.window
}

/**
 * A window's line under its bar: "60078 of 60000 credits · spent until
 * tomorrow 09:08" when used up, else "0 of 12000 credits" and when it resets
 * if the server says. An OpenCode Go window (a percent, no credits) is just
 * "resets tomorrow 01:22".
 */
@OptIn(ExperimentalTime::class)
fun zaiWindowLine(w: ZaiWindow, now: Long = Clock.System.now().toEpochMilliseconds(), zone: TimeZone = TimeZone.currentSystemDefault()): String {
    // An OpenCode Go window has a percent only: no credits to show.
    val credits = if (w.used == null && w.limit == null) null else "${w.used ?: "?"} of ${w.limit ?: "?"} credits"
    val reset = parseServerTime(w.resetsAt)?.let { resetTime(it, now, zone) }
    val tail = when {
        w.spent -> "spent until ${reset ?: "it resets"}"
        reset != null -> "resets $reset"
        else -> null
    }
    return listOfNotNull(credits, tail).joinToString(" · ")
}

/** A coming time, briefly: "09:08" today, "tomorrow 09:08", "Mon 09:08" within the week, else "2 Nov 09:08". */
@OptIn(ExperimentalTime::class)
fun resetTime(at: Long, now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
    val hm = "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    val days = t.date.toEpochDays() - today.toEpochDays()
    return when {
        days <= 0 -> hm
        days == 1L -> "tomorrow $hm"
        days < 7 -> t.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() } + " " + hm
        else -> "${t.day} ${t.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} $hm"
    }
}
