package dev.claudiverse.app.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.claudiverse.app.AppContainer
import dev.claudiverse.app.MainActivity
import dev.claudiverse.app.R
import dev.claudiverse.core.HumanRequest
import dev.claudiverse.core.PoolChange
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Keeps the app listening for seats that ask for the operator while it is not
 * on screen. A foreground service, so Android keeps the process (and its live
 * connection through the app's VPN) alive; its own notification is quiet and
 * permanent. Each open request becomes a high-priority notification that opens
 * its seat, and disappears once the request is resolved anywhere.
 *
 * It also listens to the account pool: an account that needs re-auth becomes
 * a notification that opens its Re-authorize, gone once it is re-authorized.
 */
class WatchService : Service() {

    private val app by lazy { AppContainer.get(this) }
    private var watching: Job? = null
    private var watchingPool: Job? = null
    /** Pool accounts whose "needs re-auth" notification is up. */
    private val reauthShown = mutableSetOf<Long>()
    /** Request id and last update per seat already shown, so an unchanged request does not alert again. */
    private val shown = mutableMapOf<Long, Pair<Long, String?>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoing(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        app.humanRequests.start()
        watching = app.scope.launch { app.humanRequests.open.collect(::show) }
        app.watchPool()
        watchingPool = app.scope.launch {
            // What needs re-auth now, also what broke while the app was not listening.
            runCatching { app.api.pool() }.onSuccess { pool ->
                pool.accounts.forEach { a ->
                    if (a.needsReauth) showReauth(a.id, a.label, a.reauthReason ?: a.cooldownReason) else hideReauth(a.id)
                }
            }
            // Then each change as it comes; the first value is whatever was last seen, maybe long settled.
            app.poolEvents.latest.drop(1).collect { c -> c?.let(::onPoolChange) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        watching?.cancel()
        watchingPool?.cancel()
        app.unwatchPool()
        app.humanRequests.stop()
        super.onDestroy()
    }

    private fun show(open: Map<Long, HumanRequest>) {
        val nm = getSystemService(NotificationManager::class.java)
        (shown.keys - open.keys).forEach { seat ->
            nm.cancel(requestId(seat))
            shown.remove(seat)
        }
        if (!canNotify()) return
        open.values.forEach { r ->
            val stamp = r.id to r.updatedAt
            if (shown[r.sessionId] == stamp) return@forEach
            shown[r.sessionId] = stamp
            nm.notify(requestId(r.sessionId), requestNotification(r))
        }
    }

    private fun onPoolChange(c: PoolChange) {
        val id = c.accountId ?: return
        when (c.change) {
            "needs_reauth" -> showReauth(id, c.label ?: "An account", c.reason)
            "reauthorized" -> hideReauth(id)
        }
    }

    private fun showReauth(id: Long, label: String, reason: String?) {
        if (!canNotify() || !reauthShown.add(id)) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(
            reauthId(id),
            NotificationCompat.Builder(this, CHANNEL_POOL)
                .setSmallIcon(R.drawable.ic_stat_claudiverse)
                .setContentTitle("$label needs re-auth")
                .setContentText("Tap to sign in to it again" + (reason?.let { " ($it)" } ?: ""))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(openReauth(this, id))
                .build(),
        )
    }

    private fun hideReauth(id: Long) {
        if (reauthShown.remove(id)) getSystemService(NotificationManager::class.java).cancel(reauthId(id))
    }

    private fun canNotify() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestNotification(r: HumanRequest): Notification =
        NotificationCompat.Builder(this, CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_claudiverse)
            .setContentTitle("${r.seatTitle} needs you")
            .setContentText(r.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(r.summary, r.detail).joinToString("\n\n")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openSeat(this, r.sessionId, r.seatTitle))
            .build()

    private fun ongoing(): Notification =
        NotificationCompat.Builder(this, CHANNEL_WATCHING)
            .setSmallIcon(R.drawable.ic_stat_claudiverse)
            .setContentTitle("Watching for seats that need you")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .build()

    companion object {
        /**
         * A channel's sound is fixed once Android has created it, so a new
         * sound needs a new channel id; the earlier ones are deleted.
         */
        const val CHANNEL_REQUESTS = "human_requests_alert"
        private val OLD_CHANNELS_REQUESTS = listOf("human_requests", "human_requests_poke")
        const val CHANNEL_WATCHING = "watching"
        const val CHANNEL_POOL = "pool_accounts"
        const val EXTRA_SEAT_ID = "dev.claudiverse.app.SEAT_ID"
        const val EXTRA_SEAT_TITLE = "dev.claudiverse.app.SEAT_TITLE"
        private const val ONGOING_ID = 0x0c1a
        private const val REQUEST_BASE = 0x1000
        private const val REAUTH_BASE = 0x2000_0000
        const val EXTRA_REAUTH_ACCOUNT_ID = "dev.claudiverse.app.REAUTH_ACCOUNT_ID"

        private fun requestId(seatId: Long) = REQUEST_BASE + (seatId % 0x0fff_ffff).toInt()

        private fun reauthId(accountId: Long) = REAUTH_BASE + (accountId % 0x0fff_ffff).toInt()

        /** Opens the app on the Pool tab with this account's Re-authorize. */
        fun openReauth(context: Context, accountId: Long): PendingIntent =
            PendingIntent.getActivity(
                context, reauthId(accountId),
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_REAUTH_ACCOUNT_ID, accountId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }

        /** Opens the app on a seat; unique per seat so each notification keeps its own target. */
        fun openSeat(context: Context, seatId: Long, title: String): PendingIntent =
            PendingIntent.getActivity(
                context, (seatId % Int.MAX_VALUE).toInt(),
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_SEAT_ID, seatId)
                    .putExtra(EXTRA_SEAT_TITLE, title),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            OLD_CHANNELS_REQUESTS.forEach(nm::deleteNotificationChannel)
            // The system's notification sound, which a high-importance channel plays by default.
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_REQUESTS, "Seats that need you", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "A top-level seat asked for you" },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_POOL, "Accounts that need re-auth", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "A pool account the server can no longer use until it is signed in again" },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_WATCHING, "Watching", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Shown while the app listens for seats that need you" },
            )
        }
    }
}
