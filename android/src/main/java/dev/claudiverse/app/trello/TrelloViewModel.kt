package dev.claudiverse.app.trello

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.claudiverse.app.AppContainer
import dev.claudiverse.core.TrelloGrant
import dev.claudiverse.core.TrelloWriteRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Seats' Trello write requests to approve or deny, and the grants they made,
 * to revoke. The store stays live while this screen is open; each action's
 * outcome comes back from the server and every device sees it.
 */
class TrelloViewModel(private val app: AppContainer) : ViewModel() {

    val requests: StateFlow<List<TrelloWriteRequest>> = app.trello.requests
    val grants: StateFlow<List<TrelloGrant>> = app.trello.grants
    val supported: StateFlow<Boolean> = app.trello.supported

    /** Requests and grants with an action in flight: their buttons wait for it. */
    private val busyState = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = busyState.asStateFlow()

    /** The last thing that did not go through, for people. */
    private val noticeState = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = noticeState.asStateFlow()

    init {
        app.watchTrello()
        viewModelScope.launch { app.trello.refresh() }
    }

    override fun onCleared() {
        app.unwatchTrello()
    }

    fun approve(id: Long) = act(requestKey(id), "approved") { app.trello.approve(id) }

    fun deny(id: Long) = act(requestKey(id), "denied") { app.trello.deny(id) }

    fun revoke(grantId: Long) = act(grantKey(grantId), "revoked") { app.trello.revoke(grantId) }

    fun dismissNotice() {
        noticeState.value = null
    }

    private fun act(key: String, doing: String, call: suspend () -> Unit) {
        if (key in busyState.value) return
        busyState.update { it + key }
        noticeState.value = null
        viewModelScope.launch {
            try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                noticeState.value = trelloActionNotice(e, doing)
            } finally {
                busyState.update { it - key }
            }
        }
    }

    companion object {
        fun requestKey(id: Long) = "request:$id"
        fun grantKey(id: Long) = "grant:$id"
    }
}
