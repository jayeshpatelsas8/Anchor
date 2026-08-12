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

    fun getCurrentLocation(callback: (Location?) -> Unit) {
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
            callback(null)
            return
        }

        // Always request a fresh fix — never trust lastLocation cache
        requestFreshLocation(callback)
    }

    private fun requestFreshLocation(callback: (Location?) -> Unit) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            callback(null)
            return
        }

        // Keep CPU alive so Doze doesn't kill GPS mid-fix
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "Anchor::GpsFix"
        )
        wakeLock.acquire(35_000)

        var delivered = false
        val handler = Handler(Looper.getMainLooper())

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (!delivered) {
                    delivered = true
                    handler.removeCallbacksAndMessages(null)
                    fusedLocationClient.removeLocationUpdates(this)
                    if (wakeLock.isHeld) wakeLock.release()
                    callback(locationResult.lastLocation)
                }
            }
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000L
        )
            .setWaitForAccurateLocation(true)   // wait for GPS satellites, not cell towers
            .setMinUpdateIntervalMillis(500)
            .setDurationMillis(30_000)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            // Hard timeout: if GPS never locks, fail cleanly
            handler.postDelayed({
                if (!delivered) {
                    delivered = true
                    fusedLocationClient.removeLocationUpdates(locationCallback)
                    if (wakeLock.isHeld) wakeLock.release()
                    callback(null)
                }
            }, 30_000)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location updates", e)
            if (!delivered) {
                delivered = true
                handler.removeCallbacksAndMessages(null)
                if (wakeLock.isHeld) wakeLock.release()
                callback(null)
            }
        }
    }

    companion object {
        private const val TAG = "LocationUtils"
    }
}
