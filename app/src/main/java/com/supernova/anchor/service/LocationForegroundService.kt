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

        fun start(context: Context, senderNumber: String, replyChannel: com.supernova.anchor.utils.ReplyChannel) {
            val intent = Intent(context, LocationForegroundService::class.java).apply {
                putExtra("sender", senderNumber)
                putExtra("reply_channel", replyChannel.name)
            }
            context.startForegroundService(intent)
        }
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private val handler = Handler(Looper.getMainLooper())
    private var delivered = false
    private var bestLocation: Location? = null
    private var bestSource = "FAILED"
    // Tracked independently of bestLocation/bestSource above (which only
    // remembers whichever source is currently winning) so the final report
    // can show BOTH sources' own best reading, even the one that lost.
    private var bestGpsLocation: Location? = null
    private var bestFusedLocation: Location? = null
    private lateinit var senderNumber: String
    private var replyChannel: com.supernova.anchor.utils.ReplyChannel = com.supernova.anchor.utils.ReplyChannel.TEXT

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
        replyChannel = intent.getStringExtra("reply_channel")
            ?.let { runCatching { com.supernova.anchor.utils.ReplyChannel.valueOf(it) }.getOrNull() }
            ?: com.supernova.anchor.utils.ReplyChannel.TEXT

        DebugLogger.log(TAG, ">>> START for $senderNumber via $replyChannel")

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
                    if (bestGpsLocation == null || loc.accuracy < bestGpsLocation!!.accuracy) {
                        bestGpsLocation = loc
                    }
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
                    if (bestFusedLocation == null || loc.accuracy < bestFusedLocation!!.accuracy) {
                        bestFusedLocation = loc
                    }
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
            .setMaxUpdateDelayMillis(2000L)
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

        return START_REDELIVER_INTENT
    }

    private fun deliver(location: Location, source: String) {
        if (delivered) return
        delivered = true
        DebugLogger.log(TAG, "DELIVERING source=$source acc=${location.accuracy}")

        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val time = df.format(Date(location.time))
        val ageSec = (System.currentTimeMillis() - location.time) / 1000

        // The source that WASN'T delivered, if it produced any reading at
        // all — reported regardless of how poor its accuracy is. Nothing
        // gets silently dropped just because it lost the race in deliver().
        // (source is "GPS", "FUSED", or "CACHE"; CACHE only happens on the
        // full-timeout last-resort path, where there's no "other" reading
        // to compare against, so it just falls through to null below.)
        val other: Location? = when (source) {
            "GPS" -> bestFusedLocation
            "FUSED", "CACHE" -> bestGpsLocation
            else -> null
        }
        val otherLabel = if (source == "GPS") "Fused" else "GPS"

        // ONE canonical message — same content regardless of which channel
        // ends up carrying it. sendSms() replies on whichever channel the
        // original command arrived on (see sendSms's doc comment) — the
        // content itself never changes based on transport.
        val report = buildString {
            appendLine("Device location:")
            appendLine("Lat: ${location.latitude}, Lng: ${location.longitude}")
            appendLine("https://maps.google.com/maps?q=${location.latitude},${location.longitude}")
            appendLine("Recorded: $time")
            appendLine("Source: $source")
            appendLine("Accuracy: \u00b1${"%.1f".format(location.accuracy)}m")
            if (source != "GPS") appendLine("[FALLBACK: age=${ageSec}s]")
            if (other != null) {
                val otherAgeSec = (System.currentTimeMillis() - other.time) / 1000
                appendLine()
                appendLine("Also received ($otherLabel, not used):")
                appendLine("Lat: ${other.latitude}, Lng: ${other.longitude}")
                appendLine("https://maps.google.com/maps?q=${other.latitude},${other.longitude}")
                appendLine("Recorded: ${df.format(Date(other.time))}")
                append("Accuracy: \u00b1${"%.1f".format(other.accuracy)}m (age=${otherAgeSec}s)")
            }
        }.trimEnd()

        // Same information as [report] above, laid out as natural fields
        // instead of one long string — for the DATA channel, so a multi-part
        // reply splits at meaningful boundaries (the link is whole, the date
        // is whole, the source line is whole) rather than an arbitrary byte
        // cut through the middle of one. The coordinate line is dropped here
        // specifically because the maps link already carries the same
        // lat/lng in its query string — no need to send it twice when every
        // extra field is its own SMS. sendSms() below only uses this list
        // when replyChannel is DATA; TEXT still sends the unified [report].
        val dataParts = buildList {
            add("https://maps.google.com/maps?q=${location.latitude},${location.longitude}")
            add("Recorded: $time")
            add(
                buildString {
                    append("Source: $source, Accuracy: \u00b1${"%.1f".format(location.accuracy)}m")
                    if (source != "GPS") append(" [FALLBACK age=${ageSec}s]")
                }
            )
            if (other != null) {
                val otherAgeSec = (System.currentTimeMillis() - other.time) / 1000
                add(
                    "Also($otherLabel): https://maps.google.com/maps?q=${other.latitude},${other.longitude} " +
                        "\u00b1${"%.1f".format(other.accuracy)}m @${df.format(Date(other.time))} (age=${otherAgeSec}s)"
                )
            }
        }

        sendSms(senderNumber, report, dataParts)
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

    /**
     * Replies on whichever channel the original command arrived on
     * ([replyChannel], set in onStartCommand from the "reply_channel"
     * extra) — deterministic, not a data-then-fallback choice. The local
     * echo and the TEXT-channel send always use the unified [text]; the
     * DATA channel uses [dataParts] (natural field boundaries) when given,
     * so a multi-part reply splits at meaningful points instead of an
     * arbitrary byte cut — falls back to byte-chopping [text] itself only
     * if [dataParts] wasn't provided. A genuine send failure on the DATA
     * channel still falls back to text SMS so nothing is silently dropped.
     */
    private fun sendSms(number: String, text: String, dataParts: List<String>? = null) {
        when (replyChannel) {
            com.supernova.anchor.utils.ReplyChannel.TEXT -> sendRegularTextSmsFallback(number, text)
            com.supernova.anchor.utils.ReplyChannel.DATA -> {
                com.supernova.anchor.data.MessageRepository.addMessage(
                    applicationContext,
                    com.supernova.anchor.data.ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        text = text,
                        sender = number,
                        timestamp = System.currentTimeMillis(),
                        isIncoming = false
                    )
                )
                val result = if (dataParts != null) {
                    com.supernova.anchor.utils.DataSmsSender.sendParts(applicationContext, number, dataParts)
                } else {
                    com.supernova.anchor.utils.DataSmsSender.send(applicationContext, number, text)
                }
                when (result) {
                    is com.supernova.anchor.utils.DataSmsSender.Result.Sent -> {
                        DebugLogger.log(TAG, "SMS sent to $number as data SMS (${result.parts} part(s))")
                    }
                    is com.supernova.anchor.utils.DataSmsSender.Result.PartialFailure -> {
                        DebugLogger.log(TAG, "SMS: only ${result.sentParts}/${result.totalParts} parts sent (${result.reason}), sending full text SMS as a clean retry")
                        sendRegularTextSmsFallback(number, text)
                    }
                    is com.supernova.anchor.utils.DataSmsSender.Result.Failed -> {
                        DebugLogger.log(TAG, "SMS: data SMS send failed (${result.reason}), sending as text SMS instead so it isn't dropped")
                        sendRegularTextSmsFallback(number, text)
                    }
                }
            }
        }
    }

    private fun sendRegularTextSmsFallback(number: String, text: String) {
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
            DebugLogger.log(TAG, "Fallback text SMS sent to $number")
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
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }
}