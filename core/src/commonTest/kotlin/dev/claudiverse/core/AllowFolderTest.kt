package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Allowing a folder keeps the machine's list free of duplicates: a parent replaces the folders inside it. */
class AllowFolderTest {
    /** A machine's roots, as the agent keeps them, answering add and remove like the server relays them. */
    private class Machine(var roots: MutableList<String>) {
        val calls = mutableListOf<String>()
        fun api() = Api({ ServerAccess("http://server:4010", "tok") }, HttpClient(MockEngine { r: HttpRequestData ->
            val path = Json.parseToJsonElement((r.body as TextContent).text).jsonObject["path"]!!.jsonPrimitive.content
            if (r.method == HttpMethod.Post) roots.add(path) else roots.remove(path)
            calls += "${r.method.value} $path"
            respond(buildJsonObject { put("roots", JsonArray(roots.map(::JsonPrimitive))) }.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }))
    }

    @Test
    fun aParentReplacesTheFoldersInsideIt() = runTest {
        val m = Machine(mutableListOf("/Users/me/Documents/projects", "/Users/me/Documents/archive", "/Users/me/Downloads"))
        val roots = m.api().allowFolder("my-mac", "/Users/me/Documents/", m.roots.toList())
        assertEquals(listOf("/Users/me/Downloads", "/Users/me/Documents"), roots)
        assertEquals("POST /Users/me/Documents", m.calls.first(), "the parent is added before any child is removed")
    }

    @Test
    fun aFolderAlreadyCoveredAddsNothing() = runTest {
        val m = Machine(mutableListOf("/Users/me/Documents"))
        val roots = m.api().allowFolder("my-mac", "/Users/me/Documents/projects/x", m.roots.toList())
        assertEquals(listOf("/Users/me/Documents"), roots)
        assertEquals(emptyList(), m.calls)
    }

    @Test
    fun insideIsByWholeSegments() {
        assertTrue(isInside("/a/b/c", "/a/b"))
        assertTrue(isInside("/a/b", "/a/b"))
        assertFalse(isInside("/a/bc", "/a/b"))
        assertEquals("/a/b", normalFolder("/a/b/"))
        assertEquals("/", normalFolder("/"))
    }
}
