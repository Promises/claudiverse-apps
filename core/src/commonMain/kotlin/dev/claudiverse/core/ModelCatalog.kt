package dev.claudiverse.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * The models the apps offer for a seat: the server's list (GET /api/models,
 * the one the web panel shows too), or the built-in [SessionChoices] lists
 * until it is read and on a server without it.
 */
data class ModelCatalog(
    val models: List<ModelChoice>,
    val olderModels: List<ModelChoice>,
    val aliases: List<Choice>,
    /** Read from the server rather than built in. */
    val fromServer: Boolean,
) {
    /**
     * The models a seat can be started with on a machine running Claude Code
     * [version]: every current one the list has (Z.ai's GLM too), by exact id
     * so a seat shows as the model it runs, and only those that Claude Code
     * knows (a bare "haiku" is Haiku 4.5 before 2.1.293 and 5.5 after).
     */
    fun spawnModels(version: String?): List<ModelChoice> = models.filter { it.knownBy(version) }

    /**
     * What New seat offers, one group per family in the list's order (Opus,
     * Sonnet, …, then "GLM (Z.ai)"), each with its [ModelGroup.older] models
     * kept apart to fold away; only models the machine's Claude Code knows.
     * The built-in list has no families: one "Claude" group.
     */
    fun spawnGroups(version: String?): List<ModelGroup> {
        val current = models.filter { it.knownBy(version) }.groupBy { it.family }
        val older = olderModels.filter { it.knownBy(version) }.groupBy { it.family }
        return (current.keys + older.keys).map { f ->
            ModelGroup(f ?: CLAUDE, groupLabel(f), current[f].orEmpty(), older[f].orEmpty())
        }
    }

    companion object {
        /** Z.ai's GLM models, served through the server's proxy. */
        const val GLM = "glm"

        /** DeepSeek models, billed through OpenCode credits. */
        const val DEEPSEEK = "deepseek"

        /** Mistral's own models, on the operator's Mistral Vibe Pro plan. */
        const val MISTRAL = "mistral"

        /** The group of a model without a family: the built-in list, all Claude. */
        const val CLAUDE = "claude"

        /** "Opus", "Sonnet", "GLM (Z.ai)"; a family this app does not know yet, by its name. */
        fun groupLabel(family: String?): String = when (family) {
            null -> "Claude"
            GLM -> "GLM (Z.ai)"
            DEEPSEEK -> "DeepSeek (OpenCode)"
            MISTRAL -> "Mistral (Vibe Pro)"
            else -> family.replaceFirstChar { it.uppercase() }
        }

        val BUILT_IN = ModelCatalog(SessionChoices.models, SessionChoices.olderModels, SessionChoices.aliases, fromServer = false)

        /**
         * The server's list: models in display order, each `older` or not.
         * oneM is its `accepts_1m_suffix` ([1m] opts into 1M); a model whose
         * bare id is already 1M has `one_m` and needs no variant.
         */
        fun from(o: JsonObject): ModelCatalog {
            val all = o.array("models").objects().mapNotNull { m ->
                val id = m.str("id") ?: return@mapNotNull null
                ModelChoice(m.str("label") ?: id, id, oneM = m.flag("accepts_1m_suffix"), since = m.str("since"), family = m.str("family")) to m.flag("older")
            }
            val aliases = o.array("aliases").objects().mapNotNull { a -> a.str("id")?.let { Choice(it, a.str("label") ?: it) } }
            return ModelCatalog(
                models = all.filterNot { it.second }.map { it.first },
                olderModels = all.filter { it.second }.map { it.first },
                aliases = aliases,
                fromServer = true,
            )
        }
    }
}

/** One family's models as New seat offers them: [models] first, [older] folded away. [id] is the family. */
data class ModelGroup(val id: String, val label: String, val models: List<ModelChoice>, val older: List<ModelChoice>)

/** The id a spawn sends for [choice]: with `[1m]` when [oneM] is asked and the model takes it ([ModelChoice.spawnOneM]); null for the machine's default. */
fun spawnModelId(choice: ModelChoice?, oneM: Boolean): String? = choice?.let { if (oneM && it.spawnOneM) "${it.id}[1m]" else it.id }

/** The model list, built in until [refresh] reads the server's; a server without one, or one out of reach, keeps what there is. */
class ModelCatalogs(private val api: Api, private val scope: CoroutineScope) {
    private val state = MutableStateFlow(ModelCatalog.BUILT_IN)
    val catalog: StateFlow<ModelCatalog> = state.asStateFlow()

    fun refresh() {
        scope.launch {
            try {
                val served = api.modelCatalog()
                if (served != null) state.value = served
                CoreLog.info(
                    if (served != null) "models: ${served.models.size + served.olderModels.size} from the server"
                    else "models: the server has no list; the ${if (state.value.fromServer) "server's" else "built-in"} one stays",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CoreLog.info("models: kept the ${if (state.value.fromServer) "server's" else "built-in"} list: ${e.message}")
            }
        }
    }
}
