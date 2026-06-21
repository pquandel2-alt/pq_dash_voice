package cc.quandel.dashvoice

import cc.quandel.dashvoice.util.AppLog as Log
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Ruft HAs `conversation/process` direkt per REST auf — für lokale Sofort-Befehle, die
 * on-device (Vosk) erkannt wurden und an Whisper vorbei ausgeführt werden sollen.
 *
 * Synchron (blockierend) — vom Aufrufer auf einem Background-Thread nutzen.
 * Muster (HttpsURLConnection + Bearer-Token) aus [HaSensorFetcher] übernommen.
 */
class HaConversation(private val haUrl: String, private val token: String) {

    /** responseType: action_done | query_answer | error … ; ok = Befehl ausgeführt. */
    data class Result(val responseType: String, val speech: String) {
        val ok: Boolean get() = responseType == "action_done"
    }

    fun process(
        text: String,
        agentId: String = "conversation.home_assistant",
        language: String = "de"
    ): Result? {
        if (token.isBlank() || text.isBlank()) return null
        return try {
            val base = haUrl.trimEnd('/')
            val conn = URL("$base/api/conversation/process").openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.doOutput = true
            val body = JSONObject()
                .put("text", text)
                .put("agent_id", agentId)
                .put("language", language)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val resp = json.optJSONObject("response")
            val rtype = resp?.optString("response_type", "") ?: ""
            val speech = resp?.optJSONObject("speech")
                ?.optJSONObject("plain")?.optString("speech", "") ?: ""
            Result(rtype, speech)
        } catch (e: Exception) {
            Log.w(TAG, "conversation/process: ${e.message}")
            null
        }
    }

    companion object { private const val TAG = "HaConv" }
}
