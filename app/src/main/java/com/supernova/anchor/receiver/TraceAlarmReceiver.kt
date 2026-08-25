package com.supernova.anchor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.supernova.anchor.service.TraceForegroundService
import com.supernova.anchor.utils.DebugLogger
import com.supernova.anchor.utils.ReplyChannel

/**
 * AlarmManager heartbeat receiver for trace burst mode.
 * Fires even when the device is in deep Doze, re-acquiring a WakeLock
 * before restarting or continuing the trace service.
 */
class TraceAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Force-wake CPU so we can restart the trace service if needed
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Anchor::TraceAlarm"
        )
        wakeLock.acquire(15_000)

        try {
            DebugLogger.log("TraceAlarm", "Heartbeat alarm fired")

            if (!TraceForegroundService.isRunning(context)) {
                val sender = intent.getStringExtra("sender_number") ?: return
                val interval = intent.getIntExtra("interval_minutes", 15)
                val replyChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ReplyChannel.entries[intent.getIntExtra("reply_channel", 0)]
                } else {
                    ReplyChannel.TEXT
                }
                TraceForegroundService.start(context, sender, interval, replyChannel)
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}
