package cc.quandel.dashvoice.particle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticleAnimationClockTest {
    @Test
    fun `keeps frame precision at current epoch-sized timestamps`() {
        val epoch = 1_788_000_000_000L
        val first = ParticleAnimationClock.elapsedSeconds(epoch + 1_000L, epoch, 1f)
        val nextFrame = ParticleAnimationClock.elapsedSeconds(epoch + 1_016L, epoch, 1f)

        assertEquals(1f, first, 0.0001f)
        assertTrue(nextFrame > first)
        assertEquals(0.016f, nextFrame - first, 0.0001f)
    }

    @Test
    fun `applies configured animation speed`() {
        assertEquals(2f, ParticleAnimationClock.elapsedSeconds(2_000L, 1_000L, 2f), 0.0001f)
    }
}
