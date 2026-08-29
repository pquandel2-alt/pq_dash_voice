package cc.quandel.dashvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reines Logik-Testen ohne Netzwerk: [HaConversation.Result] entscheidet, ob ein Befehl als
 * erfolgreich gilt, und trägt die conversation_id für den nächsten Follow-up-Turn weiter.
 */
class HaConversationTest {

    @Test
    fun `action_done is ok`() {
        val r = HaConversation.Result("action_done", "Erledigt", "conv-1")
        assertTrue(r.ok)
    }

    @Test
    fun `query_answer is not ok`() {
        val r = HaConversation.Result("query_answer", "Es ist 5 Grad warm.")
        assertFalse(r.ok)
    }

    @Test
    fun `error response is not ok`() {
        val r = HaConversation.Result("error", "Ich habe das nicht verstanden.")
        assertFalse(r.ok)
    }

    @Test
    fun `conversationId defaults to blank when not provided`() {
        val r = HaConversation.Result("action_done", "")
        assertEquals("", r.conversationId)
    }

    @Test
    fun `conversationId round-trips for follow-up turns`() {
        val r = HaConversation.Result("query_answer", "Und morgen?", "conv-42")
        assertEquals("conv-42", r.conversationId)
    }
}
