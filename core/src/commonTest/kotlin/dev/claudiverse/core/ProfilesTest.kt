package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Seat profiles: reading them as the server lists them, saving with revisions, and live events. */
class ProfilesTest {

    /** A profile as GET /api/profiles lists it (the server's serialize plus derived status). */
    private fun listed(
        id: Long = 7,
        name: String = "ipsum-admin",
        revision: Long = 3,
        fleet: String? = "ipsum-fleet",
        model: String? = "claude-opus-5-5[1m]",
        status: String = "running",
        restartNeeded: Boolean = true,
    ) = """{"id":$id,"name":"$name","revision":$revision,"machine":"my-mac.local",
        "folder":"/Users/me/Documents/work/client/ipsum-api","fleet":${fleet?.let { "\"$it\"" }},
        "role":"orchestrator","can_notify":true,"runtime":"stock+mod","model":${model?.let { "\"$it\"" }},"effort":"high",
        "permission_mode":"auto","mcp":["claudiverse","ghidra"],"pooled":false,"autostart":true,"resume":"latest","debug":false,
        "last_start":{"at":"2026-10-05T13:50:00Z","outcome":"started","reason":null,"seat":"ipsum-admin"},
        "inserted_at":"2026-10-05T13:00:00","updated_at":"2026-10-05T13:40:00",
        "status":"$status","seat":${if (status == "running") "204200" else "null"},"restart_needed":$restartNeeded}"""

