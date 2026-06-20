package cc.quandel.dashvoice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import cc.quandel.dashvoice.util.AppLog as Log

/** Streams 16-bit PCM (as received from the HA TTS stage) to the speaker. */
class AudioPlayer {
    private var track: AudioTrack? = null
    private var sampleRate = 22050
    private var channels = 1
    private var bytesWritten = 0

    fun start(sampleRate: Int, channels: Int, @Suppress("UNUSED_PARAMETER") width: Int, volume: Float = 1f) {
        stop()
        this.sampleRate = sampleRate
        this.channels = channels
        bytesWritten = 0
        val channelMask =
            if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf =
            AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        Log.d(TAG, "AudioTrack state=${t.state} playState=${t.playState}")
        t.setVolume(volume.coerceIn(0f, 1f))
        t.play()
        track = t
    }

    fun write(pcm: ByteArray) {
        val n = track?.write(pcm, 0, pcm.size) ?: 0
        bytesWritten += maxOf(n, 0)
    }

    /**
     * Called when HA signals end of TTS audio (audio-stop). Computes the exact remaining
     * playback time from how many frames are still ahead of the playback head, lets STREAM
     * mode drain, and releases once — no flaky marker callback. In MODE_STREAM the marker is
     * often already passed by the time we get here, so it never fires; this is deterministic.
     */
    fun finishPlaying(handler: Handler, onDone: () -> Unit) {
        val t = track
        if (t == null) { onDone(); return }

        val writtenFrames = (bytesWritten / (channels * 2)).toInt()
        var done = false
        fun release() {
            if (done) return; done = true
            try { t.release() } catch (_: Exception) {}
            track = null; bytesWritten = 0
            onDone()
        }

        // Hardware output latency via hidden API (widely available, falls back to 200ms).
        val hwLatencyMs = try {
            val m = AudioTrack::class.java.getMethod("getLatency")
            (m.invoke(t) as? Int) ?: 200
        } catch (_: Exception) { 200 }

        // Frames still queued ahead of the playback head = what's left to play.
        val remainingMs = try {
            val head = t.playbackHeadPosition.toLong()
            (writtenFrames - head).coerceAtLeast(0L) * 1000L / sampleRate
        } catch (_: Exception) {
            writtenFrames.toLong() * 1000L / sampleRate
        }

        Log.d(TAG, "finishing: ${writtenFrames} frames @${sampleRate}Hz, remaining~${remainingMs}ms, hwLatency~${hwLatencyMs}ms")

        // Let the already-written buffer drain to the speaker, then release exactly once.
        try { t.stop() } catch (_: Exception) {}
        handler.postDelayed({ release() }, remainingMs + hwLatencyMs + 80L)
    }

    fun stop() {
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
        bytesWritten = 0
    }

    companion object {
        private const val TAG = "AudioPlayer"
    }
}
