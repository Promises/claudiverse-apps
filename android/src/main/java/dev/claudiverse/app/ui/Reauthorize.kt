package dev.claudiverse.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.claudiverse.core.AccountSignIn
import dev.claudiverse.core.AnthropicAccount
import dev.claudiverse.core.Reauthorization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Where a re-authorization stands. */
private sealed interface ReauthStep {
    /** Asking the server for a sign-in page. [force]: this one replaces a different account. */
    data class Starting(val force: Boolean) : ReauthStep

    /** The page is open in the browser; waiting for its code. */
    data class Pasting(val signIn: AccountSignIn, val force: Boolean, val problem: String? = null) : ReauthStep

    data class Sending(val signIn: AccountSignIn, val force: Boolean) : ReauthStep

    /** The code was for another Anthropic account; nothing changed. */
    data class OtherAccount(val result: Reauthorization.OtherAccount) : ReauthStep

    /** The sign-in page could not be had. */
    data class Failed(val problem: String) : ReauthStep
}

/**
 * Re-authorize one pool account, as Add account signs one in: the server's
 * sign-in page opens in the browser, the operator signs in to [account]'s
 * Anthropic account and pastes the code it shows. The account keeps its
 * label and priority; nothing is typed but the code, and nothing deleted.
 *
 * A code for a different Anthropic account changes nothing and says so;
 * Replace anyway takes a fresh sign-in (the first code is spent) and stores
 * that account in this one's place.
 */
@Composable
fun ReauthorizeDialog(
    account: AnthropicAccount,
    start: suspend () -> AccountSignIn,
    complete: suspend (AccountSignIn, String, Boolean) -> Reauthorization,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var step by remember(account.id) { mutableStateOf<ReauthStep>(ReauthStep.Starting(force = false)) }
    var code by remember(account.id) { mutableStateOf("") }

    fun open(url: String): String? = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: ActivityNotFoundException) {
        "No browser to open the sign-in page."
    }

    // Each Starting asks for a fresh page and opens it: a code is good for one sign-in only.
    val starting = step as? ReauthStep.Starting
    LaunchedEffect(starting) {
        val s = starting ?: return@LaunchedEffect
        step = try {
            val signIn = start()
            code = ""
            ReauthStep.Pasting(signIn, s.force, open(signIn.url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReauthStep.Failed("Could not begin the sign-in: ${e.message}")
        }
    }

    fun send(signIn: AccountSignIn, force: Boolean) {
        step = ReauthStep.Sending(signIn, force)
        scope.launch {
            step = try {
                when (val r = complete(signIn, code, force)) {
                    is Reauthorization.Done -> {
                        val status = r.account?.status?.replace('_', ' ')
                        onDone("${account.label} re-authorized" + (status?.let { ": $it" } ?: ""))
                        return@launch
                    }
                    is Reauthorization.OtherAccount -> ReauthStep.OtherAccount(r)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReauthStep.Pasting(signIn, force, "Not re-authorized: ${e.message}")
            }
        }
    }

    val who = account.email?.let { "as $it" } ?: "to the account ${account.label} uses"
    when (val s = step) {
        is ReauthStep.Starting, is ReauthStep.Sending -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Re-authorize ${account.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(if (s is ReauthStep.Starting) "Opening the sign-in page…" else "Checking the code…")
                }
            },
            confirmButton = {},
            dismissButton = { if (s is ReauthStep.Starting) TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        is ReauthStep.Pasting -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (s.force) "Replace ${account.label}" else "Re-authorize ${account.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (s.force) "Sign in to the account that should replace ${account.label}, then paste the code the page shows."
                        else "In the browser, sign in $who and approve. Then copy the code the page shows and paste it here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        code, { code = it },
                        label = { Text("Code") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { clipboard.getText()?.text?.let { code = it.trim() } }) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    s.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { step = ReauthStep.Starting(s.force) }) { Text("Open a new sign-in page") }
                }
            },
            confirmButton = {
                TextButton(onClick = { send(s.signIn, s.force) }, enabled = code.isNotBlank()) { Text(if (s.force) "Replace" else "Re-authorize") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        is ReauthStep.OtherAccount -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("A different account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The server's sentence first: when account ids decide, either email can be missing.
                    Text(
                        s.result.message.takeIf { it.isNotBlank() && !it.startsWith("HTTP ") } ?: (
                            listOfNotNull(
                                s.result.signedIn?.let { "That code signed in as $it" },
                                s.result.stored?.let { "${account.label} is $it" },
                            ).joinToString(", but ").ifEmpty { "That code signed in to a different account than ${account.label}" } + "."
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Nothing was changed. Sign in again as the right account, or replace ${account.label} with the other one.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { step = ReauthStep.Starting(force = true) }) {
                    Text("Replace anyway", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = { step = ReauthStep.Starting(force = false) }) { Text("Sign in again") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            },
        )

        is ReauthStep.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Re-authorize ${account.label}") },
            text = { Text(s.problem, color = MaterialTheme.colorScheme.error) },
            confirmButton = { TextButton(onClick = { step = ReauthStep.Starting(force = false) }) { Text("Try again") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}
