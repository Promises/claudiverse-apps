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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * A seat asking for write access (create, move, comment) to one Trello
 * board. The operator approves or denies it; the server tells the seat.
 */
data class TrelloWriteRequest(
    val id: Long,
    val sessionId: Long?,
    val seatTitle: String,
    val board: String,
    val boardName: String?,
    val boardUrl: String?,
    val reason: String?,
    /** open, approved or denied; anything else is treated as not open. */
    val status: String,
    val decidedAt: String?,
    val insertedAt: String?,
    val updatedAt: String?,
) {
    /** Waiting for the operator: the only state with Approve and Deny. */
    val isOpen: Boolean get() = status == OPEN

    /** The board as people know it: its name, else its short link. */
    val boardLabel: String get() = boardName?.takeIf { it.isNotBlank() } ?: board

    /** "pm-seat asks for write access to Example Board" */
    val prompt: String get() = "$seatTitle asks for write access to $boardLabel"

    /** When this copy was last changed: its decision, else its last asking. Ms; 0 when it says neither. */
    val changedAtMs: Long get() = maxOf(parseServerTime(decidedAt) ?: 0, parseServerTime(updatedAt ?: insertedAt) ?: 0)

    companion object {
        const val OPEN = "open"

        fun from(o: JsonObject) = TrelloWriteRequest(
            id = o.requireLong("id"),
            sessionId = o.long("session_id"),
            seatTitle = o.text("seat_title"),
            board = o.text("board"),
            boardName = o.str("board_name"),
            boardUrl = o.str("board_url"),
            reason = o.str("reason"),
            status = o.str("status") ?: "unknown",
            decidedAt = o.str("decided_at"),
            insertedAt = o.str("inserted_at"),
            updatedAt = o.str("updated_at"),
        )
    }
}

/** A seat's write access to one board, from an approved request; it lasts until revoked. */
data class TrelloGrant(
    val id: Long,
    val seatTitle: String,
    val board: String,
    val boardName: String?,
    val requestId: Long?,
    val grantedAt: String?,
) {
    val boardLabel: String get() = boardName?.takeIf { it.isNotBlank() } ?: board

    companion object {
        fun from(o: JsonObject) = TrelloGrant(
            id = o.requireLong("id"),
            seatTitle = o.text("seat_title"),
            board = o.text("board"),
            boardName = o.str("board_name"),
            requestId = o.long("request_id"),
            grantedAt = o.str("granted_at"),
        )
    }
}

/** Approved: the request as decided, and the grant it made. */
data class TrelloApproval(val request: TrelloWriteRequest, val grant: TrelloGrant?)

/** Approve or deny answered 409: someone decided it first. [request] is the decision when the server sends it. */
class TrelloAlreadyDecided(message: String, val request: TrelloWriteRequest?) : Exception(message)

/** Approve or deny answered 404: no such request (any more). */
class TrelloRequestGone(message: String) : Exception(message)

/**
 * Of two copies of a request, the one to keep: the later change wins, and on
 * a tie a decision beats "open" (a decision is final). An older copy (a GET
 * reply that raced a newer event) never overwrites a newer one.
 */
fun newerOf(a: TrelloWriteRequest?, b: TrelloWriteRequest): TrelloWriteRequest {
    if (a == null) return b
    return when {
        b.changedAtMs > a.changedAtMs -> b
        b.changedAtMs < a.changedAtMs -> a
        !b.isOpen && a.isOpen -> b
        else -> a
    }
}

/**
 * Trello write requests and grants, kept current while something watches them:
 * live through `operator:human_requests` (shared with [HumanRequests]; the
 * channel counts its users), re-read on every (re)join and every
 * [RECHECK_MS]. A decided request stays, with its decision, so every device
 * can close its prompt.
 */
class TrelloRequests(private val api: Api, private val socket: PhoenixSocket, private val scope: CoroutineScope) {
    private val byId = LinkedHashMap<Long, TrelloWriteRequest>()
    private val grantsById = LinkedHashMap<Long, TrelloGrant>()

    private val requestsState = MutableStateFlow<List<TrelloWriteRequest>>(emptyList())
    /** Open ones newest first, then decided ones newest first. */
    val requests: StateFlow<List<TrelloWriteRequest>> = requestsState.asStateFlow()

    private val openState = MutableStateFlow<List<TrelloWriteRequest>>(emptyList())
    /** The ones waiting for the operator, newest first. */
    val open: StateFlow<List<TrelloWriteRequest>> = openState.asStateFlow()

    private val grantsState = MutableStateFlow<List<TrelloGrant>>(emptyList())
    val grants: StateFlow<List<TrelloGrant>> = grantsState.asStateFlow()