    @Test
    fun profilesGroupByFleetInNameOrderWithNoFleetLast() {
        val p = { id: Long, name: String, fleet: String?, status: String -> Profile.from(json(listed(id = id, name = name, fleet = fleet, status = status))) }
        val groups = profileGroups(listOf(p(1, "a", null, "stopped"), p(2, "b", "lorem-fleet", "running"), p(3, "c", "Meta", "stopped"), p(4, "d", "lorem-fleet", "stopped")))
        assertEquals(listOf("lorem-fleet", "Meta", null), groups.map { it.fleet })
        assertEquals(listOf("b", "d"), groups[0].profiles.map { it.name })
        assertEquals(1, groups[0].running)
        assertEquals(listOf("a"), groups[2].profiles.map { it.name })
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun event(op: String, profile: String) = json("""{"op":"$op","profile":$profile}""")

    @Test
    fun aListedProfileReadsEveryField() {
        val p = Profile.from(json(listed()))
        assertEquals(7, p.id)
        assertEquals("ipsum-admin", p.name)
        assertEquals(3, p.revision)
        assertEquals(
            ProfileFields(
                machine = "my-mac.local", folder = "/Users/me/Documents/work/client/ipsum-api", fleet = "ipsum-fleet",
                role = "orchestrator", canNotify = true, runtime = "stock+mod", model = "claude-opus-5-5[1m]", effort = "high",
                permissionMode = "auto", mcp = listOf("claudiverse", "ghidra"), pooled = false, autostart = true, resume = "latest", debug = false,
            ),
            p.fields,
        )
        assertEquals(ProfileStart("2026-10-05T13:50:00Z", "started", null, "ipsum-admin"), p.lastStart)
        assertEquals("running", p.status)
        assertEquals(204200, p.seat)
        assertTrue(p.restartNeeded)
    }

    /** requested_at (server 22d83f1+) is kept apart from at, the outcome's time; an older server's start falls back to at. */
    @Test
    fun aStartKnowsWhenItWasAskedFor() {
        val withRequest = ProfileStart.from(json("""{"at":"2026-10-05T14:04:16Z","requested_at":"2026-10-05T14:04:09Z","outcome":"started","seat":204109}"""))
        assertEquals("2026-10-05T14:04:09Z", withRequest.requestedAt)
        assertEquals("2026-10-05T14:04:16Z", withRequest.at)
        assertEquals("204109", withRequest.seat)
        assertEquals("2026-10-05T14:04:16Z", ProfileStart.from(json("""{"at":"2026-10-05T14:04:16Z","outcome":"started"}""")).requestedAt)
    }

    /** Server 6a2f344: a seat its machine started that never registered can be stopped; the stop is recorded on the start. */
    @Test
    fun anUnregisteredSeatCanBeStoppedAndItsStopIsRecorded() {
        val p = Profile.from(json(listed(status = "unregistered", restartNeeded = false)))
        assertTrue(p.canStop)
        assertTrue(!p.isRunning)
        assertTrue(!Profile.from(json(listed(status = "stopped", restartNeeded = false))).canStop)
        val stopped = ProfileStart.from(json("""{"at":"2026-10-06T08:00:07Z","outcome":"started","stopped_at":"2026-10-06T08:10:00Z"}"""))
        assertEquals("2026-10-06T08:10:00Z", stopped.stoppedAt)
        assertNull(ProfileStart.from(json("""{"at":"2026-10-06T08:00:07Z","outcome":"started"}""")).stoppedAt)
    }

    /** Server ddd6eeb: pooled is the default; a profile sent without it reads pooled, one saved false stays false. */
    @Test
    fun pooledIsTheDefault() {
        assertTrue(ProfileFields(machine = "m", folder = "/x").pooled)
        assertTrue(ProfileFields.from(json("""{"machine":"m","folder":"/x"}""")).pooled)
        assertTrue(!ProfileFields.from(json("""{"machine":"m","folder":"/x","pooled":false}""")).pooled)
    }

    /** The editor's fields go back as the server named them; null clears an optional one. */
    @Test
    fun fieldsRoundTripThroughTheServersNames() {
        val fields = Profile.from(json(listed(fleet = null, model = null))).fields
        val sent = fields.toJson()
        assertEquals(kotlinx.serialization.json.JsonNull, sent["fleet"])
        assertEquals("auto", sent["permission_mode"]?.jsonPrimitive?.content)
        assertEquals(fields, ProfileFields.from(sent))
    }

    // --- merge after a 409 ---

    private val base = ProfileFields(machine = "my-mac", folder = "/a", model = "opus", effort = "high")

    @Test
    fun aMergeKeepsMyEditsAndTakesTheirOtherChanges() {
        val mine = base.copy(effort = "max")
        val theirs = base.copy(folder = "/b", autostart = true)
        val merged = ProfileFields.merge(base, mine, theirs)
        assertEquals(base.copy(effort = "max", folder = "/b", autostart = true), merged.fields)
        assertEquals(emptyList(), merged.conflicts)
    }

    @Test
    fun aFieldBothChangedDifferentlyIsAConflictThatKeepsMine() {
        val merged = ProfileFields.merge(base, base.copy(model = "sonnet", mcp = listOf("x")), base.copy(model = "haiku", mcp = listOf("x")))
        assertEquals("sonnet", merged.fields.model)
        assertEquals(listOf("model"), merged.conflicts, "the same change on both sides is not a conflict")
    }

    // --- live events ---

    @Test
    fun eventsAddReplaceAndDelete() {
        var all = Profiles.apply(emptyList(), event("created", listed(revision = 1, status = "stopped", restartNeeded = false)))
        assertEquals(listOf(1L), all.map { it.revision })
        all = Profiles.apply(all, event("start", listed(revision = 1, status = "running", restartNeeded = false)))
        assertEquals("running", all.single().status)
        all = Profiles.apply(all, event("deleted", """{"id":7,"name":"ipsum-admin"}"""))
        assertEquals(emptyList(), all)
    }

    /** The reply to a later edit can land before an earlier edit's event: the older revision must not win. */
    @Test
    fun anOlderRevisionNeverReplacesANewerOne() {
        val newer = Profile.from(json(listed(revision = 5)))
        val all = Profiles.apply(listOf(newer), event("updated", listed(revision = 4)))
        assertEquals(5, all.single().revision)
    }

    @Test
    fun profileEventsMoveAMemberBetweenFleets() {
        val ipsum = Fleet("ipsum-fleet", emptyList(), emptyList(), listOf(FleetMember(7, "ipsum-admin", "orchestrator", "my-mac", "running", 1), FleetMember(8, "ipsum-orchestrator", "member", "my-mac", "stopped", null)))
        var fleets = Fleets.applyProfile(listOf(ipsum), event("updated", listed(fleet = "crt", status = "stopped")))
        assertEquals(listOf("ipsum-orchestrator"), fleets.first { it.fleet == "ipsum-fleet" }.members.map { it.name }, "the old fleet keeps its other members")
        assertEquals(listOf(FleetMember(7, "ipsum-admin", "orchestrator", "my-mac.local", "stopped", null)), fleets.first { it.fleet == "crt" }.members)

        fleets = Fleets.applyProfile(fleets, event("deleted", """{"id":7,"name":"ipsum-admin"}"""))
        assertEquals(listOf("ipsum-fleet"), fleets.map { it.fleet }, "a fleet left with nothing at all is gone")
        fleets = Fleets.applyProfile(fleets, event("updated", listed(id = 8, name = "ipsum-orchestrator", fleet = null, status = "stopped")))
        assertEquals(emptyList(), fleets, "a profile given no fleet leaves its member list, and the emptied fleet goes")
    }

    // --- the API ---

    private val requests = mutableListOf<HttpRequestData>()

    private fun api(status: HttpStatusCode, body: String) = Api(
        { ServerAccess("http://server:4010", "tok") },
        HttpClient(MockEngine { request ->
            requests += request
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }),
    )

    private fun HttpRequestData.jsonBody(): JsonObject = Json.parseToJsonElement((body as TextContent).text).jsonObject

    @Test
    fun aRenameOfARunningSeatRestartsItAndSaysWhatMoved() = runTest {
        val r = api(HttpStatusCode.Accepted, """{"profile":${listed(name = "ipsum-boss", revision = 4)},"moved":{"past_seats":3,"watch_lists":0,"handoffs":1}}""")
            .renameProfile(7, 3, "ipsum-boss") as ProfileRename.Renamed
        assertEquals("http://server:4010/api/profiles/7/rename", requests.last().url.toString())
        assertEquals("ipsum-boss", requests.last().jsonBody()["name"]?.jsonPrimitive?.content)
        assertEquals("3", requests.last().jsonBody()["revision"]?.jsonPrimitive?.content)
        assertTrue(r.restarting)
        assertEquals("ipsum-boss", r.profile.name)
        assertEquals("3 past seats, 1 handoff", renameMoved(r.moved))
        assertEquals("1 handoff, 2 seats watching it", renameMoved(mapOf("handoffs" to 1, "handoff_versions" to 5, "watchers" to 2)))
        assertEquals("5 handoff versions, 2 new kinds", renameMoved(mapOf("handoff_versions" to 5, "new_kinds" to 2)))
        assertEquals("", renameMoved(emptyMap()))
        // A stopped profile: renamed at once.
        assertFalse((api(HttpStatusCode.OK, """{"profile":${listed(status = "stopped")},"moved":{}}""").renameProfile(7, 3, "x") as ProfileRename.Renamed).restarting)
    }

    @Test
    fun aRenameIsRefusedConflictedOrInvalidAsTheServerSays() = runTest {
        val taken = api(HttpStatusCode.Conflict, """{"error":"taken","code":"name_taken"}""").renameProfile(7, 3, "ipsum-orchestrator")
        assertEquals("That name is taken: a profile or a running seat already has it.", renameRefusal(taken as ProfileRename.Refused))
        val stale = api(HttpStatusCode.Conflict, """{"error":"edited","code":"stale","profile":${listed(revision = 6)}}""").renameProfile(7, 3, "x")
        assertEquals(6L, (stale as ProfileRename.Conflict).current.revision)
        val bad = api(HttpStatusCode.UnprocessableEntity, """{"errors":{"name":["must be 1-64 of A-Z a-z 0-9 . _ -"]}}""").renameProfile(7, 3, "a b")
        assertEquals(listOf("must be 1-64 of A-Z a-z 0-9 . _ -"), (bad as ProfileRename.Invalid).errors["name"])
        // Offline: thrown, as for a start.
        assertFailsWith<ApiException> { api(HttpStatusCode.GatewayTimeout, """{"error":"machine offline"}""").renameProfile(7, 3, "x") }
    }

    @Test
    fun anEditSendsTheRevisionItRead() = runTest {
        val saved = api(HttpStatusCode.OK, """{"profile":${listed(revision = 4)}}""").updateProfile(7, 3, base)
        assertEquals(4, assertIs<ProfileSave.Saved>(saved).profile.revision)
        val r = requests.single()
        assertEquals(HttpMethod.Patch, r.method)
        assertEquals("http://server:4010/api/profiles/7", r.url.toString())
        assertEquals("3", r.jsonBody()["revision"]?.jsonPrimitive?.content)
        assertNull(r.jsonBody()["name"], "the name is never sent with an edit")
    }

    @Test
    fun anEditOvertakenByAnotherIsAConflictWithTheCurrentProfile() = runTest {
        val saved = api(HttpStatusCode.Conflict, """{"error":"edited since you read it","profile":${listed(revision = 6)}}""").updateProfile(7, 3, base)
        assertEquals(6, assertIs<ProfileSave.Conflict>(saved).current.revision)
    }

    @Test
    fun aRefusedCreateNamesItsFields() = runTest {
        val saved = api(HttpStatusCode.UnprocessableEntity, """{"errors":{"name":["a profile with this name exists"],"folder":["must be an absolute path"]}}""")
            .createProfile("ipsum-admin", base)
        assertEquals(
            mapOf("name" to listOf("a profile with this name exists"), "folder" to listOf("must be an absolute path")),
            assertIs<ProfileSave.Invalid>(saved).errors,
        )
        assertEquals("ipsum-admin", requests.single().jsonBody()["name"]?.jsonPrimitive?.content)
    }

    /** A start the machine refuses is a failure the caller shows, with the agent's code and text. */
    @Test
    fun aRefusedStartCarriesTheMachinesCode() = runTest {
        val e = assertFailsWith<ApiException> {
            api(HttpStatusCode.Forbidden, """{"error":"outside_roots: /etc is not in an allowed folder","code":"outside_roots"}""").startProfile(7)
        }
        assertEquals(403, e.httpStatus)
        assertEquals("outside_roots", e.code)
        assertEquals("HTTP 403: outside_roots: /etc is not in an allowed folder", e.message)
    }

    @Test
    fun aDeleteTakesTheEmptyNoContentReply() = runTest {
        api(HttpStatusCode.NoContent, "").deleteProfile(7)
        assertEquals(HttpMethod.Delete, requests.single().method)
    }

    @Test
    fun hostsCarryTheirProfileMenus() {
        val h = Host.from(json("""{"name":"my-mac","online":true,"modes":["default","auto"],"runtimes":[{"name":"stock+mod","version":"2.1.289"},{"name":"patched"}],"mcp":["claudiverse"]}"""))
        assertEquals(listOf("default", "auto"), h.modes)
        assertEquals(listOf(HostRuntime("stock+mod", "2.1.289"), HostRuntime("patched", null)), h.runtimes)
        assertEquals(listOf("claudiverse"), h.mcp)
    }
}
