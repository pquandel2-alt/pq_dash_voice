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
     * Called when HA signals end of TTS audio (audio-stop). Lets the AudioTrack drain
     * all buffered samples before releasing — prevents the tail being cut off.
     * Calls [onDone] on the main thread once playback is truly finished.
     */
    fun finishPlaying(handler: Handler, onDone: () -> Unit) {
        val t = track
        if (t == null) { onDone(); return }
        try { t.stop() } catch (_: Exception) {}
        // Estimate how many ms of audio are still in the hardware buffer.
        val drainMs = try {
            val writtenSamples = (bytesWritten / (channels * 2)).toLong()
            val playedSamples = t.playbackHeadPosition.toLong()
            val pendingSamples = (writtenSamples - playedSamples).coerceAtLeast(0L)
            (pendingSamples * 1000L / sampleRate).coerceAtMost(4000L)
        } catch (_: Exception) { 300L }
        Log.d(TAG, "drain ~${drainMs + 250}ms (wrote ${bytesWritten}B)")
        handler.postDelayed({
            try { t.release() } catch (_: Exception) {}
            track = null
            bytesWritten = 0
            onDone()
        }, drainMs + 250L)
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
