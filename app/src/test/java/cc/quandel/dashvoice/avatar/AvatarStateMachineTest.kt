package cc.quandel.dashvoice.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarStateMachineTest {

    private var now = 100_000L
    private val missing = mutableSetOf<String>()

    private fun machine(
        lingerMs: Long = 4_000L,
        urgentMs: Long = 600L,
        alwaysShow: Boolean = false,
    ) = AvatarStateMachine(
        config = AvatarStateMachine.Config(lingerMs, urgentMs, alwaysShow),
        available = { it !in missing },
        clock = { now },
    )

    /** Simuliert den Player: spielt die erste Szene an und startet sie. */
    private fun AvatarStateMachine.startPlaying(): String {
        val scene = startScene()!!
        onClipStarted(scene)
        return scene
    }

    /** Simuliert eine Clipgrenze nach [durationMs]: Zeit vor, nächste Szene starten. */
    private fun AvatarStateMachine.boundary(durationMs: Long): String? {
        val next = nextScene(durationMs)
        now += durationMs
        if (next == null) onPlaybackStopped() else onClipStarted(next)
        return next
    }

    // ── Priorität ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `aggregate uses priority responding over processing over listening over idle`() {
        assertEquals(AssistState.RESPONDING, AssistState.aggregateHa(listOf("idle", "listening", "responding", "processing")))
        assertEquals(AssistState.PROCESSING, AssistState.aggregateHa(listOf("listening", "processing", "idle")))
        assertEquals(AssistState.LISTENING, AssistState.aggregateHa(listOf("idle", "listening")))
        assertEquals(AssistState.IDLE, AssistState.aggregateHa(listOf("idle", "unavailable", null)))
        assertEquals(AssistState.IDLE, AssistState.aggregateHa(emptyList()))
    }

    @Test
    fun `states map to scenes`() {
        assertEquals("listening", AssistState.LISTENING.scene)
        assertEquals("thinking", AssistState.PROCESSING.scene)
        assertEquals("speaking", AssistState.RESPONDING.scene)
        assertEquals("idle", AssistState.IDLE.scene)
    }

    @Test
    fun `hidden while everything is idle`() {
        val m = machine()
        assertNull(m.startScene())
        assertFalse(m.shouldBeVisible())
    }

    @Test
    fun `active satellite shows avatar with mapped scene`() {
        val m = machine()
        m.onAssistState(AssistState.PROCESSING)
        assertEquals("thinking", m.startScene())
        assertTrue(m.shouldBeVisible())
    }

    @Test
    fun `state change is queued for the next clip boundary`() {
        val m = machine()
        m.onAssistState(AssistState.LISTENING)
        assertEquals("listening", m.startPlaying())
        assertEquals("listening", m.nextScene(2_000))            // Loop
        m.onAssistState(AssistState.PROCESSING)
        assertEquals("thinking", m.nextScene(2_000))
        assertNull(m.urgentSwitch(2_000))                         // processing ist nicht dringend
        m.onAssistState(AssistState.RESPONDING)
        assertEquals("speaking", m.boundary(2_000))
    }

    @Test
    fun `missing scene falls back to idle`() {
        missing += "thinking"
        val m = machine()
        m.onAssistState(AssistState.PROCESSING)
        assertEquals("idle", m.startScene())
    }

    // ── Nachlauf ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `linger keeps idle loop then hides at clip boundary`() {
        val m = machine(lingerMs = 4_000)
        m.onAssistState(AssistState.RESPONDING)
        m.startPlaying()
        now += 1_000
        m.onAssistState(AssistState.IDLE)                         // Aktivität endet bei t0
        assertEquals("idle", m.boundary(1_500))                    // t0+1.5s: Nachlauf läuft
        assertTrue(m.shouldBeVisible())
        assertEquals("idle", m.boundary(2_000))                    // Grenze bei t0+3.5s < 4s
        assertNull(m.nextScene(2_000))                              // Grenze bei t0+5.5s ≥ 4s → ausblenden
        assertNull(m.boundary(2_000))
        assertFalse(m.isPlaying)
    }

    @Test
    fun `new activity during linger continues instead of hiding`() {
        val m = machine(lingerMs = 1_000)
        m.onAssistState(AssistState.LISTENING)
        m.startPlaying()
        m.onAssistState(AssistState.IDLE)
        assertNull(m.nextScene(3_000))
        m.onAssistState(AssistState.LISTENING)
        assertEquals("listening", m.nextScene(3_000))
    }

    @Test
    fun `always show keeps idle loop running`() {
        val m = machine(alwaysShow = true)
        assertEquals("idle", m.startPlaying())
        now += 60_000
        assertEquals("idle", m.nextScene(5_000))
    }

    // ── Schlaf ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `night mode goes to sleep after activity and hides on sleep frame`() {
        val m = machine(lingerMs = 4_000)
        m.onNightMode(true)
        m.onAssistState(AssistState.RESPONDING)
        assertEquals("speaking", m.startPlaying())
        m.onAssistState(AssistState.IDLE)
        assertEquals("sleep_in", m.boundary(1_000))
        assertEquals(AvatarStateMachine.Pose.ASLEEP, m.poseAfterCurrent)
        assertEquals("sleep", m.boundary(1_000))
        assertEquals("sleep", m.boundary(1_000))                    // Loop im Schlaf
        assertNull(m.boundary(2_000))                              // Nachlauf vorbei → ausblenden
    }

    @Test
    fun `always show at night plays sleep_in once then sleep loop`() {
        val m = machine(alwaysShow = true)
        m.onNightMode(true)
        assertEquals("sleep_in", m.startPlaying())
        assertEquals("sleep", m.boundary(1_000))
        assertEquals("sleep", m.boundary(3_000))
        assertEquals("sleep", m.boundary(3_000))
    }

    @Test
    fun `activity while asleep wakes up first then plays target`() {
        val m = machine(alwaysShow = true)
        m.onNightMode(true)
        m.startPlaying()
        m.boundary(1_000)                                          // jetzt: sleep
        m.onAssistState(AssistState.LISTENING)
        assertEquals("wake_up", m.nextScene(3_000))
        assertEquals("wake_up", m.urgentSwitch(3_000))             // dringend, Restlaufzeit lang
        m.onClipStarted("wake_up")
        assertNull(m.urgentSwitch(1_500))                           // Aufwachen nicht abbrechen
        assertEquals("listening", m.boundary(1_500))
    }

    @Test
    fun `turning night mode off wakes up into idle`() {
        val m = machine(alwaysShow = true)
        m.onNightMode(true)
        m.startPlaying()
        m.boundary(1_000)
        m.onNightMode(false)
        assertEquals("wake_up", m.boundary(3_000))
        assertEquals("idle", m.boundary(1_000))
    }

    @Test
    fun `wake up near boundary is queued instead of crossfaded`() {
        val m = machine(alwaysShow = true, urgentMs = 600)
        m.onNightMode(true)
        m.startPlaying()
        m.boundary(1_000)
        m.onNightMode(false)
        assertNull(m.urgentSwitch(400))
        assertEquals("wake_up", m.nextScene(400))
    }

    @Test
    fun `hidden avatar at night starts directly with target scene`() {
        val m = machine()
        m.onNightMode(true)
        m.onAssistState(AssistState.LISTENING)
        assertEquals("listening", m.startScene())
    }

    @Test
    fun `sleep mode disabled without sleep clips`() {
        missing += "sleep_in"
        val m = machine(alwaysShow = true)
        m.onNightMode(true)
        assertEquals("idle", m.startScene())
    }

    // ── One-Shots ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `one shot plays once then lingers in idle loop and hides`() {
        val m = machine(lingerMs = 4_000)
        assertTrue(m.onOneShot("happy"))
        assertEquals("happy", m.startPlaying())
        assertTrue(m.currentIsOneShot)
        assertNull(m.pendingOneShot)
        assertTrue(m.shouldBeVisible())
        assertEquals("idle", m.boundary(2_000))                     // One-Shot endet → Nachlauf ab hier
        assertFalse(m.currentIsOneShot)
        assertEquals("idle", m.boundary(3_000))                     // 3 s < 4 s
        assertNull(m.boundary(3_000))                              // 6 s ≥ 4 s
    }

    @Test
    fun `one shot during idle loop is queued at next boundary`() {
        val m = machine(alwaysShow = true)
        m.startPlaying()
        assertTrue(m.onOneShot("Alert"))
        assertEquals("alert", m.nextScene(2_000))
        assertNull(m.urgentSwitch(2_000))
        assertEquals("alert", m.boundary(2_000))
        assertEquals("idle", m.boundary(2_000))
    }

    @Test
    fun `one shot ignored while a satellite is active`() {
        val m = machine()
        m.onAssistState(AssistState.LISTENING)
        assertFalse(m.onOneShot("happy"))
        assertNull(m.pendingOneShot)
    }

    @Test
    fun `one shot ignored in sleep mode`() {
        val m = machine()
        m.onNightMode(true)
        assertFalse(m.onOneShot("happy"))
    }

    @Test
    fun `one shot ignored for unknown scene`() {
        missing += "dance"
        val m = machine()
        assertFalse(m.onOneShot("dance"))
    }

    @Test
    fun `pending one shot dropped when activity starts`() {
        val m = machine()
        assertTrue(m.onOneShot("happy"))
        m.onAssistState(AssistState.LISTENING)
        assertNull(m.pendingOneShot)
        assertEquals("listening", m.startScene())
    }

    // ── Sofortwechsel ───────────────────────────────────────────────────────────────────────

    @Test
    fun `listening is urgent when remaining time exceeds threshold`() {
        val m = machine(alwaysShow = true, urgentMs = 600)
        m.startPlaying()
        m.onAssistState(AssistState.LISTENING)
        assertEquals("listening", m.urgentSwitch(2_000))
        assertNull(m.urgentSwitch(600))
        assertEquals("listening", m.nextScene(600))
    }

    @Test
    fun `no urgent switch when already listening`() {
        val m = machine()
        m.onAssistState(AssistState.LISTENING)
        m.startPlaying()
        assertNull(m.urgentSwitch(3_000))
    }

    @Test
    fun `listening interrupts speaking for a follow up question`() {
        val m = machine()
        m.onAssistState(AssistState.RESPONDING)
        m.startPlaying()
        m.onAssistState(AssistState.LISTENING)
        assertEquals("listening", m.urgentSwitch(3_000))
    }

    @Test
    fun `stop resets so next show starts fresh`() {
        val m = machine(lingerMs = 0)
        m.onAssistState(AssistState.LISTENING)
        m.startPlaying()
        m.onAssistState(AssistState.IDLE)
        assertNull(m.boundary(1_000))
        assertNull(m.currentScene)
        assertNull(m.startScene())
    }

    // ── Wegtippen ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `dismiss hides immediately and stays hidden while state is unchanged`() {
        val m = machine()
        m.onAssistState(AssistState.LISTENING)
        m.startPlaying()
        m.dismiss()
        assertFalse(m.shouldBeVisible())
        assertNull(m.nextScene(3_000))
        assertNull(m.urgentSwitch(3_000))
        m.onPlaybackStopped()
        assertNull(m.startScene())
    }

    @Test
    fun `dismiss has no linger when the stuck state ends`() {
        val m = machine(lingerMs = 4_000)
        m.onAssistState(AssistState.LISTENING)
        m.startPlaying()
        m.dismiss()
        m.onPlaybackStopped()
        m.onAssistState(AssistState.IDLE)
        assertNull(m.startScene())
    }

    @Test
    fun `new activity after dismiss shows avatar again`() {
        val m = machine()
        m.onAssistState(AssistState.LISTENING)
        m.startPlaying()
        m.dismiss()
        m.onPlaybackStopped()
        m.onAssistState(AssistState.PROCESSING)
        assertEquals("thinking", m.startScene())
    }

    @Test
    fun `dismiss also overrides always show until next activity or one shot`() {
        val m = machine(alwaysShow = true)
        m.startPlaying()
        m.dismiss()
        m.onPlaybackStopped()
        assertNull(m.startScene())
        assertTrue(m.onOneShot("happy"))
        assertEquals("happy", m.startScene())
    }
}
