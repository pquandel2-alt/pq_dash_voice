package cc.quandel.dashvoice.avatar

import org.json.JSONArray
import org.json.JSONObject

/** Parser für die (komprimierten) HA-WebSocket-Nachrichten, die der Avatar braucht. */
object HaMessages {

    /** Ein Zustands-Update. [initial] = Teil des Anfangs-Snapshots („a“), keine echte Änderung. */
    data class StateUpdate(val entityId: String, val state: String?, val initial: Boolean)

    /**
     * Wertet ein `subscribe_entities`-Event aus:
     * `{"a": {id: {"s": ...}}, "c": {id: {"+": {"s": ...}}}, "r": [id, ...]}`.
     * Entfernte Entitäten werden mit state = null gemeldet. Änderungen ohne neuen Zustand
     * (nur Attribute/Zeitstempel) werden ignoriert.
     */
    fun parseEntityEvent(event: JSONObject): List<StateUpdate> {
        val out = ArrayList<StateUpdate>()
        event.optJSONObject("a")?.let { added ->
            for (id in added.keys()) {
                val s = added.optJSONObject(id)?.opt("s")
                out += StateUpdate(id, s?.toString(), initial = true)
            }
        }
        event.optJSONObject("c")?.let { changed ->
            for (id in changed.keys()) {
                val plus = changed.optJSONObject(id)?.optJSONObject("+") ?: continue
                if (!plus.has("s")) continue
                out += StateUpdate(id, plus.opt("s")?.toString(), initial = false)
            }
        }
        event.optJSONArray("r")?.let { removed ->
            for (i in 0 until removed.length()) {
                out += StateUpdate(removed.optString(i), null, initial = false)
            }
        }
        return out
    }

    /** Entity-IDs aus einem `get_states`-Ergebnis, die mit [prefix] beginnen. */
    fun entityIdsWithPrefix(states: JSONArray, prefix: String): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until states.length()) {
            val id = states.optJSONObject(i)?.optString("entity_id").orEmpty()
            if (id.startsWith(prefix)) out += id
        }
        return out
    }

    /** Szene aus einem input_text-Wert „<szene> <zeitstempel>“. */
    fun sceneFromClipValue(value: String?): String? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty() || v == "unknown" || v == "unavailable") return null
        return v.substringBefore(' ').trim().lowercase().ifEmpty { null }
    }

    /** HA-Basis-URL (scheme://host[:port]) aus einer beliebigen Dashboard-URL. */
    fun baseUrl(dashboardUrl: String): String {
        val m = Regex("^(https?://[^/?#]+)", RegexOption.IGNORE_CASE).find(dashboardUrl.trim())
        return m?.groupValues?.get(1) ?: dashboardUrl.trim().trimEnd('/')
    }

    fun webSocketUrl(baseUrl: String): String {
        val b = baseUrl.trimEnd('/')
        return when {
            b.startsWith("https://", ignoreCase = true) -> "wss://" + b.substring(8)
            b.startsWith("http://", ignoreCase = true) -> "ws://" + b.substring(7)
            else -> b
        } + "/api/websocket"
    }
}
