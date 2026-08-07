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

    var satelliteName: String
        get() = sp.getString(KEY_NAME, DEFAULT_NAME) ?: DEFAULT_NAME
        set(v) = sp.edit().putString(KEY_NAME, v).apply()

    var wakeWord: String
        get() = sp.getString(KEY_WAKE, DEFAULT_WAKE) ?: DEFAULT_WAKE
        set(v) = sp.edit().putString(KEY_WAKE, v).apply()

    /** Far-field-Default 0.40 (niedriger = empfindlicher). UI-Regler rechts = empfindlicher. */
    var wakeThreshold: Float
        get() = sp.getFloat(KEY_THRESHOLD, 0.4f)
        set(v) = sp.edit().putFloat(KEY_THRESHOLD, v).apply()

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

    /** On-Device-Sofortbefehle (Vosk, an Whisper vorbei). */
    var instantCommandsEnabled: Boolean
        get() = sp.getBoolean(KEY_INSTANT_CMD, true)
        set(v) = sp.edit().putBoolean(KEY_INSTANT_CMD, v).apply()

    /** Befehls-Verben: enthält das Vosk-Transkript eines davon → lokaler Befehlspfad. Komma-getrennt. */
    var commandVerbs: List<String>
        get() = (sp.getString(KEY_CMD_VERBS, DEFAULT_CMD_VERBS) ?: DEFAULT_CMD_VERBS)
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString(KEY_CMD_VERBS, v.joinToString(",")).apply()

    /** Follow-up: nach einer Antwort kurz weiter zuhören (ohne erneutes „Ok Nabu"). */
    var followUpEnabled: Boolean
        get() = sp.getBoolean(KEY_FOLLOWUP, true)
        set(v) = sp.edit().putBoolean(KEY_FOLLOWUP, v).apply()

    /** Barge-in: laufende Sprachausgabe durch Sprechen unterbrechen. */
    var bargeInEnabled: Boolean
        get() = sp.getBoolean(KEY_BARGEIN, true)
        set(v) = sp.edit().putBoolean(KEY_BARGEIN, v).apply()

    /** Lokale Timer/Wecker auf dem Tablet. */
    var timersEnabled: Boolean
        get() = sp.getBoolean(KEY_TIMERS, true)
        set(v) = sp.edit().putBoolean(KEY_TIMERS, v).apply()

    /** Ähnlichkeits-Schwelle (0..1) für das Fuzzy-Matching auf echte Gerätenamen. */
    var fuzzyThreshold: Float
        get() = sp.getFloat(KEY_FUZZY, 0.55f)
        set(v) = sp.edit().putFloat(KEY_FUZZY, v).apply()

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

    companion object {
        const val DEFAULT_URL = "https://ha.quandel-home.cc"
        const val DEFAULT_PORT = 10700
        const val DEFAULT_NAME = "Wand-Tablet"
        const val DEFAULT_WAKE = "ok_nabu"
        const val DEFAULT_SCREENSAVER_DELAY = 120_000L
        // Befehls-Verben (de). Bewusst eher spezifisch, um Fragen nicht fälschlich als Befehl zu werten.
        const val DEFAULT_CMD_VERBS =
            "schalt,einschalt,ausschalt,mach an,mach aus,aktivier,deaktivier,starte,start ,stoppe,stopp," +
            "dimm,öffne,schließe,fahr,runterfahren,hochfahren,herunterfahren,heize,stelle"

        private const val KEY_URL = "url"
        private const val KEY_PORT = "port"
        private const val KEY_NAME = "name"
        private const val KEY_WAKE = "wake"
        private const val KEY_THRESHOLD = "threshold"
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
        private const val KEY_INSTANT_CMD = "instant_commands"
        private const val KEY_CMD_VERBS = "command_verbs"
        private const val KEY_FOLLOWUP = "followup_enabled"
        private const val KEY_BARGEIN = "bargein_enabled"
        private const val KEY_TIMERS = "timers_enabled"
        private const val KEY_FUZZY = "fuzzy_threshold"
        private const val KEY_TIMER_END = "timer_end_at"
        private const val KEY_DOORBELL_ENTITY = "doorbell_entity"
        private const val KEY_DOORBELL_URL = "doorbell_url"
        private const val KEY_DOORBELL_DISMISS = "doorbell_dismiss"
    }
}
