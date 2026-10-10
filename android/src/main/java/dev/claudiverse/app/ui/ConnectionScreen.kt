package dev.claudiverse.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import dev.claudiverse.core.ApiException
import dev.claudiverse.core.Me
import dev.claudiverse.core.SignIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.LinearProgressIndicator
import dev.claudiverse.app.update.AppUpdates
import dev.claudiverse.app.update.UpdateState
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.claudiverse.app.ServerConfig
import dev.claudiverse.app.vpn.VpnProfileInfo
import dev.claudiverse.app.vpn.VpnState
import de.blinkt.openvpn.core.ConnectionStatus
import kotlinx.coroutines.launch
import dev.claudiverse.core.relativeTime

@Composable
fun ConnectionScreen(
    vpn: VpnState,
    profile: VpnProfileInfo?,
    config: ServerConfig,
    onImport: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSaveCredentials: (String, String) -> Unit,
    onSaveConfig: (ServerConfig) -> Unit,
    requestsSupported: StateFlow<Boolean>,
    updates: AppUpdates,
    testServer: suspend () -> String,
    account: AccountActions,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VpnCard(vpn, profile, onImport, onConnect, onDisconnect, onSaveCredentials)
        AccountCard(config, account)
        ServerCard(config, onSaveConfig, testServer)
        NotifyCard(config, onSaveConfig, requestsSupported)
        UpdateCard(updates)
    }
}

/** What the Account card does with the server: who the token is, sign in for a token, revoke it. */
class AccountActions(
    val me: suspend () -> Me,
    val signIn: suspend (email: String, password: String) -> SignIn,
    val signOut: suspend () -> Unit,
)

/**
 * Who this app is signed in as, and with what powers. Signing in with the
 * operator's account mints a "super" token for this phone (profiles,
 * tokens, machines' folders, as the desktop has); it replaces whatever token
 * was pasted. Signing out revokes a signed-in token on the server; a token
 * minted on the server and pasted in is only forgotten here.
 */
@Composable
private fun AccountCard(config: ServerConfig, account: AccountActions) {
    var who by remember { mutableStateOf<Me?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var signingIn by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(config.baseUrl, config.token) {
        who = null
        status = if (config.isComplete) "Checking…" else null
        if (config.isComplete) runCatching { account.me() }
            .onSuccess { who = it; status = null }
            .onFailure { status = if ((it as? ApiException)?.httpStatus == 401) "This token is no longer valid: sign in again." else "Not checked: ${it.message}" }
    }
    if (signingIn) SignInDialog(account, onDone = { signingIn = false })
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            who?.let { me ->
                Text("Signed in as ${me.email}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (me.scope) {
                        "super" -> "Full access: profiles, tokens and machines' folders, as the desktop app."
                        "app" -> "App access: seats, spawn, stop and resume. Sign in for profiles."
                        else -> "Seat access (${me.scope ?: "api"}): sign in for the app's controls."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { signingIn = true }, enabled = config.baseUrl.isNotBlank()) {
                    Text(if (who?.scope == "super") "Sign in again" else "Sign in")
                }
                if (config.token.isNotBlank()) OutlinedButton(onClick = {
                    scope.launch {
                        status = "Signing out…"
                        status = runCatching { account.signOut() }.fold({ null }, { "Signed out here; the server said: ${it.message}" })
                    }
                }) { Text("Sign out") }
            }
            if (config.baseUrl.isBlank()) Text("Set the server URL below first.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SignInDialog(account: AccountActions, onDone: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Sign in") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { account.signIn(email.trim(), password) }
                        .onSuccess { onDone() }
                        .onFailure { e ->
                            val ex = e as? ApiException
                            error = when (ex?.httpStatus) {
                                401 -> "Wrong email or password."
                                429 -> "Too many attempts: wait ${ex.retryAfterSeconds ?: 60} s."
                                else -> "Not signed in: ${e.message}"
                            }
                            busy = false
                        }
                }
            }) { Text(if (busy) "Signing in…" else "Sign in") }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !busy) { Text("Cancel") } },
    )
}

