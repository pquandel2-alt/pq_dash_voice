package cc.quandel.dashvoice.particle

import org.junit.Assert.assertEquals
import org.junit.Test

class ParticleAssistTimingTest {
    @Test
    fun `keeps avatar visible for configured minimum`() {
        assertEquals(3_000L, ParticleAssistTiming.hideDelayMs(1_000L, 3_000L, 5_000L, 500L))
    }

    @Test
    fun `uses completion hold after minimum elapsed`() {
        assertEquals(1_200L, ParticleAssistTiming.hideDelayMs(1_000L, 7_000L, 5_000L, 1_200L))
    }

    @Test
    fun `never returns a negative delay`() {
        assertEquals(0L, ParticleAssistTiming.hideDelayMs(5_000L, 4_000L, 0L, -1L))
    }
}
