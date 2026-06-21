package cc.quandel.dashvoice

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cc.quandel.dashvoice.util.AppLog as Log

/** Feuert wenn ein lokaler Timer abläuft → weckt VoiceService zum Klingeln. */
class TimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Timer abgelaufen → klingeln")
        VoiceService.fireTimer(context)
    }

    companion object {
        private const val TAG = "TimerReceiver"
        private const val REQ = 9
        const val ACTION_TIMER = "cc.quandel.dashvoice.TIMER_FIRED"

        fun schedule(context: Context, triggerAtMs: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(context)
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } catch (e: Exception) {
                am.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(context))
        }

        private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, REQ,
            Intent(context, TimerReceiver::class.java).setAction(ACTION_TIMER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
