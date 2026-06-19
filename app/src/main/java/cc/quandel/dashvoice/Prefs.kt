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

    var wakeThreshold: Float
        get() = sp.getFloat(KEY_THRESHOLD, 0.5f)
        set(v) = sp.edit().putFloat(KEY_THRESHOLD, v).apply()

    var screensaverDelayMs: Long
        get() = sp.getLong(KEY_SCREENSAVER_DELAY, DEFAULT_SCREENSAVER_DELAY)
        set(v) = sp.edit().putLong(KEY_SCREENSAVER_DELAY, v).apply()

    var haToken: String
        get() = sp.getString(KEY_HA_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_HA_TOKEN, v).apply()

    var screensaverSensors: String
        get() = sp.getString(KEY_SCREENSAVER_SENSORS, "") ?: ""
        set(v) = sp.edit().putString(KEY_SCREENSAVER_SENSORS, v).apply()

    var ttsVolume: Int
        get() = sp.getInt(KEY_TTS_VOLUME, 80)
        set(v) = sp.edit().putInt(KEY_TTS_VOLUME, v).apply()

    companion object {
        const val DEFAULT_URL = "https://ha.quandel-home.cc"
        const val DEFAULT_PORT = 10700
        const val DEFAULT_NAME = "Wand-Tablet"
        const val DEFAULT_WAKE = "ok_nabu"
        const val DEFAULT_SCREENSAVER_DELAY = 120_000L

        private const val KEY_URL = "url"
        private const val KEY_PORT = "port"
        private const val KEY_NAME = "name"
        private const val KEY_WAKE = "wake"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_SCREENSAVER_DELAY = "screensaver_delay"
        private const val KEY_HA_TOKEN = "ha_token"
        private const val KEY_SCREENSAVER_SENSORS = "screensaver_sensors"
        private const val KEY_TTS_VOLUME = "tts_volume"
    }
}
