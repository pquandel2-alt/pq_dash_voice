package cc.quandel.dashvoice.avatar

import android.os.Handler
import android.os.Looper
import cc.quandel.dashvoice.util.AppLog as Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Schlanker Home-Assistant-WebSocket-Client für den Avatar.
 *
 * Ablauf: auth → get_states (alle Entitäten mit [satellitePrefix] finden) → subscribe_entities
 * gezielt auf diese Satelliten + [extraEntities]. Neue/entfernte Satelliten werden über
 * `entity_registry_updated` erkannt und die Subscription neu aufgebaut. Bei Verbindungsabbruch
 * automatischer Reconnect mit exponentiellem Backoff. Alle Callbacks kommen im Main-Thread an.
 */
class HaWebSocketClient(
    private val baseUrl: String,
    private val token: String,
    private val satellitePrefix: String,
    private val extraEntities: List<String>,
    private val listener: Listener,
) {
    interface Listener {
        /** Vollständiger Anfangszustand nach (Re-)Subscribe – ersetzt alles Bisherige. */
        fun onSnapshot(updates: List<HaMessages.StateUpdate>)
        fun onUpdates(updates: List<HaMessages.StateUpdate>)
        fun onConnectionChanged(connected: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var socket: WebSocket? = null
    private var generation = 0
    private var stopped = true
    private var backoffMs = INITIAL_BACKOFF_MS
    private var nextId = 1
    private val pending = HashMap<Int, String>()
    private var entitySubscriptionId = -1
    private var subscribedIds: Set<String> = emptySet()
    private var awaitingSnapshot = false

    private val reconnect = Runnable { connect() }

    fun start() {
        synchronized(lock) {
            if (!stopped) return
            stopped = false
        }
        connect()
    }

    fun stop() {
        main.removeCallbacks(reconnect)
        val ws: WebSocket?
        synchronized(lock) {
            stopped = true
            generation++
            ws = socket
            socket = null
        }
        ws?.close(1000, "stop")
        main.post { listener.onConnectionChanged(false) }
    }

    private fun connect() {
        val gen: Int
        synchronized(lock) {
            if (stopped) return
            generation++
            gen = generation
            pending.clear()
            entitySubscriptionId = -1
            subscribedIds = emptySet()
        }
        val url = HaMessages.webSocketUrl(baseUrl)
        Log.i(TAG, "Verbinde $url")
        val request = Request.Builder().url(url).build()
        val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isCurrent(gen)) handleMessage(webSocket, text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (isCurrent(gen)) onDisconnected("geschlossen ($code $reason)")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (isCurrent(gen)) onDisconnected(t.message ?: t.javaClass.simpleName)
            }
        })
        synchronized(lock) {
            if (gen == generation) socket = ws else ws.cancel()
        }
    }

    private fun isCurrent(gen: Int) = synchronized(lock) { gen == generation && !stopped }

    private fun onDisconnected(reason: String) {
        val delay: Long
        synchronized(lock) {
            socket = null
            generation++
            delay = backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        Log.w(TAG, "Verbindung verloren: $reason – Reconnect in ${delay / 1000}s")
        main.post { listener.onConnectionChanged(false) }
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, delay)
    }

    private fun send(ws: WebSocket, kind: String?, payload: JSONObject) {
        synchronized(lock) {
            val id = nextId++
            payload.put("id", id)
            if (kind != null) pending[id] = kind
        }
        ws.send(payload.toString())
    }

    private fun handleMessage(ws: WebSocket, text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        when (msg.optString("type")) {
            "auth_required" -> ws.send(
                JSONObject().put("type", "auth").put("access_token", token).toString()
            )
            "auth_ok" -> {
                Log.i(TAG, "Authentifiziert (HA ${msg.optString("ha_version")})")
                synchronized(lock) { backoffMs = INITIAL_BACKOFF_MS }
                main.post { listener.onConnectionChanged(true) }
                send(ws, KIND_STATES, JSONObject().put("type", "get_states"))
                send(
                    ws, KIND_REGISTRY,
                    JSONObject().put("type", "subscribe_events").put("event_type", "entity_registry_updated"),
                )
            }
            "auth_invalid" -> {
                Log.e(TAG, "Token ungültig: ${msg.optString("message")}")
                synchronized(lock) { backoffMs = MAX_BACKOFF_MS }
                ws.close(1000, "auth_invalid")
            }
            "result" -> handleResult(ws, msg)
            "event" -> handleEvent(ws, msg)
        }
    }

    private fun handleResult(ws: WebSocket, msg: JSONObject) {
        val kind = synchronized(lock) { pending.remove(msg.optInt("id")) } ?: return
        if (!msg.optBoolean("success", false)) {
            Log.w(TAG, "$kind fehlgeschlagen: ${msg.optJSONObject("error")}")
            return
        }
        if (kind == KIND_STATES) {
            val states = msg.optJSONArray("result") ?: JSONArray()
            val ids = (HaMessages.entityIdsWithPrefix(states, satellitePrefix) + extraEntities)
                .filter { it.isNotBlank() }
                .toSortedSet()
            subscribeEntities(ws, ids)
        }
    }

    private fun subscribeEntities(ws: WebSocket, ids: Set<String>) {
        val oldId: Int
        synchronized(lock) {
            if (ids == subscribedIds && entitySubscriptionId > 0) return
            oldId = entitySubscriptionId
            subscribedIds = ids
            awaitingSnapshot = true
        }
        if (oldId > 0) {
            send(ws, null, JSONObject().put("type", "unsubscribe_events").put("subscription", oldId))
        }
        Log.i(TAG, "Beobachte ${ids.size} Entitäten: ${ids.joinToString()}")
        val payload = JSONObject().put("type", "subscribe_entities").put("entity_ids", JSONArray(ids.toList()))
        synchronized(lock) {
            val id = nextId++
            payload.put("id", id)
            entitySubscriptionId = id
        }
        ws.send(payload.toString())
    }

    private fun handleEvent(ws: WebSocket, msg: JSONObject) {
        val event = msg.optJSONObject("event") ?: return
        val id = msg.optInt("id")
        val subId = synchronized(lock) { entitySubscriptionId }
        if (id == subId) {
            val updates = HaMessages.parseEntityEvent(event)
            val snapshot = synchronized(lock) {
                val first = awaitingSnapshot
                awaitingSnapshot = false
                first
            }
            main.post { if (snapshot) listener.onSnapshot(updates) else listener.onUpdates(updates) }
            return
        }
        // entity_registry_updated: neue/entfernte Satelliten → neu entdecken.
        val data = event.optJSONObject("data") ?: return
        val entityId = data.optString("entity_id")
        val action = data.optString("action")
        if (entityId.startsWith(satellitePrefix) && (action == "create" || action == "remove")) {
            Log.i(TAG, "Satellit $action: $entityId → Subscription aktualisieren")
            send(ws, KIND_STATES, JSONObject().put("type", "get_states"))
        }
    }

    companion object {
        private const val TAG = "AvatarWS"
        private const val KIND_STATES = "get_states"
        private const val KIND_REGISTRY = "registry"
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
