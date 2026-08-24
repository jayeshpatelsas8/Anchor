package com.supernova.anchor.service

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

/**
 * Foreground service for periodic GPS trace, sent via SMS at a configured
 * interval.
 *
 * Two modes, chosen purely by interval length:
 *
 *  - CONTINUOUS (interval <= CONTINUOUS_MODE_MAX_MINUTES): GPS + Fused
 *    listeners stay "warm" for the whole session, foreground service alive
 *    the entire time. Needed because a cold GPS lock can take up to several
 *    minutes in the worst case — at fast intervals there isn't a safe
 *    window to cold-start fresh for every single send.
 *
 *  - BURST (interval > CONTINUOUS_MODE_MAX_MINUTES): nothing is held
 *    between sends. Each tick acquires one fresh fix using the same GPS +
 *    Fused race LocationForegroundService uses for `locate` (fast-path on
 *    GPS accuracy<=20m within 60s, else best-available after a bounded
 *    timeout), sends it, then fully stops — no lingering notification, no
 *    idle polling — and schedules the next tick via AlarmManager, which
 *    reliably wakes the process even through Doze.
 *
 * Both modes always report GPS AND Fused when both produced a reading, even
 * if one is low-accuracy — same principle as LocationForegroundService's
 * `locate` report: never silently drop a reading just because it lost the
 * race, the requester can judge accuracy for themselves.
 *
 * Session state (active/sender/interval/channel) is persisted to
 * AppSettings, not just held in memory — a BURST-mode trace can sit for a
 * long time between ticks, and Android is free to kill the whole process
 * while it's "asleep" between AlarmManager firings. Every scheduled alarm's
 * Intent also carries the same extras directly, so a freshly restarted
 * process reconstructs the session from the Intent alone, with no
 * dependency on in-memory state having survived.
 */
class TraceForegroundService : Service() {

