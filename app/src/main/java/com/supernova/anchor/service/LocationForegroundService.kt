package com.supernova.anchor.service

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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.supernova.anchor.MainActivity
import com.supernova.anchor.R
import com.supernova.anchor.utils.DebugLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocationForegroundService : Service() {

    companion object {
        private const val TAG = "LocFgService"
        private const val NOTIF_ID = 9999
        private const val CHANNEL_ID = "anchor_location_channel"

        fun start(context: Context, senderNumber: String) {
            val intent = Intent(context, LocationForegroundService::class.java).apply {
                putExtra("sender", senderNumber)
            }
            context.startForegroundService(intent)
        }
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private val handler = Handler(Looper.getMainLooper())
    private var delivered = false
    private var bestLocation: Location? = null
    private var bestSource = "FAILED"
    private lateinit var senderNumber: String

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    override fun onCreate() {
        super.onCreate()
        DebugLogger.init(this)
        DebugLogger.log(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        senderNumber = intent?.getStringExtra("sender") ?: run {
            DebugLogger.log(TAG, "No sender in intent, stopping")
            stopSelf()
            return START_REDELIVER_INTENT
        }

        DebugLogger.log(TAG, ">>> START for $senderNumber")

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Anchor::FgLoc"
        )
        wakeLock.acquire(30_000)

        startForeground(NOTIF_ID, buildNotification())

        // --- Raw GPS Provider ---
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (!delivered) {
                    val ageSec = (System.currentTimeMillis() - loc.time) / 1000
                    DebugLogger.log(TAG, "Raw GPS: acc=${loc.accuracy}m age=${ageSec}s")
                    if (bestLocation == null || loc.accuracy < bestLocation!!.accuracy) {
                        bestLocation = loc
                        bestSource = "GPS"
                    }
                    if (loc.accuracy <= 20f && ageSec < 60) {
                        deliver(loc, "GPS")
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0L, 0f, gpsListener, Looper.getMainLooper()
            )
            DebugLogger.log(TAG, "GPS listener registered")
        } catch (e: Exception) {
            DebugLogger.log(TAG, "GPS register failed: ${e.message}")
        }

        // --- Fused Parallel (Maps-level config) ---
        val fusedCb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                if (!delivered) {
                    val loc = r.lastLocation ?: return
                    val ageSec = (System.currentTimeMillis() - loc.time) / 1000
                    DebugLogger.log(TAG, "Fused: acc=${loc.accuracy}m age=${ageSec}s")
                    if (bestLocation == null || loc.accuracy < bestLocation!!.accuracy) {
                        bestLocation = loc
                        bestSource = if (ageSec < 30) "FUSED" else "CACHE"
                    }
                }
            }
        }

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(500)
            .setMaxUpdateDelayMillis(2000L)   // ← NEW: Maps-style max delay
            .setDurationMillis(25_000)
            .build()

        try {
            fusedClient.requestLocationUpdates(req, fusedCb, Looper.getMainLooper())
            DebugLogger.log(TAG, "Fused listener registered")
        } catch (e: Exception) {
            DebugLogger.log(TAG, "Fused register failed: ${e.message}")
        }

        // --- Timeout ---
        handler.postDelayed({
            if (!delivered) {
                if (bestLocation != null) {
                    deliver(bestLocation!!, bestSource)
                } else {
                    DebugLogger.log(TAG, "Timeout, trying lastLocation cache")
                    fusedClient.lastLocation
                        .addOnSuccessListener { loc ->
                            if (loc != null) deliver(loc, "CACHE") else fail()
                        }
                        .addOnFailureListener { fail() }
                }
            }
        }, 20_000)

        return START_REDELIVER_INTENT  // ← CHANGED: restarts with same intent if killed by OEM
    }

    private fun deliver(location: Location, source: String) {
        if (delivered) return
        delivered = true
        DebugLogger.log(TAG, "DELIVERING source=$source acc=${location.accuracy}")

        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val time = df.format(Date(location.time))
        val ageSec = (System.currentTimeMillis() - location.time) / 1000

        val msg = buildString {
            appendLine("Device location:")
            appendLine("Lat: ${location.latitude}, Lng: ${location.longitude}")
            appendLine("https://maps.google.com/maps?q=${location.latitude},${location.longitude}")
            appendLine("Recorded: $time")
            append("Source: $source")
            if (source != "GPS") appendLine("\n[FALLBACK: age=${ageSec}s]") else appendLine()
        }

        sendSms(senderNumber, msg)
        cleanup()
        stopSelf()
    }

    private fun fail() {
        if (delivered) return
        delivered = true
        DebugLogger.log(TAG, "COMPLETE FAILURE")
        sendSms(senderNumber, "Location unavailable. No GPS fix and no cache.")
        cleanup()
        stopSelf()
    }

    private fun sendSms(number: String, text: String) {
        try {
            val sms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            if (text.length > 160) {
                val parts = sms.divideMessage(text)
                sms.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                sms.sendTextMessage(number, null, text, null, null)
            }
            DebugLogger.log(TAG, "SMS sent to $number")
        } catch (e: Exception) {
            DebugLogger.log(TAG, "SMS send failed: ${e.message}")
        }
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
    }

    override fun onDestroy() {
        cleanup()
        DebugLogger.log(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Anchor Location", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anchor")
            .setContentText("Acquiring location...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)  // ← NEW: Maps-level priority
            .build()
    }
}
