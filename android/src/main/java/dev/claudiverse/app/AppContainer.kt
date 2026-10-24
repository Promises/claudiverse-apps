package dev.claudiverse.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import dev.claudiverse.app.seat.Drafts
import dev.claudiverse.app.seat.PrefsOutboxStore
import dev.claudiverse.app.seat.TranscriptImages
import dev.claudiverse.app.update.AppUpdates
import dev.claudiverse.app.vpn.AppVpn
import dev.claudiverse.app.vpn.AutoVpn
import dev.claudiverse.core.ModelCatalogs
import dev.claudiverse.core.Api
import dev.claudiverse.core.CoreLog
import dev.claudiverse.core.HumanRequests
import dev.claudiverse.core.PoolEvents
import dev.claudiverse.core.Restarts
import dev.claudiverse.core.Profiles
import dev.claudiverse.core.Outbox
import dev.claudiverse.core.PhoenixSocket
import dev.claudiverse.core.SpawnTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The app's long-lived objects, one of each per process.
 *
 * The OpenVPN core owns the Application class (ICSOpenVPNApplication), so this
 * hangs off the application context instead of a custom Application.
 */
class AppContainer private constructor(context: Context) {
    init {
        // First, before anything below can log.
        CoreLog.sink = { Log.i("claudiverse", it) }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(context)
    val vpn = AppVpn(context, settings)

    val api = Api { settings.config.value.access }
    val outbox = Outbox(PrefsOutboxStore(context), api, scope)
    /** What was typed to each seat but not sent. */
    val drafts = Drafts(context)
    val socket = PhoenixSocket({ settings.config.value.access }, scope)
    val humanRequests = HumanRequests(api, socket, scope)
    /** Turns the app's VPN on away from the server's network and off on it. */
    val autoVpn = AutoVpn(context, settings, vpn, scope)
    /** Updates this app from the build the server holds. */
    val updates = AppUpdates(context, api, scope)
    /** Spawns started from the app, followed to their outcome. */
    val spawns = SpawnTracker(api, socket, scope)
    /** The models a seat can run: the server's list once read, the built-in one until then. */
    val models = ModelCatalogs(api, scope)
    /** Pictures in transcripts, cached by file. */
    val images = TranscriptImages(api)
    /** Live changes to the Anthropic account pool (operator:events "pool"), while the Pool tab or the watcher listens. */
    val poolEvents = PoolEvents(socket, scope)
    private var poolWatchers = 0

    /** The Pool tab and the watcher each listen to the pool; its channel stays joined while any of them does. */
    fun watchPool() = synchronized(this) { if (poolWatchers++ == 0) poolEvents.start() }

    fun unwatchPool() = synchronized(this) { if (--poolWatchers == 0) poolEvents.stop() }

    /** Seat restarts as they happen (operator:events), while a seat's restart or Restart idle seats follows them. */
    val restarts = Restarts(socket, scope)
    private var restartWatchers = 0

    fun watchRestarts() = synchronized(this) { if (restartWatchers++ == 0) restarts.start() }

    fun unwatchRestarts() = synchronized(this) { if (--restartWatchers == 0) restarts.stop() }
    /** Seat profiles, kept current from operator:events while the Profiles screens are open. */
    val profiles = Profiles(api, socket, scope)
    private var profileWatchers = 0

    /** The list and the editor each watch the profiles; the store runs while any of them does. */
    fun watchProfiles() = synchronized(this) { if (profileWatchers++ == 0) profiles.start() }

    fun unwatchProfiles() = synchronized(this) { if (--profileWatchers == 0) profiles.stop() }
    /**
     * The seat list's Running filter is off: fetch stopped seats too. Held here,
     * not in the screen, because the list's view model outlives the screen and
     * must read the current value on every poll.
     */
    val seatsIncludeStopped = MutableStateFlow(false)

    init {
        autoVpn.start()
        // Restarts are followed for as long as the app runs: the server's auto-restarts
        // happen with no screen asking, and a seat shows its latest one when opened.
        watchRestarts()
        // A returned network (Wi-Fi back, the tunnel up) reconnects at once
        // instead of waiting out the socket's backoff.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = socket.reconnectNow()
                },
            )
        }
    }

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
