package dev.claudiverse.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import dev.claudiverse.app.BuildConfig
import dev.claudiverse.app.AppContainer
import dev.claudiverse.core.Api
import dev.claudiverse.core.AppBuild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.io.File
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    /** The server has no build published (or no build store yet). */
    data object NonePublished : UpdateState
    data class Available(val build: AppBuild) : UpdateState
    data class Downloading(val build: AppBuild, val done: Long) : UpdateState
    /** Handed to Android's installer; its confirmation follows. */
    data class Installing(val build: AppBuild) : UpdateState
    /** The phone has not allowed this app to install updates yet. */
    data object NeedsInstallPermission : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** A build is an update when its version is higher than the installed one. */
fun isUpdate(installedVersionCode: Long, build: AppBuild): Boolean = build.versionCode > installedVersionCode

/**
 * Updates the app from the Claudiverse server, over whatever connection the
 * app has (LAN, a system VPN, or its own tunnel). The server keeps only the
 * newest build. Integrity: the download's sha256 must match the server's, and
 * Android itself refuses an update not signed with the installed app's key.
 */
class AppUpdates(private val context: Context, private val api: Api, private val scope: CoroutineScope) {

    private val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val status: StateFlow<UpdateState> = state.asStateFlow()

    val installedVersionCode: Long = BuildConfig.VERSION_CODE.toLong()
    val installedVersion: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_COMMIT})"

    private var job: Job? = null

    fun check() {
        if (job?.isActive == true) return
        state.value = UpdateState.Checking
        job = scope.launch {
            state.value = runCatching { api.latestBuild() }.fold(
                onSuccess = { b ->
                    when {
                        b == null -> UpdateState.NonePublished
                        isUpdate(installedVersionCode, b) -> UpdateState.Available(b)
                        else -> UpdateState.UpToDate
                    }
                },
                onFailure = { UpdateState.Failed("Check failed: ${it.message}") },
            )
        }
    }

    fun install(build: AppBuild) {
        if (job?.isActive == true) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            state.value = UpdateState.NeedsInstallPermission
            return
        }
        job = scope.launch {
            try {
                val file = File(context.cacheDir, "updates/claudiverse.apk").apply { parentFile?.mkdirs() }
                state.value = UpdateState.Downloading(build, 0)
                withContext(Dispatchers.IO) {
                    file.outputStream().asSink().buffered().use { sink ->
                        api.download(build.url, sink) { done -> state.value = UpdateState.Downloading(build, done) }
                    }
                }
                val sum = sha256(file)
                if (sum != build.sha256) {
                    file.delete()
                    state.value = UpdateState.Failed("Download corrupt: sha256 $sum, expected ${build.sha256}")
                    return@launch
                }
                state.value = UpdateState.Installing(build)
                commit(file)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.i(TAG, "update failed: ${e.message}")
                state.value = UpdateState.Failed("Update failed: ${e.message}")
            }
        }
    }

    /** Called by [UpdateStatusReceiver] when the installer reports a failure or a cancel. */
    fun installerResult(message: String) {
        state.value = UpdateState.Failed(message)
    }

    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun commit(file: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            // An app updating itself may skip the prompt where Android allows it (12+).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("claudiverse.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateStatusReceiver::class.java),
                // Mutable: the installer adds the status extras to it.
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
            )
            session.commit(status.intentSender)
        }
    }

    private companion object {
        const val TAG = "claudiverse"
    }
}

/**
 * The installer's answer. "Pending user action" carries the confirmation
 * screen to show; success needs nothing (Android restarts the app's process);
 * anything else is reported on the update card.
 */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("claudiverse", "update installed")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                AppContainer.get(context).updates.installerResult("Not installed: $message")
            }
        }
    }
}
