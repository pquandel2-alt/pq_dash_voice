package cc.quandel.dashvoice

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cc.quandel.dashvoice.util.AppLog as Log
import java.util.Calendar

/**
 * Geplanter Selbst-Neustart: weckt die App einmal täglich (04:00) und startet Service + UI frisch,
 * um Hänger/Memory-Leaks (v.a. WebView) proaktiv abzuräumen. Re-plant danach den nächsten Alarm.
 *
 * Greift, solange der Prozess lebt; bei hartem EMUI-Kill ergänzen START_STICKY/BootReceiver.
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Geplanter Selbst-Neustart")
        // Service sicherstellen (START_STICKY) und UI frisch relaunchen (neuer WebView).
        VoiceService.start(context)
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Activity-Relaunch fehlgeschlagen: ${e.message}")
        }
        schedule(context)
    }

    companion object {
        private const val TAG = "Restart"
        private const val REQ = 7
        const val ACTION_RESTART = "cc.quandel.dashvoice.SELF_RESTART"

        /** Plant den nächsten Selbst-Neustart auf das kommende 04:00 Uhr. */
        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, REQ,
                Intent(context, RestartReceiver::class.java).setAction(ACTION_RESTART),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val next = nextRestartMillis()
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } catch (e: Exception) {
                am.set(AlarmManager.RTC_WAKEUP, next, pi)
            }
            Log.i(TAG, "Selbst-Neustart geplant für ${java.util.Date(next)}")
        }

        private fun nextRestartMillis(): Long {
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 4)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis
        }
    }
}