/** This app's version, and the newer build the server holds, if any. */
@Composable
private fun UpdateCard(updates: AppUpdates) {
    val context = LocalContext.current
    val state by updates.status.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App updates", style = MaterialTheme.typography.titleMedium)
            Text("Installed: ${updates.installedVersion}", style = MaterialTheme.typography.bodySmall)
            when (val s = state) {
                UpdateState.Idle -> OutlinedButton(onClick = updates::check) { Text("Check for update") }
                UpdateState.Checking -> Text("Checking…", style = MaterialTheme.typography.bodySmall)
                UpdateState.UpToDate -> {
                    Text("Up to date.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = updates::check) { Text("Check again") }
                }
                UpdateState.NonePublished -> {
                    Text("The server has no build published.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = updates::check) { Text("Check again") }
                }
                is UpdateState.Available -> {
                    Text(
                        "Available: ${s.build.versionName} (${s.build.commit}), ${s.build.size / 1_048_576} MB" +
                            (s.build.insertedAt?.let { ", published ${relativeTime(it)}" } ?: "") +
                            (s.build.notes?.let { "\n$it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { updates.install(s.build) }) { Text("Update") }
                }
                is UpdateState.Downloading -> {
                    val total = s.build.size.coerceAtLeast(1)
                    Text("Downloading ${s.done / 1_048_576} of ${total / 1_048_576} MB", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { (s.done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.Installing -> Text("Installing ${s.build.versionName}: confirm in Android's prompt. The app restarts when done.",
                    style = MaterialTheme.typography.bodySmall)
                UpdateState.NeedsInstallPermission -> {
                    Text("Allow this app to install updates, then tap Update again.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)))
                        updates.check()
                    }) { Text("Allow installs") }
                }
                is UpdateState.Failed -> {
                    Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = updates::check) { Text("Check again") }
                }
            }
        }
    }
}

/**
 * The watcher: a permanent quiet notification while it listens, and a loud one
 * when a top-level seat asks for the operator. Android pauses background apps
 * to save battery, so it only stays reliable when the app is exempt.
 */
@Composable
private fun NotifyCard(config: ServerConfig, onSave: (ServerConfig) -> Unit, supportedFlow: StateFlow<Boolean>) {
    val context = LocalContext.current
    val supported by supportedFlow.collectAsStateWithLifecycle()
    val power = context.getSystemService(PowerManager::class.java)
    // Re-read when the screen comes back from the system dialog.
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleResumeEffect(Unit) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Seats that need you", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Notify me when a seat asks for me (keeps a quiet notification while it listens)",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(config.notifyRequests, { onSave(config.copy(notifyRequests = it)) })
            }
            if (!supported) Text("This server does not offer seat requests yet.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            if (config.notifyRequests) {
                if (exempt) Text("Background: unrestricted, so the watcher keeps running.", style = MaterialTheme.typography.bodySmall)
                else {
                    Text("Android may pause the watcher to save battery. Allow the app to run unrestricted:",
                        style = MaterialTheme.typography.bodySmall)
                    @SuppressLint("BatteryLife")
                    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                    OutlinedButton(onClick = { context.startActivity(ask) }) { Text("Allow background use") }
                }
            }
        }
    }
}

@Composable
private fun VpnCard(
    vpn: VpnState,
    profile: VpnProfileInfo?,
    onImport: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSaveCredentials: (String, String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("In-app VPN", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                VpnPill(vpn)
            }
            Text(
                "Only this app's traffic goes through the tunnel; the rest of the phone is untouched. " +
                    "Android has one VPN slot, so connecting replaces any other VPN that is up.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (vpn.detail.isNotBlank()) Text(vpn.detail, style = MaterialTheme.typography.bodySmall)
            if (vpn.connected) Text("↓ ${bytes(vpn.bytesIn)}  ↑ ${bytes(vpn.bytesOut)}", style = MaterialTheme.typography.labelSmall)

            if (profile == null) {
                Text("No profile yet. Import a self-contained .ovpn (certificates inline).", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Profile: ${profile.name}", style = MaterialTheme.typography.bodyMedium)
                profile.problem?.let { ErrorBanner(it) }
                if (profile.needsCredentials) CredentialsForm(profile.hasCredentials, onSaveCredentials)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profile != null) {
                    if (vpn.idle) Button(onClick = onConnect, enabled = profile.problem == null) { Text("Connect") }
                    else OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                }
                OutlinedButton(onClick = onImport) { Text(if (profile == null) "Import .ovpn" else "Replace profile") }
            }
        }
    }
}

@Composable
fun VpnPill(vpn: VpnState) = when (vpn.level) {
    ConnectionStatus.LEVEL_CONNECTED -> Pill("VPN up", StatusColors.good)
    ConnectionStatus.LEVEL_NOTCONNECTED -> Pill("VPN off", StatusColors.muted)
    ConnectionStatus.LEVEL_AUTH_FAILED -> Pill("auth failed", StatusColors.bad)
    ConnectionStatus.LEVEL_NONETWORK -> Pill("no network", StatusColors.bad)
    ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> Pill("needs input", StatusColors.bad)
    ConnectionStatus.LEVEL_VPNPAUSED -> Pill("paused", StatusColors.busy)
    else -> Pill("connecting", StatusColors.busy)
}

@Composable
private fun CredentialsForm(saved: Boolean, onSave: (String, String) -> Unit) {
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    Text(
        if (saved) "This profile uses a username and password (saved)." else "This profile needs a username and password.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(user, { user = it }, label = { Text("VPN username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        pass, { pass = it }, label = { Text("VPN password") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = { onSave(user, pass); pass = "" }, enabled = user.isNotBlank() && pass.isNotBlank()) {
        Text("Save credentials")
    }
}

@Composable
private fun ServerCard(config: ServerConfig, onSave: (ServerConfig) -> Unit, testServer: suspend () -> String) {
    var url by remember(config) { mutableStateOf(config.baseUrl) }
    var token by remember(config) { mutableStateOf(config.token) }
    var author by remember(config) { mutableStateOf(config.author) }
    var auto by remember(config) { mutableStateOf(config.autoConnect) }
    // copy(), not the constructor: settings this card does not edit must survive a save.
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val edited = config.copy(baseUrl = url, token = token, author = author, autoConnect = auto)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Claudiverse server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                url, { url = it }, label = { Text("Server URL") }, placeholder = { Text("http://host:port") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                token, { token = it }, label = { Text("API token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                author, { author = it }, label = { Text("Post to the forum as") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use the VPN only when the server is out of reach (off on its local network, and never in place of another VPN)",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(auto, { auto = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(edited) }, enabled = edited != config) { Text("Save") }
                OutlinedButton(onClick = {
                    onSave(edited)
                    result = "Testing…"
                    scope.launch { result = runCatching { "OK — signed in as ${testServer()}" }.getOrElse { "Failed: ${it.message}" } }
                }, enabled = edited.isComplete) { Text("Save & test") }
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun bytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / (1024.0 * 1024))
}
