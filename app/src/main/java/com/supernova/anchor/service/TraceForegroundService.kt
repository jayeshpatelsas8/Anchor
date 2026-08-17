package com.supernova.anchor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.supernova.anchor.MainActivity
import com.supernova.anchor.R
import com.supernova.anchor.utils.DebugLogger
import java.util.concurrent.TimeUnit

/**
 * Foreground service for continuous GPS trace.
 * Broadcasts location via SMS at regular intervals.
 * Runs with highest priority to prevent system kill.
 */
class TraceForegroundService : Service() {

    companion object {
        private const val TAG = "TraceForegroundService"
        private const val CHANNEL_ID = "trace_service_channel"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_START = "com.supernova.anchor.action.START_TRACE"
        private const val ACTION_STOP = "com.supernova.anchor.action.STOP_TRACE"

        @Volatile
        var isRunning = false
            private set

        private var senderNumber: String = ""
        private var intervalMinutes: Int = 15

        fun start(context: Context, number: String, interval: Int) {
            if (isRunning) {
                senderNumber = number
                intervalMinutes = interval
                DebugLogger.log(TAG, "Trace updated: interval=$interval min")
                return
            }
            val intent = Intent(context, TraceForegroundService::class.java).apply {
                action = ACTION_START
                putExtra("sender_number", number)
                putExtra("interval_minutes", interval)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TraceForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var currentLocation: android.location.Location? = null
    private var timerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                senderNumber = intent.getStringExtra("sender_number") ?: return START_NOT_STICKY
                intervalMinutes = intent.getIntExtra("interval_minutes", 15)
                startTrace()
            }
            ACTION_STOP -> {
                stopTrace()
            }
        }
        return START_STICKY
    }

    private fun startTrace() {
        if (isRunning) return
        isRunning = true

        val notification = buildNotification("Trace active - every ${intervalMinutes}min")
        startForeground(NOTIFICATION_ID, notification)

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L
        ).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                currentLocation = result.lastLocation
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest, locationCallback, Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
            sendSms(senderNumber, "Trace failed: location permission missing.")
            stopTrace()
            return
        }

        timerThread = Thread {
            while (isRunning) {
                try {
                    Thread.sleep(TimeUnit.MINUTES.toMillis(intervalMinutes.toLong()))
                    if (!isRunning) break
                    sendLocationSms()
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply { start() }

        sendSms(senderNumber, "Trace started. Location every ${intervalMinutes} min. Reply 'trace stop' to end.")
        DebugLogger.log(TAG, "Trace started: interval=$intervalMinutes min, sender=$senderNumber")
    }

    private fun sendLocationSms() {
        val loc = currentLocation
        if (loc == null) {
            sendSms(senderNumber, "Trace: Location unavailable")
            return
        }
        val mapsLink = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
        val message = """Trace:
${"%.6f".format(loc.latitude)},${"%.6f".format(loc.longitude)}
Acc:${loc.accuracy.toInt()}m
$mapsLink""".trimIndent()
        sendSms(senderNumber, message)
        DebugLogger.log(TAG, "Trace SMS: ${loc.latitude},${loc.longitude}")
    }

    private fun sendSms(phoneNumber: String, message: String) {
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

    private fun stopTrace() {
        isRunning = false
        timerThread?.interrupt()
        timerThread = null
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            // ignore
        }
        sendSms(senderNumber, "Trace stopped.")
        DebugLogger.log(TAG, "Trace stopped")
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
                description = "Continuous GPS trace notifications"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (isRunning) stopTrace()
        super.onDestroy()
    }
}
