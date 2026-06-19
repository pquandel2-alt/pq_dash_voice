package cc.quandel.dashvoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import cc.quandel.dashvoice.util.AppLog as Log
import cc.quandel.dashvoice.audio.AudioCapture
import cc.quandel.dashvoice.audio.AudioPlayer
import cc.quandel.dashvoice.wake.NoopWakeWordDetector
import cc.quandel.dashvoice.wake.OpenWakeWordDetector
import cc.quandel.dashvoice.wake.WakeWordDetector
import cc.quandel.dashvoice.wyoming.WyomingServer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceService : Service(), WyomingServer.Listener {

    private enum class State { IDLE, STREAMING, SPEAKING }

    @Volatile private var state = State.IDLE
    @Volatile private var satelliteActive = false
    private var streamStartedAt = 0L

    private lateinit var prefs: Prefs
    private lateinit var capture: AudioCapture
    private lateinit var player: AudioPlayer
    private lateinit var server: WyomingServer
    private lateinit var audioManager: AudioManager
    private lateinit var connectivityManager: ConnectivityManager
    private var audioFocusReq: AudioFocusRequest? = null
    private var wake: WakeWordDetector = NoopWakeWordDetector()
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val ttsTimeoutRunnable = Runnable {
        if (state == State.SPEAKING) {
            Log.w(TAG, "TTS-Timeout → reset IDLE")
            abandonTtsFocus()
            state = State.IDLE
            VoiceEvents.onIdle?.invoke()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Netzwerk verfügbar — starte Wyoming-Server neu")
            handler.post {
                if (!server.isConnected) {
                    server.stop()
                    server.start()
                }
                VoiceEvents.onNetworkAvailable?.invoke()
            }
        }
    }

    private val keepAlivePing = object : Runnable {
        override fun run() {
            if (server.isConnected) server.sendPingOrKick()
            handler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        startForegroundWithNotification()
        acquireWakeLock()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        player = AudioPlayer()
        capture = AudioCapture()
        wake = try {
            val d = OpenWakeWordDetector(this, prefs.wakeWord, prefs.wakeThreshold)
            if (d.available) d else NoopWakeWordDetector()
        } catch (e: Exception) {
            Log.w(TAG, "wake init failed: ${e.message}")
            NoopWakeWordDetector()
        }

        server = WyomingServer(prefs.satellitePort, prefs.satelliteName, prefs.wakeWord, this)
        server.start()

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        capture.start { frame -> onAudioFrame(frame) }
        handler.postDelayed(keepAlivePing, PING_INTERVAL_MS)
        Log.i(TAG, "started (wake=${if (wake.available) "openWakeWord" else "tap-to-talk"})")
    }

    private fun onAudioFrame(frame: ShortArray) {
        when (state) {
            State.IDLE ->
                if (satelliteActive && server.isConnected && wake.available && wake.accept(frame)) {
                    triggerPipeline()
                }
            State.STREAMING -> {
                server.sendAudioChunk(shortsToBytes(frame))
                if (System.currentTimeMillis() - streamStartedAt > MAX_STREAM_MS) endStreaming()
            }
            State.SPEAKING -> { /* ignore mic during playback */ }
        }
    }

    @Synchronized
    fun triggerPipeline() {
        if (state != State.IDLE || !server.isConnected) return
        Log.i(TAG, "trigger -> pipeline")
        wake.reset()
        server.sendRunPipeline()
        server.sendDetection(prefs.wakeWord)
        server.sendAudioStart()
        streamStartedAt = System.currentTimeMillis()
        state = State.STREAMING
        handler.post { VoiceEvents.onWake?.invoke() }
    }

    @Synchronized
    private fun endStreaming() {
        if (state != State.STREAMING) return
        Log.i(TAG, "streaming beendet -> audio-stop")
        server.sendAudioStop()
        state = State.SPEAKING
        handler.postDelayed(ttsTimeoutRunnable, TTS_TIMEOUT_MS)
    }

    private fun requestTtsFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({}, handler)
            .build()
        audioFocusReq = req
        val result = audioManager.requestAudioFocus(req)
        Log.d(TAG, "AudioFocus: ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "granted" else "denied($result)"}")
    }

    private fun abandonTtsFocus() {
        audioFocusReq?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusReq = null
    }

    private fun shortsToBytes(s: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in s) bb.putShort(v)
        return bb.array()
    }

    // ---- WyomingServer.Listener ----
    override fun onRunSatellite() { satelliteActive = true; Log.i(TAG, "run-satellite") }
    override fun onPauseSatellite() {
        satelliteActive = false; abandonTtsFocus(); state = State.IDLE
        handler.post { VoiceEvents.onIdle?.invoke() }
    }
    override fun onClientConnected() {
        Log.i(TAG, "HA connected")
        handler.post { VoiceEvents.onConnected?.invoke() }
    }
    override fun onClientDisconnected() {
        satelliteActive = false; abandonTtsFocus(); state = State.IDLE
        Log.i(TAG, "HA disconnected")
        handler.post {
            VoiceEvents.onDisconnected?.invoke()
            VoiceEvents.onIdle?.invoke()
        }
    }

    override fun onVoiceStopped() {
        if (state == State.STREAMING) endStreaming()
    }

    override fun onTranscript(text: String) {
        Log.i(TAG, "transcript: $text")
        if (state == State.STREAMING) endStreaming()
        handler.post { VoiceEvents.onTranscript?.invoke(text) }
    }

    override fun onSynthesize(text: String) {
        Log.i(TAG, "synthesize: $text")
        handler.post { VoiceEvents.onResponse?.invoke(text) }
    }

    override fun onTtsAudioStart(rate: Int, width: Int, channels: Int) {
        Log.i(TAG, "TTS start ${rate}Hz/${channels}ch")
        handler.removeCallbacks(ttsTimeoutRunnable)
        requestTtsFocus()
        state = State.SPEAKING
        player.start(rate, channels, width, prefs.ttsVolume / 100f)
    }

    override fun onTtsAudioChunk(pcm: ByteArray) = player.write(pcm)

    override fun onTtsAudioStop() {
        Log.i(TAG, "TTS audio-stop — draining buffer")
        player.finishPlaying(handler) {
            Log.i(TAG, "TTS fertig -> played")
            abandonTtsFocus()
            server.sendPlayed()
            state = State.IDLE
            VoiceEvents.onIdle?.invoke()
        }
    }

    override fun onError(text: String) {
        Log.w(TAG, "pipeline error: $text")
        abandonTtsFocus(); state = State.IDLE
        handler.post { VoiceEvents.onIdle?.invoke() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TALK) triggerPipeline()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(keepAlivePing)
        capture.stop()
        player.stop()
        server.stop()
        wake.close()
        abandonTtsFocus()
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Voice Satellite", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("DashVoice")
            .setContentText("Sprach-Satellit aktiv")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DashVoice::mic").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    companion object {
        private const val TAG = "VoiceService"
        private const val CHANNEL = "dashvoice"
        private const val NOTIF_ID = 1
        private const val MAX_STREAM_MS = 12000L
        private const val TTS_TIMEOUT_MS = 5000L
        private const val PING_INTERVAL_MS = 30_000L
        const val ACTION_TALK = "cc.quandel.dashvoice.TALK"

        fun start(ctx: Context) {
            val i = Intent(ctx, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun talk(ctx: Context) {
            val i = Intent(ctx, VoiceService::class.java).setAction(ACTION_TALK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
