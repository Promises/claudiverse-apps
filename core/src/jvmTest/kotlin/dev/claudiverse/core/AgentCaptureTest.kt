package dev.claudiverse.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A replay recorded from a real seat with a fork and an Explore subagent
 * (mod 0.9.0), run through the feed. The recording is not kept in the repo:
 * copy it to core/build/agent-capture.json to run this; without it the test
 * does nothing.
 */
class AgentCaptureTest {
    @Test
    fun aRecordedForkAndSubagentEachGetTheirOwnThread() {
        val file = File("build/agent-capture.json")
        if (!file.exists()) return
        val feed = SeatFeed()
        feed.onEvent("replay", Json.parseToJsonElement(file.readText()).jsonObject)

        println("agents: " + feed.agents.map { "${it.type}/${it.label}/${it.status}/tool=${it.toolUseId}" })
        feed.agents.forEach { a ->
            println("thread ${a.label}: " + feed.agentTranscript(a.id).items.map { it::class.simpleName + ":" + it.toString().take(80) })
        }
        println("main: " + feed.transcript().items.map { it::class.simpleName + ":" + it.toString().take(60) })

        assertEquals(listOf("fork", "Explore"), feed.agents.map { it.type })
        assertTrue(feed.agents.all { it.status == "completed" && it.toolUseId != null })
        assertTrue(feed.agents.all { feed.agentTranscript(it.id).items.isNotEmpty() })
        // An agent's prompt, and a fork's set-up, are not shown as the operator's.
        assertTrue(feed.agents.none { a -> feed.agentTranscript(a.id).items.any { it is TranscriptItem.UserText } })
        assertEquals("fork started", feed.agentTranscript(feed.agents[0].id).items.filterIsInstance<TranscriptItem.Event>().first().label)
        // Nothing an agent wrote is in main.
        val agentTexts = feed.agents.flatMap { a -> feed.agentTranscript(a.id).items.filterIsInstance<TranscriptItem.AssistantText>().map { it.text } }
        val mainTexts = feed.transcript().items.filterIsInstance<TranscriptItem.AssistantText>().map { it.text }
        assertTrue(agentTexts.none { it in mainTexts }, "an agent's text is in main")
    }
}
