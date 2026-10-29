package cc.quandel.dashvoice

import android.content.Context
import android.content.SharedPreferences

/** Simple SharedPreferences-backed configuration store. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("dashvoice", Context.MODE_PRIVATE)

    var dashboardUrl: String
        get() = sp.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(v) = sp.edit().putString(KEY_URL, v).apply()

    var satellitePort: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT)
        set(v) = sp.edit().putInt(KEY_PORT, v).apply()

    /**
     * Tablet als eigener Wyoming-Sprachsatellit (Mikrofon, Wake Word, Tap-to-Talk, TTS).
     * Aus = Tablet hört nicht zu; der Video-Avatar zeigt nur die anderen Satelliten.
     */
    var satelliteEnabled: Boolean
        get() = sp.getBoolean(KEY_SATELLITE_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_SATELLITE_ENABLED, v).apply()

    var satelliteName: String
        get() = sp.getString(KEY_NAME, DEFAULT_NAME) ?: DEFAULT_NAME
        set(v) = sp.edit().putString(KEY_NAME, v).apply()

    /** Software-Mikrofon-Verstärkung (1.0–6.0) als Ersatz für fehlendes Hardware-AGC. 1.0 = unverändert. */
    var micGain: Float
        get() = sp.getFloat(KEY_MIC_GAIN, 1.0f)
        set(v) = sp.edit().putFloat(KEY_MIC_GAIN, v).apply()

    /** NoiseSuppressor aktiv. Abschaltbar als Far-field-Test (NS kann entfernte Sprache dämpfen). */
    var noiseSuppressionEnabled: Boolean
        get() = sp.getBoolean(KEY_NOISE_SUPPRESSION, true)
        set(v) = sp.edit().putBoolean(KEY_NOISE_SUPPRESSION, v).apply()

    var screensaverDelayMs: Long
        get() = sp.getLong(KEY_SCREENSAVER_DELAY, DEFAULT_SCREENSAVER_DELAY)
        set(v) = sp.edit().putLong(KEY_SCREENSAVER_DELAY, v).apply()

    var haToken: String
        get() = sp.getString(KEY_HA_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_HA_TOKEN, v).apply()

    var screensaverSensors: String
        get() = sp.getString(KEY_SCREENSAVER_SENSORS, "") ?: ""
        set(v) = sp.edit().putString(KEY_SCREENSAVER_SENSORS, v).apply()

    /**
     * URL für den Brain-Graph-Screensaver (Live-3D-Graph vom HA-Add-on).
     * Leer = klassischer Uhr-Screensaver. Gesetzt (z. B. http://192.168.178.101:8099/?kiosk)
     * = Vollbild-Graph statt Uhr.
     */
    var screensaverBrainUrl: String
        get() = sp.getString(KEY_SAVER_BRAIN_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_SAVER_BRAIN_URL, v).apply()

    /**
     * Fester Kamera-Zoom-Abstand für den Brain-Graph-Screensaver (als &zoom=
     * an die Kiosk-URL angehängt). Leer = Frontend-Default (2200) verwenden.
     * Nötig, weil die Kräfte-Simulation auf Tablet-Hardware unvorhersehbar lang
     * braucht — ein fester, manuell getunter Wert ist sofort korrekt.
     */
    var screensaverZoomDistance: String
        get() = sp.getString(KEY_SAVER_ZOOM, "") ?: ""
        set(v) = sp.edit().putString(KEY_SAVER_ZOOM, v).apply()

    /**
     * Zeitfenster (HH:mm), in dem statt des Brain-Graph-Screensavers nur die Uhr gezeigt
     * wird (z. B. nachts, damit Graph/WebView nicht die ganze Nacht laufen). Leer = kein
     * Zeitfenster, Graph läuft immer, solange screensaverBrainUrl gesetzt ist. Darf über
     * Mitternacht hinausgehen (z. B. 22:00–06:00).
     */
    var screensaverClockFrom: String
        get() = sp.getString(KEY_SAVER_CLOCK_FROM, "") ?: ""
        set(v) = sp.edit().putString(KEY_SAVER_CLOCK_FROM, v).apply()

    var screensaverClockTo: String
        get() = sp.getString(KEY_SAVER_CLOCK_TO, "") ?: ""
        set(v) = sp.edit().putString(KEY_SAVER_CLOCK_TO, v).apply()

    /**
     * Optionale HA-Entität (z. B. ein input_boolean), die per Automation gesetzt werden kann,
     * um die Uhr/Graph-Auswahl unabhängig vom Zeitfenster zu erzwingen: Zustand "on" erzwingt
     * die Uhr, jeder andere Zustand erzwingt den Graph (falls screensaverBrainUrl gesetzt ist).
     * Leer = deaktiviert, es gilt weiterhin nur das Zeitfenster.
     */
    var screensaverClockEntity: String
        get() = sp.getString(KEY_SAVER_CLOCK_ENTITY, "") ?: ""
        set(v) = sp.edit().putString(KEY_SAVER_CLOCK_ENTITY, v).apply()

    /**
     * Gestensteuerung im Brain-Graph-Screensaver (Handtracking über die Frontkamera,
     * MediaPipe läuft rein clientseitig im Frontend). Default aus — verlangt eine
     * CAMERA-Permission, die nur bei Aktivierung angefragt wird.
     */
    var screensaverGesturesEnabled: Boolean
        get() = sp.getBoolean(KEY_SAVER_GESTURES, false)
        set(v) = sp.edit().putBoolean(KEY_SAVER_GESTURES, v).apply()

    var ttsVolume: Int
        get() = sp.getInt(KEY_TTS_VOLUME, 80)
        set(v) = sp.edit().putInt(KEY_TTS_VOLUME, v).apply()

    var animationStyle: Int
        get() = sp.getInt(KEY_ANIMATION_STYLE, 6)   // Default: Gesicht/Blase
        set(v) = sp.edit().putInt(KEY_ANIMATION_STYLE, v).apply()

    /** Ablaufzeitpunkt (epoch ms) des aktiven lokalen Timers; 0 = keiner. Für die Display-Anzeige. */
    var timerEndAt: Long
        get() = sp.getLong(KEY_TIMER_END, 0L)
        set(v) = sp.edit().putLong(KEY_TIMER_END, v).apply()

    /** HA-Entität für Klingel-Trigger (z. B. input_boolean.klingel_kamera). Leer = deaktiviert. */
    var doorbellEntity: String
        get() = sp.getString(KEY_DOORBELL_ENTITY, "") ?: ""
        set(v) = sp.edit().putString(KEY_DOORBELL_ENTITY, v).apply()

    /** URL der Kamera-Ansicht (z. B. Lovelace mit Kamera-Karte). */
    var doorbellCameraUrl: String
        get() = sp.getString(KEY_DOORBELL_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_DOORBELL_URL, v).apply()

    /** Auto-Dismiss nach Sekunden (Default: 30). */
    var doorbellAutoDismissSec: Int
        get() = sp.getInt(KEY_DOORBELL_DISMISS, 30)
        set(v) = sp.edit().putInt(KEY_DOORBELL_DISMISS, v).apply()

    /** Zeigt den Partikel-Avatar während einer Assist-Interaktion über dem normalen Screensaver. */
    var enableParticleScreensaver: Boolean
        get() = sp.getBoolean(KEY_PARTICLE_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_PARTICLE_ENABLED, v).apply()

    /** Mindestdauer, für die der Assist-Avatar nach dem Wake Word sichtbar bleibt. */
    var particleAssistMinVisibleMs: Long
        get() = sp.getLong(KEY_PARTICLE_ASSIST_MIN_VISIBLE, DEFAULT_PARTICLE_ASSIST_MIN_VISIBLE_MS)
        set(v) = sp.edit().putLong(KEY_PARTICLE_ASSIST_MIN_VISIBLE, v).apply()

    /** Partikel-Qualität: AUTO, LOW, MEDIUM, HIGH. */
    var particleQuality: String
        get() = sp.getString(KEY_PARTICLE_QUALITY, "AUTO") ?: "AUTO"
        set(v) = sp.edit().putString(KEY_PARTICLE_QUALITY, v).apply()

    /** Zeigt das Sprach-Transkript als Overlay im Partikel-Screensaver. */
    var particleShowTranscript: Boolean
        get() = sp.getBoolean(KEY_PARTICLE_SHOW_TRANSCRIPT, true)
        set(v) = sp.edit().putBoolean(KEY_PARTICLE_SHOW_TRANSCRIPT, v).apply()

    /** Zeigt die Assistenten-Antwort als Overlay im Partikel-Screensaver. */
    var particleShowResponse: Boolean
        get() = sp.getBoolean(KEY_PARTICLE_SHOW_RESPONSE, true)
        set(v) = sp.edit().putBoolean(KEY_PARTICLE_SHOW_RESPONSE, v).apply()

    /** Assembly-Animation (Zusammensetzen aus Chaos) an/aus. Aus = Figur erscheint sofort fertig. */
    var particleAssemblyAnimEnabled: Boolean
        get() = sp.getBoolean(KEY_PARTICLE_ASSEMBLY_ANIM, true)
        set(v) = sp.edit().putBoolean(KEY_PARTICLE_ASSEMBLY_ANIM, v).apply()

    /** Geschwindigkeitsfaktor der Partikel-Animation (0.5–2.0×, 1.0 = normal). */
    var particleAnimationSpeed: Float
        get() = sp.getFloat(KEY_PARTICLE_ANIM_SPEED, 1.0f)
        set(v) = sp.edit().putFloat(KEY_PARTICLE_ANIM_SPEED, v).apply()

    // ── KI-Video-Avatar ──────────────────────────────────────────────────────────────────

    /** Video-Avatar über der UI, gesteuert über alle assist_satellite-Entitäten in HA. */
    var avatarEnabled: Boolean
        get() = sp.getBoolean(KEY_AVATAR_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_AVATAR_ENABLED, v).apply()

    /** Pfad der Clips relativ zur HA-Basis-URL (www/avatar → /local/avatar/). */
    var avatarClipPath: String
        get() = sp.getString(KEY_AVATAR_PATH, DEFAULT_AVATAR_PATH) ?: DEFAULT_AVATAR_PATH
        set(v) = sp.edit().putString(KEY_AVATAR_PATH, v).apply()

    var avatarSatellitePrefix: String
        get() = sp.getString(KEY_AVATAR_PREFIX, DEFAULT_AVATAR_PREFIX) ?: DEFAULT_AVATAR_PREFIX
        set(v) = sp.edit().putString(KEY_AVATAR_PREFIX, v).apply()

    var avatarNightEntity: String
        get() = sp.getString(KEY_AVATAR_NIGHT, DEFAULT_AVATAR_NIGHT) ?: DEFAULT_AVATAR_NIGHT
        set(v) = sp.edit().putString(KEY_AVATAR_NIGHT, v).apply()

    var avatarClipEntity: String
        get() = sp.getString(KEY_AVATAR_CLIP_ENTITY, DEFAULT_AVATAR_CLIP_ENTITY) ?: DEFAULT_AVATAR_CLIP_ENTITY
        set(v) = sp.edit().putString(KEY_AVATAR_CLIP_ENTITY, v).apply()

    /** Satelliten, die den Avatar nie auslösen (kommagetrennte Entity-IDs). */
    var avatarIgnoredSatellites: String
        get() = sp.getString(KEY_AVATAR_IGNORED, "") ?: ""
        set(v) = sp.edit().putString(KEY_AVATAR_IGNORED, v).apply()

    /** Zusätzliche Szenen-Basisnamen (z. B. "wave") oder Dateinamen (z. B. "party.mp4"), kommagetrennt. */
    var avatarExtraClips: String
        get() = sp.getString(KEY_AVATAR_EXTRA, "") ?: ""
        set(v) = sp.edit().putString(KEY_AVATAR_EXTRA, v).apply()

    /** Nachlauf (idle-Loop) nach der letzten Aktivität, bevor ausgeblendet wird. */
    var avatarLingerMs: Long
        get() = sp.getLong(KEY_AVATAR_LINGER, 4_000L)
        set(v) = sp.edit().putLong(KEY_AVATAR_LINGER, v).apply()

    /** Restlaufzeit, ab der dringende Wechsel (listening, wake_up) sofort per Crossfade erfolgen. */
    var avatarUrgentThresholdMs: Long
        get() = sp.getLong(KEY_AVATAR_URGENT, 600L)
        set(v) = sp.edit().putLong(KEY_AVATAR_URGENT, v).apply()

    var avatarCrossfadeMs: Long
        get() = sp.getLong(KEY_AVATAR_CROSSFADE, 200L)
        set(v) = sp.edit().putLong(KEY_AVATAR_CROSSFADE, v).apply()

    /** Frames, die am Clipende übersprungen werden (gegen doppelten Home-Frame an der Naht). */
    var avatarTrimFrames: Int
        get() = sp.getInt(KEY_AVATAR_TRIM, 1)
        set(v) = sp.edit().putInt(KEY_AVATAR_TRIM, v).apply()

    /** "16:9" oder "9:16". */
    var avatarAspect: String
        get() = sp.getString(KEY_AVATAR_ASPECT, "16:9") ?: "16:9"
        set(v) = sp.edit().putString(KEY_AVATAR_ASPECT, v).apply()

    /** Panelgröße in Prozent der verfügbaren Fläche. */
    var avatarSizePercent: Int
        get() = sp.getInt(KEY_AVATAR_SIZE, 100)
        set(v) = sp.edit().putInt(KEY_AVATAR_SIZE, v).apply()

    var avatarAlwaysShow: Boolean
        get() = sp.getBoolean(KEY_AVATAR_ALWAYS, false)
        set(v) = sp.edit().putBoolean(KEY_AVATAR_ALWAYS, v).apply()

    companion object {
        const val DEFAULT_URL = "https://ha.quandel-home.cc"
        const val DEFAULT_PORT = 10700
        const val DEFAULT_NAME = "Wand-Tablet"
        const val DEFAULT_SCREENSAVER_DELAY = 120_000L
        const val DEFAULT_PARTICLE_ASSIST_MIN_VISIBLE_MS = 5_000L
        const val DEFAULT_AVATAR_PATH = "/local/avatar/"
        const val DEFAULT_AVATAR_PREFIX = "assist_satellite."
        const val DEFAULT_AVATAR_NIGHT = "input_boolean.avatar_nacht"
        const val DEFAULT_AVATAR_CLIP_ENTITY = "input_text.avatar_clip"

        private const val KEY_URL = "url"
        private const val KEY_PORT = "port"
        private const val KEY_NAME = "name"
        private const val KEY_MIC_GAIN = "mic_gain"
        private const val KEY_NOISE_SUPPRESSION = "noise_suppression"
        private const val KEY_SCREENSAVER_DELAY = "screensaver_delay"
        private const val KEY_HA_TOKEN = "ha_token"
        private const val KEY_SCREENSAVER_SENSORS = "screensaver_sensors"
        private const val KEY_SAVER_BRAIN_URL = "saver_brain_url"
        private const val KEY_SAVER_ZOOM = "saver_zoom_distance"
        private const val KEY_SAVER_CLOCK_FROM = "saver_clock_from"
        private const val KEY_SAVER_CLOCK_TO = "saver_clock_to"
        private const val KEY_SAVER_CLOCK_ENTITY = "saver_clock_entity"
        private const val KEY_SAVER_GESTURES = "saver_gestures_enabled"
        private const val KEY_TTS_VOLUME = "tts_volume"
        private const val KEY_ANIMATION_STYLE = "animation_style"
        private const val KEY_TIMER_END = "timer_end_at"
        private const val KEY_DOORBELL_ENTITY = "doorbell_entity"
        private const val KEY_DOORBELL_URL = "doorbell_url"
        private const val KEY_DOORBELL_DISMISS = "doorbell_dismiss"
        private const val KEY_PARTICLE_ENABLED = "particle_screensaver_enabled"
        private const val KEY_PARTICLE_ASSIST_MIN_VISIBLE = "particle_assist_min_visible"
        private const val KEY_PARTICLE_QUALITY = "particle_quality"
        private const val KEY_PARTICLE_SHOW_TRANSCRIPT = "particle_show_transcript"
        private const val KEY_PARTICLE_SHOW_RESPONSE = "particle_show_response"
        private const val KEY_PARTICLE_ASSEMBLY_ANIM = "particle_assembly_anim"
        private const val KEY_PARTICLE_ANIM_SPEED = "particle_anim_speed"
        private const val KEY_AVATAR_ENABLED = "avatar_enabled"
        private const val KEY_AVATAR_IGNORED = "avatar_ignored_satellites"
        private const val KEY_SATELLITE_ENABLED = "satellite_enabled"
        private const val KEY_AVATAR_PATH = "avatar_clip_path"
        private const val KEY_AVATAR_PREFIX = "avatar_satellite_prefix"
        private const val KEY_AVATAR_NIGHT = "avatar_night_entity"
        private const val KEY_AVATAR_CLIP_ENTITY = "avatar_clip_entity"
        private const val KEY_AVATAR_EXTRA = "avatar_extra_clips"
        private const val KEY_AVATAR_LINGER = "avatar_linger_ms"
        private const val KEY_AVATAR_URGENT = "avatar_urgent_threshold_ms"
        private const val KEY_AVATAR_CROSSFADE = "avatar_crossfade_ms"
        private const val KEY_AVATAR_TRIM = "avatar_trim_frames"
        private const val KEY_AVATAR_ASPECT = "avatar_aspect"
        private const val KEY_AVATAR_SIZE = "avatar_size_percent"
        private const val KEY_AVATAR_ALWAYS = "avatar_always_show"
    }
}
