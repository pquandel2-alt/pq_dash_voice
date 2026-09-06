package cc.quandel.dashvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmGainLimiterTest {
    @Test
    fun highGainDoesNotHardClipSpeech() {
        val limiter = PcmGainLimiter()
        val frame = shortArrayOf(-12_000, -6_000, 0, 6_000, 12_000)

        val stats = limiter.process(frame, 5.96f)

        assertTrue(stats.limited)
        assertTrue(stats.outputPeak <= 28_000)
        assertTrue(frame.none { it == Short.MIN_VALUE || it == Short.MAX_VALUE })
    }

    @Test
    fun requestedGainIsPreservedWhenThereIsHeadroom() {
        val limiter = PcmGainLimiter(releaseFactor = 1.0f)
        val frame = shortArrayOf(-2_000, 0, 2_000)

        val stats = limiter.process(frame, 2.0f)

        assertEquals(2.0f, stats.appliedGain, 0.001f)
        assertEquals(4_000, stats.outputPeak)
        assertTrue(!stats.limited)
    }

    @Test
    fun limiterAttacksImmediatelyWhenInputGetsLoud() {
        val limiter = PcmGainLimiter(releaseFactor = 1.0f)
        limiter.process(shortArrayOf(1_000), 6.0f)
        val loudFrame = shortArrayOf(20_000)

        val stats = limiter.process(loudFrame, 6.0f)

        assertTrue(stats.appliedGain <= 1.4f)
        assertTrue(stats.outputPeak <= 28_000)
    }
}
