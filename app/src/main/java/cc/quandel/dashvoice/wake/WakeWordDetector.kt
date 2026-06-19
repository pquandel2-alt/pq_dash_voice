package cc.quandel.dashvoice.wake

/** Streaming wake-word detector. Feed 16 kHz mono 16-bit PCM frames. */
interface WakeWordDetector {
    /** Returns true on the frame where the wake word fires. */
    fun accept(frame: ShortArray): Boolean
    fun reset()
    fun close()

    /** False when no usable model is loaded (service then falls back to tap-to-talk). */
    val available: Boolean
}
