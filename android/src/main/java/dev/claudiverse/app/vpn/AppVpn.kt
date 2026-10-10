package dev.claudiverse.app.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.net.VpnService
import android.os.IBinder
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import dev.claudiverse.app.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStreamReader

/** What the tunnel is doing, reduced to what the app shows and acts on. */
data class VpnState(
    val level: ConnectionStatus = ConnectionStatus.LEVEL_NOTCONNECTED,
    val detail: String = "",
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
) {
    val connected: Boolean get() = level == ConnectionStatus.LEVEL_CONNECTED
    val idle: Boolean
        get() = level == ConnectionStatus.LEVEL_NOTCONNECTED || level == ConnectionStatus.LEVEL_AUTH_FAILED
}

/** The imported profile as the app shows it. */
data class VpnProfileInfo(
    val name: String,
    val needsCredentials: Boolean,
    val hasCredentials: Boolean,
    /** A problem the core reports with the profile, or null when it is usable. */
    val problem: String?,
)

/**
 * The app's own tunnel.
 *
 * The OpenVPN core runs in-process (its service lives in this APK), and the
 * profile allows exactly one app through the tunnel: this one. Android routes
 * only this package's traffic into the VPN, so the rest of the phone keeps its
 * normal network. The system still has one VPN slot, so connecting replaces any
 * other VPN that is up.
 */
class AppVpn(private val context: Context, private val settings: Settings) {

    private val state = MutableStateFlow(VpnState())
    val status: StateFlow<VpnState> = state.asStateFlow()

    private val stateListener = object : VpnStatus.StateListener {
        override fun updateState(
            stateName: String?, logmessage: String?, localizedResId: Int, level: ConnectionStatus, intent: Intent?,
        ) {
            val text = runCatching { context.getString(localizedResId) }.getOrNull() ?: stateName.orEmpty()
            val detail = listOfNotNull(text.takeIf { it.isNotBlank() }, logmessage?.takeIf { it.isNotBlank() })
                .joinToString(" — ")
            state.value = state.value.copy(level = level, detail = detail)
        }

        override fun setConnectedVPN(uuid: String?) = Unit
    }

    private val byteListener = VpnStatus.ByteCountListener { bytesIn, bytesOut, _, _ ->
        state.value = state.value.copy(bytesIn = bytesIn, bytesOut = bytesOut)
    }

    init {
        VpnStatus.addStateListener(stateListener)
        VpnStatus.addByteCountListener(byteListener)
    }

    fun profile(): VpnProfile? =
        settings.profileUuid?.let { ProfileManager.get(context, it) }

    fun profileInfo(): VpnProfileInfo? = profile()?.let { p ->
        val check = p.checkProfile(context)
        VpnProfileInfo(
            name = p.mName ?: "",
            needsCredentials = p.isUserPWAuth,
            hasCredentials = !p.mUsername.isNullOrEmpty() && !p.mPassword.isNullOrEmpty(),
            problem = if (check == R.string.no_error_found) null else context.getString(check),
        )
    }

    /**
     * Import an .ovpn file and make it this app's profile, replacing any earlier
     * one. The file must be self-contained (inline `<ca>`, `<cert>`, `<key>`…):
     * paths to files beside it cannot be followed through the picker.
     */
    fun importProfile(uri: Uri, displayName: String) {
        val parser = ConfigParser()
        context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "cannot open $uri" }
            parser.parseConfig(InputStreamReader(stream))
        }
        val profile = parser.convertProfile()
        profile.mName = displayName.removeSuffix(".ovpn").ifBlank { "claudiverse" }
        restrictToThisApp(profile)

        val manager = ProfileManager.getInstance(context)
        val previous = profile()
        // Replacing a profile (a new remote, say) keeps the saved login when
        // the new one also asks for a username and password.
        if (previous != null && profile.isUserPWAuth && profile.mUsername.isNullOrEmpty()) {
            profile.mUsername = previous.mUsername
            profile.mPassword = previous.mPassword
        }
        previous?.let { manager.removeProfile(context, it) }
        manager.addProfile(profile)
        ProfileManager.saveProfile(context, profile)
        manager.saveProfileList(context)
        settings.profileUuid = profile.uuidString
    }

    fun setCredentials(username: String, password: String) {
        val profile = profile() ?: return
        profile.mUsername = username
        profile.mPassword = password
        ProfileManager.saveProfile(context, profile)
    }

    /** The consent dialog to show first, or null when the app may already start a VPN. */
    fun consentIntent(): Intent? = VpnService.prepare(context)

    /** Start the tunnel. Call only once [consentIntent] returns null. */
    fun connect(): Boolean {
        val profile = profile() ?: return false
        // Re-assert on every start: a profile saved by an older build, or edited
        // by the core, must never widen to the whole phone.
        restrictToThisApp(profile)
        ProfileManager.saveProfile(context, profile)
        VPNLaunchHelper.startOpenVpn(profile, context, "claudiverse app", true)
        return true
    }

    fun disconnect() {
        ProfileManager.setConntectedVpnProfileDisconnected(context)
        val intent = Intent(context, OpenVPNService::class.java).setAction(OpenVPNService.START_SERVICE)
        context.bindService(intent, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                runCatching { IOpenVPNServiceInternal.Stub.asInterface(binder).stopVPN(false) }
                context.unbindService(this)
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }, Context.BIND_AUTO_CREATE)
    }

    private fun restrictToThisApp(profile: VpnProfile) {
        profile.mAllowedAppsVpnAreDisallowed = false
        profile.mAllowedAppsVpn = hashSetOf(context.packageName)
        profile.mAllowAppVpnBypass = false
    }
}
