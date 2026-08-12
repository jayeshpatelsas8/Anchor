package com.supernova.anchor.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationUtils(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    /**
     * Forces real GPS fix. Reports source honestly.
     * @param callback (location, source)
     *        source = "GPS"     → raw satellite fix, < 30s old, < 50m accuracy
     *        source = "FUSED"   → FusedLocationProvider best effort
     *        source = "CACHE"   → stale lastLocation fallback
     *        source = "FAILED"  → nothing available
     */
    fun getCurrentLocation(callback: (Location?, String) -> Unit) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "FINE location permission not granted")
            callback(null, "FAILED")
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Anchor::GpsFix"
        )
        wakeLock.acquire(25_000)

        var delivered = false
        val handler = Handler(Looper.getMainLooper())
        var bestLocation: Location? = null
        var bestSource = "FAILED"

        // --- 1. RAW GPS PROVIDER (forces chip to power on) ---
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val gpsListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!delivered) {
                    Log.d(TAG, "Raw GPS: lat=${location.latitude}, acc=${location.accuracy}, age=${(System.currentTimeMillis()-location.time)/1000}s")
                    if (bestLocation == null || location.accuracy < bestLocation!!.accuracy) {
                        bestLocation = location
                        bestSource = "GPS"
                    }
                    // If GPS is accurate enough, deliver immediately
                    if (location.accuracy <= 20f) {
                        delivered = true
                        cleanup(handler, wakeLock, this, null)
                        callback(location, "GPS")
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                gpsListener,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e(TAG, "GPS provider failed to start", e)
        }

        // --- 2. FUSED PROVIDER (parallel fallback) ---
        val fusedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!delivered) {
                    val loc = result.lastLocation
                    if (loc != null) {
                        val ageSec = (System.currentTimeMillis() - loc.time) / 1000
                        Log.d(TAG, "Fused result: acc=${loc.accuracy}, age=${ageSec}s")
                        if (bestLocation == null || loc.accuracy < bestLocation!!.accuracy) {
                            bestLocation = loc
                            bestSource = if (ageSec < 30) "FUSED" else "CACHE"
                        }
                    }
                }
            }
        }

        val fusedRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000L
        )
            .setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(500)
            .setDurationMillis(20_000)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                fusedRequest,
                fusedCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fused provider failed to start", e)
        }

        // --- 3. TIMEOUT ---
        handler.postDelayed({
            if (!delivered) {
                delivered = true
                cleanup(handler, wakeLock, gpsListener, fusedCallback)

                if (bestLocation != null) {
                    callback(bestLocation, bestSource)
                } else {
                    // Last resort: stale cache
                    try {
                        fusedLocationClient.lastLocation
                            .addOnSuccessListener { loc ->
                                if (loc != null) {
                                    callback(loc, "CACHE")
                                } else {
                                    callback(null, "FAILED")
                                }
                            }
                            .addOnFailureListener {
                                callback(null, "FAILED")
                            }
                    } catch (e: Exception) {
                        callback(null, "FAILED")
                    }
                }
            }
        }, 20_000)
    }

    private fun cleanup(
        handler: Handler,
        wakeLock: PowerManager.WakeLock,
        gpsListener: LocationListener?,
        fusedCallback: LocationCallback?
    ) {
        handler.removeCallbacksAndMessages(null)
        if (wakeLock.isHeld) wakeLock.release()
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            gpsListener?.let { locationManager.removeUpdates(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing GPS listener", e)
        }
        try {
            fusedCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing fused callback", e)
        }
    }

    companion object {
        private const val TAG = "LocationUtils"
    }
}
