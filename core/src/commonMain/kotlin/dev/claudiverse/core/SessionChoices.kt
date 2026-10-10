package dev.claudiverse.core

/**
 * A model as `/model` takes it: the exact id, so the fleet's Opus 5 and
 * Opus 5.5 seats can be told apart (a bare "opus" alias cannot). [oneM] adds
 * the `[1m]` variant, the 1M-context build; dropping the suffix would quietly
 * switch a seat to the smaller window. Ids are the ones Claude Code 2.1.286/287
 * carries.
 */
data class ModelChoice(val label: String, val id: String, val oneM: Boolean)

/** An id a seat command takes, and how it is shown. */
data class Choice(val id: String, val label: String)

/** What the apps offer for a seat's model, effort and permission mode. */
object SessionChoices {
    // 1M where the model has a 1M build: Anthropic's listing says 1M, or Claude
    // Code itself carries a [1m] id for it (Sonnet 4.6 and 4.5 are listed at
    // 200K but ship 1M builds).
    val models = listOf(
        ModelChoice("Opus 5.5", "claude-opus-5-5", oneM = true),
        ModelChoice("Opus 5", "claude-opus-5", oneM = true),
        ModelChoice("Sonnet 5.5", "claude-sonnet-5-5", oneM = true),
        ModelChoice("Sonnet 5", "claude-sonnet-5", oneM = true),
        ModelChoice("Fable 5.1", "claude-fable-5-1", oneM = true),
        ModelChoice("Fable 5", "claude-fable-5", oneM = true),
        ModelChoice("Mythos 5.1", "claude-mythos-5-1", oneM = true),
        ModelChoice("Mythos 5", "claude-mythos-5", oneM = true),
        ModelChoice("Haiku 4.5", "claude-haiku-4-5", oneM = false),
    )

    /** Earlier generations, folded away so the list stays short. Claude 3 is left out. */
    val olderModels = listOf(
        ModelChoice("Opus 4.8", "claude-opus-4-8", oneM = true),
        ModelChoice("Opus 4.7", "claude-opus-4-7", oneM = true),
        ModelChoice("Opus 4.6", "claude-opus-4-6", oneM = true),
        ModelChoice("Opus 4.5", "claude-opus-4-5", oneM = false),
        ModelChoice("Opus 4.1", "claude-opus-4-1", oneM = false),
        ModelChoice("Sonnet 4.6", "claude-sonnet-4-6", oneM = true),
        ModelChoice("Sonnet 4.5", "claude-sonnet-4-5", oneM = true),
        ModelChoice("Sonnet 4", "claude-sonnet-4-0", oneM = false),
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

    /** A seat reports e.g. "claude-opus-5-5" (or a dated id); match exactly so 5 and 5.5 stay apart. */
    fun isRunning(model: ModelChoice, running: String?): Boolean =
        running == model.id || running?.startsWith(model.id + "-2") == true
}
