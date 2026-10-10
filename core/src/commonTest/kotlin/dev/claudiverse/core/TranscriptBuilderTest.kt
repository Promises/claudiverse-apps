package dev.claudiverse.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptBuilderTest {

    private fun build(vararg json: String) =
        TranscriptBuilder.build(json.map { Json.parseToJsonElement(it).jsonObject }, emptySet())

    /** The row the operator saw as a user bubble: message 27480238, verbatim shape. */
    @Test
    fun metaNoteWithStringContentIsAToolResultNotAUserBubble() {
        val items = build(
            """{"type":"user","isMeta":true,"turnCompanion":true,"message":{"role":"user",
               "content":"[Image: original 1080x2410, displayed at 896x2000. Multiply coordinates by 1.21 to map to original image.]"}}""",
        )
        assertEquals(1, items.size)
        val item = items[0]
        assertTrue(item is TranscriptItem.Event, "expected a collapsed tool result, got $item")
        item as TranscriptItem.Event
        assertEquals("Tool result", item.label)
        assertTrue(item.text.startsWith("[Image: original 1080x2410"))
    }

    @Test
    fun metaNoteWithTextBlocksIsAToolResult() {
        val items = build(
            """{"type":"user","isMeta":true,"message":{"role":"user",
               "content":[{"type":"text","text":"first line\nsecond line"}]}}""",
        )
        val item = items.single() as TranscriptItem.Event
        assertEquals("first line", item.summary)
        assertEquals("first line\nsecond line", item.text)
    }

    /** A terminal paste, as Claude Code records it (this seat, 2026-10-05): text with a placeholder, then the image. */
    @Test
    fun aPastedImageShowsInItsMessagesBubbleInPlaceOfItsPlaceholder() {
        val item = build(
            """{"type":"user","uuid":"u1","imagePasteIds":[3],"message":{"role":"user","content":[
               {"type":"text","text":"[Image #3] here it is with text aswell"},
               {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}}]}}""",
        ).single() as TranscriptItem.UserText
        assertEquals("here it is with text aswell", item.text)
        assertEquals(listOf(TranscriptImage("image/png", base64 = "iVBORw0KGgo=")), item.images)
    }

    /** The server moves a pasted image into its file store and sends a reference in its place (Failover, 2026-10-05). */
    @Test
    fun anImageTheServerStoredIsAReferenceToItsFile() {
        val item = build(
            """{"type":"user","uuid":"u4","message":{"role":"user","content":[{"type":"text","text":"[Image #6] look"},
               {"type":"image","source":{"type":"url","url":"/api/files/Ab3_x","media_type":"image/png"},"size":151634}]}}""",
        ).single() as TranscriptItem.UserText
        assertEquals("look", item.text)
        assertEquals(listOf(TranscriptImage("image/png", url = "/api/files/Ab3_x", size = 151634)), item.images)
    }

    @Test
    fun anImageAloneIsStillABubbleAndTextAloneKeepsItsBrackets() {
        val image = build(
            """{"type":"user","uuid":"u2","message":{"role":"user","content":[{"type":"text","text":"[Image #4]"},
               {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"/9j/"}}]}}""",
        ).single() as TranscriptItem.UserText
        assertEquals("", image.text)
        assertEquals("image/jpeg", image.images.single().mediaType)

        // No image came with it, so "[Image #5]" is what the operator typed, and stays.
        val text = build("""{"type":"user","uuid":"u3","message":{"role":"user","content":[{"type":"text","text":"see [Image #5]"}]}}""").single() as TranscriptItem.UserText
        assertEquals("see [Image #5]", text.text)
        assertEquals(emptyList(), text.images)
    }

    /** A permission request with no recorded answer is answered once its tool call has a result or its turn ended. */
    @Test
    fun aPermissionIsAnsweredOnceItsToolRanOrItsTurnEnded() {
        fun ask(id: String, tool: String?) =
            """{"type":"control_request","request_id":"$id","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{}${tool?.let { ",\"tool_use_id\":\"$it\"" } ?: ""}}}"""
        val call = """{"type":"assistant","uuid":"a1","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}"""
        val ran = """{"type":"user","uuid":"u1","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}"""
        fun pending(vararg rows: String) = build(*rows).filterIsInstance<TranscriptItem.Permission>().map { it.pending }

        assertEquals(listOf(true), pending(call, ask("r1", "t1")))
        assertEquals(listOf(false), pending(call, ask("r1", "t1"), ran))
        assertEquals(listOf(false), pending(ask("r2", null), """{"type":"result","uuid":"e1"}"""))
        assertEquals(listOf(false), pending(ask("r5", null), """{"type":"turn_complete"}"""))
        // A request after the turn's end is a new turn's, still waiting.
        assertEquals(listOf(false, true), pending(ask("r3", null), """{"type":"result","uuid":"e2"}""", ask("r4", null)))
    }

    /** Copy takes a message's text as written: a reply's Markdown, a tool call's input and result. */
    @Test
    fun copyTakesTheTextAsWritten() {
        assertEquals("**bold** and `code`", clipboardText(TranscriptItem.AssistantText("k", "**bold** and `code`")))
        assertEquals("Bash: ls\n\n{\"command\":\"ls\"}\n\na\nb", clipboardText(TranscriptItem.ToolUse("k", "t", "Bash", "ls", "{\"command\":\"ls\"}", "a\nb", false)))
        assertEquals("Bash: ls\n\n{}", clipboardText(TranscriptItem.ToolUse("k", "t", "Bash", "ls", "{}", null, false)))
        assertEquals(null, clipboardText(TranscriptItem.UserText("k", " ", injected = false)))
    }

    /** The conversation as one text: each message under who it is from. */
    @Test
    fun theConversationAsTextNamesWhoSaidWhat() {
        val items = listOf(
            TranscriptItem.UserText("u", "hi", injected = false),
            TranscriptItem.AssistantText("a", "hello\nthere"),
            TranscriptItem.TurnEnd("e", "Turn complete · 2.0 s"),
            TranscriptItem.UserText("blank", " ", injected = false),
        )
        assertEquals("You:\nhi\n\nClaude:\nhello\nthere\n\nTurn complete · 2.0 s", conversationText(items))
    }

    /** A tool result with pictures (a Read of a PNG): its images, stored or inline, and its text without placeholders. */
    @Test
    fun aToolResultsImagesAreImagesNotText() {
        val items = build(
            """{"type":"assistant","uuid":"a1","message":{"content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/x/shot.png"}}]}}""",
            """{"type":"user","uuid":"u1","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":[
                {"type":"text","text":"Read the image"},
                {"type":"image","source":{"type":"url","url":"/api/files/abc","media_type":"image/png"},"size":28000},
                {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"/9j/4AAQ"}},
                {"type":"image","source":{"type":"url","url":""}}]}]}}""",
        )
        val t = items.single() as TranscriptItem.ToolUse
        assertEquals(listOf(TranscriptImage("image/png", url = "/api/files/abc", size = 28000), TranscriptImage("image/jpeg", base64 = "/9j/4AAQ")), t.images)
        // The one with nothing to show stays a placeholder.
        assertEquals("Read the image\n[image]", t.result)
        // A text-only result is unchanged.
        val plain = build(
            """{"type":"assistant","uuid":"a2","message":{"content":[{"type":"tool_use","id":"t2","name":"Bash","input":{"command":"ls"}}]}}""",
            """{"type":"user","uuid":"u2","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"a\nb"}]}}""",
        ).single() as TranscriptItem.ToolUse
        assertEquals("a\nb", plain.result)
        assertTrue(plain.images.isEmpty())
    }

    /** Stock 2.1.29x records a background task finishing as a user turn of its own making: an event, not a bubble. */
    @Test
    fun aTaskNotificationIsAnEventNotTheOperator() {
        val text = "<task-notification>\n<task-id>b07m19lpx</task-id>\n<status>completed</status>\n<summary>Background command \"Wait 3 seconds\" completed (exit code 0)</summary>\n</task-notification>"
        val json = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))
        val withOrigin = build("""{"type":"user","uuid":"t1","promptSource":"system","origin":{"kind":"task-notification"},"message":{"role":"user","content":$json}}""").single()
        val e = withOrigin as TranscriptItem.Event
        assertEquals("task-notification", e.label)
        assertEquals("Background command \"Wait 3 seconds\" completed (exit code 0)", e.summary)
        // A mirror that kept only the text is told by the text.
        assertTrue(build("""{"type":"user","message":{"content":$json}}""").single() is TranscriptItem.Event)
        // The operator typing about it is still the operator.
        assertTrue(build("""{"type":"user","uuid":"t2","message":{"role":"user","content":"what did the <task-notification> say?"}}""").single() is TranscriptItem.UserText)
    }

    /** The control: without isMeta the same row is the user's own message. */
    @Test
    fun plainUserMessageStaysAUserBubble() {
        val items = build("""{"type":"user","message":{"role":"user","content":"hello seat"}}""")
        val item = items.single()
        assertTrue(item is TranscriptItem.UserText, "expected a user bubble, got $item")
        assertEquals("hello seat", (item as TranscriptItem.UserText).text)
    }

    /** Inbox prompts on semi seats carry proxy_source inside message and stay user bubbles. */
    @Test
    fun inboxPromptStaysAUserBubble() {
        val items = build("""{"type":"user","message":{"role":"user","content":"queued prompt","proxy_source":"inbox"}}""")
        assertTrue(items.single() is TranscriptItem.UserText)
    }

    @Test
    fun repeatedKeysAreMadeUnique() {
        val same = """{"type":"assistant","uuid":"u1","message":{"content":[{"type":"text","text":"a"}]}}"""
        val keys = TranscriptBuilder.uniqueKeys(build(same, same)).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
