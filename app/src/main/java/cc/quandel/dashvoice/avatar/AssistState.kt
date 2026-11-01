package cc.quandel.dashvoice.avatar

/** Zustand eines Home-Assistant-`assist_satellite`, geordnet nach Anzeige-Priorität (niedrig → hoch). */
enum class AssistState(val scene: String) {
    IDLE(AvatarScenes.IDLE),
    LISTENING(AvatarScenes.LISTENING),
    PROCESSING(AvatarScenes.THINKING),
    RESPONDING(AvatarScenes.SPEAKING);

    companion object {
        /** HA-Zustandstext → AssistState. Unbekannt/unavailable zählt als idle. */
        fun fromHa(state: String?): AssistState = when (state?.trim()?.lowercase()) {
            "listening" -> LISTENING
            "processing" -> PROCESSING
            "responding" -> RESPONDING
            else -> IDLE
        }

        /** Aggregat über alle Satelliten: responding > processing > listening > idle. */
        fun aggregate(states: Collection<AssistState>): AssistState =
            states.maxByOrNull { it.ordinal } ?: IDLE

        fun aggregateHa(states: Collection<String?>): AssistState =
            aggregate(states.map { fromHa(it) })
    }
}

/** Szenen-Namen der Clips (`<szene>_<n>.mp4`). */
object AvatarScenes {
    const val IDLE = "idle"
    const val LISTENING = "listening"
    const val THINKING = "thinking"
    const val SPEAKING = "speaking"
    const val SLEEP_IN = "sleep_in"
    const val SLEEP = "sleep"
    const val WAKE_UP = "wake_up"

    /** Bekannte Basisnamen, nach deren Varianten der Cache sucht. */
    val DEFAULT_BASES = listOf(
        "idle", "listening", "thinking",
        "speaking_a", "speaking_b", "speaking_c",
        "happy", "alert", "look_left", "look_right",
        "sleep_in", "sleep", "wake_up",
    )

    const val POSTER = "home.png"
}
