package dev.claudiverse.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One question of an AskUserQuestion call. */
data class QuestionSpec(
    val header: String?,
    val question: String,
    val options: List<Pair<String, String?>>,
    val multiSelect: Boolean,
)

/**
 * The answer to an AskUserQuestion from the options picked, [picked] holding
 * each question's choices in the order they were picked: one line per
 * question (`header: label`, or just the label for a single question);
 * multi-select joins with ", ". Questions with nothing picked are left out.
 */
fun composeAnswer(questions: List<QuestionSpec>, picked: List<List<String>>): String =
    questions.mapIndexedNotNull { i, q ->
        val chosen = picked.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
        val answer = chosen.joinToString(", ")
        if (questions.size > 1 && q.header != null) "${q.header}: $answer" else answer
    }.joinToString("\n")

/**
 * The same answer as [composeAnswer], structured: each question's text to its
 * picked labels (", " between several), as AskUserQuestion's own answers are.
 * The server passes it to the seat as is, so it needs no header lines to tell
 * questions apart (headers can be missing or repeated). Questions with
 * nothing picked are left out.
 */
fun answerMap(questions: List<QuestionSpec>, picked: List<List<String>>): Map<String, String> =
    questions.mapIndexedNotNull { i, q ->
        picked.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { q.question to it.joinToString(", ") }
    }.toMap()

/**
 * An image in a message, of [mediaType] (image/png, image/jpeg, …): either a
 * file in the server's store at [url] (server-relative, "/api/files/<id>",
 * fetched with the client's token; the server moves pasted images there), or
 * inline [base64] data as Claude Code records it. [size] in bytes when the
 * server gives it.
 */
data class TranscriptImage(val mediaType: String, val base64: String? = null, val url: String? = null, val size: Long? = null)

/** What the transcript shows, built from the seat's mirrored messages. */
sealed interface TranscriptItem {
    val key: String

    /** [images]: pictures the operator pasted or attached in the terminal, in the order sent. */
    data class UserText(override val key: String, val text: String, val injected: Boolean, val images: List<TranscriptImage> = emptyList()) : TranscriptItem
    data class AssistantText(override val key: String, val text: String) : TranscriptItem
    data class Thinking(override val key: String, val text: String) : TranscriptItem
    data class ToolUse(
        override val key: String,
        val toolUseId: String,
        val name: String,
        val summary: String,
        val input: String,
        val result: String?,
        val resultIsError: Boolean,
        /** Images the result carries (a Read of a picture, a screenshot), in order; its text parts stay in [result]. */
        val images: List<TranscriptImage> = emptyList(),
    ) : TranscriptItem
    data class Question(
        override val key: String,
        val toolUseId: String,
        val questions: List<QuestionSpec>,
        val answer: String?,
    ) : TranscriptItem
    data class Permission(
        override val key: String,
        val requestId: String,
        val toolName: String,
        val detail: String?,
        val input: String,
        val pending: Boolean,
    ) : TranscriptItem
    /** Something the harness queued into the seat (a task notification…), shown collapsed. */
    data class Event(override val key: String, val label: String, val summary: String, val text: String) : TranscriptItem
    data class TurnEnd(override val key: String, val text: String) : TranscriptItem
    data class Note(override val key: String, val text: String) : TranscriptItem
}

/**
 * What Copy puts on the clipboard for one transcript item: its text as
 * written (a reply's Markdown source, a tool call's input and result, a
 * question and its answer). Null for an item with nothing to copy.
 */
fun clipboardText(item: TranscriptItem): String? = when (item) {
    is TranscriptItem.UserText -> item.text
    is TranscriptItem.AssistantText -> item.text
    is TranscriptItem.Thinking -> item.text
    is TranscriptItem.ToolUse -> listOfNotNull("${item.name}: ${item.summary}", item.input, item.result).joinToString("\n\n")
    is TranscriptItem.Question -> (item.questions.map { it.question } + listOfNotNull(item.answer)).joinToString("\n")
    is TranscriptItem.Permission -> listOfNotNull(item.toolName, item.detail, item.input).joinToString("\n")
    is TranscriptItem.Event -> item.text
    is TranscriptItem.TurnEnd -> item.text
    is TranscriptItem.Note -> item.text
}?.takeIf { it.isNotBlank() }

