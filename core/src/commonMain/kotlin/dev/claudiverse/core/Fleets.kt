package dev.claudiverse.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * The fleets, kept current while something shows them: read from
 * GET /api/fleets on every (re)join of operator:events and every
 * [RECHECK_MS], and changed in between by each live "runner" event.
 */
class Fleets(private val api: Api, private val socket: PhoenixSocket, private val scope: CoroutineScope) {

    private val state = MutableStateFlow<List<Fleet>>(emptyList())
    val all: StateFlow<List<Fleet>> = state.asStateFlow()

    private val lock = SynchronizedObject()
    private var jobs: List<Job> = emptyList()

    fun start() = synchronized(lock) {
        if (jobs.isNotEmpty()) return@synchronized
        val ch = socket.channel(TOPIC)
        jobs = listOf(
            scope.launch {
                ch.events.collect { e ->
                    when (e.event) {
                        "runner" -> runCatching { FleetRunner.from(e.payload) }.getOrNull()?.let { r -> state.update { applyRunner(it, r) } }
                        "profile" -> state.update { applyProfile(it, e.payload) }
                    }
                }
            },
            scope.launch { ch.joined.collect { if (it) refresh() } },
            scope.launch {
                while (isActive) {
                    refresh()
                    delay(RECHECK_MS)
                }
            },
        )
        ch.join()
    }

    fun stop() = synchronized(lock) {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    @Throws(CancellationException::class)
    suspend fun refresh() {
        try {
            state.value = api.fleets()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CoreLog.info("fleets not read: ${e.message}")
        }
    }

    internal companion object {
        const val TOPIC = "operator:events"
        const val RECHECK_MS = 60_000L

        /**
         * One live "profile" event, as the fleets' declared members: the
         * profile's member replaced, moved to its new fleet (a fleet it leaves
         * keeps its other members), or removed when deleted or given no fleet.
         * A fleet left with nothing at all is gone, as the server would list it.
         */
        fun applyProfile(fleets: List<Fleet>, event: JsonObject): List<Fleet> {
            val p = event.obj("profile") ?: return fleets
            val id = p.long("id") ?: return fleets
            val profile = if (event.str("op") == "deleted") null else runCatching { Profile.from(p) }.getOrNull() ?: return fleets
            val target = profile?.fields?.fleet
            val without = fleets.mapNotNull { f ->
                if (f.members.none { it.id == id }) return@mapNotNull f
                val left = f.copy(members = f.members.filter { it.id != id })
                left.takeUnless { it.members.isEmpty() && it.seats.isEmpty() && it.runners.isEmpty() && it.fleet != target }
            }
            if (profile == null || target == null) return without
            val member = FleetMember.of(profile)
            val exists = without.any { it.fleet == target }
            val placed = without.map { f -> if (f.fleet == target) f.copy(members = (f.members + member).sortedBy { it.name.lowercase() }) else f }
            return if (exists) placed else placed + Fleet(target, emptyList(), emptyList(), listOf(member))
        }

        /** One live runner event: its slot replaced (or added, with its fleet if new); "stopped" removes it. */
        fun applyRunner(fleets: List<Fleet>, r: FleetRunner): List<Fleet> {
            val existing = fleets.firstOrNull { it.fleet == r.fleet }
            if (existing == null) return if (r.status == "stopped") fleets else fleets + Fleet(r.fleet, listOf(r), emptyList())
            return fleets.map { f ->
                if (f.fleet != r.fleet) f
                else {
                    val others = f.runners.filter { it.slot != r.slot }
                    f.copy(runners = (if (r.status == "stopped") others else others + r).sortedBy { it.slot })
                }
            }
        }
    }
}

/**
 * A change to the Anthropic account pool, as the server pushes it ("pool" on
 * operator:events): [change] is preferred (the operator chose an account;
 * [by] is who), upgrade or degrade (the best account changed: a recovery or a
 * fallback) or cooling (an account cooled after a rate limit). The list itself
 * is not in the event: refetch it.
 */
/**
 * One change to the pool, as the server announces it. [change] is open:
 * preferred, upgrade, degrade, cooling, reset, capped, step, needs_reauth,
 * reauthorized, and whatever a newer server adds, which a reader shows
 * generically. An account-level change names its account ([accountId],
 * [label]) and, for needs_reauth, why ([reason]).
 */
data class PoolChange(
    val change: String,
    val by: String?,
    val at: String?,
    val currentId: Long?,
    val preferredId: Long?,
    val accountId: Long? = null,
    val label: String? = null,
    val reason: String? = null,
    /** The server's own sentence for the change, when it sends one. */
    val text: String? = null,
) {
    companion object {
        fun from(o: JsonObject) = PoolChange(
            change = o.str("change") ?: "unknown",
            by = o.str("by"),
            at = o.str("at"),
            currentId = o.long("current_id"),
            preferredId = o.long("preferred_id"),
            accountId = o.long("auth_id") ?: o.long("account_id"),
            label = o.str("label"),
            reason = o.str("reason"),
            text = o.str("text"),
        )
    }
}

/**
 * Machines coming and going: each "host" event on operator:events (a host
 * agent joined or left) counts up [changes] while started, so a list of
 * machines knows to read them again.
 */
class HostEvents(private val socket: PhoenixSocket, private val scope: CoroutineScope) {

    private val state = MutableStateFlow(0L)
    val changes: StateFlow<Long> = state.asStateFlow()

    private val lock = SynchronizedObject()
    private var job: Job? = null
    private var channel: PhoenixSocket.Channel? = null

    fun start() = synchronized(lock) {
        if (job != null) return@synchronized
        val ch = socket.channel(Fleets.TOPIC).also { channel = it }
        job = scope.launch { ch.events.collect { e -> if (e.event == "host") state.value++ } }
        ch.join()
    }

    fun stop() = synchronized(lock) {
        job?.cancel()
        job = null
        channel?.leave()
        channel = null
    }
}

/** The pool's changes, live while started: [latest] is the newest one since the app started. */
class PoolEvents(private val socket: PhoenixSocket, private val scope: CoroutineScope) {

    private val state = MutableStateFlow<PoolChange?>(null)
    val latest: StateFlow<PoolChange?> = state.asStateFlow()

    private val lock = SynchronizedObject()
    private var job: Job? = null
    private var channel: PhoenixSocket.Channel? = null

    fun start() = synchronized(lock) {
        if (job != null) return@synchronized
        val ch = socket.channel(Fleets.TOPIC).also { channel = it }
        job = scope.launch {
            ch.events.collect { e -> if (e.event == "pool") state.value = PoolChange.from(e.payload) }
        }
        ch.join()
    }

    /** Stop listening, and give the channel claim back: a stop and start (sign out, sign in) must not add a claim. */
    fun stop() = synchronized(lock) {
        job?.cancel()
        job = null
        channel?.leave()
        channel = null
    }
}

