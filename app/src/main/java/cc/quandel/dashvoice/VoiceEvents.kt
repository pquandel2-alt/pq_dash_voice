package cc.quandel.dashvoice

/**
 * Simple main-thread event bus between VoiceService and MainActivity.
 * VoiceService posts on the main looper; MainActivity sets callbacks in onResume/onPause.
 */
object VoiceEvents {
    var onWake: (() -> Unit)? = null
    var onTranscript: ((text: String) -> Unit)? = null
    var onResponse: ((text: String) -> Unit)? = null
    /** On-Device-Sofortbefehl ausgeführt (action_done) — nur visuelle Bestätigung, keine Stimme. */
    var onCommandDone: ((speech: String) -> Unit)? = null
    var onIdle: (() -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onNetworkAvailable: (() -> Unit)? = null
}
