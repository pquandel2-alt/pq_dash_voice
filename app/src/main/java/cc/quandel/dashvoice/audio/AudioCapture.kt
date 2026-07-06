package cc.quandel.dashvoice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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

    @SuppressLint("MissingPermission")
    fun start(onFrame: (ShortArray) -> Unit) {
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
        rec.startRecording()
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
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val frame = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                    if (gain > 1.0f) applyGain(frame)
                    onFrame(frame)
                }
            }
        }
    }

    /** Verstärkt Samples mit Peak-Clipping in-place (int16-Bereich). */
    private fun applyGain(frame: ShortArray) {
        for (i in frame.indices) {
            frame[i] = (frame[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        try { aec?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        aec = null; ns = null; agc = null
        recorder?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        recorder = null
    }

    companion object {
        private const val TAG = "AudioCapture"
    }
}