    private val supportedState = MutableStateFlow(true)
    /** False when the server has no Trello endpoints (404). */
    val supported: StateFlow<Boolean> = supportedState.asStateFlow()

    private val lock = SynchronizedObject()
    private var jobs: List<Job> = emptyList()
    private var channel: PhoenixSocket.Channel? = null
    /** Request ids an event touched since the running refresh began: kept even if its reply lacks them. */
    private var touchedDuringRefresh: MutableSet<Long>? = null

    fun start() = synchronized(lock) {
        if (jobs.isNotEmpty()) return@synchronized
        val ch = socket.channel(TOPIC).also { channel = it }
        jobs = listOf(
            scope.launch { ch.events.collect { onEvent(it.event, it.payload) } },
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
        channel?.leave()
        channel = null
    }

    @Throws(CancellationException::class)
    suspend fun refresh() {
        synchronized(lock) { touchedDuringRefresh = mutableSetOf() }
        try {
            val list = api.trelloWriteRequests()
            val grants = api.trelloGrants()
            supportedState.value = true
            synchronized(lock) {
                val touched = touchedDuringRefresh.orEmpty()
                val merged = LinkedHashMap<Long, TrelloWriteRequest>()
                list.forEach { merged[it.id] = newerOf(byId[it.id], it) }
                // The server is the authority on what is listed, except what an event brought while the GET ran.
                byId.values.filter { it.id !in merged && it.id in touched }.forEach { merged[it.id] = it }
                byId.clear()
                byId.putAll(merged)
                grantsById.clear()
                grants.forEach { grantsById[it.id] = it }
                publishLocked()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? ApiException)?.httpStatus == 404) supportedState.value = false
            CoreLog.info("trello requests not read: ${e.message}")
        } finally {
            synchronized(lock) { touchedDuringRefresh = null }
        }
    }

    /** Approve: the seat gets write access to that board. Throws [TrelloAlreadyDecided] or [TrelloRequestGone]. */
    @Throws(Exception::class)
    suspend fun approve(id: Long): TrelloApproval = decide(id) { api.approveTrelloWrite(id) }.also { a ->
        a.grant?.let { g -> synchronized(lock) { grantsById[g.id] = g; publishLocked() } }
    }

    /** Deny: the seat is told no. Throws [TrelloAlreadyDecided] or [TrelloRequestGone]. */
    @Throws(Exception::class)
    suspend fun deny(id: Long): TrelloWriteRequest = decide(id) { api.denyTrelloWrite(id) }

    /** Revoke a grant: the seat loses write access to that board, and is told. */
    @Throws(Exception::class)
    suspend fun revoke(grantId: Long) {
        api.revokeTrelloGrant(grantId)
        synchronized(lock) {
            grantsById.remove(grantId)
            publishLocked()
        }
    }

    private suspend fun <T> decide(id: Long, call: suspend () -> T): T {
        try {
            return call().also { result ->
                val r = (result as? TrelloApproval)?.request ?: result as? TrelloWriteRequest
                r?.let(::upsert)
            }
        } catch (e: TrelloAlreadyDecided) {
            e.request?.let(::upsert)
            throw e
        } catch (e: TrelloRequestGone) {
            synchronized(lock) {
                byId.remove(id)
                publishLocked()
            }
            throw e
        }
    }

    internal fun onEvent(event: String, payload: JsonObject) {
        when (event) {
            "trello_write_request", "trello_write_request_decided" ->
                runCatching { TrelloWriteRequest.from(payload) }.getOrNull()?.let { r ->
                    upsert(r)
                    // Approved on another device: its grant is not in the event, so read the grants now.
                    if (r.status == "approved") scope.launch { refresh() }
                }
            "trello_write_grant_revoked" -> payload.long("id")?.let { id ->
                synchronized(lock) {
                    grantsById.remove(id)
                    publishLocked()
                }
            }
        }
    }

    private fun upsert(r: TrelloWriteRequest) = synchronized(lock) {
        touchedDuringRefresh?.add(r.id)
        byId[r.id] = newerOf(byId[r.id], r)
        publishLocked()
    }

    private fun publishLocked() {
        val all = byId.values.sortedByDescending { it.changedAtMs }
        requestsState.value = all.filter { it.isOpen } + all.filterNot { it.isOpen }
        openState.value = all.filter { it.isOpen }
        grantsState.value = grantsById.values.sortedByDescending { parseServerTime(it.grantedAt) ?: 0 }
    }

    private companion object {
        const val TOPIC = "operator:human_requests"
        const val RECHECK_MS = 120_000L
    }
}
