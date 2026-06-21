package cc.quandel.dashvoice

import cc.quandel.dashvoice.util.AppLog as Log
import org.json.JSONArray
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Holt die steuerbaren HA-Entitäten (friendly_name) per REST und cached sie — als Wörterbuch
 * für das Fuzzy-Matching der lokal (Vosk) erkannten Befehle auf echte Gerätenamen.
 */
class HaEntities(private val haUrl: String, private val token: String) {

    @Volatile var names: List<String> = emptyList()
        private set

    fun refresh() {
        if (token.isBlank()) return
        try {
            val base = haUrl.trimEnd('/')
            val conn = URL("$base/api/states").openConnection() as HttpsURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val out = ArrayList<String>(128)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("entity_id")
                val domain = id.substringBefore('.')
                if (domain !in CONTROLLABLE) continue
                val fn = o.optJSONObject("attributes")?.optString("friendly_name")?.trim()
                if (!fn.isNullOrEmpty()) out.add(fn.lowercase())
            }
            names = out.distinct()
            Log.i(TAG, "Entitäten geladen: ${names.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Entity-Fetch: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "HaEntities"
        private val CONTROLLABLE = setOf(
            "light", "switch", "scene", "script", "cover", "climate", "fan",
            "media_player", "input_boolean", "automation", "group", "vacuum",
            "lock", "humidifier", "button", "select", "input_select"
        )
    }
}
