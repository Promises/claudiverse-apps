package dev.claudiverse.app.spawn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.claudiverse.app.AppContainer
import dev.claudiverse.core.AutoRestart
import dev.claudiverse.core.AutoRestartOverride
import dev.claudiverse.core.Fleet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AutoRestartState(
    /** The global setting; null until read (or from a server without auto-restart). */
    val global: AutoRestart? = null,
    val fleets: List<Fleet> = emptyList(),
    val loading: Boolean = true,
    /** A change in flight: the controls wait for it. */
    val saving: Boolean = false,
    val problem: String? = null,
    /** The server has no auto-restart (an older one). */
    val unsupported: Boolean = false,
)

/**
 * The server's auto-restart: on or off and how long a seat must have been idle,
 * for every fleet, and each fleet's own override of either (inherit, on, off;
 * its own idle minutes). Every change is written at once and the server's
 * answer shown.
 */
class AutoRestartViewModel(private val app: AppContainer) : ViewModel() {

    private val state = MutableStateFlow(AutoRestartState())
    val ui: StateFlow<AutoRestartState> = state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            state.update { it.copy(loading = true, problem = null) }
            try {
                val global = app.api.autoRestart()
                val fleets = app.api.fleets()
                state.update { it.copy(global = global, fleets = fleets, loading = false, unsupported = global == null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(loading = false, problem = "Not loaded: ${e.message}") }
            }
        }
    }

    fun setEnabled(on: Boolean) = save { app.api.setAutoRestart(enabled = on).let { g -> state.update { it.copy(global = g) } } }

    /** [minutes] as typed; the server takes 1 minute to 24 hours. */
    fun setMinIdleMinutes(minutes: Int) = save {
        val g = app.api.setAutoRestart(minIdleS = minutes * 60)
        state.update { it.copy(global = g) }
        // What applies to a fleet that inherits the minutes changed with it.
        state.update { it.copy(fleets = app.api.fleets()) }
    }

    /** A fleet's override as a whole: a null field inherits; nothing set clears it. */
    fun setFleet(fleet: String, override: AutoRestartOverride) = save {
        val (o, effective) = app.api.setFleetAutoRestart(fleet, override)
        state.update { s ->
            s.copy(fleets = s.fleets.map { f -> if (f.fleet == fleet) f.copy(autoRestart = o, autoRestartEffective = effective) else f })
        }
    }

    private fun save(change: suspend () -> Unit) {
        if (state.value.saving) return
        state.update { it.copy(saving = true, problem = null) }
        viewModelScope.launch {
            try {
                change()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(problem = "Not changed: ${e.message}") }
            } finally {
                state.update { it.copy(saving = false) }
            }
        }
    }
}
