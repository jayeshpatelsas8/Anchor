package com.supernova.anchor.utils

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings

object LocationSettingsUtils {

    fun isGpsEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    fun isHighAccuracyEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
               lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun openLocationSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }
}
