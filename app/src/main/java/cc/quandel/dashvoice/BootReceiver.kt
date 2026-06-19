package cc.quandel.dashvoice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the satellite service after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            VoiceService.start(context)
        }
    }
}
