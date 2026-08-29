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
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import cc.quandel.dashvoice.audio.AudioCapture
import cc.quandel.dashvoice.audio.AudioPlayer
import cc.quandel.dashvoice.audio.Ringer
import cc.quandel.dashvoice.util.AppLog as Log
import cc.quandel.dashvoice.wyoming.WyomingServer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thin Wyoming satellite for Home Assistant Assist.
 *
 * The tablet only captures/plays audio. Wake-word detection, STT, conversation handling and
 * TTS all run in the Assist pipeline selected for this satellite in Home Assistant.
 */
class VoiceService : Service(), WyomingServer.Listener {

    // RECOGNIZING remains for UI/backwards compatibility but is no longer entered locally.
    enum class State { IDLE, RECOGNIZING, STREAMING, SPEAKING }

    @Volatile private var state = State.IDLE
    @Volatile private var satelliteActive = false
    @Volatile private var manualPipeline = false
    @Volatile private var ignoreTtsChunks = false
    private var captureNoiseSuppression = true

    private lateinit var prefs: Prefs
    private lateinit var capture: AudioCapture
    private lateinit var player: AudioPlayer
    private lateinit var server: WyomingServer
    private lateinit var audioManager: AudioManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var nsdManager: NsdManager
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var audioFocusReq: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringer: Ringer? = null
    private var battery: BatteryReporter? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val ttsTimeoutRunnable = Runnable {
        if (state == State.SPEAKING) {
            Log.w(TAG, "TTS-Timeout → Satellit zurück auf IDLE")
            player.stop()
            abandonTtsFocus()
            manualPipeline = false
            setIdle()
            restartRemoteWakeAfterError()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (!server.isConnected) {
                    Log.i(TAG, "Netzwerk verfügbar — Wyoming-Server neu starten")
                    server.stop()
                    server.start()
                }
                VoiceEvents.onNetworkAvailable?.invoke()
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            try {
                if (!server.isListening) {
                    Log.w(TAG, "Watchdog: Wyoming-Server lauscht nicht — Neustart")
                    server.stop()
                    server.start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Watchdog-Fehler: ${e.message}")
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Log.initFileLog(getExternalFilesDir(null) ?: filesDir)
        startForegroundWithNotification()
        acquireWakeLock()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        player = AudioPlayer()
        ringer = Ringer(this)

        captureNoiseSuppression = prefs.noiseSuppressionEnabled
        capture = AudioCapture(gain = prefs.micGain, noiseSuppression = captureNoiseSuppression)
        server = WyomingServer(prefs.satellitePort, prefs.satelliteName, this)
        server.start()
        registerWyomingService()

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        capture.start(::onAudioFrame)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        RestartReceiver.schedule(this)
        battery = BatteryReporter(this, prefs.dashboardUrl, prefs.haToken, prefs.satelliteName)
            .also { it.start() }
        Log.i(TAG, "started (HA remote wake-word satellite, no local speech models)")
    }

    /** Continuously feed the active HA wake/Assist pipeline (official always-streaming mode). */
    private fun onAudioFrame(frame: ShortArray) {
        if (!server.isConnected || (!satelliteActive && !manualPipeline)) return
        if (ringer?.isRinging == true) return
        server.sendAudioChunk(shortsToBytes(frame))
    }

    /** Manual mic button: still no local recognition; the stream starts directly at HA STT. */
    @Synchronized
    private fun triggerManualPipeline() {
        if (!server.isConnected || state == State.SPEAKING) return
        Log.i(TAG, "tap-to-talk → HA Assist pipeline")
        manualPipeline = true
        server.sendRunPipelineManual()
        state = State.STREAMING
        VoiceEvents.voiceState = State.STREAMING
        notifyWakeUi()
    }

    private fun notifyWakeUi() {
        handler.post {
            if (VoiceEvents.onWake != null) {
                VoiceEvents.onWake?.invoke()
            } else {
                try {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Activity-Start fehlgeschlagen: ${e.message}")
                }
            }
        }
    }

    private fun setIdle() {
        state = State.IDLE
        VoiceEvents.voiceState = State.IDLE
        handler.post { VoiceEvents.onIdle?.invoke() }
    }

    /** Advertise the Wyoming endpoint so HA can discover/register the tablet automatically. */
    private fun registerWyomingService() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = prefs.satelliteName
            serviceType = WYOMING_SERVICE_TYPE
            port = prefs.satellitePort
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Wyoming mDNS registriert: ${info.serviceName}:${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Wyoming mDNS Registrierung fehlgeschlagen: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "Wyoming mDNS abgemeldet")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Wyoming mDNS Abmeldung fehlgeschlagen: $errorCode")
            }
        }
        nsdRegistrationListener = listener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Wyoming mDNS nicht verfügbar: ${e.message}")
        }
    }

    private fun restartRemoteWakeAfterError() {
        if (!satelliteActive || !server.isConnected) return
        handler.postDelayed({
            if (satelliteActive && server.isConnected && state == State.IDLE) {
                Log.i(TAG, "HA-Wake-Pipeline neu anfordern")
                Thread { server.sendRunPipelineRemoteWake() }.start()
            }
        }, PIPELINE_RESTART_DELAY_MS)
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
        audioManager.requestAudioFocus(req)
    }

    private fun abandonTtsFocus() {
        audioFocusReq?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusReq = null
    }

    private fun shortsToBytes(samples: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bb.putShort(sample)
        return bb.array()
    }

    // ---- WyomingServer.Listener ----

    override fun onRunSatellite() {
        satelliteActive = true
        manualPipeline = false
        setIdle()
        Log.i(TAG, "run-satellite → starte HA-Wake-Pipeline")
        server.sendRunPipelineRemoteWake()
    }

    override fun onPauseSatellite() {
        satelliteActive = false
        manualPipeline = false
        abandonTtsFocus()
        setIdle()
        Log.i(TAG, "pause-satellite")
    }

    override fun onClientConnected() {
        Log.i(TAG, "HA connected")
        VoiceEvents.connected = true
        handler.post { VoiceEvents.onConnected?.invoke() }
    }

    override fun onClientDisconnected() {
        satelliteActive = false
        manualPipeline = false
        abandonTtsFocus()
        setIdle()
        Log.i(TAG, "HA disconnected")
        VoiceEvents.connected = false
        handler.post { VoiceEvents.onDisconnected?.invoke() }
    }

    override fun onWakeDetected(name: String) {
        Log.i(TAG, "HA wake detected: ${name.ifBlank { "configured wake word" }}")
        state = State.STREAMING
        VoiceEvents.voiceState = State.STREAMING
        notifyWakeUi()
    }

    override fun onVoiceStopped() {
        // HA's pipeline VAD owns end-of-speech; audio streaming itself remains continuous.
        if (state == State.STREAMING) {
            state = State.SPEAKING
            VoiceEvents.voiceState = State.SPEAKING
            handler.postDelayed(ttsTimeoutRunnable, TTS_TIMEOUT_MS)
        }
    }

    override fun onTranscript(text: String) {
        Log.i(TAG, "transcript: $text")
        handler.post { VoiceEvents.onTranscript?.invoke(text) }
    }

    override fun onSynthesize(text: String) {
        Log.i(TAG, "synthesize: $text")
        handler.post { VoiceEvents.onResponse?.invoke(text) }
    }

    override fun onTtsAudioStart(rate: Int, width: Int, channels: Int) {
        Log.i(TAG, "TTS start ${rate}Hz/${channels}ch")
        handler.removeCallbacks(ttsTimeoutRunnable)
        ignoreTtsChunks = false
        requestTtsFocus()
        state = State.SPEAKING
        VoiceEvents.voiceState = State.SPEAKING
        player.start(rate, channels, width, prefs.ttsVolume / 100f)
    }

    override fun onTtsAudioChunk(pcm: ByteArray) {
        if (ignoreTtsChunks) return
        player.write(pcm)
        handler.post { VoiceEvents.onTtsLevel?.invoke(rms16(pcm)) }
    }

    override fun onTtsAudioStop() {
        Log.i(TAG, "TTS audio-stop — draining buffer")
        player.finishPlaying(handler) {
            if (state != State.SPEAKING) return@finishPlaying
            abandonTtsFocus()
            Thread { server.sendPlayed() }.start()
            val wasManual = manualPipeline
            manualPipeline = false
            setIdle()
            // A manual ASR run is not restartable; restore HA-controlled remote wake afterwards.
            if (wasManual) restartRemoteWakeAfterError()
        }
    }

    override fun onError(text: String) {
        Log.w(TAG, "pipeline error: $text")
        handler.removeCallbacks(ttsTimeoutRunnable)
        player.stop()
        abandonTtsFocus()
        manualPipeline = false
        setIdle()
        restartRemoteWakeAfterError()
    }

    /** RMS level (0..1) for the speaking animation. */
    private fun rms16(pcm: ByteArray): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)
            sum += (sample * sample).toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0f
        return ((Math.sqrt(sum / count) / 32768.0) * 4.0).coerceIn(0.0, 1.0).toFloat()
    }

    // Existing local alarm playback remains for timers already scheduled before this update.
    private val timerAutoStop = Runnable { stopTimerRing() }

    private fun ringTimer() {
        prefs.timerEndAt = 0L
        ringer?.start()
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
        handler.post { VoiceEvents.onTimerRinging?.invoke() }
        handler.removeCallbacks(timerAutoStop)
        handler.postDelayed(timerAutoStop, TIMER_AUTOSTOP_MS)
    }

    private fun stopTimerRing() {
        handler.removeCallbacks(timerAutoStop)
        ringer?.stop()
        handler.post { VoiceEvents.onTimerStopped?.invoke() }
    }

    private fun applySettings() {
        capture.gain = prefs.micGain
        if (prefs.noiseSuppressionEnabled != captureNoiseSuppression) {
            captureNoiseSuppression = prefs.noiseSuppressionEnabled
            capture.stop()
            capture = AudioCapture(gain = prefs.micGain, noiseSuppression = captureNoiseSuppression)
            capture.start(::onAudioFrame)
        }
        Log.i(TAG, "Audio live übernommen: gain=${prefs.micGain} ns=${prefs.noiseSuppressionEnabled}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TALK -> Thread { triggerManualPipeline() }.start()
            TimerReceiver.ACTION_TIMER -> ringTimer()
            ACTION_TIMER_STOP -> stopTimerRing()
            ACTION_APPLY_SETTINGS -> applySettings()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(ttsTimeoutRunnable)
        capture.stop()
        player.stop()
        server.stop()
        nsdRegistrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (_: Exception) {
            }
        }
        nsdRegistrationListener = null
        ringer?.stop()
        battery?.stop()
        abandonTtsFocus()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "HA Voice Satellite", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("DashVoice")
            .setContentText("Home-Assistant-Sprachsatellit aktiv")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
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
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        private const val TTS_TIMEOUT_MS = 30_000L
        private const val PIPELINE_RESTART_DELAY_MS = 1_000L
        private const val TIMER_AUTOSTOP_MS = 120_000L
        private const val WYOMING_SERVICE_TYPE = "_wyoming._tcp."
        const val ACTION_TALK = "cc.quandel.dashvoice.TALK"
        const val ACTION_TIMER_STOP = "cc.quandel.dashvoice.TIMER_STOP"
        const val ACTION_APPLY_SETTINGS = "cc.quandel.dashvoice.APPLY_SETTINGS"

        private fun startService(ctx: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun start(ctx: Context) = startService(ctx, Intent(ctx, VoiceService::class.java))
        fun talk(ctx: Context) = startService(
            ctx, Intent(ctx, VoiceService::class.java).setAction(ACTION_TALK)
        )
        fun applySettings(ctx: Context) = startService(
            ctx, Intent(ctx, VoiceService::class.java).setAction(ACTION_APPLY_SETTINGS)
        )
        fun fireTimer(ctx: Context) = startService(
            ctx, Intent(ctx, VoiceService::class.java).setAction(TimerReceiver.ACTION_TIMER)
        )
        fun stopTimer(ctx: Context) = startService(
            ctx, Intent(ctx, VoiceService::class.java).setAction(ACTION_TIMER_STOP)
        )
    }
}
