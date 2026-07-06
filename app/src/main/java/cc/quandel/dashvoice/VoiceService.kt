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
import cc.quandel.dashvoice.audio.Ringer
import cc.quandel.dashvoice.wake.LocalRecognizer
import cc.quandel.dashvoice.wake.NoopWakeWordDetector
import cc.quandel.dashvoice.wake.OpenWakeWordDetector
import cc.quandel.dashvoice.wake.WakeWordDetector
import cc.quandel.dashvoice.wyoming.WyomingServer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceService : Service(), WyomingServer.Listener {

    enum class State { IDLE, RECOGNIZING, STREAMING, SPEAKING }

    @Volatile private var state = State.IDLE
    @Volatile private var satelliteActive = false
    private var streamStartedAt = 0L
    private var lastTtsEndedAt = 0L
    private var vadSilenceFrames = 0
    private var vadHadSpeech = false

    // On-Device-Sofortbefehle (Vosk): puffert das Audio während RECOGNIZING für ggf. Fallback an HA.
    @Volatile private var local: LocalRecognizer? = null
    private val bufferedFrames = ArrayList<ShortArray>(128)
    @Volatile private var entities: HaEntities? = null
    @Volatile private var followUpActive = false
    @Volatile private var ignoreTtsChunks = false
    private var ringer: Ringer? = null
    private var battery: BatteryReporter? = null
    // Aktuell im Capture aktiver NS-Zustand — um bei applySettings zu erkennen, ob ein Neuaufbau nötig ist.
    private var captureNoiseSuppression = true

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
            lastTtsEndedAt = System.currentTimeMillis()
            wake.reset()
            state = State.IDLE
            VoiceEvents.voiceState = State.IDLE
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

        player = AudioPlayer()
        captureNoiseSuppression = prefs.noiseSuppressionEnabled
        capture = AudioCapture(
            gain = prefs.micGain,
            noiseSuppression = captureNoiseSuppression
        )
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

        ringer = Ringer(this)
        // Vosk + Gerätenamen im Hintergrund laden (erster Start kopiert ~45 MB Modell → onCreate nicht blockieren).
        if (prefs.instantCommandsEnabled) {
            Thread {
                val r = try { LocalRecognizer(this) } catch (e: Throwable) {
                    Log.w(TAG, "LocalRecognizer init: ${e.message}"); null
                }
                local = r?.takeIf { it.available }
                val ent = HaEntities(prefs.dashboardUrl, prefs.haToken)
                ent.refresh()
                entities = ent
                Log.i(TAG, "On-Device-Befehle: ${if (local != null) "aktiv" else "aus"}, Geräte: ${ent.names.size}")
            }.start()
        }

        capture.start { frame -> onAudioFrame(frame) }
        // Kein eigener Keepalive-Ping: HA pingt selbst, wir ponten (auf dem Client-Thread).
        // Ein ausgehender Ping vom Main-Thread warf NetworkOnMainThreadException und killte die Verbindung.
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        RestartReceiver.schedule(this)
        // Akkustand des Tablets als HA-Entität melden (für Lade-Relais-Automation).
        battery = BatteryReporter(this, prefs.dashboardUrl, prefs.haToken, prefs.satelliteName).also { it.start() }
        Log.i(TAG, "started (wake=${if (wake.available) "openWakeWord" else "tap-to-talk"})")
    }

    private fun onAudioFrame(frame: ShortArray) {
        when (state) {
            State.IDLE ->
                if (satelliteActive && server.isConnected && wake.available
                    && System.currentTimeMillis() - lastTtsEndedAt > TTS_WAKEWORD_COOLDOWN_MS
                    && wake.accept(frame)) {
                    onWakeDetected()
                }
            State.RECOGNIZING -> {
                local?.accept(frame)
                if (bufferedFrames.size < MAX_BUFFER_FRAMES) bufferedFrames.add(frame.copyOf())
                detectSilence(frame)
                val elapsed = System.currentTimeMillis() - streamStartedAt
                if (followUpActive && !vadHadSpeech && elapsed > FOLLOW_UP_NO_SPEECH_MS) abortToIdle()
                else if (elapsed > MAX_STREAM_MS) endRecognizing()
            }
            State.STREAMING -> {
                server.sendAudioChunk(shortsToBytes(frame))
                detectSilence(frame)
                if (System.currentTimeMillis() - streamStartedAt > MAX_STREAM_MS) endStreaming()
            }
            State.SPEAKING -> {
                // Barge-in: während der Sprachausgabe aufs Wake-Word hören (AEC unterdrückt die eigene Stimme).
                if (prefs.bargeInEnabled && wake.available && ringer?.isRinging != true && wake.accept(frame)) {
                    bargeIn()
                }
            }
        }
    }

    /** Nach dem Wake-Word: wenn Vosk bereit → lokal erkennen, sonst direkt die HA-Pipeline. */
    private fun onWakeDetected() {
        if (local != null) startRecognizing() else triggerPipeline()
    }

    @Synchronized
    private fun startRecognizing(followUp: Boolean = false) {
        if (state != State.IDLE) return
        Log.i(TAG, if (followUp) "follow-up -> RECOGNIZING" else "wake -> RECOGNIZING (on-device)")
        wake.reset()
        local?.reset()
        bufferedFrames.clear()
        vadSilenceFrames = 0
        vadHadSpeech = false
        followUpActive = followUp
        streamStartedAt = System.currentTimeMillis()
        state = State.RECOGNIZING
        VoiceEvents.voiceState = State.RECOGNIZING
        handler.post { VoiceEvents.onWake?.invoke() }
    }

    @Synchronized
    private fun endRecognizing() {
        if (state != State.RECOGNIZING) return
        followUpActive = false
        state = State.SPEAKING   // Mic ab jetzt ignorieren, während wir entscheiden
        VoiceEvents.voiceState = State.SPEAKING
        Thread { decideCommand() }.start()
    }

    /** Follow-up ohne Sprache → sauber zurück nach IDLE (kein Whisper-Call). */
    @Synchronized
    private fun abortToIdle() {
        if (state != State.RECOGNIZING) return
        Log.i(TAG, "Follow-up: keine Sprache → IDLE")
        followUpActive = false
        bufferedFrames.clear()
        state = State.IDLE
        VoiceEvents.voiceState = State.IDLE
        handler.post { VoiceEvents.onIdle?.invoke() }
    }

    /** Läuft auf eigenem Thread: Timer? lokaler Befehl (Fuzzy/Mehrgerät)? sonst Fallback an Whisper. */
    private fun decideCommand() {
        val text = local?.finalText().orEmpty()
        Log.i(TAG, "Vosk: \"$text\"")

        // 1) Lokaler Timer/Wecker
        if (prefs.timersEnabled) {
            val ms = LocalTimer.parse(text)
            if (ms != null) { scheduleTimer(ms); return }
        }

        // 2) Lokaler Gerätebefehl (Normalisierung + Fuzzy + Mehrgerät)
        val cmds = CommandParser.parse(text, prefs.commandVerbs, entities?.names ?: emptyList(), prefs.fuzzyThreshold)
        if (cmds != null) {
            val conv = HaConversation(prefs.dashboardUrl, prefs.haToken)
            var ok = 0
            for (c in cmds) {
                Log.i(TAG, "lokaler Befehl: \"$c\"")
                val res = conv.process(c)
                if (res != null && res.ok) ok++
            }
            if (ok > 0) {
                Log.i(TAG, "Befehle ausgeführt: $ok/${cmds.size}")
                lastTtsEndedAt = System.currentTimeMillis()
                bufferedFrames.clear()
                state = State.IDLE
                VoiceEvents.voiceState = State.IDLE
                handler.post { VoiceEvents.onCommandDone?.invoke("") }
                maybeStartFollowUp()
                return
            }
            Log.i(TAG, "kein Befehl ausgeführt -> Fallback Whisper")
        } else {
            Log.i(TAG, "kein Befehl erkannt -> Fallback Whisper")
        }
        fallbackToPipeline()
    }

    /** Nach einem Turn (Befehl/Antwort) kurz weiter zuhören, ohne erneutes „Ok Nabu". */
    private fun maybeStartFollowUp() {
        if (!prefs.followUpEnabled || !server.isConnected || local == null) return
        handler.postDelayed({
            if (state == State.IDLE) startRecognizing(followUp = true)
        }, FOLLOW_UP_DELAY_MS)
    }

    private fun bargeIn() {
        Log.i(TAG, "Barge-in: Wake während TTS → unterbreche")
        ignoreTtsChunks = true
        handler.removeCallbacks(ttsTimeoutRunnable)
        player.stop()
        abandonTtsFocus()
        Thread { server.sendPlayed() }.start()
        state = State.IDLE
        VoiceEvents.voiceState = State.IDLE
        lastTtsEndedAt = 0L
        startRecognizing()
    }

    // ---- lokaler Timer/Wecker ----
    private fun scheduleTimer(ms: Long) {
        val label = LocalTimer.humanize(ms)
        Log.i(TAG, "LocalTimer gestellt: ${ms}ms ($label)")
        val endAt = System.currentTimeMillis() + ms
        prefs.timerEndAt = endAt
        TimerReceiver.schedule(this, endAt)
        lastTtsEndedAt = System.currentTimeMillis()
        bufferedFrames.clear()
        state = State.IDLE
        VoiceEvents.voiceState = State.IDLE
        handler.post { VoiceEvents.onTimerSet?.invoke(label) }
        maybeStartFollowUp()
    }

    private val timerAutoStop = Runnable { stopTimerRing() }

    private fun ringTimer() {
        prefs.timerEndAt = 0L   // Countdown vorbei → Chip ausblenden
        ringer?.start()
        // Kiosk-UI nach vorne holen, damit das Stopp-Overlay sichtbar ist und der Screen angeht.
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
        handler.post { VoiceEvents.onTimerRinging?.invoke() }
        handler.removeCallbacks(timerAutoStop)
        handler.postDelayed(timerAutoStop, TIMER_AUTOSTOP_MS)
    }

    private fun stopTimerRing() {
        handler.removeCallbacks(timerAutoStop)
        ringer?.stop()
        handler.post { VoiceEvents.onTimerStopped?.invoke() }
    }

    /** Sendet die gepufferten Frames an HA (Whisper→Gemini→TTS), wenn kein lokaler Befehl griff. */
    private fun fallbackToPipeline() {
        if (!server.isConnected) {
            bufferedFrames.clear(); state = State.IDLE
            VoiceEvents.voiceState = State.IDLE
            handler.post { VoiceEvents.onIdle?.invoke() }
            return
        }
        Log.i(TAG, "Fallback -> Pipeline (${bufferedFrames.size} frames)")
        server.sendRunPipeline()
        server.sendDetection(prefs.wakeWord)
        server.sendAudioStart()
        for (f in bufferedFrames) server.sendAudioChunk(shortsToBytes(f))
        bufferedFrames.clear()
        server.sendAudioStop()
        state = State.SPEAKING
        VoiceEvents.voiceState = State.SPEAKING
        handler.postDelayed(ttsTimeoutRunnable, TTS_TIMEOUT_MS)
    }

    @Synchronized
    fun triggerPipeline() {
        if (state != State.IDLE || !server.isConnected) return
        Log.i(TAG, "trigger -> pipeline")
        wake.reset()
        vadSilenceFrames = 0
        vadHadSpeech = false
        server.sendRunPipeline()
        server.sendDetection(prefs.wakeWord)
        server.sendAudioStart()
        streamStartedAt = System.currentTimeMillis()
        state = State.STREAMING
        VoiceEvents.voiceState = State.STREAMING
        handler.post { VoiceEvents.onWake?.invoke() }
    }

    @Synchronized
    private fun endStreaming() {
        if (state != State.STREAMING) return
        Log.i(TAG, "streaming beendet -> audio-stop")
        server.sendAudioStop()
        state = State.SPEAKING
        VoiceEvents.voiceState = State.SPEAKING
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

    private fun detectSilence(frame: ShortArray) {
        if (System.currentTimeMillis() - streamStartedAt < MIN_VAD_WAIT_MS) return
        var sum = 0L
        for (s in frame) sum += s.toLong() * s
        val rms = Math.sqrt(sum.toDouble() / frame.size).toFloat()
        if (rms >= VAD_THRESHOLD) {
            vadHadSpeech = true
            vadSilenceFrames = 0
        } else if (vadHadSpeech) {
            if (++vadSilenceFrames >= VAD_SILENCE_FRAMES) {
                Log.d(TAG, "lokale VAD: Stille erkannt (rms=$rms)")
                if (state == State.RECOGNIZING) endRecognizing() else endStreaming()
            }
        }
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
        VoiceEvents.voiceState = State.IDLE
        handler.post { VoiceEvents.onIdle?.invoke() }
    }
    override fun onClientConnected() {
        Log.i(TAG, "HA connected")
        VoiceEvents.connected = true
        handler.post { VoiceEvents.onConnected?.invoke() }
    }
    override fun onClientDisconnected() {
        satelliteActive = false; abandonTtsFocus(); state = State.IDLE
        VoiceEvents.voiceState = State.IDLE
        Log.i(TAG, "HA disconnected")
        VoiceEvents.connected = false
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
        ignoreTtsChunks = false
        requestTtsFocus()
        state = State.SPEAKING
        VoiceEvents.voiceState = State.SPEAKING
        player.start(rate, channels, width, prefs.ttsVolume / 100f)
    }

    override fun onTtsAudioChunk(pcm: ByteArray) {
        if (ignoreTtsChunks) return
        player.write(pcm)
        val level = rms16(pcm)
        handler.post { VoiceEvents.onTtsLevel?.invoke(level) }
    }

    /** RMS-Pegel (0..1) eines 16-bit-PCM-Chunks (Little-Endian) für die Mund-/Lippensync-Animation. */
    private fun rms16(pcm: ByteArray): Float {
        var sum = 0.0; var n = 0; var i = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)  // signed 16-bit LE
            sum += (s * s).toDouble(); n++; i += 2
        }
        if (n == 0) return 0f
        val rms = Math.sqrt(sum / n) / 32768.0
        return (rms * 4.0).coerceIn(0.0, 1.0).toFloat()   // Sprache hat niedrigen RMS → anheben
    }

    override fun onTtsAudioStop() {
        Log.i(TAG, "TTS audio-stop — draining buffer")
        player.finishPlaying(handler) {
            if (state != State.SPEAKING) return@finishPlaying  // z. B. nach Barge-in nichts überschreiben
            Log.i(TAG, "TTS fertig -> played")
            abandonTtsFocus()
            lastTtsEndedAt = System.currentTimeMillis()
            wake.reset()
            state = State.IDLE
            VoiceEvents.voiceState = State.IDLE
            VoiceEvents.onIdle?.invoke()
            Thread { server.sendPlayed() }.start()
            maybeStartFollowUp()
        }
    }

    override fun onError(text: String) {
        Log.w(TAG, "pipeline error: $text")
        abandonTtsFocus(); state = State.IDLE
        VoiceEvents.voiceState = State.IDLE
        handler.post { VoiceEvents.onIdle?.invoke() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Off-Main-Thread: triggerPipeline macht Socket-I/O (sendRunPipeline/Detection/AudioStart).
        // Der Wake-Word-Pfad läuft auf dem Capture-Thread; Tap-to-Talk/ACTION_TALK kommt vom Main-Thread
        // und würde sonst NetworkOnMainThreadException werfen. @Synchronized macht den Aufruf thread-safe.
        when (intent?.action) {
            ACTION_TALK -> Thread { triggerPipeline() }.start()
            TimerReceiver.ACTION_TIMER -> ringTimer()
            ACTION_TIMER_STOP -> stopTimerRing()
            ACTION_APPLY_SETTINGS -> applySettings()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        capture.stop()
        player.stop()
        server.stop()
        wake.close()
        local?.close()
        ringer?.stop()
        battery?.stop()
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

    /**
     * Übernimmt geänderte Audio-Einstellungen LIVE, ohne Service-Neustart (von SettingsActivity getriggert).
     * Wake-Empfindlichkeit (Threshold) und Mic-Gain wirken sofort; NoiseSuppression ist an die Audio-Session
     * gebunden und braucht einen kurzen Capture-Neuaufbau, daher nur bei tatsächlicher Änderung.
     */
    private fun applySettings() {
        (wake as? OpenWakeWordDetector)?.threshold = prefs.wakeThreshold
        capture.gain = prefs.micGain
        if (prefs.noiseSuppressionEnabled != captureNoiseSuppression) {
            captureNoiseSuppression = prefs.noiseSuppressionEnabled
            capture.stop()
            capture = AudioCapture(gain = prefs.micGain, noiseSuppression = captureNoiseSuppression)
            capture.start { frame -> onAudioFrame(frame) }
        }
        Log.i(TAG, "Einstellungen live übernommen: threshold=${prefs.wakeThreshold} " +
            "gain=${prefs.micGain} ns=${prefs.noiseSuppressionEnabled}")
    }

    companion object {
        private const val TAG = "VoiceService"
        private const val CHANNEL = "dashvoice"
        private const val NOTIF_ID = 1
        private const val MAX_STREAM_MS = 12000L
        private const val MAX_BUFFER_FRAMES = 160  // ~12,8s @80ms/Frame — Puffer für Vosk-Fallback
        private const val TTS_TIMEOUT_MS = 30000L
        private const val WATCHDOG_INTERVAL_MS = 60_000L  // Server-Selbstheilung: alle 60s prüfen
        private const val MIN_VAD_WAIT_MS = 1500L   // 1,5s Mindest-Streaming bevor VAD feuern darf
        private const val VAD_THRESHOLD = 500f      // RMS-Amplitude (0–32768); bei Problemen erhöhen
        private const val VAD_SILENCE_FRAMES = 12   // 12 × 80ms = 960ms Stille → audio-stop
        private const val TTS_WAKEWORD_COOLDOWN_MS = 2000L  // 2s Sperrzeit nach TTS-Ende gegen Echo-Trigger
        private const val FOLLOW_UP_DELAY_MS = 700L         // kurze Pause vor Follow-up-Zuhören (Echo abklingen)
        private const val FOLLOW_UP_NO_SPEECH_MS = 5000L    // Follow-up bricht ab, wenn nichts gesagt wird
        private const val TIMER_AUTOSTOP_MS = 120_000L      // Wecker stoppt spätestens nach 2 min
        const val ACTION_TALK = "cc.quandel.dashvoice.TALK"
        const val ACTION_TIMER_STOP = "cc.quandel.dashvoice.TIMER_STOP"
        const val ACTION_APPLY_SETTINGS = "cc.quandel.dashvoice.APPLY_SETTINGS"

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

        /** Geänderte Audio-Einstellungen live an den laufenden Dienst übergeben (kein Neustart nötig). */
        fun applySettings(ctx: Context) {
            val i = Intent(ctx, VoiceService::class.java).setAction(ACTION_APPLY_SETTINGS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /** Vom TimerReceiver bei Ablauf aufgerufen. */
        fun fireTimer(ctx: Context) {
            val i = Intent(ctx, VoiceService::class.java).setAction(TimerReceiver.ACTION_TIMER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /** Stoppt den klingelnden Wecker (z. B. vom UI-Tap). */
        fun stopTimer(ctx: Context) {
            val i = Intent(ctx, VoiceService::class.java).setAction(ACTION_TIMER_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
