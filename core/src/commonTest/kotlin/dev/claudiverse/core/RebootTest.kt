package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A machine's safe reboot (server 1f16a19, host agent 0.23): its state, its reopens, and the words both apps use. */
class RebootTest {
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun reboots(): Reboots {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return Reboots(PhoenixSocket({ ServerAccess("", "") }, scope), scope)
    }

    private val brief = """{"kind":"rolling","taken_at":"2026-11-10T03:00:00Z","checked_at":"2026-11-10T03:59:00Z","boot_time":null,
        "seats":[{"title":"lorem-admin","busy":true},{"title":"ipsum-orchestrator","busy":false}],"runners":[{"slot":"ipsum-w1"}],"busy":1}"""
    private fun done(id: String?, host: String = "my-mac", ok: Boolean = true) =
        """{"reopen_id":${id?.let { "\"$it\"" } ?: "null"},"host":"$host","state":"done","ok":$ok,"error":${if (ok) "null" else "\"tmux gone\""},
        "result":{"id":"r1","kind":"rolling","taken_at":"2026-11-10T03:00:00Z","reopened_at":"2026-11-10T04:05:00Z",
        "seats":[{"title":"lorem-admin","session_id":"s1","busy":true,"resumed":true,"outcome":"started","code":null,"reason":null},
                 {"title":"ipsum-orchestrator","busy":false,"resumed":false,"outcome":"skipped","code":"running","reason":"already running"}],
        "runners":[{"slot":"ipsum-w1","tmux":"ipsum-w1","outcome":"started","code":null,"reason":null}],
        "counts":{"seats":{"started":1,"skipped":1,"failed":0},"runners":{"started":1}}}}"""

    /** An agent before 0.23, or a server that never says: no reboot, so nothing is shown. */
    @Test
    fun noRebootKeyMeansNothingShown() {
        assertNull(Host.from(json("""{"name":"my-mac","online":true,"seats":2,"roots":[]}""")).reboot)
        assertNull(Host.from(json("""{"name":"my-mac","online":true,"seats":2,"roots":[],"reboot":null}""")).reboot)
        val h = Host.from(json("""{"name":"my-mac","online":true,"seats":2,"roots":[],"reboot":{"recoverable":$brief,"prepared":null,"last_reopen":null,"reopening":null}}"""))
        assertEquals(listOf("lorem-admin"), h.reboot?.recoverable?.midTurn)
        assertEquals(listOf("ipsum-w1"), h.reboot?.recoverable?.runners)
    }

    /** A fast machine: the reopen's done comes before the 202 that names it; looked up after, it is still there. */
    @Test
    fun aReopenThatFinishesBeforeItsReplyIsKept() {
        val r = reboots()
        r.onEvent("host_reopen", json(done("re-1")))
        val d = assertNotNull(r.finished("re-1"))
        assertEquals("Reopened: seats 1 started, 1 skipped; runners 1 started.", RebootWording.reopened(d.result!!))
        assertNull(r.finished("re-2"))
    }

    /** One the machine did by itself (no id) is never taken for the one the app asked for. */
    @Test
    fun aReopenTheMachineDidItselfIsKeptApart() {
        val r = reboots()
        r.onEvent("host_reopen", json(done(null)))
        assertTrue(r.done.value.isEmpty())
        assertEquals("my-mac", r.machineReopens.value["my-mac"]?.host)
        // "started" is not done.
        r.onEvent("host_reopen", json("""{"reopen_id":"re-3","host":"my-mac","state":"started"}"""))
        assertNull(r.finished("re-3"))
    }

    @Test
    fun aHostRebootEventReplacesTheListsCopy() {
        val r = reboots()
        val listed = Host.from(json("""{"name":"my-mac","online":true,"seats":2,"roots":[],"reboot":{"recoverable":$brief}}"""))
        assertNotNull(r.rebootOf(listed)?.recoverable)
        r.onEvent("host_reboot", json("""{"host":"my-mac","reboot":{"recoverable":null,"prepared":$brief,"last_reopen":null,"reopening":null}}"""))
        assertNull(r.rebootOf(listed)?.recoverable)
        assertNotNull(r.rebootOf(listed)?.prepared)
    }

    @Test
    fun theWordsBothAppsUse() {
        val b = RebootBrief.from(json(brief))
        assertEquals("Reopen seats (2)", RebootWording.reopenButton(b))
        assertEquals("Reopen 2 seats and 1 runner", RebootWording.reopenAction(b))
        assertEquals(
            "my-mac rebooted without Prepare reboot. As of 4 min ago it ran these. Reopen them, each seat on its own conversation? " +
                "The seat that was mid-turn gets one \"continue\" once back.",
            RebootWording.reopenConfirm("my-mac", b, "4 min ago"),
        )
        assertEquals(
            "Ready for a reboot: 2 seats (mid-turn: lorem-admin), 1 runner, written down just now. After the reboot my-mac reopens them by itself.",
            RebootWording.prepared("my-mac", b, "just now"),
        )
        val s = RebootSummary.from(json(brief.replace("\"busy\":1}", "\"busy\":1,\"excluded\":[{\"title\":\"ipsum-w9-t1\",\"reason\":\"runner seat\"}],\"workers\":3,\"others\":[\"tv\"]}")))
        assertEquals(
            listOf("Not coming back (no Claude Code seat in them): tv.", "3 runner workers left to their runners.", "Left out: ipsum-w9-t1 (runner seat)."),
            RebootWording.preparedNotes(s),
        )
        val d = ReopenDone.from(json(done("re-1", ok = false)))
        assertEquals("The reopen failed: tmux gone", RebootWording.failed(d))
        val report = d.result!!
        assertEquals(
            listOf("lorem-admin started (mid-turn: it gets one continue)", "ipsum-orchestrator skipped: already running", "runner ipsum-w1 started"),
            report.outcomes.map(RebootWording::outcome),
        )
        assertEquals("nothing", RebootWording.counts(mapOf("started" to 0)))
    }

    /** reopen_seats: 202 names the reopen; 409 and 404 are their own kinds, for the UI to show as states. */
    @Test
    fun reopenSeatsAnswers() = runTest {
        fun api(status: HttpStatusCode, body: String) = Api({ ServerAccess("http://server:4010", "tok") }, HttpClient(MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        assertEquals("re-9", api(HttpStatusCode.Accepted, """{"reopen_id":"re-9"}""").reopenSeats("my-mac"))
        assertFailsWith<AlreadyReopening> { api(HttpStatusCode.Conflict, """{"error":"my-mac is reopening its seats already"}""").reopenSeats("my-mac") }
        assertFailsWith<NothingToReopen> { api(HttpStatusCode.NotFound, """{"error":"my-mac has no seats to reopen"}""").reopenSeats("my-mac") }
    }
}