/**
 * A transcript as plain text, each message under who it is from, for reading
 * and selecting across messages as one document.
 */
fun conversationText(items: List<TranscriptItem>): String = items.mapNotNull { item ->
    val text = clipboardText(item) ?: return@mapNotNull null
    val who = when (item) {
        is TranscriptItem.UserText -> if (item.injected) "Injected" else "You"
        is TranscriptItem.AssistantText -> "Claude"
        is TranscriptItem.Thinking -> "Thinking"
        is TranscriptItem.ToolUse -> "Tool"
        is TranscriptItem.Question -> "Question"
        is TranscriptItem.Permission -> "Permission"
        is TranscriptItem.Event -> item.label.ifBlank { "Event" }
        is TranscriptItem.TurnEnd, is TranscriptItem.Note -> null
    }
    if (who == null) text else "$who:\n$text"
}.joinToString("\n\n")

/**
 * Turns the raw message stream into [TranscriptItem]s.
 *
 * Kept as a pure function of the messages so a replay and a live stream build
 * the same transcript: tool results attach to their tool_use by id, a question
 * counts as answered once its tool_result exists, a permission request is
 * pending until resolved.
 */
object TranscriptBuilder {

    /** High-volume message types with nothing to show. */
    private val NOISE = setOf("stream_event", "progress", "set_in_progress_tool_use_ids", "stream_request_start")

    fun isNoise(message: JsonObject): Boolean = message.text("type") in NOISE

    /**
     * The key a message's items are built under: its uuid, else its position in
     * the list built from (a stored operator prompt has no uuid). Everything
     * that finds a message's items by key goes through here.
     */
    fun messageKey(message: JsonObject, index: Int): String = message.str("uuid") ?: "m$index"

