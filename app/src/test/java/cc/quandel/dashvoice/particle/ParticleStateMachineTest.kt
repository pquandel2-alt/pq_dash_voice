package cc.quandel.dashvoice.particle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticleStateMachineTest {

    @Test
    fun `starts in OFF and is inactive`() {
        val sm = ParticleStateMachine()
        assertEquals(ParticleState.OFF, sm.getState())
        assertFalse(sm.isActive())
    }

    @Test
    fun `transitionTo changes state and reports active`() {
        val sm = ParticleStateMachine()
        val changed = sm.transitionTo(ParticleState.ASSEMBLING)
        assertTrue(changed)
        assertEquals(ParticleState.ASSEMBLING, sm.getState())
        assertTrue(sm.isActive())
    }

    @Test
    fun `transitionTo same state is a no-op and returns false`() {
        val sm = ParticleStateMachine()
        sm.transitionTo(ParticleState.LISTENING)
        val changedAgain = sm.transitionTo(ParticleState.LISTENING)
        assertFalse(changedAgain)
        assertEquals(ParticleState.LISTENING, sm.getState())
    }

    @Test
    fun `onStateChanged callback fires with previous and new state`() {
        val sm = ParticleStateMachine()
        var seenPrev: ParticleState? = null
        var seenNew: ParticleState? = null
        sm.onStateChanged = { prev, new -> seenPrev = prev; seenNew = new }

        sm.transitionTo(ParticleState.THINKING)

        assertEquals(ParticleState.OFF, seenPrev)
        assertEquals(ParticleState.THINKING, seenNew)
    }

    @Test
    fun `onStateChanged callback does not fire on no-op transition`() {
        val sm = ParticleStateMachine()
        sm.transitionTo(ParticleState.SPEAKING)
        var callbackCount = 0
        sm.onStateChanged = { _, _ -> callbackCount++ }

        sm.transitionTo(ParticleState.SPEAKING)

        assertEquals(0, callbackCount)
    }

    @Test
    fun `full LISTENING to THINKING to SPEAKING to SUCCESS to IDLE cycle`() {
        val sm = ParticleStateMachine()
        val visited = mutableListOf<ParticleState>()
        sm.onStateChanged = { _, new -> visited.add(new) }

        sm.transitionTo(ParticleState.ASSEMBLING)
        sm.transitionTo(ParticleState.LISTENING)
        sm.transitionTo(ParticleState.THINKING)
        sm.transitionTo(ParticleState.SPEAKING)
        sm.transitionTo(ParticleState.SUCCESS)
        sm.transitionTo(ParticleState.IDLE)

        assertEquals(
            listOf(
                ParticleState.ASSEMBLING,
                ParticleState.LISTENING,
                ParticleState.THINKING,
                ParticleState.SPEAKING,
                ParticleState.SUCCESS,
                ParticleState.IDLE
            ),
            visited
        )
        assertEquals(ParticleState.IDLE, sm.getState())
    }

    @Test
    fun `OFF makes isActive false again`() {
        val sm = ParticleStateMachine()
        sm.transitionTo(ParticleState.LISTENING)
        sm.transitionTo(ParticleState.OFF)
        assertFalse(sm.isActive())
    }
}
