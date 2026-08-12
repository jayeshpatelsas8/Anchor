package com.supernova.anchor.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
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
     * @param callback (location, isFresh)
     *        isFresh = true  → real-time GPS fix
     *        isFresh = false → fallback to last known location
     */
    fun getCurrentLocation(callback: (Location?, Boolean) -> Unit) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Location permission not granted")
            callback(null, false)
            return
        }

        requestFreshLocation(callback)
    }

    private fun requestFreshLocation(callback: (Location?, Boolean) -> Unit) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            callback(null, false)
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "Anchor::GpsFix"
        )
        wakeLock.acquire(15_000)

        var delivered = false
        val handler = Handler(Looper.getMainLooper())

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!delivered) {
                    delivered = true
                    handler.removeCallbacksAndMessages(null)
                    fusedLocationClient.removeLocationUpdates(this)
                    if (wakeLock.isHeld) wakeLock.release()
                    callback(result.lastLocation, true) // fresh GPS
                }
            }
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000L
        )
            .setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(500)
            .setDurationMillis(10_000)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            // 10-second timeout → fallback to lastLocation so SMS never goes silent
            handler.postDelayed({
                if (!delivered) {
                    delivered = true
                    fusedLocationClient.removeLocationUpdates(locationCallback)
                    if (wakeLock.isHeld) wakeLock.release()

                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { loc ->
                            callback(loc, false) // stale fallback
                        }
                        .addOnFailureListener {
                            callback(null, false)
                        }
                }
            }, 10_000)

        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location updates", e)
            if (!delivered) {
                delivered = true
                handler.removeCallbacksAndMessages(null)
                if (wakeLock.isHeld) wakeLock.release()
                callback(null, false)
            }
        }
    }

    companion object {
        private const val TAG = "LocationUtils"
    }
}
