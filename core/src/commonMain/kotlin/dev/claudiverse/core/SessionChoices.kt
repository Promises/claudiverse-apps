package dev.claudiverse.core

/**
 * A model as `/model` and `--model` take it: the exact id, so the fleet's
 * Opus 5 and Opus 5.5 seats can be told apart (a bare "opus" alias cannot).
 *
 * [oneM]: the model's window is 200K and its `[1m]` variant opts into 1M;
 * dropping the suffix would quietly switch a seat to the smaller window. A
 * model whose bare id is already 1M has no oneM: `[1m]` changes nothing
 * there, and most of them have no `[1m]` spelling at all.
 *
 * [since]: the first Claude Code version that knows the id, when the fleet
 * may still run older ones.
 */
data class ModelChoice(val label: String, val id: String, val oneM: Boolean, val since: String? = null) {
    /** Whether Claude Code [version] knows this model. An unknown version counts as too old for a model with a [since]. */
    fun knownBy(version: String?): Boolean {
        if (since == null) return true
        // A seat's client_version carries its mod after the number: compare the number alone.
        val v = ClientVersion.parse(version).claudeCode ?: return false
        return versionAtLeast(v, since)
    }
}

/** An id a seat command takes, and how it is shown. */
data class Choice(val id: String, val label: String)

/** What the apps offer for a seat's model, effort and permission mode. */
object SessionChoices {
    // As Claude Code's own model catalog has it (measured in 2.1.287, 2.1.291
    // and 2.1.296, which agree): every Claude 5 model, Opus 4.7 and Opus 4.8
    // are native 1M, so their bare id is the 1M window. [1m] opts in only on a
    // 200K model with the 1M beta, never on Opus 4.5/4.1 or Haiku 4.5.
    // Haiku 5.5 is new in 2.1.293.
    val models = listOf(
        ModelChoice("Opus 5.5", "claude-opus-5-5", oneM = false),
        ModelChoice("Opus 5", "claude-opus-5", oneM = false),
        ModelChoice("Sonnet 5.5", "claude-sonnet-5-5", oneM = false),
        ModelChoice("Sonnet 5", "claude-sonnet-5", oneM = false),
        ModelChoice("Fable 5.1", "claude-fable-5-1", oneM = false),
        ModelChoice("Fable 5", "claude-fable-5", oneM = false),
        ModelChoice("Mythos 5.1", "claude-mythos-5-1", oneM = false),
        ModelChoice("Mythos 5", "claude-mythos-5", oneM = false),
        ModelChoice("Haiku 5.5", "claude-haiku-5-5", oneM = false, since = "2.1.293"),
        ModelChoice("Haiku 4.5", "claude-haiku-4-5", oneM = false),
    )

    /** Earlier generations, folded away so the list stays short. Claude 3 is left out. The server's list replaces these when it has one ([ModelCatalog]). */
    val olderModels = listOf(
        ModelChoice("Opus 4.8", "claude-opus-4-8", oneM = false),
        ModelChoice("Opus 4.7", "claude-opus-4-7", oneM = false),
        ModelChoice("Opus 4.6", "claude-opus-4-6", oneM = true),
        ModelChoice("Opus 4.5", "claude-opus-4-5", oneM = false),
        ModelChoice("Opus 4.1", "claude-opus-4-1", oneM = false),
        ModelChoice("Sonnet 4.6", "claude-sonnet-4-6", oneM = true),
        ModelChoice("Sonnet 4.5", "claude-sonnet-4-5", oneM = true),
        ModelChoice("Sonnet 4", "claude-sonnet-4-0", oneM = true),
    )

    /** Aliases with no single model behind them. */
    val aliases = listOf(Choice("default", "Default"), Choice("opusplan", "Opus plan"))

    /** What `/effort` accepts. */
    val efforts = listOf("low", "medium", "high", "xhigh", "max", "auto")

    /**
     * The modes the apps may set. bypassPermissions and dontAsk are left out on
     * purpose: a mis-tap must not switch off a seat's permission checks.
     */
    /** A seat's claudiverse tools, least first. Paging is a separate switch. */
    val accessLevels = listOf(Choice("basic", "Basic"), Choice("full", "Full"))

    val modes = listOf(Choice("default", "Default"), Choice("acceptEdits", "Accept edits"), Choice("plan", "Plan"), Choice("auto", "Auto"))

    /**
     * A seat reports e.g. "claude-opus-5-5" (or a dated id); match exactly so 5
     * and 5.5 stay apart. A model a seat was started with may carry `[1m]`.
     */
    fun isRunning(model: ModelChoice, running: String?): Boolean {
        val r = running?.replace(ONE_M, "") ?: return false
        return r == model.id || r.startsWith(model.id + "-2")
    }

    private val ONE_M = Regex("""\[1m]""", RegexOption.IGNORE_CASE)
}

/** Dotted versions compared part by part as numbers ("2.1.296" >= "2.1.293"); a part that is not a number counts as 0. */
internal fun versionAtLeast(version: String, min: String): Boolean {
    val a = version.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val b = min.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return true
}
