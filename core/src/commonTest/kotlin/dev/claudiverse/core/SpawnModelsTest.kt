package dev.claudiverse.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What New seat offers: every family the server lists, Z.ai's GLM among them, older ones apart, and the 1M window only where it is asked for by suffix. */
class SpawnModelsTest {

    // As the live GET /api/models has them (trimmed): Claude 5 ids are bare 1M, the older ones take [1m], GLM takes neither.
    private val catalog = ModelCatalog.from(Json.parseToJsonElement("""{"models":[
        {"id":"claude-opus-5-5","label":"Opus 5.5","family":"opus","one_m":true,"accepts_1m_suffix":false,"since":null,"older":false},
        {"id":"claude-opus-4-6","label":"Opus 4.6","family":"opus","one_m":false,"accepts_1m_suffix":true,"since":null,"older":true},
        {"id":"claude-sonnet-5-5","label":"Sonnet 5.5","family":"sonnet","one_m":true,"accepts_1m_suffix":false,"since":null,"older":false},
        {"id":"claude-haiku-5-5","label":"Haiku 5.5","family":"haiku","one_m":true,"accepts_1m_suffix":false,"since":"2.1.293","older":false},
        {"id":"glm-5.3","label":"GLM-5.3 (Z.ai)","family":"glm","one_m":false,"accepts_1m_suffix":false,"since":null,"older":false},
        {"id":"glm-5.3-flash","label":"GLM-5.3 Flash (Z.ai)","family":"glm","one_m":false,"accepts_1m_suffix":false,"since":null,"older":false},
        {"id":"deepseek-v4.1-flash","label":"DeepSeek V4.1 Flash (OpenCode credits)","family":"deepseek","one_m":false,"accepts_1m_suffix":false,"since":null,"older":false}
    ],"aliases":[]}""").jsonObject)

    @Test
    fun glmIsOfferedAsItsOwnFamily() {
        val groups = catalog.spawnGroups("2.1.300")
        assertEquals(listOf("opus", "sonnet", "haiku", "glm", "deepseek"), groups.map { it.id })
        assertEquals("DeepSeek (OpenCode)", groups.last().label)
        assertEquals("Mistral (Vibe Pro)", ModelCatalog.groupLabel("mistral"))
        val glm = groups[3]
        assertEquals("GLM (Z.ai)", glm.label)
        assertEquals(listOf("glm-5.3", "glm-5.3-flash"), glm.models.map { it.id })
        assertTrue(catalog.spawnModels("2.1.300").any { it.id == "glm-5.3" })
    }

    @Test
    fun olderModelsAreKeptApartAndTheMachinesVersionStillFilters() {
        val opus = catalog.spawnGroups("2.1.300").first()
        assertEquals(listOf("claude-opus-5-5"), opus.models.map { it.id })
        assertEquals(listOf("claude-opus-4-6"), opus.older.map { it.id })
        assertFalse(catalog.spawnGroups("2.1.291").any { g -> g.models.any { it.id == "claude-haiku-5-5" } }, "Haiku 5.5 needs 2.1.293")
        assertEquals(listOf("Claude"), ModelCatalog.BUILT_IN.spawnGroups("2.1.300").map { it.label })
    }

    @Test
    fun theOneMillionWindowIsAskedOnlyWhereItIsASuffix() {
        fun m(id: String) = (catalog.models + catalog.olderModels).first { it.id == id }
        assertEquals("glm-5.3[1m]", spawnModelId(m("glm-5.3"), oneM = true), "GLM: the proxy strips [1m] before Z.ai")
        assertEquals("claude-opus-4-6[1m]", spawnModelId(m("claude-opus-4-6"), oneM = true))
        assertEquals("claude-opus-5-5", spawnModelId(m("claude-opus-5-5"), oneM = true), "a bare-1M Claude 5 id never gets [1m]")
        assertEquals("glm-5.3", spawnModelId(m("glm-5.3"), oneM = false))
        assertNull(spawnModelId(null, oneM = true))
        assertEquals("deepseek-v4.1-flash", spawnModelId(m("deepseek-v4.1-flash"), oneM = true), "DeepSeek takes no [1m]")
    }
}