    companion object {
        private const val TAG = "TraceForegroundService"
        private const val CHANNEL_ID = "trace_service_channel"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_START = "com.supernova.anchor.action.START_TRACE"
        private const val ACTION_STOP = "com.supernova.anchor.action.STOP_TRACE"
        private const val ALARM_REQUEST_CODE = 9001

        // At or below this interval, cold-starting GPS fresh every cycle
        // isn't safe — a worst-case cold lock can take close to this long,
        // leaving no margin. Above it, there's comfortably enough slack to
        // stop between sends and re-acquire fresh each time.
        private const val CONTINUOUS_MODE_MAX_MINUTES = 5

        // BURST mode's per-tick acquisition budget: generous enough to
        // match the worst-case cold-lock time, but always leaves at least
        // 1 minute of margin before the next scheduled tick.
        private const val BURST_MAX_ACQUISITION_MINUTES = 4L

        // Same fast-path GPS threshold LocationForegroundService uses for `locate`.
        private const val GPS_GOOD_ACCURACY_M = 20f
        private const val GPS_GOOD_MAX_AGE_S = 60

        private var senderNumber: String = ""
        private var intervalMinutes: Int = 15
        private var replyChannel: ReplyChannel = ReplyChannel.TEXT

        /** Reads persisted state, not in-memory — correct even from a freshly restarted process. */
        fun isRunning(context: Context): Boolean =
            AppSettings(context).getBoolean(AppSettings.TRACE_ACTIVE)

        fun start(context: Context, number: String, interval: Int, channel: ReplyChannel) {
            val appSettings = AppSettings(context)
            val wasRunning = appSettings.getBoolean(AppSettings.TRACE_ACTIVE)

            senderNumber = number
            intervalMinutes = interval
            replyChannel = channel
            persistSession(context, active = true, number, interval, channel)

            if (wasRunning) {
                DebugLogger.log(TAG, "Trace updated: interval=$interval min, channel=$channel")
                // Reschedule immediately so the new interval takes effect
                // from now, rather than waiting out whatever was left of
                // the previous one.
                scheduleNextTick(context, delayMinutes = 0)
                return
            }

            try {
                context.startForegroundService(buildTickIntent(context, number, interval, channel))
            } catch (e: Exception) {
                // Backstop for the rare race where SmsCommandProcessor's own
                // ACCESS_BACKGROUND_LOCATION check passed but the OS still
                // refuses the start. Without this, the failure is silent —
                // no reply — AND persistSession() above already marked the
                // session active, which would leave TRACE_ACTIVE stuck true
                // for a trace that never actually started. Revert it.
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
            // Cancel unconditionally — a BURST-mode trace may currently be
            // "asleep" with no live Service instance at all, so this can't
            // depend on isRunning or any in-memory state to actually work.
            cancelScheduledTick(context)
            persistSession(context, active = false, "", 15, ReplyChannel.TEXT)
            context.startService(Intent(context, TraceForegroundService::class.java).apply { action = ACTION_STOP })
        }

        private fun persistSession(context: Context, active: Boolean, number: String, interval: Int, channel: ReplyChannel) {
            val s = AppSettings(context)
            s.setBoolean(AppSettings.TRACE_ACTIVE, active)
            s.setString(AppSettings.TRACE_SENDER_NUMBER, number)
            s.setString(AppSettings.TRACE_INTERVAL_MINUTES, interval.toString())
            s.setString(AppSettings.TRACE_REPLY_CHANNEL, channel.name)
        }

        private fun buildTickIntent(context: Context, number: String, interval: Int, channel: ReplyChannel): Intent {
            return Intent(context, TraceForegroundService::class.java).apply {
                action = ACTION_START
                putExtra("sender_number", number)
                putExtra("interval_minutes", interval)
                putExtra("reply_channel", channel.name)
            }
        }

        private fun alarmPendingIntent(context: Context): PendingIntent {
            val intent = buildTickIntent(context, senderNumber, intervalMinutes, replyChannel)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, ALARM_REQUEST_CODE, intent, flags)
            } else {
                PendingIntent.getService(context, ALARM_REQUEST_CODE, intent, flags)
            }
        }

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
                    // Falls back to an inexact wake — still fires, may just
                    // be a bit late. Happens when the user hasn't granted
                    // the "Alarms & reminders" special access on Android 12+.
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

    // --- CONTINUOUS mode state ---
    private var continuousLocationManager: LocationManager? = null
    private var continuousFusedClient: FusedLocationProviderClient? = null
    private var continuousGpsListener: LocationListener? = null
    private var continuousFusedCallback: LocationCallback? = null
    private var continuousBestGps: Location? = null
    private var continuousBestFused: Location? = null
    private var timerThread: Thread? = null
    private var isContinuousActive = false

    // --- BURST mode state ---
    private var burstLocationManager: LocationManager? = null
    private var burstFusedClient: FusedLocationProviderClient? = null
    private var burstGpsListener: LocationListener? = null
    private var burstFusedCallback: LocationCallback? = null
    private var burstBestGps: Location? = null
    private var burstBestFused: Location? = null
    private var burstDelivered = false
    private var burstWakeLock: PowerManager.WakeLock? = null
    private val burstHandler = Handler(Looper.getMainLooper())

    /**
     * Unlike LocationForegroundService's single 30s wakelock (one bounded
     * operation, one lock), trace has two very different needs: BURST mode
     * needs one lock per tick (bounded, like locate), while CONTINUOUS mode
     * runs indefinitely and only needs the CPU awake for the brief moment
     * of building+sending each periodic report — not for the idle time in
     * between. Holding one lock for the whole session would waste battery
     * for no real benefit; the LocationRequest registration itself doesn't
     * need the CPU held awake to keep receiving updates.
     *
     * So: acquire fresh, briefly, around each actual unit of work, using a
     * timed acquire (auto-releases even if release() is never explicitly
     * reached — same safety-net pattern LocationForegroundService already
     * uses) rather than one long-lived indefinite lock.
     */
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
                senderNumber = intent.getStringExtra("sender_number") ?: return START_NOT_STICKY
                intervalMinutes = intent.getIntExtra("interval_minutes", 15)
                replyChannel = intent.getStringExtra("reply_channel")
                    ?.let { runCatching { ReplyChannel.valueOf(it) }.getOrNull() }
                    ?: ReplyChannel.TEXT

