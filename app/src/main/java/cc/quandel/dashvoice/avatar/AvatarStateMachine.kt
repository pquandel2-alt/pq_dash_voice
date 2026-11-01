package cc.quandel.dashvoice.avatar

import kotlin.random.Random

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
    private val random: Random = Random.Default,
) {
    data class Config(
        /** Wie lange nach der letzten Aktivität noch der idle-Loop gezeigt wird. */
        val lingerMs: Long = 4_000L,
        /** Mindest-Restlaufzeit, ab der ein dringender Wechsel sofort (Crossfade) erfolgt. */
        val urgentThresholdMs: Long = 600L,
        /** Avatar dauerhaft zeigen (auch komplett idle). */
        val alwaysShow: Boolean = false,
        /** Wahrscheinlichkeit, dass nach einem idle-Clip eine Bewegung (accent) statt idle folgt. */
        val accentChance: Float = 0.35f,
        /** Szenen, die den idle-Loop beleben (Umschauen, Lächeln …). */
        val accents: List<String> = listOf("look_left", "look_right", "happy"),
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

    /** Vom Nutzer weggetippt: ausgeblendet bis zur nächsten neuen Aktivität bzw. One-Shot. */
    var dismissed: Boolean = false
        private set

    private var activityEndedAt: Long? = null

    /** Für den nächsten Clip bereits ausgewürfelte idle-/accent-Szene (damit sync() nicht flackert). */
    private var idlePlan: String? = null
    private var lastWasAccent = false
    private var lastAccent: String? = null

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
        if (state != AssistState.IDLE) {
            pendingOneShot = null
            dismissed = false
        }
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
        dismissed = false
        return true
    }

    /** Nutzer hat den Avatar weggetippt: sofort ausblenden, kein Nachlauf. */
    fun dismiss() {
        dismissed = true
        pendingOneShot = null
        activityEndedAt = null
    }

    /** Vom Player: ein Clip der Szene [scene] ist gerade sichtbar geworden (Clipgrenze oder Crossfade). */
    fun onClipStarted(scene: String) {
        val now = clock()
        // Der vorherige Clip war ein One-Shot → ab jetzt läuft der Nachlauf.
        if (currentIsOneShot) activityEndedAt = now
        currentIsOneShot = pendingOneShot != null && scene == pendingOneShot
        if (currentIsOneShot) pendingOneShot = null
        currentScene = scene
        idlePlan = null
        lastWasAccent = scene in config.accents || currentIsOneShot
        if (scene in config.accents) lastAccent = scene
    }

    /** Vom Player: Wiedergabe beendet, Overlay ausgeblendet, Player freigegeben. */
    fun onPlaybackStopped() {
        if (currentIsOneShot) activityEndedAt = clock()
        currentScene = null
        currentIsOneShot = false
    }

    // ── Ausgänge ────────────────────────────────────────────────────────────────────────────

    /** Soll das Overlay jetzt (weiter) sichtbar sein? */
    fun shouldBeVisible(now: Long = clock()): Boolean = !dismissed && (currentIsOneShot || visibleAt(now))

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
        if (!isPlaying || dismissed) return null
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
        return idleOrAccent()
    }

    /**
     * Dringender Sofortwechsel (Crossfade) statt Warten auf die Clipgrenze? Nur für listening
     * und wake_up und nur, wenn die Restlaufzeit über dem Schwellwert liegt.
     */
    fun urgentSwitch(remainingMs: Long): String? {
        val current = currentScene ?: return null
        if (dismissed || remainingMs <= config.urgentThresholdMs) return null
        var next = nextScene(remainingMs)
        // Zustandsclip (speaking …) und Aktivität vorbei, ohne Nachlauf: erst auf idle, dort wird ausgeblendet.
        if (next == null && current in STATE_SCENES && assistState == AssistState.IDLE) next = resolve(AvatarScenes.IDLE)
        if (next == null || next == current) return null
        return if (isUrgent(current, next)) next else null
    }

    /**
     * Zustandsclips (listening/thinking/speaking) müssen dem Zustand sofort folgen – sonst
     * spricht der Mund weiter, obwohl die Antwort vorbei ist, oder thinking/speaking werden
     * bei ~10 s langen Clips komplett übersprungen. idle, Bewegungen und One-Shots weichen
     * einer Aktivität. Ein- und Ausschlafen wird nie abgebrochen (wake_up nur aus dem sleep-Loop).
     */
    private fun isUrgent(current: String, next: String): Boolean {
        if (current == AvatarScenes.WAKE_UP || current == AvatarScenes.SLEEP_IN) return false
        if (next == AvatarScenes.WAKE_UP) return current == AvatarScenes.SLEEP
        if (current in STATE_SCENES) return true
        return next in STATE_SCENES
    }

    /** Wahrscheinlichster nächster Sofortwechsel – der zweite Player lädt diesen Clip vor. */
    fun likelyUrgentScene(): String = when (currentScene) {
        AvatarScenes.SLEEP_IN, AvatarScenes.SLEEP -> AvatarScenes.WAKE_UP
        AvatarScenes.LISTENING -> AvatarScenes.THINKING
        AvatarScenes.THINKING -> AvatarScenes.SPEAKING
        AvatarScenes.SPEAKING -> AvatarScenes.IDLE
        else -> AvatarScenes.LISTENING
    }

    // ── intern ──────────────────────────────────────────────────────────────────────────────

    private fun visibleAt(t: Long): Boolean {
        if (dismissed) return false
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

    /** idle oder – mit Wahrscheinlichkeit [Config.accentChance] – eine Bewegung; nie zwei hintereinander. */
    private fun idleOrAccent(): String? {
        idlePlan?.let { return it }
        val idle = resolve(AvatarScenes.IDLE)
        val candidates = config.accents.filter { available(it) && it != lastAccent }
            .ifEmpty { config.accents.filter { available(it) } }
        val plan = if (idle == null || lastWasAccent || candidates.isEmpty() ||
            random.nextFloat() >= config.accentChance
        ) idle else candidates[random.nextInt(candidates.size)]
        idlePlan = plan
        return plan
    }

    private fun resolve(scene: String): String? = when {
        available(scene) -> scene
        available(AvatarScenes.IDLE) -> AvatarScenes.IDLE
        else -> null
    }

    companion object {
        val STATE_SCENES = setOf(AvatarScenes.LISTENING, AvatarScenes.THINKING, AvatarScenes.SPEAKING)
    }
}
