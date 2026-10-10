package dev.claudiverse.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dev.claudiverse.app.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** What the app's own tunnel should do after a network change. */
enum class VpnAction { Connect, Disconnect, None }

/**
 * The app's VPN is a fallback: only for when the server cannot be reached
 * any other way, and never at the cost of another VPN (Android has one VPN
 * slot, and connecting takes it from whatever VPN was up).
 */
fun decideVpn(
    serverReachableWithoutUs: Boolean,
    ourVpnActive: Boolean,
    otherVpnActive: Boolean,
    autoConnect: Boolean,
    profileReady: Boolean,
): VpnAction = when {
    // On the local network (or reachable through another route): the tunnel is not needed.
    serverReachableWithoutUs && ourVpnActive -> VpnAction.Disconnect
    serverReachableWithoutUs -> VpnAction.None
    // Away, and nothing else gets us there: bring the tunnel up, unless that would evict another VPN.
    !ourVpnActive && !otherVpnActive && autoConnect && profileReady -> VpnAction.Connect
    else -> VpnAction.None
}

/**
 * Re-decides the app's VPN whenever the phone's networks change: joining the
 * server's network turns the tunnel off, leaving it turns the tunnel on.
 *
 * "Reachable without us" is probed with the server's health endpoint (no
 * token, 3 s). While our tunnel is down the probe uses the phone's normal
 * network, so a system VPN counts as a route; while it is up the probe is
 * bound to the underlying Wi-Fi or mobile network, since through the tunnel
 * it would always succeed.
 */
class AutoVpn(private val context: Context, private val settings: Settings, private val vpn: AppVpn, private val scope: CoroutineScope) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var pending: Job? = null

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = schedule()
            override fun onLost(network: Network) = schedule()
        })
        schedule()
    }

    /** Networks come and go in bursts (Wi-Fi drops, mobile takes over): decide once things settle. */
    @Synchronized
    fun schedule() {
        pending?.cancel()
        pending = scope.launch {
            delay(SETTLE_MS)
            evaluate()
        }
    }

    private suspend fun evaluate() {
        val config = settings.config.value
        if (!config.isComplete) return
        val ours = !vpn.status.value.idle
        val other = !ours && anyVpnNetwork()
        val reachable = probe(config.baseUrl, bypassTunnel = ours)
        val profile = vpn.profileInfo()
        val ready = profile != null && profile.problem == null && (!profile.needsCredentials || profile.hasCredentials)
        val action = decideVpn(reachable, ours, other, config.autoConnect, ready)
        Log.i(TAG, "auto-vpn: reachable=$reachable ours=$ours other=$other -> $action")
        when (action) {
            VpnAction.Disconnect -> vpn.disconnect()
            // Only without a pending consent: the dialog is never popped from the background.
            VpnAction.Connect -> if (vpn.consentIntent() == null) runCatching { vpn.connect() }
                .onFailure { Log.i(TAG, "auto-vpn: connect failed: ${it.message}") }
            VpnAction.None -> Unit
        }
    }

    private fun anyVpnNetwork(): Boolean =
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }

    /** The Wi-Fi, ethernet or mobile network under any VPN, Wi-Fi and ethernet first. */
    private fun underlyingNetwork(): Network? = cm.allNetworks
        .mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
        .filter { (_, c) -> !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        .sortedBy { (_, c) -> if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 0 else 1 }
        .firstOrNull()?.first

    private suspend fun probe(baseUrl: String, bypassTunnel: Boolean): Boolean = withContext(Dispatchers.IO) {
        val builder = OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(PROBE_TIMEOUT_S + 1, TimeUnit.SECONDS)
        if (bypassTunnel) {
            val net = underlyingNetwork() ?: return@withContext false
            builder.socketFactory(net.socketFactory).dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = net.getAllByName(hostname).toList()
            })
        }
        runCatching {
            builder.build().newCall(Request.Builder().url("${baseUrl.trimEnd('/')}/api/health").build()).execute()
                .use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "claudiverse"
        const val SETTLE_MS = 2_000L
        const val PROBE_TIMEOUT_S = 3L
    }
}
