package dev.claudiverse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The model list as Claude Code's own catalog has it: which ids exist, which take [1m], and which versions know them. */
class ModelChoicesTest {

    private fun model(id: String) = (SessionChoices.models + SessionChoices.olderModels).single { it.id == id }

    @Test
    fun haiku55IsListedAboveHaiku45AndIsNewIn2_1_293() {
        val ids = SessionChoices.models.map { it.id }
        assertTrue(ids.indexOf("claude-haiku-5-5") in 0 until ids.indexOf("claude-haiku-4-5"))
        val haiku = model("claude-haiku-5-5")
        assertTrue(haiku.knownBy("2.1.293"))
        assertTrue(haiku.knownBy("2.1.296"))
        assertFalse(haiku.knownBy("2.1.291"))
        assertFalse(haiku.knownBy(null), "a machine that does not say its version is not offered a model it may not know")
        assertTrue(model("claude-opus-5-5").knownBy(null))
    }

    /** [1m] only where it opts a 200K model into 1M: a native 1M model's bare id is already 1M. */
    @Test
    fun onlyTwoHundredKModelsOfferTheOneMVariant() {
        val oneM = (SessionChoices.models + SessionChoices.olderModels).filter { it.oneM }.map { it.id }.toSet()
        assertEquals(setOf("claude-opus-4-6", "claude-sonnet-4-6", "claude-sonnet-4-5"), oneM)
    }

    @Test
    fun aSeatStartedWithTheSuffixStillShowsAsItsModel() {
        assertTrue(SessionChoices.isRunning(model("claude-opus-5-5"), "claude-opus-5-5[1m]"))
        assertTrue(SessionChoices.isRunning(model("claude-opus-5-5"), "claude-opus-5-5[1M]"))
        assertFalse(SessionChoices.isRunning(model("claude-opus-5"), "claude-opus-5-5[1m]"))
    }

    @Test
    fun aSpawnSendsExactIdsTheMachinesClaudeCodeKnows() {
        assertEquals(listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-haiku-4-5"),
            SessionChoices.spawnModels("2.1.296").map { it.id })
        assertEquals(listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-4-5"),
            SessionChoices.spawnModels("2.1.291").map { it.id })
        assertFalse(SessionChoices.spawnModels(null).any { it.id == "claude-haiku-5-5" })
    }

    @Test
    fun versionsCompareAsNumbers() {
        assertTrue(versionAtLeast("2.1.300", "2.1.293"), "300 > 293, though \"2.1.3\" sorts before \"2.1.2\" as text")
        assertFalse(versionAtLeast("2.1.29", "2.1.293"))
        assertTrue(versionAtLeast("2.2", "2.1.293"))
        assertTrue(versionAtLeast("2.1.293-beta", "2.1.293"))
    }
}
