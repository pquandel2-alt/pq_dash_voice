package cc.quandel.dashvoice.particle

enum class ParticleState(val displayName: String) {
    IDLE("Idle"),
    ASSEMBLING("Assembling"),
    LISTENING("Listening"),
    THINKING("Thinking"),
    SPEAKING("Speaking"),
    SUCCESS("Success"),
    ERROR("Error"),
    WAKE("Wake"),
    OFF("Off");

    companion object {
        fun fromString(s: String): ParticleState? = values().find { it.name == s }
    }
}
