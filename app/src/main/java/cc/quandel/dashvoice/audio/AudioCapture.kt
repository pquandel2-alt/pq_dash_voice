package cc.quandel.dashvoice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import cc.quandel.dashvoice.util.AppLog as Log
import kotlin.concurrent.thread

/**
 * Captures 16 kHz mono 16-bit PCM from the microphone and delivers fixed-size frames.
 * Default frame = 1280 samples (80 ms) to match the openWakeWord chunk size.
 */
class AudioCapture(
    private val sampleRate: Int = 16000,
    private val frameSamples: Int = 1280
) {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

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
            AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
            Log.d(TAG, "AcousticEchoCanceler aktiviert")
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
            Log.d(TAG, "NoiseSuppressor aktiviert")
        }
        worker = thread(name = "audio-capture") {
            val buf = ShortArray(frameSamples)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    onFrame(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
                }
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
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
