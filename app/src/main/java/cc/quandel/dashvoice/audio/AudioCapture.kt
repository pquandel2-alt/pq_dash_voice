package cc.quandel.dashvoice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import cc.quandel.dashvoice.util.AppLog as Log
import kotlin.concurrent.thread

/**
 * Captures 16 kHz mono 16-bit PCM from the microphone and delivers fixed-size frames.
 * Default frame = 1280 samples (80 ms) to match the openWakeWord chunk size.
 */
class AudioCapture(
    private val sampleRate: Int = 16000,
    private val frameSamples: Int = 1280,
    /** Software-Verstärkung (1.0 = aus). Ersetzt fehlendes Hardware-AGC für Far-field. */
    gain: Float = 1.0f,
    /** NoiseSuppressor aktivieren (falls Hardware verfügbar). */
    private val noiseSuppression: Boolean = true
) {
    /** Live änderbar (VoiceService.applySettings) — wird pro Frame ausgewertet, kein Neustart nötig. */
    @Volatile var gain: Float = gain
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    // Referenzen halten, damit die Effekte nicht weg-GC't werden
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private val gainLimiter = PcmGainLimiter()
    @Volatile private var latestStats: PcmGainLimiter.Stats? = null
    @Volatile private var startedAtMs = 0L
    @Volatile private var lastFrameAtMs = 0L
    @Volatile private var terminalError: String? = null

    @SuppressLint("MissingPermission")
    fun start(onFrame: (ShortArray) -> Unit, onFailure: (String) -> Unit = {}) {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, frameSamples * 2 * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            rec.release()
            return
        }
        recorder = rec
        running = true
        startedAtMs = SystemClock.elapsedRealtime()
        lastFrameAtMs = 0L
        terminalError = null
        try {
            rec.startRecording()
        } catch (e: Exception) {
            running = false
            recorder = null
            val reason = "AudioRecord start failed: ${e.javaClass.simpleName} ${e.message}"
            terminalError = reason
            Log.e(TAG, reason)
            rec.release()
            onFailure(reason)
            return
        }
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
            Log.d(TAG, "AcousticEchoCanceler aktiviert")
        }
        if (noiseSuppression && NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
            Log.d(TAG, "NoiseSuppressor aktiviert")
        } else if (!noiseSuppression) {
            Log.d(TAG, "NoiseSuppressor per Einstellung deaktiviert")
        }
        // AutomaticGainControl: hebt leise/entfernte Sprache → bessere Far-field-Erkennung.
        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true }
            Log.d(TAG, "AutomaticGainControl aktiviert")
        } else {
            Log.d(TAG, "AutomaticGainControl nicht verfügbar")
        }
        if (gain > 1.0f) Log.d(TAG, "Software-Mic-Gain aktiv: ${gain}x")
        worker = thread(name = "audio-capture") {
            val buf = ShortArray(frameSamples)
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    when {
                        n > 0 -> {
                            lastFrameAtMs = SystemClock.elapsedRealtime()
                            val frame = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                            latestStats = gainLimiter.process(frame, gain)
                            onFrame(frame)
                        }
                        n < 0 && running -> {
                            val reason = "AudioRecord.read failed: ${readErrorName(n)} ($n)"
                            terminalError = reason
                            running = false
                            Log.e(TAG, reason)
                            onFailure(reason)
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    val reason = "Audio capture crashed: ${e.javaClass.simpleName} ${e.message}"
                    terminalError = reason
                    running = false
                    Log.e(TAG, reason, e)
                    onFailure(reason)
                }
            }
        }
    }

    fun audioStats(): PcmGainLimiter.Stats? = latestStats

    /** Returns a diagnostic when AudioRecord looks alive to Android but stopped producing PCM. */
    fun healthIssue(maxFrameAgeMs: Long): String? {
        terminalError?.let { return it }
        if (!running) return "AudioCapture is not running"
        if (worker?.isAlive != true) return "audio-capture thread is not alive"
        val recording = try {
            recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (_: Exception) {
            false
        }
        if (!recording) return "AudioRecord is not recording"

        val now = SystemClock.elapsedRealtime()
        val reference = lastFrameAtMs.takeIf { it > 0L } ?: startedAtMs
        val age = now - reference
        return if (reference > 0L && age > maxFrameAgeMs) {
            "no PCM frame for ${age}ms"
        } else null
    }

    fun stop() {
        running = false
        val activeRecorder = recorder
        try { activeRecorder?.stop() } catch (_: Exception) {}
        val activeWorker = worker
        if (activeWorker != Thread.currentThread()) activeWorker?.join(1_000)
        worker = null
        try { aec?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        aec = null; ns = null; agc = null
        try { activeRecorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    private fun readErrorName(code: Int): String = when (code) {
        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        AudioRecord.ERROR -> "ERROR"
        else -> "UNKNOWN"
    }

    companion object {
        private const val TAG = "AudioCapture"
    }
}
