package dev.claudiverse.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FolderTab { Recent, Browse }

/** What a spawn asks a machine for beyond its folder, name and mode. */
data class SpawnExtras(val model: String?, val pooled: Boolean?)

/**
 * The model and pooling a spawn on [host] sends, as the web panel's Start a
 * seat: a model only to an agent that offers it, pooling only to one that
 * offers it and is not pool-only (always pooled, nothing to ask). Nothing for
 * a null choice, so a caller with no pickers sends what it always did.
 */
fun spawnExtras(host: Host?, model: String?, pooled: Boolean?): SpawnExtras {
    val spawn = host?.spawn
    return SpawnExtras(
        model = model?.takeIf { it.isNotBlank() && spawn?.offersModel == true },
        pooled = pooled?.takeIf { spawn?.offersPool == true && !spawn.poolOnly },
    )
}

data class NewSeatState(
    val hosts: List<Host> = emptyList(),
    val hostsLoading: Boolean = true,
    /** Null when hosts loaded; otherwise why they did not (e.g. the server cannot spawn yet). */
    val hostsError: String? = null,
    val host: String? = null,
    val tab: FolderTab = FolderTab.Recent,
    val recents: List<RecentDir> = emptyList(),
    val recentIndex: Int = 0,
    val listing: DirListing? = null,
    /** The machine's allowed folders: a whitelist extended on the machine, or from the desktop where the machine allows it. */
    val roots: List<String> = emptyList(),
    val listingError: String? = null,
    val title: String = "",
    /** True once the user has typed a title: it then no longer follows the folder. */
    val titleEdited: Boolean = false,
    val mode: String = "auto",
    /** A model id to start with ([SessionChoices.spawnModels]); null leaves the machine's default. */
    val model: String? = null,
    /** Use the server's account pool; null leaves the machine's default. */
    val pooled: Boolean? = null,
    val prompt: String = "",
    val spawning: Boolean = false,
    val spawnError: String? = null,
) {
    /** The folder a spawn would use: the picked recent one, or the folder being browsed ("" is the roots list: none yet). */
    val path: String?
        get() = when (tab) {
            FolderTab.Recent -> recents.getOrNull(recentIndex)?.path?.takeIf(::allowed)
            FolderTab.Browse -> listing?.path?.takeIf { it.isNotEmpty() }
        }

    val canSpawn: Boolean get() = !spawning && host != null && path != null && title.isNotBlank()

    /** Inside the machine's whitelist: anywhere else the agent refuses the spawn (403). */
    fun allowed(path: String): Boolean = rootOf(path, roots) != null
}

/**
 * The New seat screen: a machine, a folder (recent or browsed), a title that
 * defaults to the folder's name, a permission mode (auto by default, as the
 * server's spawn) and an optional first prompt. The outcome of a spawn
 * follows in [ClaudiverseClient.spawns]. [close] when the screen goes.
 */
class NewSeatSession internal constructor(private val client: ClaudiverseClient, parent: CoroutineScope) {

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val state = MutableStateFlow(NewSeatState())
    val ui: StateFlow<NewSeatState> = state.asStateFlow()
    private var load: Job? = null

    init {
        reloadHosts()
    }

    /** Read the machines again (after a folder was added to one, say), keeping the one picked. */
    fun reloadHosts() {
        scope.launch {
            try {
                val hosts = client.api.hosts()
                val keep = state.value.host?.takeIf { name -> hosts.any { it.name == name && it.online } }
                state.update { it.copy(hosts = hosts, hostsLoading = false, hostsError = null) }
                (keep ?: hosts.firstOrNull { it.online }?.name)?.let(::pickHost)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = if ((e as? ApiException)?.httpStatus == 404) "This server can't spawn seats yet." else "Machines not loaded: ${e.message}"
                state.update { it.copy(hostsLoading = false, hostsError = why) }
            }
        }
    }

    fun pickHost(name: String) {
        val host = state.value.hosts.firstOrNull { it.name == name }?.takeIf { it.online } ?: return
        state.update { it.copy(host = name, roots = host.roots, recents = emptyList(), recentIndex = 0, listing = null, listingError = null, spawnError = null) }
        load?.cancel()
        load = scope.launch {
            val recents = try {
                client.api.recentDirs(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            // Pick the first recent folder the whitelist allows; with none there is nothing to pick from: start in Browse.
            state.update { s ->
                val first = recents.indexOfFirst { s.allowed(it.path) }
                s.copy(recents = recents, recentIndex = first.coerceAtLeast(0), tab = if (first < 0) FolderTab.Browse else s.tab)
            }
            followTitle()
            list("")
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

    /** Open [path] on the current machine ("" lists its roots). The folder you are in is the one used. */
    fun browse(path: String) {
        state.value.host ?: return
        load?.cancel()
        load = scope.launch { list(path) }
    }

    private suspend fun list(path: String) {
        val host = state.value.host ?: return
        try {
            val l = client.api.listDir(host, path)
            // The roots list ("") is the freshest word on the whitelist.
            state.update { it.copy(listing = l, listingError = null, roots = if (path.isEmpty()) l.dirs else it.roots) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(listingError = listingError(e)) }
        }
        followTitle()
    }

    fun setTitle(title: String) = state.update { it.copy(title = title, titleEdited = true, spawnError = null) }
    fun setMode(mode: String) = state.update { it.copy(mode = mode) }
    fun setPrompt(prompt: String) = state.update { it.copy(prompt = prompt) }

    /** Spawn; true once the server has accepted it (the outcome follows in spawns), false with spawnError set. */
    @Throws(CancellationException::class)
    suspend fun spawn(): Boolean {
        val s = state.value
        val host = s.host ?: return false
        val path = s.path ?: return false
        state.update { it.copy(spawning = true, spawnError = null) }
        return try {
            client.spawns.spawn(host, path, s.title.trim(), s.mode, s.prompt.trim().ifEmpty { null })
            state.update { it.copy(spawning = false) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(spawning = false, spawnError = spawnError(e, host)) }
            false
        }
    }

    fun close() = scope.cancel()

    /** The title follows the folder's name until the user types one. */
    private fun followTitle() = state.update { s ->
        if (s.titleEdited) s else s.copy(title = s.path?.let(::seatTitleFor).orEmpty())
    }

    private fun listingError(e: Throwable): String = when ((e as? ApiException)?.httpStatus) {
        403 -> "Outside this machine's allowed folders"
        404 -> "Folder not found"
        504 -> "The machine did not answer (offline?)"
        else -> "Not listed: ${e.message}"
    }

    private fun spawnError(e: Throwable, host: String): String = when ((e as? ApiException)?.httpStatus) {
        400 -> "Not spawned: ${e.message?.substringAfter(": ")}"
        403 -> "Not spawned: the folder is outside $host's allowed folders, or this client may not spawn"
        404 -> "Not spawned: unknown machine or folder"
        429 -> "Not spawned: $host is rate-limited, try again in a few seconds"
        504 -> "Not spawned: $host did not answer (offline?)"
        else -> "Not spawned: ${e.message}"
    }
}
