package cc.quandel.dashvoice.wyoming

import cc.quandel.dashvoice.util.AppLog as Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Wyoming satellite endpoint. The satellite is a TCP **server**; Home Assistant's
 * "Wyoming Protocol" integration connects in (default port 10700).
 *
 * Behaviour follows rhasspy/wyoming-satellite's AlwaysStreamingSatellite: the microphone
 * is streamed to Home Assistant, HA performs wake-word detection, and reports the selected
 * Assist wake word back with a detection event.
 */
class WyomingServer(
    private val port: Int,
    private val satelliteName: String,
    private val listener: Listener
) {
    interface Listener {
        fun onRunSatellite()
        fun onPauseSatellite()
        fun onClientConnected()
        fun onClientDisconnected()
        fun onWakeDetected(name: String)
        fun onVoiceStopped()
        fun onTranscript(text: String)
        fun onSynthesize(text: String)
        fun onTtsAudioStart(rate: Int, width: Int, channels: Int)
        fun onTtsAudioChunk(pcm: ByteArray)
        fun onTtsAudioStop()
        fun onError(text: String)
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientOut: OutputStream? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var running = false
    private var acceptThread: Thread? = null

    val isConnected: Boolean get() = clientOut != null

    /** True solange der Server-Socket lebt und der Accept-Loop laufen soll (für den Watchdog). */
    val isListening: Boolean get() = running && serverSocket?.isClosed == false

    fun start() {
        if (running) return
        running = true
        acceptThread = thread(name = "wyoming-accept") {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                Log.i(TAG, "Listening on :$port")
                while (running) {
                    val sock = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "accept: ${e.message}")
                        break
                    }
                    handleClient(sock)
                }
            } catch (e: Exception) {
                Log.e(TAG, "server error: ${e.message}")
            }
        }
    }

    private fun handleClient(sock: Socket) {
        Log.i(TAG, "Client connected: ${sock.inetAddress}")
        try {
            sock.tcpNoDelay = true
        } catch (_: Exception) {
        }
        val input = DataInputStream(sock.getInputStream().buffered())
        clientSocket = sock
        clientOut = sock.getOutputStream().buffered()
        listener.onClientConnected()
        try {
            while (running) {
                val ev = WyomingEvent.readFrom(input) ?: break
                dispatch(ev)
            }
        } catch (e: Exception) {
            Log.w(TAG, "client loop: ${e.message}")
        } finally {
            clientOut = null
            clientSocket = null
            try {
                sock.close()
            } catch (_: Exception) {
            }
            listener.onClientDisconnected()
            Log.i(TAG, "Client disconnected")
        }
    }

    private fun dispatch(ev: WyomingEvent) {
        when (ev.type) {
            "ping" -> send(WyomingEvent("pong", ev.data))
            "audio-chunk" -> ev.payload?.let { listener.onTtsAudioChunk(it) }
            else -> {
                val payloadInfo = ev.payload?.let { " payload=${it.size}B" } ?: ""
                Log.d(TAG, "recv: ${ev.type} data=${ev.data}$payloadInfo")
                when (ev.type) {
                    "describe" -> sendInfo()
                    "run-satellite" -> listener.onRunSatellite()
                    "pause-satellite" -> listener.onPauseSatellite()
                    "detect" -> { /* HA wake-word stage started */ }
                    "detection" -> listener.onWakeDetected(ev.data?.optString("name") ?: "")
                    "voice-started" -> { /* logged above */ }
                    "voice-stopped" -> listener.onVoiceStopped()
                    "transcribe" -> { /* ASR stage marker */ }
                    "synthesize" -> listener.onSynthesize(ev.data?.optString("text") ?: "")
                    "transcript" -> listener.onTranscript(ev.data?.optString("text") ?: "")
                    "audio-start" -> {
                        val d = ev.data
                        listener.onTtsAudioStart(
                            d?.optInt("rate", 22050) ?: 22050,
                            d?.optInt("width", 2) ?: 2,
                            d?.optInt("channels", 1) ?: 1
                        )
                    }
                    "audio-stop" -> listener.onTtsAudioStop()
                    "error" -> listener.onError(ev.data?.optString("text") ?: "unknown")
                    else -> Log.w(TAG, "Unhandled: ${ev.type} data=${ev.data}")
                }
            }
        }
    }

    // ---- outgoing ----

    private fun send(ev: WyomingEvent) {
        val out = clientOut ?: return
        try {
            ev.writeTo(out)
        } catch (e: Exception) {
            Log.w(TAG, "send ${ev.type}: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun sendInfo() {
        val satellite = JSONObject()
            .put("name", satelliteName)
            .put("attribution", JSONObject().put("name", "DashVoice").put("url", ""))
            .put("installed", true)
            .put("has_vad", false)
            .put("supports_trigger", true)
        val info = JSONObject()
            .put("satellite", satellite)
        send(WyomingEvent("info", info))
    }

    /** HA performs wake-word detection and automatically restarts the pipeline after TTS. */
    fun sendRunPipelineRemoteWake() {
        val data = JSONObject()
            .put("start_stage", "wake")
            .put("end_stage", "tts")
            .put("restart_on_end", true)
            .put("snd_format", JSONObject().put("rate", 22050).put("width", 2).put("channels", 1))
        send(WyomingEvent("run-pipeline", data))
    }

    /** Manual push-to-talk still uses HA for STT, intent handling and TTS. */
    fun sendRunPipelineManual() {
        val data = JSONObject()
            .put("start_stage", "asr")
            .put("end_stage", "tts")
            .put("restart_on_end", false)
            .put("snd_format", JSONObject().put("rate", 22050).put("width", 2).put("channels", 1))
        send(WyomingEvent("run-pipeline", data))
    }

    fun sendAudioStart(rate: Int = 16000, width: Int = 2, channels: Int = 1) {
        send(
            WyomingEvent(
                "audio-start",
                JSONObject().put("rate", rate).put("width", width)
                    .put("channels", channels).put("timestamp", System.currentTimeMillis())
            )
        )
    }

    fun sendAudioChunk(pcm: ByteArray, rate: Int = 16000, width: Int = 2, channels: Int = 1) {
        val data = JSONObject().put("rate", rate).put("width", width)
            .put("channels", channels).put("timestamp", System.currentTimeMillis())
        send(WyomingEvent("audio-chunk", data, pcm))
    }

    fun sendAudioStop() {
        send(WyomingEvent("audio-stop", JSONObject().put("timestamp", System.currentTimeMillis())))
    }

    fun sendPlayed() = send(WyomingEvent("played"))

    fun stop() {
        running = false
        try {
            clientSocket?.close()
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        clientOut = null
        clientSocket = null
        serverSocket = null
        acceptThread = null
    }

    companion object {
        private const val TAG = "WyomingServer"
    }
}
