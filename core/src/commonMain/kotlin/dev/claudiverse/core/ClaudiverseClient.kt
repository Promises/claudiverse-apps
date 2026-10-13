package dev.claudiverse.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The client's long-lived parts, wired once: what the macOS app holds for as
 * long as it runs. (The Android app wires the same parts in its AppContainer.)
 *
 * [access] is read on every call, so signing in or changing the server applies
 * to the next request; call [socket]'s reconnectNow() after such a change.
 */
class ClaudiverseClient(access: () -> ServerAccess, outboxStore: OutboxStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val api = Api(access)
    val socket = PhoenixSocket(access, scope)
    val outbox = Outbox(outboxStore, api, scope)
    val humanRequests = HumanRequests(api, socket, scope)
    val spawns = SpawnTracker(api, socket, scope)
    val fleets = Fleets(api, socket, scope)
    val profiles = Profiles(api, socket, scope)
    val poolEvents = PoolEvents(socket, scope)
    /** The models to offer: the server's list once [ModelCatalogs.refresh] has read it, built in until then. */
    val models = ModelCatalogs(api, scope)
    /** Seat restarts as they happen; start it while a restart is followed. */
    val restarts = Restarts(socket, scope)

    /** The New seat screen's state and actions; close it when the screen goes. */
    fun openNewSeat(): NewSeatSession = NewSeatSession(this, scope)

    /** One seat's live transcript and actions; close it when its screen goes. */
    fun seat(seatId: Long): SeatSession = SeatSession(this, seatId, scope)

    /** Stop everything: the socket closes, polling ends. */
    fun close() {
        humanRequests.stop()
        fleets.stop()
        profiles.stop()
        poolEvents.stop()
        restarts.stop()
        scope.cancel()
    }
}
