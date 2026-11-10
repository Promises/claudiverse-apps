package dev.claudiverse.app.machines

import dev.claudiverse.core.AlreadyReopening
import dev.claudiverse.core.ApiException
import dev.claudiverse.core.HostReboot
import dev.claudiverse.core.NothingToReopen
import dev.claudiverse.core.RebootBrief

/**
 * What a machine's card offers for a safe reboot. Nothing for a machine that
 * is offline or whose agent has no safe reboot (older than 0.23: reboot is
 * null). Reopen only while the machine offers a list and is not reopening.
 */
data class RebootActions(val canPrepare: Boolean, val reopen: RebootBrief?, val reopening: Boolean) {
    companion object {
        val NONE = RebootActions(canPrepare = false, reopen = null, reopening = false)
    }
}

fun rebootActions(online: Boolean, reboot: HostReboot?): RebootActions {
    if (!online || reboot == null) return RebootActions.NONE
    val reopening = reboot.reopening != null
    return RebootActions(canPrepare = !reopening, reopen = reboot.recoverable?.takeIf { !reopening }, reopening = reopening)
}

/** What to tell the operator when Prepare reboot did not go through. */
fun prepareNotice(host: String, e: Exception): String = when ((e as? ApiException)?.httpStatus) {
    409 -> "$host's host agent is older than 0.23: no safe reboot"
    504 -> "$host did not answer (offline?)"
    403 -> "This app may not prepare reboots"
    else -> "Not prepared: ${e.message}"
}

/** What to tell the operator when Reopen seats did not start. */
fun reopenNotice(host: String, e: Exception): String = when (e) {
    is AlreadyReopening -> "A reopen of $host is running already"
    is NothingToReopen -> "$host has nothing to reopen"
    else -> when ((e as? ApiException)?.httpStatus) {
        504 -> "$host did not answer (offline?)"
        403 -> "This app may not reopen seats"
        else -> "Not reopened: ${e.message}"
    }
}
