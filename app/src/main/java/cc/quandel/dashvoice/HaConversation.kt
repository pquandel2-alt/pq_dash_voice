package cc.quandel.dashvoice

import cc.quandel.dashvoice.util.AppLog as Log
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Ruft HAs `conversation/process` direkt per REST auf — für Sprachverarbeitung via
 * HomeIntent, Whisper oder andere Conversation Agents.
 *
 * Synchron (blockierend) — vom Aufrufer auf einem Background-Thread nutzen.
 * Muster (HttpsURLConnection + Bearer-Token) aus [HaSensorFetcher] übernommen.
 */
class HaConversation(private val haUrl: String, private val token: String) {

    /** responseType: action_done | query_answer | error … ; ok = Befehl ausgeführt. */
    data class Result(val responseType: String, val speech: String, val conversationId: String = "") {
        val ok: Boolean get() = responseType == "action_done"
    }

    fun process(
        text: String,
        conversationId: String? = null,
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
            if (!conversationId.isNullOrBlank()) {
                body.put("conversation_id", conversationId)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val resp = json.optJSONObject("response")
            val rtype = resp?.optString("response_type", "") ?: ""
            val speech = resp?.optJSONObject("speech")
                ?.optJSONObject("plain")?.optString("speech", "") ?: ""
            val respConvId = json.optString("conversation_id", "")
            Result(rtype, speech, respConvId)
        } catch (e: Exception) {
            Log.w(TAG, "conversation/process: ${e.message}")
            null
        }
    }

    /** Ruft ein HA-Script direkt per Service-API auf — umgeht die NLU-Pipeline. Gibt sofort zurück. */
    fun runScript(entityId: String): Result? {
        if (token.isBlank()) return null
        return try {
            val base = haUrl.trimEnd('/')
            val conn = URL("$base/api/services/script/turn_on").openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 4000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.outputStream.use {
                it.write(JSONObject().put("entity_id", entityId).toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            conn.disconnect()
            Log.i(TAG, "runScript $entityId → HTTP $code")
            if (code in 200..299) Result("action_done", "") else null
        } catch (e: Exception) {
            Log.w(TAG, "runScript: ${e.message}")
            null
        }
    }

    companion object { private const val TAG = "HaConv" }
}
