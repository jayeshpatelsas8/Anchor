package com.supernova.anchor.utils

// =============================================================================
// FILE: RcsDetector.kt
// =============================================================================
//
// WHAT THIS FILE DOES:
// Detects whether the device is likely using RCS (Rich Communication Services),
// also known as "Chat Features" in Google Messages. When RCS is enabled,
// Google Messages upgrades regular SMS to RCS data messages, which bypass
// the SMS_RECEIVED broadcast entirely. This causes Anchor to miss commands.
//
// HOW IT WORKS:
// Android provides no official API to query RCS status directly. We use
// heuristics:
//   1. Check if Google Messages is the default SMS app (only app with RCS)
//   2. Check if the device has a valid SIM and data connection (RCS needs data)
//   3. Query the SMS database for RCS-specific message types (TYPE_RCS = 21)
//
// RELIABILITY:
//   - HIGH confidence: If RCS messages exist in the SMS database
//   - MEDIUM confidence: If Google Messages is default + data is available
//   - LOW confidence: If only Google Messages is default (RCS may be off)
//
// RELATIONSHIP TO OTHER FILES:
// - MainActivity.kt        : Calls showRcsWarningIfNeeded() on launch
// - SettingsActivity.kt  : Could add a "Check RCS status" button
// - AndroidManifest.xml    : No extra permissions needed for heuristic check
//
// NOTE: On Samsung devices, Samsung Messages also supports RCS, but uses
// different package name (com.samsung.android.messaging). This detector
// checks for both.
// =============================================================================

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log

object RcsDetector {

    private const val TAG = "RcsDetector"

    // Google Messages package name — the primary RCS carrier on Android
    private const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"
    // Samsung Messages package name — also supports RCS on Galaxy devices
    private const val SAMSUNG_MESSAGES_PACKAGE = "com.samsung.android.messaging"

    // SMS database type code for RCS messages (Android internal constant)
    private const val SMS_TYPE_RCS = 21

    /** Returns true if RCS is HIGHLY LIKELY to be active on this device.
     *  This is a best-effort heuristic — there is no official Android API. */
    fun isRcsLikelyActive(context: Context): Boolean {
        val isGoogleMessagesDefault = isGoogleMessagesDefaultSmsApp(context)
        val hasDataConnection = hasActiveDataConnection(context)
        val hasRcsMessagesInDb = hasRcsMessagesInDatabase(context)

        Log.d(TAG, "RCS check: GoogleMessages=$isGoogleMessagesDefault, " +
                "Data=$hasDataConnection, RcsInDb=$hasRcsMessagesInDb")

        // HIGH confidence: RCS messages already exist in the database
        if (hasRcsMessagesInDb) return true

        // MEDIUM confidence: Google Messages is default AND device has data
        // (RCS requires data to function; without data it falls back to SMS)
        if (isGoogleMessagesDefault && hasDataConnection) {
            // On some carriers, Google Messages enables RCS automatically
            // when data is available. This is the dangerous case.
            return true
        }

        return false
    }

    /** Checks if Google Messages or Samsung Messages is the default SMS app.
     *  These are the only two apps that implement RCS on Android. */
    private fun isGoogleMessagesDefaultSmsApp(context: Context): Boolean {
        return try {
            val defaultPackage = Telephony.Sms.getDefaultSmsPackage(context)
            defaultPackage == GOOGLE_MESSAGES_PACKAGE || defaultPackage == SAMSUNG_MESSAGES_PACKAGE
        } catch (e: Exception) {
            false
        }
    }

    /** Checks if the device currently has an active data connection.
     *  RCS requires mobile data or WiFi to function. */
    private fun hasActiveDataConnection(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    /** Queries the SMS database for RCS-type messages.
     *  This requires READ_SMS permission. If not granted, returns false. */
    private fun hasRcsMessagesInDatabase(context: Context): Boolean {
        return try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.TYPE),
                "${Telephony.Sms.TYPE} = ?",
                arrayOf(SMS_TYPE_RCS.toString()),
                null
            )?.use { cursor ->
                cursor.count > 0
            } ?: false
        } catch (e: SecurityException) {
            // READ_SMS not granted — can't check
            false
        }
    }

    /** Human-readable explanation for the warning dialog. */
    fun getRcsWarningMessage(): String {
        return "Google Messages 'Chat Features' (RCS) is active on this device. " +
                "RCS messages bypass standard SMS delivery, which means Anchor may not " +
                "receive your commands.\\n\\n" +
                "For reliable lost-device recovery, please disable Chat Features:\\n" +
                "Google Messages -> Profile -> Settings -> Chat features -> Turn OFF"
    }

    /** Deep-link intent to open Google Messages settings directly.
     *  Falls back to opening the app if the deep-link fails. */
    fun getOpenGoogleMessagesIntent(context: Context): android.content.Intent {
        return try {
            // Try to open Google Messages settings directly
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            intent.setClassName(GOOGLE_MESSAGES_PACKAGE, "$GOOGLE_MESSAGES_PACKAGE.ui.appsettings.GeneralSettingsActivity")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            intent
        } catch (e: Exception) {
            // Fallback: just open Google Messages
            context.packageManager.getLaunchIntentForPackage(GOOGLE_MESSAGES_PACKAGE)
                ?: android.content.Intent(android.content.Intent.ACTION_MAIN)
        }
    }
}
