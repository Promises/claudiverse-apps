package dev.claudiverse.app

import android.content.Context
import dev.claudiverse.core.ServerAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the server is and how to reach it. Empty until the user fills it in. */
data class ServerConfig(
    val baseUrl: String = "",
    val token: String = "",
    /** The name forum posts from this app go out under. */
    val author: String = ServerAccess.DEFAULT_AUTHOR,
    /** Bring the tunnel up when the server is out of reach (and take it down when it is not). */
    val autoConnect: Boolean = true,
    /** Keep a watcher running that notifies when a seat asks for the operator. */
    val notifyRequests: Boolean = true,
) {
    /** What the API reads on every call. */
    val access: ServerAccess get() = ServerAccess(baseUrl, token, author)

    val isComplete: Boolean get() = access.isComplete
}

/**
 * The app's own settings, kept apart from the OpenVPN core's preferences.
 *
 * Exposed as a [StateFlow] so the API client and the screens see a change the
 * moment it is saved, without a restart.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("claudiverse", Context.MODE_PRIVATE)

    private val state = MutableStateFlow(load())
    val config: StateFlow<ServerConfig> = state.asStateFlow()

    /** UUID of the imported OpenVPN profile in the core's ProfileManager. */
    var profileUuid: String?
        get() = prefs.getString(KEY_PROFILE, null)
        set(value) = prefs.edit().putString(KEY_PROFILE, value).apply()

    fun save(config: ServerConfig) {
        val normalised = config.copy(
            baseUrl = config.baseUrl.trim().trimEnd('/'),
            token = config.token.trim(),
            author = config.author.trim().ifEmpty { ServerAccess.DEFAULT_AUTHOR },
        )
        prefs.edit()
            .putString(KEY_URL, normalised.baseUrl)
            .putString(KEY_TOKEN, normalised.token)
            .putString(KEY_AUTHOR, normalised.author)
            .putBoolean(KEY_AUTO, normalised.autoConnect)
            .putBoolean(KEY_NOTIFY, normalised.notifyRequests)
            .apply()
        state.value = normalised
    }

    private fun load() = ServerConfig(
        baseUrl = prefs.getString(KEY_URL, "") ?: "",
        token = prefs.getString(KEY_TOKEN, "") ?: "",
        author = prefs.getString(KEY_AUTHOR, null) ?: ServerAccess.DEFAULT_AUTHOR,
        autoConnect = prefs.getBoolean(KEY_AUTO, true),
        notifyRequests = prefs.getBoolean(KEY_NOTIFY, true),
    )

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "api_token"
        const val KEY_AUTHOR = "forum_author"
        const val KEY_AUTO = "auto_connect"
        const val KEY_NOTIFY = "notify_requests"
        const val KEY_PROFILE = "vpn_profile_uuid"
    }
}
