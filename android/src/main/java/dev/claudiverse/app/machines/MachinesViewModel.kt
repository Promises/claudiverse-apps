package dev.claudiverse.app.machines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.claudiverse.app.AppContainer
import dev.claudiverse.core.Host
import dev.claudiverse.core.HostReboot
import dev.claudiverse.core.RebootSummary
import dev.claudiverse.core.ReopenDone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One machine as the Machines screen shows it. */
data class MachineState(
    /** What Prepare reboot wrote down here, from this app; its notes say what was left out. */
    val summary: RebootSummary? = null,
    val preparing: Boolean = false,
    /** A reopen this app asked for, until its outcome arrives. */
    val pendingReopen: String? = null,
    /** The outcome of the reopen this app asked for. */
    val reopened: ReopenDone? = null,
    val notice: String? = null,
)

data class MachinesState(
    val hosts: List<Host> = emptyList(),
    val loading: Boolean = true,
    val problem: String? = null,
    val machines: Map<String, MachineState> = emptyMap(),
)

/**
 * The machines seats run on, and their safe reboot (host agent 0.23+):
 * Prepare reboot writes down what runs so the machine reopens it by itself
 * after its reboot; after an unprepared reboot, Reopen seats brings back what
 * it left. Machine changes arrive as "host_reboot" events (the list is read
 * again), a reopen's outcome as "host_reopen".
 */
class MachinesViewModel(private val app: AppContainer) : ViewModel() {

    private val state = MutableStateFlow(MachinesState())
    val ui: StateFlow<MachinesState> = state.asStateFlow()

    init {
        app.watchReboots()
        reload()
        // A machine's reboot changed (prepared, reopening, reopened): read the list again.
        viewModelScope.launch { app.reboots.reboots.drop(1).collect { reload() } }
        // The machine reopened by itself after a prepared reboot: its last reopen changed.
        viewModelScope.launch { app.reboots.machineReopens.drop(1).collect { reload() } }
    }

    override fun onCleared() {
        app.unwatchReboots()
    }

    fun reload() {
        viewModelScope.launch {
            state.update { it.copy(loading = true) }
            try {
                val hosts = app.api.hosts().sortedBy { it.name.lowercase() }
                state.update { it.copy(hosts = hosts, loading = false, problem = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(loading = false, problem = "Machines not read: ${e.message}") }
            }
        }
    }

    /** The machine's reboot: the newest a "host_reboot" event said, else the list's. */
    fun rebootOf(host: Host): HostReboot? = app.reboots.rebootOf(host)

    fun prepare(host: String) {
        if (machine(host).preparing) return
        change(host) { it.copy(preparing = true, notice = null) }
        viewModelScope.launch {
            try {
                val summary = app.api.prepareReboot(host)
                change(host) { it.copy(summary = summary) }
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                change(host) { it.copy(notice = prepareNotice(host, e)) }
            } finally {
                change(host) { it.copy(preparing = false) }
            }
        }
    }

    fun reopen(host: String) {
        if (machine(host).pendingReopen != null) return
        change(host) { it.copy(notice = null, reopened = null) }
        viewModelScope.launch {
            val id = try {
                app.api.reopenSeats(host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                change(host) { it.copy(notice = reopenNotice(host, e)) }
                return@launch
            }
            change(host) { it.copy(pendingReopen = id) }
            reload()
            // Its outcome may already be in (a fast machine answers before its reply arrives).
            val done = withTimeoutOrNull(REOPEN_WAIT_MS) { app.reboots.done.mapNotNull { it[id] }.first() }
            change(host) {
                it.copy(
                    pendingReopen = null, reopened = done,
                    notice = if (done == null) "$host has not said how the reopen went yet" else null,
                )
            }
            reload()
        }
    }

    private fun machine(host: String) = state.value.machines[host] ?: MachineState()

    private fun change(host: String, f: (MachineState) -> MachineState) =
        state.update { s -> s.copy(machines = s.machines + (host to f(s.machines[host] ?: MachineState()))) }

    private companion object {
        /** A reopen starts each seat in turn; a busy machine can take minutes. */
        const val REOPEN_WAIT_MS = 10 * 60_000L
    }
}
