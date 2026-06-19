package cc.quandel.dashvoice.wake

/** Disabled detector: used when no wake-word model is present. Tap-to-talk only. */
class NoopWakeWordDetector : WakeWordDetector {
    override fun accept(frame: ShortArray): Boolean = false
    override fun reset() {}
    override fun close() {}
    override val available: Boolean = false
}
