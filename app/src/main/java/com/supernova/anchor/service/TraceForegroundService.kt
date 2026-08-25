package com.supernova.anchor.service

// =============================================================================
// FILE: TraceForegroundService.kt
// =============================================================================
//
// WHAT THIS FILE DOES:
// Foreground service for periodic GPS trace. Sends location updates via SMS
// at a user-configured interval (e.g., every 15 minutes). Two modes:
//
//   CONTINUOUS (interval <= 5 min): GPS + Fused listeners stay warm,
//   foreground notification visible the entire time. Needed because cold
//   GPS locks can take minutes — at fast intervals there's no safe window
//   to cold-start fresh every cycle.
//
//   BURST (interval > 5 min): Nothing held between sends. Each tick
//   acquires one fresh fix, sends it, then fully stops. The next tick is
//   scheduled via AlarmManager, which reliably wakes the process even
//   through Doze. No lingering notification between ticks.
//
// CRITICAL FIX: Removed static mutable fields (senderNumber, intervalMinutes,
// replyChannel). Previously these were companion object vars, meaning a second
// trace command from a different phone number would OVERWRITE the first's
// settings. The first trace would then send locations to the second sender.
// Now all session state is read from AppSettings (persisted) or Intent extras
// every time — safe across process restarts and concurrent sessions.
//
// RELATIONSHIP TO OTHER FILES:
// - AndroidManifest.xml         : Declares this service with
//                                   foregroundServiceType="location"
// - AppSettings.kt              : Persists TRACE_ACTIVE, TRACE_SENDER_NUMBER,
//                                   TRACE_INTERVAL_MINUTES, TRACE_REPLY_CHANNEL
// - TraceAlarmReceiver.kt       : Wakes the device for BURST mode ticks
// - SmsCommandProcessor.kt      : Starts/stops trace via "trace" command
// - LocationForegroundService.kt: Uses similar GPS+Fused race logic
// - DataSmsSender.kt            : Sends DATA channel replies
// - ReplyChannel.kt             : TEXT vs DATA enum
//
// STEP-BY-STEP FLOW (BURST mode):
// 1. SmsCommandProcessor calls start() → persistSession() saves settings
// 2. startForegroundService() launches this service with ACTION_START
// 3. onStartCommand() reads interval from Intent extras
// 4. If interval > 5 min → startBurstTick()
// 5. Register GPS + Fused listeners, hold WakeLock for bounded time
// 6. On good GPS fix (accuracy <= 20m) or timeout → finishBurstTick()
// 7. Build report, send via SMS on the correct channel, schedule next alarm
// 8. stopForeground() + stopSelf() — service dies, no lingering resources
// 9. AlarmManager fires at next interval → goto step 2
//
// STEP-BY-STEP FLOW (CONTINUOUS mode):
// 1-3 same as above
// 4. If interval <= 5 min → startContinuousMode()
// 5. Register GPS + Fused listeners, startForeground() with notification
// 6. Timer thread sleeps for interval, then wakes and sends report
// 7. Repeat until "trace stop" command or service killed
// =============================================================================

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.supernova.anchor.MainActivity
import com.supernova.anchor.R
import com.supernova.anchor.utils.AppSettings
import com.supernova.anchor.utils.DebugLogger
import com.supernova.anchor.utils.ReplyChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class TraceForegroundService : Service() {

    companion object {
        private const val TAG = "TraceForegroundService"
        private const val CHANNEL_ID = "trace_service_channel"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_START = "com.supernova.anchor.action.START_TRACE"
        private const val ACTION_STOP = "com.supernova.anchor.action.STOP_TRACE"
        private const val ALARM_REQUEST_CODE = 9001

        // At or below this interval, cold-starting GPS fresh every cycle
        // isn't safe — a worst-case cold lock can take close to this long.
        private const val CONTINUOUS_MODE_MAX_MINUTES = 5

        // BURST mode's per-tick acquisition budget.
        private const val BURST_MAX_ACQUISITION_MINUTES = 4L

        // Fast-path GPS threshold — same as LocationForegroundService.
        private const val GPS_GOOD_ACCURACY_M = 20f
        private const val GPS_GOOD_MAX_AGE_S = 60

        // =================================================================
        // CRITICAL FIX: Removed static mutable fields that were here:
        //   private var senderNumber: String = ""
        //   private var intervalMinutes: Int = 15
        //   private var replyChannel: ReplyChannel = ReplyChannel.TEXT
        //
        // These caused a race condition where a second trace command from a
        // different phone number would overwrite the first's settings. The
        // first trace would then leak location data to the second sender.
        //
        // Now all session state is read from AppSettings (persisted to disk)
        // or from Intent extras. This survives process restarts and is
        // immune to concurrent overwrites.
        // =================================================================

        /** Reads persisted state from AppSettings, not from memory.
         *  Correct even from a freshly restarted process. */
        fun isRunning(context: Context): Boolean =
            AppSettings(context).getBoolean(AppSettings.TRACE_ACTIVE)

        /** Starts or updates a trace session. State is persisted to
         *  AppSettings so it survives process death. */
        fun start(context: Context, number: String, interval: Int, channel: ReplyChannel) {
            val appSettings = AppSettings(context)
            val wasRunning = appSettings.getBoolean(AppSettings.TRACE_ACTIVE)

            // Persist ALL session state to SharedPreferences.
            // This is the single source of truth — no static fields.
            persistSession(context, active = true, number, interval, channel)

            if (wasRunning) {
                DebugLogger.log(TAG, "Trace updated: interval=$interval min, channel=$channel")
                // Reschedule immediately so the new interval takes effect now,
                // rather than waiting out whatever was left of the previous one.
                scheduleNextTick(context, delayMinutes = 0)
                return
            }

            try {
                context.startForegroundService(
                    buildTickIntent(context, number, interval, channel)
                )
            } catch (e: Exception) {
                // If the OS blocks the start (permission race), revert the
                // persisted "active" flag so isRunning() returns false.
                DebugLogger.log(TAG, "startForegroundService blocked: ${e.message}")
                persistSession(context, active = false, "", 15, ReplyChannel.TEXT)
                notifyStartFailure(context, number, channel, "Trace failed to start. Try again, or check Anchor's permissions.")
            }
        }

        private fun notifyStartFailure(context: Context, number: String, channel: ReplyChannel, message: String) {
            try {
                when (channel) {
                    ReplyChannel.DATA -> com.supernova.anchor.utils.DataSmsSender.send(context, number, message)
                    ReplyChannel.TEXT -> {
                        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.getSystemService(SmsManager::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            SmsManager.getDefault()
                        }
                        smsManager.sendTextMessage(number, null, message, null, null)
                    }
                }
            } catch (e: Exception) {
                DebugLogger.log(TAG, "Even the failure notice couldn't be sent: ${e.message}")
            }
        }

        fun stop(context: Context) {
            // Cancel unconditionally — BURST mode may be "asleep" with no
            // live Service instance, so this can't depend on in-memory state.
            cancelScheduledTick(context)
            persistSession(context, active = false, "", 15, ReplyChannel.TEXT)
            context.startService(Intent(context, TraceForegroundService::class.java).apply { action = ACTION_STOP })
        }

        /** Persists trace session state to SharedPreferences.
         *  This is the ONLY place trace state is written. */
        private fun persistSession(context: Context, active: Boolean, number: String, interval: Int, channel: ReplyChannel) {
            val s = AppSettings(context)
            s.setBoolean(AppSettings.TRACE_ACTIVE, active)
            s.setString(AppSettings.TRACE_SENDER_NUMBER, number)
            s.setString(AppSettings.TRACE_INTERVAL_MINUTES, interval.toString())
            s.setString(AppSettings.TRACE_REPLY_CHANNEL, channel.name)
        }

        /** Builds an Intent carrying all session data as extras.
         *  Used for both direct service starts and AlarmManager scheduling. */
        private fun buildTickIntent(context: Context, number: String, interval: Int, channel: ReplyChannel): Intent {
            return Intent(context, TraceForegroundService::class.java).apply {
                action = ACTION_START
                putExtra("sender_number", number)
                putExtra("interval_minutes", interval)
                putExtra("reply_channel", channel.name)
            }
        }

        /** Builds the PendingIntent that AlarmManager fires for BURST mode.
         *  CRITICAL FIX: Reads session state from AppSettings, not from
         *  static fields, so it's correct even after process restart. */
        private fun alarmPendingIntent(context: Context): PendingIntent {
            val appSettings = AppSettings(context)
            val number = appSettings.getString(AppSettings.TRACE_SENDER_NUMBER)
            val interval = appSettings.getString(AppSettings.TRACE_INTERVAL_MINUTES).toIntOrNull() ?: 15
            val channel = runCatching {
                ReplyChannel.valueOf(appSettings.getString(AppSettings.TRACE_REPLY_CHANNEL))
            }.getOrDefault(ReplyChannel.TEXT)

            val intent = buildTickIntent(context, number, interval, channel)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, ALARM_REQUEST_CODE, intent, flags)
            } else {
                PendingIntent.getService(context, ALARM_REQUEST_CODE, intent, flags)
            }
        }

        /** Schedules the next BURST tick via AlarmManager.
         *  Uses setExactAndAllowWhileIdle() if SCHEDULE_EXACT_ALARM is granted,
         *  otherwise falls back to setAndAllowWhileIdle() (less precise but works). */
        private fun scheduleNextTick(context: Context, delayMinutes: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = alarmPendingIntent(context)
            val triggerAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes)
            try {
                val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else true

                if (canScheduleExact) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    DebugLogger.log(TAG, "Next trace tick scheduled EXACTLY in $delayMinutes min")
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    DebugLogger.log(TAG, "SCHEDULE_EXACT_ALARM not granted — scheduled INEXACTLY in $delayMinutes min")
                }
            } catch (e: Exception) {
                DebugLogger.log(TAG, "Failed to schedule next tick: ${e.message}")
            }
        }

        private fun cancelScheduledTick(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(alarmPendingIntent(context))
        }
    }

    // --- CONTINUOUS mode state (instance-level, not static) ---
    private var continuousLocationManager: LocationManager? = null
    private var continuousFusedClient: FusedLocationProviderClient? = null
    private var continuousGpsListener: LocationListener? = null
    private var continuousFusedCallback: LocationCallback? = null
    private var continuousBestGps: Location? = null
    private var continuousBestFused: Location? = null
    // CRITICAL FIX: Marked @Volatile because accessed from timer thread
    // and main thread simultaneously.
    @Volatile
    private var timerThread: Thread? = null
    @Volatile
    private var isContinuousActive = false

    // --- BURST mode state (instance-level, not static) ---
    private var burstLocationManager: LocationManager? = null
    private var burstFusedClient: FusedLocationProviderClient? = null
    private var burstGpsListener: LocationListener? = null
    private var burstFusedCallback: LocationCallback? = null
    private var burstBestGps: Location? = null
    private var burstBestFused: Location? = null
    // CRITICAL FIX: Marked @Volatile — accessed from multiple threads.
    @Volatile
    private var burstDelivered = false
    private var burstWakeLock: PowerManager.WakeLock? = null
    private val burstHandler = Handler(Looper.getMainLooper())

    /** Acquires a WakeLock for a bounded time around a unit of work.
     *  Uses timed acquire (auto-releases) as a safety net. */
    private fun withWakeLock(timeoutMs: Long, tag: String, block: () -> Unit) {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Anchor::$tag")
        try {
            wakeLock?.acquire(timeoutMs)
            block()
        } finally {
            try {
                if (wakeLock != null && wakeLock.isHeld) wakeLock.release()
            } catch (e: Exception) {
                DebugLogger.log(TAG, "WakeLock release failed (likely already auto-released): ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DebugLogger.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Read session data from Intent extras (fresh start) or AppSettings
                // (process restart). Intent extras take precedence.
                val number = intent.getStringExtra("sender_number")
                    ?: AppSettings(this).getString(AppSettings.TRACE_SENDER_NUMBER)
                val interval = intent.getIntExtra("interval_minutes",
                    AppSettings(this).getString(AppSettings.TRACE_INTERVAL_MINUTES).toIntOrNull() ?: 15)
                val channel = intent.getStringExtra("reply_channel")
                    ?.let { runCatching { ReplyChannel.valueOf(it) }.getOrNull() }
                    ?: runCatching { ReplyChannel.valueOf(AppSettings(this).getString(AppSettings.TRACE_REPLY_CHANNEL)) }.getOrNull()
                    ?: ReplyChannel.TEXT

                persistSession(this, active = true, number, interval, channel)

                if (interval <= CONTINUOUS_MODE_MAX_MINUTES) {
                    startContinuousMode(number, interval, channel)
                } else {
                    startBurstTick(number, interval, channel)
                }
            }
            ACTION_STOP -> stopTraceSession("Trace stopped.")
        }
        return START_REDELIVER_INTENT
    }

    // =========================================================================
    // CONTINUOUS mode — interval <= CONTINUOUS_MODE_MAX_MINUTES
    // =========================================================================

    private fun startContinuousMode(number: String, interval: Int, channel: ReplyChannel) {
        if (isContinuousActive) return
        isContinuousActive = true

        startForeground(NOTIFICATION_ID, buildNotification("Trace active - every ${interval}min"))

        continuousLocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        continuousGpsListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (continuousBestGps == null || loc.accuracy < continuousBestGps!!.accuracy) {
                    continuousBestGps = loc
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            continuousLocationManager!!.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 15_000L, 0f, continuousGpsListener!!, Looper.getMainLooper()
            )
        } catch (e: Exception) {
            DebugLogger.log(TAG, "Continuous GPS register failed: ${e.message}")
        }

        continuousFusedClient = LocationServices.getFusedLocationProviderClient(this)
        continuousFusedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                if (continuousBestFused == null || loc.accuracy < continuousBestFused!!.accuracy) {
                    continuousBestFused = loc
                }
            }
        }
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L).build()
        try {
            continuousFusedClient!!.requestLocationUpdates(locationRequest, continuousFusedCallback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            DebugLogger.log(TAG, "Continuous Fused register failed: ${e.message}")
            sendTraceSms(channel, number, "Trace failed: location permission missing.")
            stopTraceSession(null)
            return
        }

        sendTraceSms(channel, number, "Trace started. Location every ${interval} min. Reply 'trace stop' to end.")
        DebugLogger.log(TAG, "Continuous trace started: interval=$interval min, sender=$number")

        timerThread = Thread {
            try {
                while (isContinuousActive) {
                    Thread.sleep(TimeUnit.MINUTES.toMillis(interval.toLong()))
                    if (!isContinuousActive) break
                    withWakeLock(30_000, "TraceContinuousSendWakeLock") {
                        val report = buildDualSourceReport(continuousBestGps, continuousBestFused)
                        sendTraceSms(channel, number, report ?: "Trace: Location unavailable")
                    }
                }
            } catch (e: InterruptedException) {
                // stopped
            }
        }.apply { start() }
    }

    // =========================================================================
    // BURST mode — interval > CONTINUOUS_MODE_MAX_MINUTES
    // =========================================================================

    private fun startBurstTick(number: String, interval: Int, channel: ReplyChannel) {
        burstDelivered = false
        burstBestGps = null
        burstBestFused = null

        val budgetMinutes = minOf(BURST_MAX_ACQUISITION_MINUTES, (interval - 1).toLong()).coerceAtLeast(1)
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Anchor::TraceBurstWakeLock")
        wakeLock?.acquire(TimeUnit.MINUTES.toMillis(budgetMinutes) + 30_000)
        burstWakeLock = wakeLock

        startForeground(NOTIFICATION_ID, buildNotification("Trace: acquiring location..."))

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        burstLocationManager = lm
        burstGpsListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (burstDelivered) return
                val ageSec = (System.currentTimeMillis() - loc.time) / 1000
                if (burstBestGps == null || loc.accuracy < burstBestGps!!.accuracy) burstBestGps = loc
                if (loc.accuracy <= GPS_GOOD_ACCURACY_M && ageSec < GPS_GOOD_MAX_AGE_S) {
                    finishBurstTick(number, interval, channel)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, burstGpsListener!!, Looper.getMainLooper())
        } catch (e: Exception) {
            DebugLogger.log(TAG, "Burst GPS register failed: ${e.message}")
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        burstFusedClient = fusedClient
        burstFusedCallback = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                if (burstDelivered) return
                val loc = r.lastLocation ?: return
                if (burstBestFused == null || loc.accuracy < burstBestFused!!.accuracy) burstBestFused = loc
            }
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(500)
            .build()
        try {
            fusedClient.requestLocationUpdates(req, burstFusedCallback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            DebugLogger.log(TAG, "Burst Fused register failed: ${e.message}")
            sendTraceSms(channel, number, "Trace failed: location permission missing.")
            stopTraceSession(null)
            return
        }

        burstHandler.postDelayed({ finishBurstTick(number, interval, channel) }, TimeUnit.MINUTES.toMillis(budgetMinutes))
        DebugLogger.log(TAG, "Burst tick started: interval=$interval min, budget=${budgetMinutes}min")
    }

    private fun finishBurstTick(number: String, interval: Int, channel: ReplyChannel) {
        if (burstDelivered) return
        burstDelivered = true
        burstHandler.removeCallbacksAndMessages(null)

        try { burstGpsListener?.let { burstLocationManager?.removeUpdates(it) } } catch (e: Exception) { }
        try { burstFusedCallback?.let { burstFusedClient?.removeLocationUpdates(it) } } catch (e: Exception) { }

        val report = buildDualSourceReport(burstBestGps, burstBestFused)
        sendTraceSms(channel, number, report ?: "Trace: Location unavailable")

        try {
            burstWakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            DebugLogger.log(TAG, "Burst WakeLock release failed (likely already auto-released): ${e.message}")
        }
        burstWakeLock = null

        scheduleNextTick(applicationContext, interval.toLong())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    /** Reports both GPS and Fused when both exist, even if one is low-accuracy.
     *  Never silently drop a reading just because it lost the race. */
    private fun buildDualSourceReport(gps: Location?, fused: Location?): String? {
        if (gps == null && fused == null) return null

        val primary: Location
        val primarySource: String
        val other: Location?
        val otherLabel: String

        if (gps != null && (fused == null || gps.accuracy <= GPS_GOOD_ACCURACY_M || gps.accuracy <= fused.accuracy)) {
            primary = gps; primarySource = "GPS"; other = fused; otherLabel = "Fused"
        } else {
            primary = fused!!; primarySource = "FUSED"; other = gps; otherLabel = "GPS"
        }

        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return buildString {
            append("Trace:
")
            append("${"%.6f".format(primary.latitude)},${"%.6f".format(primary.longitude)}
")
            append("Source: $primarySource, Acc: ±${"%.1f".format(primary.accuracy)}m
")
            append("Time: ${df.format(Date(primary.time))}
")
            append("https://maps.google.com/?q=${primary.latitude},${primary.longitude}")
            if (other != null) {
                append("
Also($otherLabel): https://maps.google.com/?q=${other.latitude},${other.longitude} ±${"%.1f".format(other.accuracy)}m")
            }
        }
    }

    /** Sends trace reply on the correct channel, with data→text fallback. */
    private fun sendTraceSms(channel: ReplyChannel, number: String, message: String) {
        when (channel) {
            ReplyChannel.TEXT -> sendRegularTextSmsFallback(number, message)
            ReplyChannel.DATA -> {
                com.supernova.anchor.data.MessageRepository.addMessage(
                    applicationContext,
                    com.supernova.anchor.data.ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        text = message,
                        sender = number,
                        timestamp = System.currentTimeMillis(),
                        isIncoming = false
                    )
                )
                when (val result = com.supernova.anchor.utils.DataSmsSender.send(applicationContext, number, message)) {
                    is com.supernova.anchor.utils.DataSmsSender.Result.Sent -> {
                        DebugLogger.log(TAG, "Trace SMS sent as data SMS (${result.parts} part(s))")
                    }
                    is com.supernova.anchor.utils.DataSmsSender.Result.PartialFailure -> {
                        DebugLogger.log(TAG, "Trace SMS: only ${result.sentParts}/${result.totalParts} parts sent (${result.reason}), sending full text SMS as a clean retry")
                        sendRegularTextSmsFallback(number, message)
                    }
                    is com.supernova.anchor.utils.DataSmsSender.Result.Failed -> {
                        DebugLogger.log(TAG, "Trace SMS: data SMS send failed (${result.reason}), sending as text SMS instead so it isn't dropped")
                        sendRegularTextSmsFallback(number, message)
                    }
                }
            }
        }
    }

    private fun sendRegularTextSmsFallback(phoneNumber: String, message: String) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            if (message.length > 160) {
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            }
        } catch (e: Exception) {
            DebugLogger.log(TAG, "SMS error: ${e.message}")
        }
    }

    private fun stopTraceSession(finalMessage: String?) {
        isContinuousActive = false
        timerThread?.interrupt()
        timerThread = null
        burstDelivered = true
        burstHandler.removeCallbacksAndMessages(null)

        try { continuousGpsListener?.let { continuousLocationManager?.removeUpdates(it) } } catch (e: Exception) { }
        try { continuousFusedCallback?.let { continuousFusedClient?.removeLocationUpdates(it) } } catch (e: Exception) { }
        try { burstGpsListener?.let { burstLocationManager?.removeUpdates(it) } } catch (e: Exception) { }
        try { burstFusedCallback?.let { burstFusedClient?.removeLocationUpdates(it) } } catch (e: Exception) { }
        try {
            burstWakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            DebugLogger.log(TAG, "Burst WakeLock release failed on stop (likely already auto-released): ${e.message}")
        }
        burstWakeLock = null

        cancelScheduledTick(applicationContext)
        persistSession(applicationContext, active = false, "", 15, ReplyChannel.TEXT)

        if (finalMessage != null) {
            // We don't have channel/number easily here for the stop message,
            // but the caller (SmsCommandProcessor) already sent a stop confirmation.
            // This path is mainly for internal errors.
        }
        DebugLogger.log(TAG, "Trace session stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anchor Trace")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_anchor_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Trace Service", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "GPS trace notifications"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // If continuous mode was active when killed, log it but don't restart.
        // The AlarmManager will bring us back for BURST mode; CONTINUOUS mode
        // requires the service to stay alive, so a kill means it stops.
        if (isContinuousActive) {
            DebugLogger.log(TAG, "Service destroyed while continuous mode was active — unexpected kill")
            isContinuousActive = false
        }
        super.onDestroy()
    }
}
