package dev.claudiverse.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Opening a seat answers a request that was waiting, not one that arrives while it is open. */
class AnsweredByOpeningTest {
    private fun request(insertedAt: String?, updatedAt: String? = null) =
        HumanRequest(id = 32, sessionId = 7, seatTitle = "lorem-admin", summary = "needs you", detail = null, insertedAt = insertedAt, updatedAt = updatedAt)

    private val opened = parseServerTime("2026-10-12T06:30:00Z")!!

    @Test
    fun aRequestWaitingWhenTheSeatOpensIsAnswered() {
        assertTrue(answeredByOpening(request("2026-10-12T06:29:00Z"), opened))
    }

    /** The bug: a seat left open in an active window answered every new request before anything could alert. */
    @Test
    fun aRequestThatArrivesWhileTheSeatIsOpenStaysOpen() {
        assertFalse(answeredByOpening(request("2026-10-12T06:39:37Z"), opened))
    }

    @Test
    fun askingAgainWhileOpenStaysOpen() {
        assertFalse(answeredByOpening(request("2026-10-12T06:00:00Z", updatedAt = "2026-10-12T06:31:00Z"), opened))
    }

    @Test
    fun aRequestWithNoTimeIsTreatedAsWaiting() {
        assertTrue(answeredByOpening(request(null), opened))
    }
}
