package com.supernova.anchor.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationUtils {

    fun isBatteryOptimizationEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestExemption(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }
}
