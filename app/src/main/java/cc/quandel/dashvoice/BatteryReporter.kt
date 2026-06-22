package cc.quandel.dashvoice

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import cc.quandel.dashvoice.util.AppLog as Log
import org.json.JSONObject
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * Meldet den Tablet-Akkustand (und Lade-Status) periodisch als HA-Entitäten — damit eine
 * HA-Automation z. B. ein Lade-Relais schalten kann (wie es die offizielle HA-App liefert).
 *
 * Erzeugt/aktualisiert per REST `POST /api/states/<entity>`:
 *   sensor.<slug>_akku        (Prozent, device_class battery)
 *   binary_sensor.<slug>_laedt (on/off, device_class battery_charging)
 */
class BatteryReporter(
    private val context: Context,
    private val haUrl: String,
    private val token: String,
    satelliteName: String
) {
    private val slug = satelliteName.lowercase(Locale.GERMAN)
        .replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "tablet" }
    private val levelEntity = "sensor.${slug}_akku"
    private val chargingEntity = "binary_sensor.${slug}_laedt"

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var running = false

    private val tick = object : Runnable {
        override fun run() {
            thread(name = "battery-report") { reportOnce() }
            if (running) handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        if (running || token.isBlank()) return
        running = true
        Log.i(TAG, "Akku-Reporter aktiv → $levelEntity / $chargingEntity")
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun reportOnce() {
        try {
            val bi: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val pct = (level * 100 / scale).coerceIn(0, 100)
            val status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            postState(
                levelEntity, pct.toString(),
                JSONObject()
                    .put("unit_of_measurement", "%")
                    .put("device_class", "battery")
                    .put("state_class", "measurement")
                    .put("friendly_name", "$slugTitle Akku")
            )
            postState(
                chargingEntity, if (charging) "on" else "off",
                JSONObject()
                    .put("device_class", "battery_charging")
                    .put("friendly_name", "$slugTitle lädt")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Akku-Report: ${e.message}")
        }
    }

    private val slugTitle get() = slug.replace('_', ' ')
        .split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun postState(entity: String, state: String, attributes: JSONObject) {
        try {
            val conn = URL("${haUrl.trimEnd('/')}/api/states/$entity").openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            val body = JSONObject().put("state", state).put("attributes", attributes)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) Log.w(TAG, "$entity → HTTP $code")
        } catch (e: Exception) {
            Log.w(TAG, "POST $entity: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Battery"
        private const val INTERVAL_MS = 60_000L
    }
}
