package com.supernova.anchor.receiver

// =============================================================================
// FILE: TraceAlarmReceiver.kt
// =============================================================================
//
// WHAT THIS FILE DOES:
// AlarmManager heartbeat receiver for trace BURST mode. When the device is
// in deep Doze (sleep), AlarmManager fires this broadcast to wake the CPU so
// TraceForegroundService can acquire a fresh GPS fix and send it via SMS.
//
// CRITICAL FIXES APPLIED:
// 1. reply_channel was stored as a String extra in the Intent but read with
//    getIntExtra(), which always returned 0. Fixed to read as String and
//    parse with ReplyChannel.valueOf().
// 2. Previously this receiver was registered in AndroidManifest.xml but
//    NEVER triggered because TraceForegroundService.alarmPendingIntent()
//    used PendingIntent.getForegroundService() instead of getBroadcast().
//    The calling code in TraceForegroundService.kt has been fixed to use
//    getBroadcast() so this receiver actually fires.
//
// RELATIONSHIP TO OTHER FILES:
// - TraceForegroundService.kt : Schedules this receiver via AlarmManager.
//                               Must use PendingIntent.getBroadcast().
// - AppSettings.kt            : Stores TRACE_ACTIVE, TRACE_SENDER_NUMBER, etc.
// - AndroidManifest.xml       : Declares this receiver with exported="false"
// - ReplyChannel.kt           : TEXT vs DATA enum
//
// STEP-BY-STEP FLOW:
// 1. AlarmManager fires the pending intent (set by TraceForegroundService)
// 2. System delivers broadcast to this receiver (even from Doze/deep sleep)
// 3. WakeLock acquired to keep CPU awake during service start
// 4. Check if trace is still active (user may have sent "trace stop")
// 5. If inactive, do nothing (WakeLock auto-releases after 15s)
// 6. If active, read sender/interval/channel from Intent extras
// 7. Start TraceForegroundService with ACTION_START
// 8. Service acquires GPS, sends location, schedules next alarm, stops
// =============================================================================

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.supernova.anchor.service.TraceForegroundService
import com.supernova.anchor.utils.DebugLogger
import com.supernova.anchor.utils.ReplyChannel

class TraceAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TraceAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Force-wake CPU so we can restart the trace service if needed.
        // PARTIAL_WAKE_LOCK keeps CPU running without turning screen on.
        // ACQUIRE_CAUSES_WAKEUP ensures we break out of deep Doze sleep.
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Anchor::TraceAlarm"
        )
        // 15-second safety timeout — auto-releases even if something crashes.
        wakeLock.acquire(15_000)

        try {
            DebugLogger.log(TAG, "Heartbeat alarm fired")

            // Check if trace is still supposed to be running.
            // If the user sent "trace stop", TRACE_ACTIVE is false and we
            // should do nothing — the alarm will be cancelled on the next
            // schedule cycle, but this handles any race condition.
            if (!TraceForegroundService.isRunning(context)) {
                DebugLogger.log(TAG, "Trace not active, ignoring alarm")
                return
            }

            // Read session data from Intent extras (carried by the PendingIntent
            // that TraceForegroundService scheduled).
            val sender = intent.getStringExtra("sender_number") ?: run {
                DebugLogger.log(TAG, "ERROR: No sender_number in alarm intent")
                return
            }
            val interval = intent.getIntExtra("interval_minutes", 15)

            // CRITICAL FIX: reply_channel is stored as a String (channel.name).
            // The old code used getIntExtra("reply_channel", 0) which always
            // returned 0 because the extra is a String, not an Int.
            // Then it did ReplyChannel.entries[0] which happened to be TEXT
            // by luck — but would break if enum order changed.
            //
            // CORRECT: Read as String, parse with valueOf(), default to TEXT.
            val replyChannel = intent.getStringExtra("reply_channel")
                ?.let { runCatching { ReplyChannel.valueOf(it) }.getOrNull() }
                ?: ReplyChannel.TEXT

            DebugLogger.log(TAG, "Restarting trace: sender=$sender interval=$interval channel=$replyChannel")

            // Start the service to acquire GPS and send the location.
            // This will create a new service instance, do one tick, then stop.
            TraceForegroundService.start(context, sender, interval, replyChannel)
        } finally {
            // Always release the WakeLock so the device can sleep again.
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}