                persistSession(this, active = true, senderNumber, intervalMinutes, replyChannel)

                if (intervalMinutes <= CONTINUOUS_MODE_MAX_MINUTES) {
                    startContinuousMode()
                } else {
                    startBurstTick()
                }
            }
            ACTION_STOP -> stopTraceSession("Trace stopped.")
        }
        return START_REDELIVER_INTENT
    }

    // =========================================================================
    // CONTINUOUS mode — interval <= CONTINUOUS_MODE_MAX_MINUTES
    // =========================================================================

    private fun startContinuousMode() {
        if (isContinuousActive) return
        isContinuousActive = true

        startForeground(NOTIFICATION_ID, buildNotification("Trace active - every ${intervalMinutes}min"))

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
        // 15s instead of the old hardcoded 5s — still "warm", far less
        // wasted polling for updates that only get used once per interval.
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L).build()
        try {
            continuousFusedClient!!.requestLocationUpdates(locationRequest, continuousFusedCallback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            DebugLogger.log(TAG, "Continuous Fused register failed: ${e.message}")
            sendTraceSms("Trace failed: location permission missing.")
            stopTraceSession(null)
            return
        }

        sendTraceSms("Trace started. Location every ${intervalMinutes} min. Reply 'trace stop' to end.")
        DebugLogger.log(TAG, "Continuous trace started: interval=$intervalMinutes min, sender=$senderNumber")

        timerThread = Thread {
            try {
                while (isContinuousActive) {
                    Thread.sleep(TimeUnit.MINUTES.toMillis(intervalMinutes.toLong()))
                    if (!isContinuousActive) break
                    // CPU only needs to be held awake for this brief send,
                    // not for the idle Thread.sleep() above — see
                    // withWakeLock's doc comment.
                    withWakeLock(30_000, "TraceContinuousSendWakeLock") {
                        val report = buildDualSourceReport(continuousBestGps, continuousBestFused)
                        sendTraceSms(report ?: "Trace: Location unavailable")
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

    private fun startBurstTick() {
        burstDelivered = false
        burstBestGps = null
        burstBestFused = null

        val budgetMinutes = minOf(BURST_MAX_ACQUISITION_MINUTES, (intervalMinutes - 1).toLong()).coerceAtLeast(1)
        // Held for the whole acquisition+send window, same bounded pattern
        // LocationForegroundService uses for `locate` — this IS the
        // critical case: without it, nothing stops the CPU from dozing
        // mid-acquisition between ticks, which is exactly the "trace goes
        // silent for long intervals" symptom this mode exists to fix.
        // +30s buffer beyond the acquisition budget covers send time itself.
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
                    finishBurstTick()
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
            sendTraceSms("Trace failed: location permission missing.")
            stopTraceSession(null)
            return
        }

        burstHandler.postDelayed({ finishBurstTick() }, TimeUnit.MINUTES.toMillis(budgetMinutes))
        DebugLogger.log(TAG, "Burst tick started: interval=$intervalMinutes min, budget=${budgetMinutes}min")
    }

    private fun finishBurstTick() {
        if (burstDelivered) return
        burstDelivered = true
        burstHandler.removeCallbacksAndMessages(null)

        try { burstGpsListener?.let { burstLocationManager?.removeUpdates(it) } } catch (e: Exception) { }
        try { burstFusedCallback?.let { burstFusedClient?.removeLocationUpdates(it) } } catch (e: Exception) { }

        val report = buildDualSourceReport(burstBestGps, burstBestFused)
        sendTraceSms(report ?: "Trace: Location unavailable")

        try {
            burstWakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            DebugLogger.log(TAG, "Burst WakeLock release failed (likely already auto-released): ${e.message}")
        }
        burstWakeLock = null

        scheduleNextTick(applicationContext, intervalMinutes.toLong())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // =========================================================================
    // Shared
    // =========================================================================

    /**
     * Same principle as LocationForegroundService.deliver(): report both
     * sources whenever both exist, even if one is low-accuracy — never
     * silently drop a reading just because it lost the race. Returns null
     * only if neither source produced anything at all.
     */
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
            append("Trace:\n")
            append("${"%.6f".format(primary.latitude)},${"%.6f".format(primary.longitude)}\n")
            append("Source: $primarySource, Acc: \u00b1${"%.1f".format(primary.accuracy)}m\n")
            append("Time: ${df.format(Date(primary.time))}\n")
            append("https://maps.google.com/?q=${primary.latitude},${primary.longitude}")
            if (other != null) {
                append("\nAlso($otherLabel): https://maps.google.com/?q=${other.latitude},${other.longitude} \u00b1${"%.1f".format(other.accuracy)}m")
            }
        }
    }

    /**
     * Replies on whichever channel the trace command arrived on
     * ([replyChannel], updated every time start() is called — including
     * re-invocation to change the interval, and refreshed from the Intent
     * on every BURST-mode tick). Deterministic, same as
     * LocationForegroundService.sendSms(). MessageRepository (Binary Mode's
     * chat log) only gets a local echo on the DATA branch — a TEXT-channel
     * reply is a real regular SMS with no data-SMS involved at all, so it
     * has no business appearing in that thread.
     */
    private fun sendTraceSms(message: String) {
        when (replyChannel) {
            ReplyChannel.TEXT -> sendRegularTextSmsFallback(senderNumber, message)
            ReplyChannel.DATA -> {
                com.supernova.anchor.data.MessageRepository.addMessage(
                    applicationContext,
                    com.supernova.anchor.data.ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        text = message,
                        sender = senderNumber,
                        timestamp = System.currentTimeMillis(),
                        isIncoming = false
                    )
                )
                when (val result = com.supernova.anchor.utils.DataSmsSender.send(applicationContext, senderNumber, message)) {
                    is com.supernova.anchor.utils.DataSmsSender.Result.Sent -> {
                        DebugLogger.log(TAG, "Trace SMS sent as data SMS (${result.parts} part(s))")
                    }
                    is com.supernova.anchor.utils.DataSmsSender.Result.PartialFailure -> {
                        DebugLogger.log(TAG, "Trace SMS: only ${result.sentParts}/${result.totalParts} parts sent (${result.reason}), sending full text SMS as a clean retry")
                        sendRegularTextSmsFallback(senderNumber, message)
                    }
                    is com.supernova.anchor.utils.DataSmsSender.Result.Failed -> {
                        DebugLogger.log(TAG, "Trace SMS: data SMS send failed (${result.reason}), sending as text SMS instead so it isn't dropped")
                        sendRegularTextSmsFallback(senderNumber, message)
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

        if (finalMessage != null) sendTraceSms(finalMessage)
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
        // Only a real user-initiated stop should cancel the scheduled next
        // tick — a BURST-mode tick calling stopSelf() after a successful
        // send must NOT be treated as "trace ended" here, since the next
        // tick is already correctly scheduled via AlarmManager at that
        // point. isContinuousActive/burstDelivered being false is what
        // distinguishes an unexpected kill (worth logging) from a normal
        // completed tick.
        if (isContinuousActive) {
            DebugLogger.log(TAG, "Service destroyed while continuous mode was active — unexpected kill")
        }
        super.onDestroy()
    }
}
