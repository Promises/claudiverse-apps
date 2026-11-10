package dev.claudiverse.app.trello

import dev.claudiverse.core.TrelloAlreadyDecided
import dev.claudiverse.core.TrelloRequestGone
import dev.claudiverse.core.TrelloWriteRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class TrelloPromptsTest {

    private fun req(id: Long, updatedAt: String, status: String = "open") = TrelloWriteRequest(
        id = id, sessionId = 12, seatTitle = "pm-seat", board = "AbCd1234", boardName = "Example Board",
        boardUrl = "https://trello.com/b/AbCd1234", reason = "file the bugs we find", status = status,
        decidedAt = null, insertedAt = "2026-11-10T10:00:00Z", updatedAt = updatedAt,
    )

    @Test
    fun aNewRequestIsPostedAndAnUnchangedOneIsNot() {
        val r = req(4, "2026-11-10T10:00:00Z")
        assertEquals(listOf(r), trelloNotifyPlan(listOf(r), emptyMap()).post)
        val again = trelloNotifyPlan(listOf(r), mapOf(4L to r.changedAtMs))
        assertEquals(emptyList<TrelloWriteRequest>(), again.post)
        assertEquals(emptySet<Long>(), again.cancel)
    }

    @Test
    fun askingAgainPostsTheSameIdAgain() {
        val first = req(4, "2026-11-10T10:00:00Z")
        val asked = req(4, "2026-11-10T10:05:00Z")
        assertEquals(listOf(asked), trelloNotifyPlan(listOf(asked), mapOf(4L to first.changedAtMs)).post)
    }

    @Test
    fun aRequestNoLongerOpenIsCancelledAndNeverPosted() {
        val decided = req(4, "2026-11-10T10:00:00Z", status = "approved")
        val plan = trelloNotifyPlan(listOf(decided), mapOf(4L to 1L, 7L to 2L))
        assertEquals(emptyList<TrelloWriteRequest>(), plan.post)
        assertEquals(setOf(4L, 7L), plan.cancel)
        val unknown = req(5, "2026-11-10T10:00:00Z", status = "expired")
        assertEquals("an unknown status is not open", emptyList<TrelloWriteRequest>(), trelloNotifyPlan(listOf(unknown), emptyMap()).post)
    }

    @Test
    fun aRefusalSaysWhatWon() {
        val won = req(4, "2026-11-10T10:00:00Z", status = "denied")
        assertEquals("Already denied elsewhere", trelloActionNotice(TrelloAlreadyDecided("409", won), "approved"))
        assertEquals("Already decided elsewhere", trelloActionNotice(TrelloAlreadyDecided("409", null), "approved"))
        assertEquals("The server no longer lists it", trelloActionNotice(TrelloRequestGone("404"), "denied"))
        assertEquals("Not approved: boom", trelloActionNotice(IllegalStateException("boom"), "approved"))
    }
}
