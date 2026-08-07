package cc.quandel.dashvoice

import cc.quandel.dashvoice.util.AppLog as Log
import org.json.JSONArray
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Holt die steuerbaren HA-Entitäten (friendly_name) per REST und cached sie — als Wörterbuch
 * für das Fuzzy-Matching der lokal (Vosk) erkannten Befehle auf echte Gerätenamen.
 *
 * Respektiert die Assist-Expose-Einstellung aus der Entity-Registry:
 * - Scripte: nur wenn explizit should_expose=true (direkte Service-Aufrufe, kein NLU-Schutz)
 * - Andere: ausgeschlossen nur wenn explizit should_expose=false
 */
class HaEntities(private val haUrl: String, private val token: String) {

    @Volatile var names: List<String> = emptyList()
        private set

    /** friendly_name.lowercase() → entity_id für alle script.*-Entitäten. Für direkten Service-Aufruf. */
    @Volatile var scriptIds: Map<String, String> = emptyMap()
        private set

    fun refresh() {
        if (token.isBlank()) return
        try {
            val base = haUrl.trimEnd('/')

            // Entity-Registry für Assist-Exposure-Info (null = Fetch fehlgeschlagen → kein Filter)
            val exposure = fetchExposure(base)

            val conn = URL("$base/api/states").openConnection() as HttpsURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val out = ArrayList<String>(128)
            val scripts = HashMap<String, String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("entity_id")
                val domain = id.substringBefore('.')
                val fn = o.optJSONObject("attributes")?.optString("friendly_name")?.trim()
                if (fn.isNullOrEmpty()) continue

                if (domain == "script") {
                    // Scripte: nur wenn Assist-Expose explizit true — direkte Service-Aufrufe
                    // umgehen HAs NLU-Schutz, daher strenger Filter nötig.
                    if (exposure == null || exposure[id] == true) {
                        scripts[fn.lowercase()] = id
                    }
                } else if (domain in CONTROLLABLE) {
                    // Andere: ausschließen nur wenn explizit false gesetzt
                    if (exposure == null || exposure[id] != false) {
                        out.add(fn.lowercase())
                    }
                }
            }
            names = out.distinct()
            scriptIds = scripts
            Log.i(TAG, "Entitäten geladen: ${names.size} (Scripte: ${scripts.size})" +
                if (exposure == null) " [kein Exposure-Filter]" else "")
        } catch (e: Exception) {
            Log.w(TAG, "Entity-Fetch: ${e.message}")
        }
    }

    /**
     * Liest die Assist-Expose-Einstellung aus der HA Entity-Registry.
     * Gibt Map<entity_id, should_expose> zurück, oder null bei Fehler (→ kein Filter).
     */
    private fun fetchExposure(base: String): Map<String, Boolean>? {
        return try {
            val conn = URL("$base/api/entity_registry").openConnection() as HttpsURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val map = HashMap<String, Boolean>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("entity_id")
                val conv = o.optJSONObject("options")?.optJSONObject("conversation") ?: continue
                // should_expose nur wenn explizit gesetzt (optBoolean default wäre falsch)
                if (conv.has("should_expose")) map[id] = conv.getBoolean("should_expose")
            }
            Log.i(TAG, "Entity-Registry: ${arr.length()} Einträge, ${map.size} mit expliziter Expose-Konfiguration")
            map
        } catch (e: Exception) {
            Log.w(TAG, "Entity-Registry-Fetch fehlgeschlagen: ${e.message} — kein Exposure-Filter")
            null
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
