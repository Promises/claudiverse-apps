package dev.claudiverse.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.claudiverse.app.AppContainer
import dev.claudiverse.app.watch.WatchService

/**
 * Brings the app back after it has been updated. Installing an update stops
 * the app, its VPN and its watcher; without this they stayed down until the
 * app was opened again, so a self-update over the app's own tunnel cut the
 * phone off. Starting the container re-decides the VPN (AutoVpn), and the
 * watcher restarts if it is enabled.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = AppContainer.get(context)
        Log.i("claudiverse", "updated to ${app.updates.installedVersion}: restarting VPN check and watcher")
        app.autoVpn.schedule()
        val config = app.settings.config.value
        if (config.notifyRequests && config.isComplete) {
            runCatching { WatchService.start(context) }.onFailure { Log.i("claudiverse", "watcher not restarted: ${it.message}") }
        }
    }
}
