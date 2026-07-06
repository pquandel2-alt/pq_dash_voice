package cc.quandel.dashvoice

/**
 * Simple main-thread event bus between VoiceService and MainActivity.
 * VoiceService posts on the main looper; MainActivity sets callbacks in onResume/onPause.
 */
object VoiceEvents {
    var onWake: (() -> Unit)? = null
    var onTranscript: ((text: String) -> Unit)? = null
    var onResponse: ((text: String) -> Unit)? = null
    /** TTS-Lautstärke (RMS 0..1) pro Audio-Chunk — treibt die Mund-/Lippensync-Animation. */
    var onTtsLevel: ((level: Float) -> Unit)? = null
    /** On-Device-Sofortbefehl ausgeführt (action_done) — nur visuelle Bestätigung, keine Stimme. */
    var onCommandDone: ((speech: String) -> Unit)? = null
    /** Timer gestellt (Bestätigung, z. B. „⏲ Timer: 5 Min"). */
    var onTimerSet: ((label: String) -> Unit)? = null
    /** Timer/Wecker klingelt — Overlay zum Stoppen anzeigen. */
    var onTimerRinging: (() -> Unit)? = null
    /** Klingeln gestoppt. */
    var onTimerStopped: (() -> Unit)? = null
    var onIdle: (() -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    /**
     * Letzter bekannter HA-Verbindungszustand (Single Source of Truth, vom Service gepflegt).
     * Eine neu erzeugte MainActivity (z. B. nach dem 04:00-Relaunch) liest dies, um den Status-Dot
     * korrekt zu initialisieren — sonst bliebe er auf Default-Rot, obwohl die Verbindung besteht.
     */
    @Volatile var connected: Boolean = false
    /** Aktueller Voice-State (IDLE, RECOGNIZING, STREAMING, SPEAKING) — Single Source of Truth.
     * Eine neu erzeugte MainActivity initialisiert die VoiceAnimationView daraus statt
     * auf das nächste onWake-Event zu warten.
     */
    @Volatile var voiceState: VoiceService.State = VoiceService.State.IDLE
    var onNetworkAvailable: (() -> Unit)? = null
}
