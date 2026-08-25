package com.supernova.anchor.receiver

// =============================================================================
// FILE: BootReceiver.kt
// =============================================================================
//
// WHAT THIS FILE DOES:
// Receives the BOOT_COMPLETED system broadcast fired after the device
// finishes rebooting. For a lost-device recovery app, this is critical:
// if the phone restarts (battery died, user rebooted, system update),
// Anchor must re-register any active trace alarms or the trace feature
// will be dead until the user manually sends a "trace" command again.
//
// STEP-BY-STEP FLOW:
// 1. System finishes boot → fires android.intent.action.BOOT_COMPLETED
// 2. Android looks up manifest-declared receivers with this intent-filter
// 3. If Anchor was not running, Android cold-starts the app process
// 4. onReceive() checks AppSettings.TRACE_ACTIVE
// 5. If true → read persisted session data (sender, interval, channel)
// 6. Re-schedule the next AlarmManager tick via TraceForegroundService
// 7. If false → do nothing (no active trace to resume)
//
// RELATIONSHIP TO OTHER FILES:
// - AndroidManifest.xml       : Declares this receiver with BOOT_COMPLETED filter
// - TraceForegroundService.kt : Provides scheduleNextTick() and persistSession()
// - AppSettings.kt            : Stores TRACE_ACTIVE, TRACE_SENDER_NUMBER, etc.
// - TraceAlarmReceiver.kt     : The receiver that AlarmManager fires at each tick
//
// PERMISSION REQUIRED:
//   RECEIVE_BOOT_COMPLETED (declared in AndroidManifest.xml)
//
// NOTE: On some OEMs (Xiaomi, OPPO, OnePlus), BOOT_COMPLETED may be
// delayed or dropped unless the app is also whitelisted in the OEM's
// "Auto Launch" or "Allow background activity" settings. This receiver
// handles the Android-standard path; OEM-specific paths are documented
// in the app's Settings > Permissions screen.
// =============================================================================

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.supernova.anchor.service.TraceForegroundService
import com.supernova.anchor.utils.AppSettings
import com.supernova.anchor.utils.DebugLogger
import com.supernova.anchor.utils.ReplyChannel

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Only handle the boot completed action. Ignore any other broadcasts
        // that might accidentally trigger this receiver.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        DebugLogger.init(context)
        DebugLogger.log(TAG, "Device boot completed — checking for resumed trace session")

        val appSettings = AppSettings(context)

        // Check if a trace was active before the reboot.
        // TRACE_ACTIVE is persisted to SharedPreferences, so it survives
        // process death and device reboots.
        if (!appSettings.getBoolean(AppSettings.TRACE_ACTIVE)) {
            DebugLogger.log(TAG, "No active trace session to resume")
            return
        }

        // Read the persisted session data.
        val senderNumber = appSettings.getString(AppSettings.TRACE_SENDER_NUMBER)
        val intervalStr = appSettings.getString(AppSettings.TRACE_INTERVAL_MINUTES)
        val channelStr = appSettings.getString(AppSettings.TRACE_REPLY_CHANNEL)

        if (senderNumber.isBlank()) {
            DebugLogger.log(TAG, "ERROR: TRACE_ACTIVE is true but sender number is blank — clearing stale state")
            appSettings.setBoolean(AppSettings.TRACE_ACTIVE, false)
            return
        }

        val interval = intervalStr.toIntOrNull() ?: 15
        val channel = runCatching { ReplyChannel.valueOf(channelStr) }.getOrDefault(ReplyChannel.TEXT)

        DebugLogger.log(TAG, "Resuming trace: sender=$senderNumber interval=$interval channel=$channel")

        // Re-schedule the next tick. TraceForegroundService will handle
        // whether this is CONTINUOUS mode (immediate start) or BURST mode
        // (schedule AlarmManager for later). We pass delayMinutes=0 so the
        // first tick happens immediately — the user likely wants to know
        // the device is back online after a reboot.
        TraceForegroundService.start(context, senderNumber, interval, channel)

        DebugLogger.log(TAG, "Trace resumed successfully after boot")
    }
}