    fun build(messages: List<JsonObject>, resolvedPermissions: Set<String>): List<TranscriptItem> {
        val results = HashMap<String, ToolResult>()
        val answeredPermissions = HashSet(resolvedPermissions)
        // A request the seat recorded no answer to was still answered once its tool call has a result, or its
        // turn ended (the seat cannot finish a turn while it waits): one answered in the terminal, say.
        val asked = ArrayList<String>()
        val askedFor = HashMap<String, String>()
        for (m in messages) {
            when (m.text("type")) {
                "user" -> contentBlocks(m).filter { it.text("type") == "tool_result" }.forEach {
                    results[it.text("tool_use_id")] = ToolResult(toolResultText(it), it.flag("is_error"), toolResultImages(it))
                }
                "control_response" -> m.obj("response")?.str("request_id")?.let(answeredPermissions::add)
                "control_request" -> {
                    val id = m.text("request_id")
                    asked += id
                    m.obj("request")?.str("tool_use_id")?.let { askedFor[id] = it }
                }
                // A turn's end: "result" from the patched runtime, "turn_complete" from the mod.
                "result", "turn_complete" -> { answeredPermissions += asked; asked.clear() }
            }
        }
        askedFor.forEach { (id, toolUse) -> if (toolUse in results) answeredPermissions += id }

        val items = ArrayList<TranscriptItem>()
        messages.forEachIndexed { index, m ->
            val key = messageKey(m, index)
            when (m.text("type")) {
                "assistant" -> contentBlocks(m).forEachIndexed { b, block ->
                    val k = "$key.$b"
                    when (block.text("type")) {
                        "text" -> block.text("text").takeIf { it.isNotBlank() }
                            ?.let { items += TranscriptItem.AssistantText(k, it) }
                        "thinking" -> block.text("thinking").takeIf { it.isNotBlank() }
                            ?.let { items += TranscriptItem.Thinking(k, it) }
                        "tool_use" -> items += toolUse(k, block, results)
                    }
                }
                "user" -> {
                    val content = (m.obj("message")?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val system = systemPrompt(m, content)
                    val local = localCommand(key, content ?: contentBlocks(m).filter { it.text("type") == "text" }.joinToString("\n") { it.text("text") })
                    if (local != null) {
                        // A slash command run at the seat (/exit, /model …): what was typed, and its output.
                        local.item?.let(items::add)
                    } else if (system != null) {
                        // Claude Code's own turn (a background task finishing, say): an event, not the operator.
                        val text = content ?: contentBlocks(m).filter { it.text("type") == "text" }.joinToString("\n") { it.text("text") }
                        if (text.isNotBlank()) items += TranscriptItem.Event(key, system, eventSummary(text), text)
                    } else if (m.flag("isMeta")) {
                        // Claude Code's own note on a user turn (e.g. the "[Image: original …]"
                        // line after it reads an image): nobody typed it, so it is
                        // never a user bubble. Content is a string or text blocks.
                        val text = content
                            ?: contentBlocks(m).filter { it.text("type") == "text" }.joinToString("\n") { it.text("text") }
                        if (text.isNotBlank()) items += TranscriptItem.Event(key, "Tool result", text.lineSequence().first(), text)
                    } else if (content != null) {
                        if (content.isNotBlank()) items += TranscriptItem.UserText(key, content, injected = false)
                    } else userBlocks(key, contentBlocks(m))?.let(items::add)
                }
                "attachment" -> m.obj("attachment")?.let { a -> attachment(key, a)?.let(items::add) }
                "control_request" -> m.obj("request")?.takeIf { it.text("subtype") == "can_use_tool" }?.let { r ->
                    val id = m.text("request_id")
                    items += TranscriptItem.Permission(
                        key, id, r.text("tool_name"), r.str("description") ?: r.str("title"),
                        r.obj("input")?.pretty().orEmpty(), pending = id !in answeredPermissions,
                    )
                }
                "result" -> items += TranscriptItem.TurnEnd(key, resultLine(m))
                "compact" -> items += TranscriptItem.Note(key, "Context compacted")
                "system" -> m.str("subtype")?.takeIf { it == "compact_boundary" }
                    ?.let { items += TranscriptItem.Note(key, "Context compacted") }
            }
        }
        return items
    }

/** A local-command message; [item] is null when it shows nothing (Claude Code's caveat, empty output). */
    private class LocalCommand(val item: TranscriptItem?)

    private val CAVEAT = Regex("""^\s*<local-command-caveat>""")
    private val COMMAND_START = Regex("""^\s*<command-(name|message)>""")
    private val COMMAND_NAME = Regex("""<command-name>(.*?)</command-name>""", RegexOption.DOT_MATCHES_ALL)
    private val COMMAND_ARGS = Regex("""<command-args>(.*?)</command-args>""", RegexOption.DOT_MATCHES_ALL)
    private val COMMAND_OUTPUT = Regex("""^\s*<local-command-(stdout|stderr)>(.*?)</local-command-\1>\s*$""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Claude Code's records of a slash command run at the seat: the caveat it
     * writes for the model (dropped), the command as typed (shown as the
     * operator's "/exit args"), and its output (an event, dropped when empty).
     * Null for any other message.
     */
    private fun localCommand(key: String, text: String): LocalCommand? {
        if (CAVEAT.containsMatchIn(text)) return LocalCommand(null)
        // Only Claude Code's own record, which starts with the tags; a message merely quoting them is the operator's.
        if (COMMAND_START.containsMatchIn(text)) COMMAND_NAME.find(text)?.let { name ->
            val args = COMMAND_ARGS.find(text)?.groupValues?.get(1)?.trim().orEmpty()
            val typed = (name.groupValues[1].trim() + if (args.isNotEmpty()) " $args" else "").trim()
            return LocalCommand(typed.takeIf { it.isNotEmpty() }?.let { TranscriptItem.UserText(key, it, injected = false) })
        }
        COMMAND_OUTPUT.find(text)?.let { out ->
            val body = out.groupValues[2].trim()
            val label = if (out.groupValues[1] == "stderr") "Command error" else "Command output"
            return LocalCommand(body.takeIf { it.isNotEmpty() }?.let { TranscriptItem.Event(key, label, eventSummary(it), it) })
        }
        return null
    }

    private val IMAGE_PLACEHOLDER = Regex("""\[Image #\d+]""")

    /**
     * A typed message in blocks: its text, and any images pasted with it (a
     * terminal paste is text with an "[Image #3]" placeholder, then the image).
     * One bubble for both; the placeholders go once the images themselves
     * show. Null when there is neither text nor an image.
     */
    private fun userBlocks(key: String, blocks: List<JsonObject>): TranscriptItem.UserText? {
        val images = blocks.filter { it.text("type") == "image" }.mapNotNull(::image)
        var text = blocks.filter { it.text("type") == "text" }.joinToString("\n") { it.text("text") }
        if (images.isNotEmpty()) text = text.replace(IMAGE_PLACEHOLDER, "").trim()
        if (text.isBlank() && images.isEmpty()) return null
        return TranscriptItem.UserText(key, text, injected = false, images = images)
    }

    /**
     * A queued command is either text injected into the seat (cv_send, the
     * composer) or a harness event such as a background task finishing. Other
     * attachments (environment, token reminders) are context, not conversation.
     */
    private fun attachment(key: String, a: JsonObject): TranscriptItem? {
        if (a.text("type") != "queued_command") return null
        val prompt = a.str("prompt")?.takeIf { it.isNotBlank() } ?: return null
        return when (val mode = a.str("commandMode")) {
            null, "prompt" -> TranscriptItem.UserText(key, prompt, injected = true)
            else -> TranscriptItem.Event(key, mode, eventSummary(prompt), prompt)
        }
    }

    /**
     * The kind of a user turn Claude Code made itself rather than the operator
     * (stock 2.1.29x: promptSource "system", origin.kind "task-notification"),
     * or null. A turn whose text is a <task-notification> counts too: a
     * mirror that sends only the text loses the origin.
     */
    private fun systemPrompt(m: JsonObject, content: String?): String? {
        val kind = m.obj("origin")?.str("kind")
        if (m.str("promptSource") == "system") return kind ?: "system"
        val text = content ?: contentBlocks(m).firstOrNull { it.text("type") == "text" }?.text("text")
        return if (text?.trimStart()?.startsWith("<task-notification>") == true) kind ?: "task-notification" else null
    }

    /** `<summary>` when the event carries one, else its first non-tag line. */
    /**
     * An agent's thread as it reads: a user row there is the agent's prompt
     * (or, for a fork, Claude Code's fork set-up), not the operator's, so it
     * is an event, not an operator bubble. Images stay with a bubble.
     */
    fun agentItems(items: List<TranscriptItem>): List<TranscriptItem> = items.map { item ->
        if (item !is TranscriptItem.UserText || item.images.isNotEmpty()) return@map item
        val fork = item.text.trimStart().startsWith("<fork-boilerplate>")
        TranscriptItem.Event(item.key, if (fork) "fork started" else "prompt", if (fork) "Forked from main's conversation" else eventSummary(item.text), item.text)
    }

    private fun eventSummary(text: String): String =
        Regex("<summary>([\\s\\S]*?)</summary>").find(text)?.groupValues?.get(1)?.trim()
            ?: text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("<") }
            ?: text.lineSequence().first()

    /**
     * List UIs need unique ids (LazyColumn throws on a repeated key), so no
     * input may produce one: a collision (two blocks sharing a uuid, say) gets
     * a suffix instead.
     */
    fun uniqueKeys(items: List<TranscriptItem>): List<TranscriptItem> {
        val seen = HashSet<String>()
        return items.map { item ->
            if (seen.add(item.key)) item
            else {
                var n = 1
                while (!seen.add("${item.key}#$n")) n++
                item.withKey("${item.key}#$n")
            }
        }
    }

    private fun TranscriptItem.withKey(k: String): TranscriptItem = when (this) {
        is TranscriptItem.UserText -> copy(key = k)
        is TranscriptItem.AssistantText -> copy(key = k)
        is TranscriptItem.Thinking -> copy(key = k)
        is TranscriptItem.ToolUse -> copy(key = k)
        is TranscriptItem.Question -> copy(key = k)
        is TranscriptItem.Permission -> copy(key = k)
        is TranscriptItem.Event -> copy(key = k)
        is TranscriptItem.TurnEnd -> copy(key = k)
        is TranscriptItem.Note -> copy(key = k)
    }

    /** A live `permission_request` event, shown until `permission_resolved` arrives. */
    fun permissionFromEvent(payload: JsonObject, pending: Boolean) = TranscriptItem.Permission(
        key = "perm." + payload.text("request_id"),
        requestId = payload.text("request_id"),
        toolName = payload.str("display_name") ?: payload.text("tool_name"),
        detail = payload.str("description") ?: payload.str("title"),
        input = payload.obj("input")?.pretty().orEmpty(),
        pending = pending,
    )

    /**
     * An image block: a file in the server's store (it moves pasted and tool
     * images there) or inline base64 when the store was unavailable.
     */
    private fun image(b: JsonObject): TranscriptImage? {
        val source = b.obj("source") ?: return null
        val type = source.str("media_type") ?: "image/png"
        return when (source.text("type")) {
            "url" -> source.str("url")?.takeIf { it.isNotEmpty() }?.let { TranscriptImage(type, url = it, size = b.long("size")) }
            "base64" -> source.str("data")?.takeIf { it.isNotEmpty() }?.let { TranscriptImage(type, base64 = it) }
            else -> null
        }
    }

    private class ToolResult(val text: String, val isError: Boolean, val images: List<TranscriptImage>)

    private fun toolUse(key: String, block: JsonObject, results: Map<String, ToolResult>): TranscriptItem {
        val id = block.text("id")
        val name = block.text("name")
        val input = block.obj("input") ?: JsonObject(emptyMap())
        val result = results[id]
        if (name == "AskUserQuestion") {
            val questions = input.array("questions").objects().map { q ->
                QuestionSpec(
                    header = q.str("header"),
                    question = q.text("question"),
                    options = q.array("options").objects().map { it.text("label") to it.str("description") },
                    multiSelect = q.flag("multiSelect"),
                )
            }
            return TranscriptItem.Question(key, id, questions, result?.text)
        }
        return TranscriptItem.ToolUse(key, id, name, summarise(name, input), input.pretty(), result?.text, result?.isError ?: false, result?.images.orEmpty())
    }

    /** The one input field that says what a tool call does, for the collapsed row. */
    private fun summarise(name: String, input: JsonObject): String {
        val field = listOf("command", "file_path", "pattern", "path", "url", "query", "description", "prompt", "content")
            .firstNotNullOfOrNull { input.str(it)?.takeIf { v -> v.isNotBlank() } }
        return (field ?: input.keys.firstOrNull()?.let { "$it: ${input.str(it)}" } ?: name)
            .lineSequence().first().take(200)
    }

    private fun toolResultText(block: JsonObject): String = when (val c = block["content"]) {
        is JsonPrimitive -> if (c.isString) c.content else ""
        // An image that can be shown is one of [toolResultImages], not text; one that can't stays "[image]".
        is JsonArray -> c.objects().mapNotNull { b ->
            when (b.text("type")) {
                "text" -> b.text("text")
                "image" -> if (image(b) != null) null else "[image]"
                else -> "[${b.text("type")}]"
            }
        }.joinToString("\n")
        else -> ""
    }

    private fun toolResultImages(block: JsonObject): List<TranscriptImage> =
        (block["content"] as? JsonArray)?.objects()?.filter { it.text("type") == "image" }?.mapNotNull(::image).orEmpty()

    private fun resultLine(m: JsonObject): String {
        val parts = mutableListOf(if (m.flag("is_error")) "Turn failed" else "Turn complete")
        if ("duration_ms" in m) parts += oneDecimal((m.long("duration_ms") ?: 0) / 1000.0) + " s"
        if ("num_turns" in m) parts += "${(m.long("num_turns") ?: 0).toInt()} steps"
        return parts.joinToString(" · ")
    }

    private fun contentBlocks(m: JsonObject): List<JsonObject> =
        m.obj("message")?.array("content").objects()
}
