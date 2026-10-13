package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The server's model list (GET /api/models), and the built-in one on a server without it. */
class ModelCatalogTest {

    private fun api(status: HttpStatusCode, body: String) =
        Api({ ServerAccess("http://server:4010", "tok") }, HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }))

    private val served = """{"source":"lorem","models":[
        {"id":"claude-opus-5-5","label":"Opus 5.5","family":"opus","one_m":true,"accepts_1m_suffix":false,"since":null,"older":false},
        {"id":"claude-haiku-5-5","label":"Haiku 5.5","family":"haiku","one_m":true,"accepts_1m_suffix":false,"since":"2.1.293","older":false},
        {"id":"claude-haiku-4-5","label":"Haiku 4.5","family":"haiku","one_m":false,"accepts_1m_suffix":false,"since":null,"older":false},
        {"id":"claude-sonnet-4-0","label":"Sonnet 4","family":"sonnet","one_m":false,"accepts_1m_suffix":true,"since":null,"older":true},
        {"label":"no id, skipped"},
        {"id":"claude-ipsum-9","label":"Ipsum 9","family":"ipsum","something_new":[1]}
    ],"aliases":[{"id":"default","label":"Default"},{"id":"opusplan","label":"Opus plan"}]}"""

    @Test
    fun theServersListReplacesTheBuiltInOne() = runTest {
        val c = api(HttpStatusCode.OK, served).modelCatalog()!!
        assertTrue(c.fromServer)
        assertEquals(listOf("claude-opus-5-5", "claude-haiku-5-5", "claude-haiku-4-5", "claude-ipsum-9"), c.models.map { it.id })
        assertEquals(listOf("claude-sonnet-4-0"), c.olderModels.map { it.id })
        assertTrue(c.olderModels.single().oneM, "accepts_1m_suffix is what offers [1m]")
        assertFalse(c.models.first().oneM, "a native 1M model offers no [1m]")
        assertEquals("2.1.293", c.models[1].since)
        assertEquals(listOf("default", "opusplan"), c.aliases.map { it.id })
        assertEquals(listOf("claude-opus-5-5", "claude-haiku-4-5"), c.spawnModels("2.1.291").map { it.id })
    }

    @Test
    fun aServerWithoutTheListLeavesTheBuiltInOne() = runTest {
        assertNull(api(HttpStatusCode.NotFound, """{"errors":{"detail":"Not Found"}}""").modelCatalog())
        assertNull(api(HttpStatusCode.OK, """{"models":[],"aliases":[]}""").modelCatalog())
        assertFalse(ModelCatalog.BUILT_IN.fromServer)
    }
}
