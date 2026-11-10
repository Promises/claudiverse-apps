package dev.claudiverse.app.trello

import dev.claudiverse.core.ApiException
import dev.claudiverse.core.TrelloAlreadyDecided
import dev.claudiverse.core.TrelloRequestGone
import dev.claudiverse.core.TrelloWriteRequest

/** What the notifier does next: requests to (re)post, and ids whose notification goes. */
data class TrelloNotifyPlan(val post: List<TrelloWriteRequest>, val cancel: Set<Long>)

/**
 * From the open requests and what is [shown] (request id to the change it was
 * shown at): post a request not shown yet, or asked again since (a newer
 * change, same id); leave an unchanged one alone; cancel one no longer open,
 * decided here or on any other device.
 */
fun trelloNotifyPlan(open: List<TrelloWriteRequest>, shown: Map<Long, Long>): TrelloNotifyPlan {
    val openIds = open.filter { it.isOpen }.map { it.id }.toSet()
    return TrelloNotifyPlan(
        post = open.filter { it.isOpen && shown[it.id] != it.changedAtMs },
        cancel = shown.keys - openIds,
    )
}

/** What to tell the operator when Approve, Deny or Revoke did not go through. */
fun trelloActionNotice(e: Exception, doing: String): String = when (e) {
    is TrelloAlreadyDecided -> e.request?.let { "Already ${it.status} elsewhere" } ?: "Already decided elsewhere"
    is TrelloRequestGone -> "The server no longer lists it"
    else -> when ((e as? ApiException)?.httpStatus) {
        403 -> "This app may not decide Trello access (it needs the app token)"
        404 -> "The server no longer lists it"
        else -> "Not $doing: ${e.message}"
    }
}
