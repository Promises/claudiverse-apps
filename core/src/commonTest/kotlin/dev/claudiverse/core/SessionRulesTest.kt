package dev.claudiverse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rules both apps answer a seat with: a question's answer, and which model a seat runs. */
class SessionRulesTest {

    private fun q(header: String?, multi: Boolean = false) = QuestionSpec(header, "Which?", listOf("A" to null, "B" to "the b one"), multi)

    @Test
    fun aSingleQuestionIsAnsweredWithItsLabelAlone() {
        assertEquals("B", composeAnswer(listOf(q("Pick")), listOf(listOf("B"))))
    }

    /** Structured: each question's text to its choices, with no header needed; unanswered ones left out. */
    @Test
    fun theStructuredAnswerKeysEachQuestionByItsText() {
        val questions = listOf(
            QuestionSpec("Same", "Which engine?", listOf("A" to null, "B" to null), false),
            QuestionSpec("Same", "Which parts?", listOf("A" to null, "B" to null), true),
            QuestionSpec(null, "Push now?", listOf("Yes" to null), false),
        )
        assertEquals(mapOf("Which engine?" to "A", "Which parts?" to "B, A"), answerMap(questions, listOf(listOf("A"), listOf("B", "A"), emptyList())))
        assertEquals(emptyMap(), answerMap(questions, emptyList()))
    }

    /** Several questions: one line each, by header; multi-select in the order picked. */
    @Test
    fun severalQuestionsAnswerOneLineEachByHeader() {
        val questions = listOf(q("First"), q("Second", multi = true), q("Third"))
        assertEquals("First: A\nSecond: B, A", composeAnswer(questions, listOf(listOf("A"), listOf("B", "A"), emptyList())))
    }

    @Test
    fun nothingPickedIsAnEmptyAnswer() {
        assertEquals("", composeAnswer(listOf(q("Pick")), emptyList()))
    }

    /** Opus 5 and Opus 5.5 must stay apart; a dated id is the same model. */
    @Test
    fun theRunningModelMatchesExactly() {
        val opus5 = SessionChoices.models.single { it.id == "claude-opus-5" }
        assertTrue(SessionChoices.isRunning(opus5, "claude-opus-5"))
        assertTrue(SessionChoices.isRunning(opus5, "claude-opus-5-20260901"))
        assertFalse(SessionChoices.isRunning(opus5, "claude-opus-5-5"))
        assertFalse(SessionChoices.isRunning(opus5, null))
    }
}
