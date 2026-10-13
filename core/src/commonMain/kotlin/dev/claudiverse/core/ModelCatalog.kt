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
    /** The models a seat can be started with on a machine running Claude Code [version]: [SPAWN_IDS] it knows. */
    fun spawnModels(version: String?): List<ModelChoice> =
        SPAWN_IDS.mapNotNull { id -> models.firstOrNull { it.id == id } }.filter { it.knownBy(version) }

    companion object {
        /**
         * Exact ids, so a seat shows as the model it runs: a bare "haiku" is
         * Haiku 4.5 on a machine before Claude Code 2.1.293 and 5.5 after.
         */
        val SPAWN_IDS = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-haiku-4-5")

        val BUILT_IN = ModelCatalog(SessionChoices.models, SessionChoices.olderModels, SessionChoices.aliases, fromServer = false)

        /**
         * The server's list: models in display order, each `older` or not.
         * oneM is its `accepts_1m_suffix` ([1m] opts into 1M); a model whose
         * bare id is already 1M has `one_m` and needs no variant.
         */
        fun from(o: JsonObject): ModelCatalog {
            val all = o.array("models").objects().mapNotNull { m ->
                val id = m.str("id") ?: return@mapNotNull null
                ModelChoice(m.str("label") ?: id, id, oneM = m.flag("accepts_1m_suffix"), since = m.str("since")) to m.flag("older")
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

/** The model list, built in until [refresh] reads the server's; a server without one, or one out of reach, keeps what there is. */
class ModelCatalogs(private val api: Api, private val scope: CoroutineScope) {
    private val state = MutableStateFlow(ModelCatalog.BUILT_IN)
    val catalog: StateFlow<ModelCatalog> = state.asStateFlow()

    fun refresh() {
        scope.launch {
            try {
                api.modelCatalog()?.let { state.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CoreLog.info("models: kept the ${if (state.value.fromServer) "server's" else "built-in"} list: ${e.message}")
            }
        }
    }
}
