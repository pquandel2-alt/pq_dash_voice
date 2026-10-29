package cc.quandel.dashvoice.avatar

/**
 * Reine Zustandslogik des Video-Avatars (keine Android-Abhängigkeiten, vollständig unit-testbar).
 *
 * Eingänge: aggregierter Satelliten-Zustand, Nachtmodus, One-Shot-Trigger und die Meldung des
 * Players, welcher Clip gerade begonnen hat. Ausgänge: welcher Clip als erster gespielt wird,
 * welcher Clip an der nächsten Clipgrenze folgt (null = an dieser Grenze ausblenden) und ob
 * sofort per Crossfade gewechselt werden muss.
 *
 * Grundannahme: Alle Wach-Clips beginnen und enden auf dem Home-Frame, sleep beginnt/endet auf
 * dem Schlaf-Frame, sleep_in geht Home → Schlaf, wake_up Schlaf → Home. Wechsel an Clipgrenzen
 * sind dadurch harte Schnitte ohne sichtbaren Sprung.
 */
class AvatarStateMachine(
    var config: Config = Config(),
    /** Gibt es für die Szene mindestens einen Clip? */
    private val available: (String) -> Boolean = { true },
    private val clock: () -> Long,
) {
    data class Config(
        /** Wie lange nach der letzten Aktivität noch der idle-Loop gezeigt wird. */
        val lingerMs: Long = 4_000L,
        /** Mindest-Restlaufzeit, ab der ein dringender Wechsel sofort (Crossfade) erfolgt. */
        val urgentThresholdMs: Long = 600L,
        /** Avatar dauerhaft zeigen (auch komplett idle). */
        val alwaysShow: Boolean = false,
    )

    enum class Pose { AWAKE, ASLEEP }

    var assistState: AssistState = AssistState.IDLE
        private set
    var nightMode: Boolean = false
        private set
    /** Angeforderter, noch nicht gestarteter One-Shot. */
    var pendingOneShot: String? = null
        private set
    /** Gerade laufende Szene; null = nichts spielt (Overlay ausgeblendet). */
    var currentScene: String? = null
        private set
    var currentIsOneShot: Boolean = false
        private set

    private var activityEndedAt: Long? = null

    val isPlaying: Boolean get() = currentScene != null

    /** Endpose des aktuellen Clips (= Startpose des nächsten). */
    val poseAfterCurrent: Pose
        get() = when (currentScene) {
            AvatarScenes.SLEEP_IN, AvatarScenes.SLEEP -> Pose.ASLEEP
            else -> Pose.AWAKE
        }

    // ── Eingänge ────────────────────────────────────────────────────────────────────────────

    fun onAssistState(state: AssistState) {
        if (state == assistState) return
        val previous = assistState
        assistState = state
        // Aktivität hat Vorrang: ein noch nicht gestarteter One-Shot verfällt.
        if (state != AssistState.IDLE) pendingOneShot = null
        if (previous != AssistState.IDLE && state == AssistState.IDLE) activityEndedAt = clock()
    }

    fun onNightMode(on: Boolean) {
        nightMode = on
    }

    /**
     * One-Shot anfordern (z. B. aus input_text.avatar_clip). Wird nur angenommen, wenn alle
     * Satelliten idle sind, kein Schlafmodus aktiv ist und es Clips für die Szene gibt.
     */
    fun onOneShot(scene: String): Boolean {
        val key = scene.trim().lowercase()
        if (key.isEmpty()) return false
        if (assistState != AssistState.IDLE || nightMode) return false
        if (!available(key)) return false
        pendingOneShot = key
        return true
    }

    /** Vom Player: ein Clip der Szene [scene] ist gerade sichtbar geworden (Clipgrenze oder Crossfade). */
    fun onClipStarted(scene: String) {
        val now = clock()
        // Der vorherige Clip war ein One-Shot → ab jetzt läuft der Nachlauf.
        if (currentIsOneShot) activityEndedAt = now
        currentIsOneShot = pendingOneShot != null && scene == pendingOneShot
        if (currentIsOneShot) pendingOneShot = null
        currentScene = scene
    }

    /** Vom Player: Wiedergabe beendet, Overlay ausgeblendet, Player freigegeben. */
    fun onPlaybackStopped() {
        if (currentIsOneShot) activityEndedAt = clock()
        currentScene = null
        currentIsOneShot = false
    }

    // ── Ausgänge ────────────────────────────────────────────────────────────────────────────

    /** Soll das Overlay jetzt (weiter) sichtbar sein? */
    fun shouldBeVisible(now: Long = clock()): Boolean = currentIsOneShot || visibleAt(now)

    /**
     * Erste Szene, wenn das Overlay gerade nicht sichtbar ist. null = ausgeblendet bleiben.
     * Ein ausgeblendeter Avatar startet immer vom Home-Frame (Poster), daher kein wake_up.
     */
    fun startScene(): String? {
        if (isPlaying || !visibleAt(clock())) return null
        if (assistState != AssistState.IDLE) return resolve(assistState.scene)
        pendingOneShot?.let { return it }
        if (wantsSleep()) return AvatarScenes.SLEEP_IN
        return resolve(AvatarScenes.IDLE)
    }

    /**
     * Szene für den Clip, der an der nächsten Clipgrenze (in [remainingMs]) folgt.
     * null = an dieser Grenze stoppen und ausblenden.
     */
    fun nextScene(remainingMs: Long): String? {
        if (!isPlaying) return null
        val boundary = clock() + remainingMs.coerceAtLeast(0L)
        val visible = visibleAt(boundary)
        if (poseAfterCurrent == Pose.ASLEEP) {
            return if (wantsSleep()) {
                if (visible) AvatarScenes.SLEEP else null   // auf dem Schlaf-Frame ausblenden
            } else {
                AvatarScenes.WAKE_UP
            }
        }
        if (assistState != AssistState.IDLE) return resolve(assistState.scene)
        pendingOneShot?.let { return it }
        if (!visible) return null                           // auf dem Home-Frame ausblenden
        if (wantsSleep()) return AvatarScenes.SLEEP_IN
        return resolve(AvatarScenes.IDLE)
    }

    /**
     * Dringender Sofortwechsel (Crossfade) statt Warten auf die Clipgrenze? Nur für listening
     * und wake_up und nur, wenn die Restlaufzeit über dem Schwellwert liegt.
     */
    fun urgentSwitch(remainingMs: Long): String? {
        val current = currentScene ?: return null
        if (remainingMs <= config.urgentThresholdMs) return null
        if (current == AvatarScenes.WAKE_UP) return null    // Aufwachen nie abbrechen
        val next = nextScene(remainingMs) ?: return null
        if (next !in URGENT || next == current) return null
        return next
    }

    // ── intern ──────────────────────────────────────────────────────────────────────────────

    private fun visibleAt(t: Long): Boolean {
        if (config.alwaysShow) return true
        if (assistState != AssistState.IDLE) return true
        if (pendingOneShot != null) return true
        // Läuft gerade ein One-Shot, beginnt der Nachlauf erst mit seinem Ende (≈ t).
        val end = if (currentIsOneShot) t else activityEndedAt ?: return false
        return t - end < config.lingerMs
    }

    private fun wantsSleep(): Boolean =
        nightMode && assistState == AssistState.IDLE && pendingOneShot == null && hasSleepClips()

    private fun hasSleepClips(): Boolean =
        available(AvatarScenes.SLEEP_IN) && available(AvatarScenes.SLEEP) && available(AvatarScenes.WAKE_UP)

    private fun resolve(scene: String): String? = when {
        available(scene) -> scene
        available(AvatarScenes.IDLE) -> AvatarScenes.IDLE
        else -> null
    }

    companion object {
        val URGENT = setOf(AvatarScenes.LISTENING, AvatarScenes.WAKE_UP)
    }
}
