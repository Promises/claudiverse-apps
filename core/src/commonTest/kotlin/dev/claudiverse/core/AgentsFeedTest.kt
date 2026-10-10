package dev.claudiverse.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A seat's agents (forks, subagents, teammates) as the mod mirrors them
 * (format v1): each in a thread of its own, never in main's transcript.
 */
class AgentsFeedTest {

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun row(uuid: String, text: String) =
        """{"type":"assistant","uuid":"$uuid","message":{"content":[{"type":"text","text":"$text"}]}}"""

    private val started = json(
        """{"type":"agent_started","agent_id":"a1","agent_type":"fork","description":"Screenshotting the viewer page",
           "fork":true,"background":false}""",
    )

    private fun texts(t: Transcript) = t.items.filterIsInstance<TranscriptItem.AssistantText>().map { it.text }

    @Test
    fun aForksRowsAreItsOwnThreadNotMains() {
        val feed = SeatFeed()
        feed.onEvent("message", json(row("m1", "main says")))
        assertEquals(SeatFeed.Change.TRANSCRIPT, feed.onEvent("message", started))
        feed.onEvent("message", json("""{"type":"agent_message","agent_id":"a1","message":${row("f1", "fork says")}}"""))
        // The same row twice (replay racing a broadcast) shows once.
        feed.onEvent("message", json("""{"type":"agent_message","agent_id":"a1","message":${row("f1", "fork says")}}"""))

        assertEquals(listOf("main says"), texts(feed.transcript()))
        assertEquals(listOf("fork says"), texts(feed.agentTranscript("a1")))
        val a = feed.agents.single()
        assertEquals("Screenshotting the viewer page", a.label)
        assertTrue(a.fork && a.isBusy)
    }

    @Test
    fun aTurnEndIdlesAnAgentAndAnEndIsFinal() {
        val feed = SeatFeed()
        feed.onEvent("message", started)
        feed.onEvent("message", json("""{"type":"agent_turn_complete","agent_id":"a1"}"""))
        assertEquals("idle", feed.agents.single().status)
        feed.onEvent("message", json("""{"type":"agent_ended","agent_id":"a1","status":"killed"}"""))
        feed.onEvent("message", json("""{"type":"agent_turn_complete","agent_id":"a1"}"""))
        assertEquals("killed", feed.agents.single().status)
        assertTrue(feed.agents.single().hasEnded)
    }

    /** The snapshot is the seat's own list; the tool call that spawned a subagent survives it. */
    @Test
    fun aSnapshotReplacesTheListAndKeepsTheSpawningToolCall() {
        val feed = SeatFeed()
        feed.onEvent("message", json("""{"type":"agent_started","agent_id":"s1","agent_type":"Explore","description":"Find the parser","fork":false,"background":false,"tool_use_id":"toolu_9"}"""))
        feed.onEvent("message", json("""{"type":"agents","agents":[
            {"agent_id":"s1","agent_type":"Explore","description":"Find the parser","name":null,"fork":false,"background":false,"status":"completed"},
            {"agent_id":"t1","agent_type":"general-purpose","description":"","name":"reviewer","fork":false,"background":true,"status":"waiting"}]}"""))
        assertEquals(listOf("s1", "t1"), feed.agents.map { it.id })
        assertEquals("toolu_9", feed.agents[0].toolUseId)
        assertEquals("completed", feed.agents[0].status)
        assertEquals("reviewer", feed.agents[1].label)
        assertTrue(!feed.agents[1].isBusy && !feed.agents[1].hasEnded)
    }

    /** A replay carries agents' rows (in its messages or its own list) and the snapshot naming them. */
    @Test
    fun aReplayRestoresAgentsAndTheirThreads() {
        val feed = SeatFeed()
        feed.onEvent("message", json("""{"type":"agent_started","agent_id":"old","agent_type":"fork","description":"gone","fork":true,"background":false}"""))
        feed.onEvent("replay", json("""{"messages":[${row("m1", "main")},{"type":"agent_message","agent_id":"a1","message":${row("f1", "in messages")}}],
            "agent_messages":[{"type":"agent_message","agent_id":"a1","message":${row("f2", "in its own list")}}],
            "agents":[{"agent_id":"a1","agent_type":"fork","description":"Screenshotting","name":null,"fork":true,"background":false,"status":"running"}],
            "busy_agents":1}"""))
        assertEquals(listOf("main"), texts(feed.transcript()))
        assertEquals(listOf("in messages", "in its own list"), texts(feed.agentTranscript("a1")))
        assertEquals(listOf("a1"), feed.agents.map { it.id })
    }

    /** In an agent's thread a user row is its prompt, not the operator's; main's stay the operator's. */
    @Test
    fun anAgentsPromptIsAnEventNotTheOperators() {
        val feed = SeatFeed()
        val prompt = """{"type":"user","uuid":"p1","message":{"role":"user","content":"Count the files"}}"""
        feed.onEvent("message", json("""{"type":"agent_message","agent_id":"a1","message":$prompt}"""))
        val e = feed.agentTranscript("a1").items.single() as TranscriptItem.Event
        assertEquals("prompt", e.label)
        assertEquals("Count the files", e.summary)
        feed.onEvent("message", json("""{"type":"user","uuid":"m1","message":{"role":"user","content":"hi"}}"""))
        assertTrue(feed.transcript().items.single() is TranscriptItem.UserText)
    }

    /** An agent's permission request shows in its thread; main's stays in main. */
    @Test
    fun anAgentsPermissionRequestIsInItsThread() {
        val feed = SeatFeed()
        feed.onEvent("message", started)
        feed.onEvent("permission_request", json("""{"request_id":"r1","tool_name":"Bash","agent_id":"a1","input":{"command":"ls"}}"""))
        feed.onEvent("permission_request", json("""{"request_id":"r2","tool_name":"Edit","input":{}}"""))
        assertEquals(listOf("r2"), feed.transcript().items.filterIsInstance<TranscriptItem.Permission>().map { it.requestId })
        assertEquals(listOf("r1"), feed.agentTranscript("a1").items.filterIsInstance<TranscriptItem.Permission>().map { it.requestId })
    }

    /** A row from an agent whose start scrolled out is still shown, under an unnamed agent, until a snapshot names it. */
    @Test
    fun aRowFromAnUnannouncedAgentIsListed() {
        val feed = SeatFeed()
        feed.onEvent("message", json("""{"type":"agent_message","agent_id":"x","message":${row("f1", "hi")}}"""))
        assertEquals("agent", feed.agents.single().label)
        assertNull(feed.agents.single().toolUseId)
    }
}
