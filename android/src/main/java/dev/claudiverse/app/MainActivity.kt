package dev.claudiverse.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.claudiverse.app.ui.SeatLink
import dev.claudiverse.app.watch.WatchService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dev.claudiverse.app.ui.AppRoot
import dev.claudiverse.app.ui.ClaudiverseTheme
import dev.claudiverse.app.ui.VpnActions
import dev.claudiverse.app.vpn.VpnProfileInfo

/**
 * The single activity. It owns what needs an Activity — the VPN consent dialog,
 * the file picker, the notification permission, and opening a seat from a
 * notification — and hands the rest to Compose.
 */
class MainActivity : ComponentActivity() {

    private val app by lazy { AppContainer.get(this) }
    private var profile by mutableStateOf<VpnProfileInfo?>(null)
    /** A seat to open, from a "needs you" notification; AppRoot navigates and clears it. */
    private val openSeat = MutableStateFlow<SeatLink?>(null)
    /** A pool account to re-authorize, from a "needs re-auth" notification; the Pool tab opens it and clears it. */
    private val openReauth = MutableStateFlow<Long?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast("Without notifications you will not hear when a seat needs you")
    }

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) app.vpn.connect()
        else toast("VPN permission was not granted")
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importProfile)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        profile = app.vpn.profileInfo()

        // Re-check on every open: the tunnel is only for when the server is out of reach.
        if (savedInstanceState == null) {
            app.autoVpn.schedule()
            if (app.settings.config.value.isComplete) app.updates.check()
        }
        handleSeatLink(intent)
        lifecycleScope.launch {
            // The watcher follows the setting, also when it is changed in the app.
            app.settings.config.map { it.notifyRequests && it.isComplete }.distinctUntilChanged().collect { on ->
                if (on) {
                    askNotificationPermission()
                    WatchService.start(this@MainActivity)
                } else WatchService.stop(this@MainActivity)
            }
        }

        setContent {
            ClaudiverseTheme {
                AppRoot(
                    app,
                    openSeat = openSeat,
                    openReauth = openReauth,
                    VpnActions(
                        profile = profile,
                        import = { picker.launch(arrayOf("*/*")) },
                        connect = ::connect,
                        disconnect = app.vpn::disconnect,
                        saveCredentials = { user, pass ->
                            app.vpn.setCredentials(user, pass)
                            profile = app.vpn.profileInfo()
                        },
                    ),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSeatLink(intent)
    }

    private fun handleSeatLink(intent: Intent?) {
        val account = intent?.getLongExtra(WatchService.EXTRA_REAUTH_ACCOUNT_ID, -1) ?: -1
        if (account >= 0) {
            openReauth.value = account
            intent?.removeExtra(WatchService.EXTRA_REAUTH_ACCOUNT_ID)
        }
        val id = intent?.getLongExtra(WatchService.EXTRA_SEAT_ID, -1) ?: -1
        if (id < 0) return
        openSeat.value = SeatLink(id, intent?.getStringExtra(WatchService.EXTRA_SEAT_TITLE) ?: "Seat")
        intent?.removeExtra(WatchService.EXTRA_SEAT_ID)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun connect() {
        val intent = app.vpn.consentIntent()
        if (intent != null) consent.launch(intent) else app.vpn.connect()
    }

    private fun importProfile(uri: Uri) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "claudiverse"
        runCatching { app.vpn.importProfile(uri, name) }
            .onSuccess { toast("Imported $name") }
            .onFailure { toast("Import failed: ${it.message}") }
        profile = app.vpn.profileInfo()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
