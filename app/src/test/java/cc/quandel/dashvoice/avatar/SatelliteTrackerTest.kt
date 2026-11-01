package cc.quandel.dashvoice.avatar

import org.junit.Assert.assertEquals
import org.junit.Test

class SatelliteTrackerTest {

    private val limits = SatelliteTracker.Limits(listeningMs = 30_000, processingMs = 60_000, respondingMs = 300_000)

    @Test
    fun `aggregates by priority`() {
        val t = SatelliteTracker(limits)
        t.update("assist_satellite.a", "listening", 0)
        t.update("assist_satellite.b", "responding", 0)
        t.update("assist_satellite.c", "unavailable", 0)
        assertEquals(AssistState.RESPONDING, t.aggregate(1_000))
    }

    @Test
    fun `stuck listening satellite is ignored after its limit`() {
        val t = SatelliteTracker(limits)
        t.update("assist_satellite.tablet", "listening", 0)
        assertEquals(AssistState.LISTENING, t.aggregate(29_999))
        assertEquals(AssistState.IDLE, t.aggregate(30_000))
        assertEquals(listOf("assist_satellite.tablet"), t.staleIds(30_000))
    }

    @Test
    fun `stuck satellite does not hide another active one`() {
        val t = SatelliteTracker(limits)
        t.update("assist_satellite.tablet", "listening", 0)
        t.update("assist_satellite.kueche", "processing", 40_000)
        assertEquals(AssistState.PROCESSING, t.aggregate(45_000))
    }

    @Test
    fun `repeated same state keeps original timestamp, new state restarts it`() {
        val t = SatelliteTracker(limits)
        t.update("s", "listening", 0)
        t.update("s", "listening", 20_000)
        assertEquals(AssistState.IDLE, t.aggregate(30_000))
        t.update("s", "idle", 31_000)
        t.update("s", "listening", 32_000)
        assertEquals(AssistState.LISTENING, t.aggregate(50_000))
    }

    @Test
    fun `age from last_changed counts against the limit after reconnect`() {
        val t = SatelliteTracker(limits)
        t.update("s", "listening", now = 100_000, ageMs = 18 * 60_000)
        assertEquals(AssistState.IDLE, t.aggregate(100_000))
    }

    @Test
    fun `removed satellite no longer counts`() {
        val t = SatelliteTracker(limits)
        t.update("s", "responding", 0)
        t.update("s", null, 1)
        assertEquals(AssistState.IDLE, t.aggregate(2))
        assertEquals(0, t.size)
    }
}
