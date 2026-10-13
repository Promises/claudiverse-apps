package dev.claudiverse.app.spawn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.claudiverse.app.AppContainer
import dev.claudiverse.core.Fleet
import dev.claudiverse.core.Host
import dev.claudiverse.core.RestartBatchEvent
import dev.claudiverse.core.RestartOptions
import dev.claudiverse.core.RestartPlan
import dev.claudiverse.core.RestartRow
import dev.claudiverse.core.foldRestart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RestartIdleState(
    val hosts: List<Host> = emptyList(),
    /** The machine whose idle seats restart; null for every machine. */
    val host: String? = null,
    val fleets: List<Fleet> = emptyList(),
    /** Fleets left out: the paused ones until the operator ticks them in. */
    val excludedFleets: Set<String> = emptySet(),
    val onlyOutdated: Boolean = true,
    val plan: List<RestartPlan> = emptyList(),
    val planning: Boolean = true,
    val problem: String? = null,
    /** The seats to restart: by default every one the plan takes that is not protected. */
    val selected: Set<Long> = emptySet(),
    /** Set once the server has taken the batch: then the screen shows its progress. */
    val batchId: String? = null,
    val rows: List<RestartRow> = emptyList(),
    val batch: RestartBatchEvent? = null,
    val starting: Boolean = false,
) {
    val selectedPlans: List<RestartPlan> get() = plan.filter { it.sessionId in selected }
    val protectedSelected: Int get() = selectedPlans.count { it.isProtected }
}

/**
 * Restart idle seats: what a batch would do first (a dry run, read again as
 * the options change), then, once confirmed, the server restarts the chosen
 * seats one at a time and their events show its progress.
 */
class RestartIdleViewModel(private val app: AppContainer) : ViewModel() {

    private val state = MutableStateFlow(RestartIdleState())
    val ui: StateFlow<RestartIdleState> = state.asStateFlow()
    private var planning: Job? = null

    init {
        app.watchRestarts()
        viewModelScope.launch {
            val hosts = runCatching { app.api.hosts() }.getOrDefault(emptyList())
            val fleets = runCatching { app.api.fleets() }.getOrDefault(emptyList())
            state.update { it.copy(hosts = hosts, fleets = fleets, excludedFleets = fleets.filter { f -> f.paused }.map { f -> f.fleet }.toSet()) }
            replan()
        }
        // A started batch: its seats with their latest outcome, and its own state.
        viewModelScope.launch {
            combine(state, app.restarts.events, app.restarts.batches) { s, events, batches -> Triple(s, events, batches) }
                .collect { (s, events, batches) ->
                    val id = s.batchId ?: return@collect
                    val rows = foldRestart(s.selectedPlans.ifEmpty { s.plan }, events, id)
                    if (rows != s.rows || batches[id] != s.batch) state.update { it.copy(rows = rows, batch = batches[id]) }
                }
        }
    }

    override fun onCleared() {
        app.unwatchRestarts()
    }

    fun pickHost(name: String?) = update { it.copy(host = name) }
    fun setOnlyOutdated(on: Boolean) = update { it.copy(onlyOutdated = on) }
    fun toggleFleet(fleet: String) = update { s ->
        s.copy(excludedFleets = if (fleet in s.excludedFleets) s.excludedFleets - fleet else s.excludedFleets + fleet)
    }

    fun toggleSeat(id: Long) = state.update { s ->
        if (s.plan.firstOrNull { it.sessionId == id }?.skipped != false) s
        else s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
    }

    /** Start the batch: exactly the selected seats, one at a time. */
    fun start() {
        val s = state.value
        if (s.selected.isEmpty() || s.starting || s.batchId != null) return
        state.update { it.copy(starting = true, problem = null) }
        viewModelScope.launch {
            try {
                val started = app.api.restartIdle(s.host, options(s).copy(sessionIds = s.selected.toList()))
                state.update { it.copy(starting = false, batchId = started.batchId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(starting = false, problem = "Not started: ${e.message}") }
            }
        }
    }

    private fun update(change: (RestartIdleState) -> RestartIdleState) {
        state.update(change)
        replan()
    }

    /** Ask for the plan again (a dry run); the selection resets to what the plan takes, protected seats left out. */
    private fun replan() {
        planning?.cancel()
        state.update { it.copy(planning = true, problem = null) }
        planning = viewModelScope.launch {
            val s = state.value
            runCatching { app.api.planIdleRestart(s.host, options(s)) }
                .onSuccess { plan ->
                    state.update {
                        it.copy(plan = plan, planning = false, selected = defaultSelection(plan))
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    state.update { it.copy(plan = emptyList(), selected = emptySet(), planning = false, problem = "No plan: ${e.message}") }
                }
        }
    }

    /**
     * The options as chosen. Paused fleets are among [RestartIdleState.excludedFleets]
     * until ticked in, so the server's own paused rule is turned off and the
     * list says exactly what is left out.
     */
    private fun options(s: RestartIdleState) = RestartOptions(
        excludeFleets = s.excludedFleets.toList().ifEmpty { null },
        onlyOutdated = s.onlyOutdated,
        excludePaused = false,
    )
}

/** The seats a plan restarts unless the operator changes it: every one it takes, protected seats left out. */
internal fun defaultSelection(plan: List<RestartPlan>): Set<Long> =
    plan.filter { !it.skipped && !it.isProtected }.map { it.sessionId }.toSet()
