package cc.quandel.dashvoice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import cc.quandel.dashvoice.util.AppLog as Log

/** Spielt einen loopenden Alarm-Ton + Vibration für abgelaufene Timer/Wecker. */
class Ringer(private val context: Context) {

    private var mp: MediaPlayer? = null
    private var vib: Vibrator? = null

    val isRinging: Boolean get() = mp != null

    fun start() {
        stop()
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
            @Suppress("DEPRECATION")
            vib = (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.apply {
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)
                vibrate(effect)
            }
            Log.i(TAG, "Ringer gestartet")
        } catch (e: Exception) {
            Log.w(TAG, "Ringer-Start fehlgeschlagen: ${e.message}")
        }
    }

    fun stop() {
        try { mp?.stop(); mp?.release() } catch (_: Exception) {}
        mp = null
        try { vib?.cancel() } catch (_: Exception) {}
        vib = null
    }

    companion object { private const val TAG = "Ringer" }
}
