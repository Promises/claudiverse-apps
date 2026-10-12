package dev.claudiverse.app.spawn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.claudiverse.app.AppContainer
import dev.claudiverse.core.ApiException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.claudiverse.core.seatTitleFor
import dev.claudiverse.core.FolderTab
import dev.claudiverse.core.HostEvents
import dev.claudiverse.core.NewSeatState
import dev.claudiverse.core.spawnExtras
import kotlinx.coroutines.flow.drop

/**
 * The New seat screen: a machine, a folder (recent or browsed), a title that
 * defaults to the folder's name, a permission mode (auto by default, as the
 * server's spawn), the model and the account pool where the machine's agent
 * offers them, and an optional first prompt. The machines are read again
 * whenever one's agent joins or leaves.
 */
class NewSeatViewModel(private val app: AppContainer) : ViewModel() {

    private val state = MutableStateFlow(NewSeatState())
    val ui: StateFlow<NewSeatState> = state.asStateFlow()
    private var load: Job? = null
    private val hostEvents = HostEvents(app.socket, viewModelScope)

    init {
        viewModelScope.launch {
            loadHosts()
            state.value.hosts.firstOrNull { it.online }?.let { pickHost(it.name) }
        }
        hostEvents.start()
        viewModelScope.launch { hostEvents.changes.drop(1).collect { loadHosts() } }
    }

    override fun onCleared() {
        hostEvents.stop()
    }

    /** Read the machines; the one picked stays picked, with what its agent now offers. */
    private suspend fun loadHosts() {
        runCatching { app.api.hosts() }
            .onSuccess { hosts -> state.update { it.copy(hosts = hosts, hostsLoading = false, hostsError = null) } }
            .onFailure { e ->
                val why = if ((e as? ApiException)?.httpStatus == 404) "This server can't spawn seats yet." else "Machines not loaded: ${e.message}"
                state.update { it.copy(hostsLoading = false, hostsError = why) }
            }
    }

    fun pickHost(name: String) {
        if (state.value.hosts.firstOrNull { it.name == name }?.online != true) return
        val picked = state.value.hosts.first { it.name == name }
        // The pool is on by default where the machine lets the seat choose, as the web panel's Start a seat.
        val pooled = picked.spawn?.takeIf { it.offersPool && !it.poolOnly }?.let { true }
        state.update {
            it.copy(host = name, roots = picked.roots, recents = emptyList(), recentIndex = 0, listing = null, listingError = null,
                spawnError = null, model = null, pooled = pooled)
        }
        load?.cancel()
        load = viewModelScope.launch {
            val recents = runCatching { app.api.recentDirs(name) }.getOrDefault(emptyList())
            // Pick the first recent folder the whitelist allows; with none there is nothing to pick from: start in Browse.
            state.update { s ->
                val first = recents.indexOfFirst { s.allowed(it.path) }
                s.copy(recents = recents, recentIndex = first.coerceAtLeast(0), tab = if (first < 0) FolderTab.Browse else s.tab)
            }
            followTitle()
            browse("")
        }
    }

    fun setTab(tab: FolderTab) {
        state.update { it.copy(tab = tab) }
        followTitle()
    }

    fun pickRecent(index: Int) {
        state.update { s -> if (s.recents.getOrNull(index)?.let { s.allowed(it.path) } == true) s.copy(recentIndex = index) else s }
        followTitle()
    }

    /** Open [path] on the current host ("" lists its roots). The folder you are in is the one used. */
    fun browse(path: String) {
        val host = state.value.host ?: return
        load?.cancel()
        load = viewModelScope.launch {
            runCatching { app.api.listDir(host, path) }
                .onSuccess { l ->
                    // The roots list ("") is the freshest word on the whitelist.
                    state.update { it.copy(listing = l, listingError = null, roots = if (path.isEmpty()) l.dirs else it.roots) }
                }
                .onFailure { e -> state.update { it.copy(listingError = listingError(e)) } }
            followTitle()
        }
    }

    fun setTitle(title: String) = state.update { it.copy(title = title, titleEdited = true, spawnError = null) }
    fun setMode(mode: String) = state.update { it.copy(mode = mode) }
    /** A model alias, or null for the machine's default. */
    fun setModel(model: String?) = state.update { it.copy(model = model) }
    fun setPooled(pooled: Boolean) = state.update { it.copy(pooled = pooled) }
    fun setPrompt(prompt: String) = state.update { it.copy(prompt = prompt) }

    /** Spawn; on success the screen closes and the snackbar takes over. */
    fun spawn(onAccepted: () -> Unit) {
        val s = state.value
        val host = s.host ?: return
        val path = s.path ?: return
        state.update { it.copy(spawning = true, spawnError = null) }
        viewModelScope.launch {
            val extras = spawnExtras(s.hosts.firstOrNull { it.name == host }, s.model, s.pooled)
            runCatching { app.spawns.spawn(host, path, s.title.trim(), s.mode, s.prompt.trim().ifEmpty { null }, extras.model, extras.pooled) }
                .onSuccess {
                    state.update { it.copy(spawning = false) }
                    onAccepted()
                }
                .onFailure { e -> state.update { it.copy(spawning = false, spawnError = spawnError(e, host)) } }
        }
    }

    /** The title follows the folder's name until the user types one. */
    private fun followTitle() = state.update { s ->
        if (s.titleEdited) s else s.copy(title = s.path?.let(::seatTitleFor).orEmpty())
    }

    private fun listingError(e: Throwable): String = when ((e as? ApiException)?.httpStatus) {
        403 -> "Outside this machine's allowed folders"
        404 -> "Folder not found"
        504 -> "The machine did not answer (offline?)"
        // The machine's own refusal, e.g. not_permitted: macOS is holding the folder
        // behind a privacy prompt, and its message says how to grant access.
        502 -> e.message?.substringAfter(": ") ?: "The machine could not list this folder"
        else -> "Not listed: ${e.message}"
    }

    private fun spawnError(e: Throwable, host: String): String = when ((e as? ApiException)?.httpStatus) {
        400 -> "Not spawned: ${e.message?.substringAfter(": ")}"
        403 -> "Not spawned: the folder is outside $host's allowed folders, or this app may not spawn"
        404 -> "Not spawned: unknown machine or folder"
        429 -> "Not spawned: $host is rate-limited, try again in a few seconds"
        504 -> "Not spawned: $host did not answer (offline?)"
        else -> "Not spawned: ${e.message}"
    }
}
