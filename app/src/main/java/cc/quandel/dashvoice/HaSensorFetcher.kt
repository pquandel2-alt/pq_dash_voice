package cc.quandel.dashvoice

import android.os.Handler
import android.os.Looper
import cc.quandel.dashvoice.util.AppLog as Log
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * Periodically fetches HA sensor states via REST API and delivers results on the main thread.
 * Usage: call start() when screensaver shows, stop() when dismissed.
 */
class HaSensorFetcher(
    private val haUrl: String,
    private val token: String,
    private val entityIds: List<String>,
    private val onResult: (List<SensorValue>) -> Unit
) {
    data class SensorValue(val name: String, val state: String, val unit: String)

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running || token.isBlank() || entityIds.isEmpty()) return
        running = true
        fetchAndSchedule()
    }

    fun stop() {
        running = false
        ui.removeCallbacksAndMessages(null)
        worker?.interrupt()
        worker = null
    }

    private fun fetchAndSchedule() {
        if (!running) return
        worker = thread(name = "ha-sensor-fetch") {
            val values = entityIds.mapNotNull { fetchOne(it) }
            if (running) ui.post { onResult(values) }
            if (running) ui.postDelayed({ fetchAndSchedule() }, POLL_INTERVAL_MS)
        }
    }

    private fun fetchOne(entityId: String): SensorValue? {
        return try {
            val base = haUrl.trimEnd('/')
            val conn = URL("$base/api/states/$entityId").openConnection() as HttpsURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val state = json.optString("state", "—")
            val attrs = json.optJSONObject("attributes")
            val unit = attrs?.optString("unit_of_measurement", "") ?: ""
            val friendlyName = attrs?.optString("friendly_name", entityId) ?: entityId
            SensorValue(friendlyName, state, unit)
        } catch (e: Exception) {
            Log.w(TAG, "fetch $entityId: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "HaSensorFetcher"
        private const val POLL_INTERVAL_MS = 60_000L
    }
}
