package com.supernova.anchor.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

object RcsDetector {

    private const val TAG = "RcsDetector"

    // SMS database type code for RCS messages (Android internal constant)
    private const val SMS_TYPE_RCS = 21

    // =================================================================
    // STRUCTURAL RCS COMPONENTS — the actual protocol engines, not UI apps
    // =================================================================
    // Google Jibe / Carrier Services = the RCS stack that handles protocol
    // registration, SIP, MSRP, etc. If this is present, the device CAN do RCS.
    private val RCS_ENGINE_PACKAGES = listOf(
        "com.google.android.ims",           // Google Carrier Services (Jibe RCS stack)
        "com.samsung.rcs",                  // Samsung RCS service (older devices)
        "com.samsung.rcs.framework",        // Samsung RCS framework (newer One UI)
        "com.qualcomm.qti.rcsservice",      // Qualcomm RCS service (some OEMs)
        "com.mediatek.rcs",                 // MediaTek RCS stack
        "com.huawei.rcs",                   // Huawei RCS service
        "com.xiaomi.rcs"                    // Xiaomi RCS framework
    )

    // RCS content-provider URIs that some OEMs expose. If these exist and
    // are queryable, an RCS subsystem is active on the device.
    private val RCS_CONTENT_URIS = listOf(
        "content://com.google.android.apps.messaging.rcs/",
        "content://com.samsung.rcs.dm/",
        "content://com.samsung.rcs.message/",
        "content://rcs/"
    )

    /** Returns true if RCS is HIGHLY LIKELY to be active on this device.
     *  Uses structural signals: RCS engines, IMS readiness, DB artifacts. */
    fun isRcsLikelyActive(context: Context): Boolean {
        val hasRcsEngine = hasRcsEngineInstalled(context)
        val hasRcsMessagesInDb = hasRcsMessagesInDatabase(context)
        val hasRcsProvider = hasRcsContentProvider(context)
        val hasDataConnection = hasActiveDataConnection(context)
        val imsReady = isImsReady(context)

        Log.d(TAG, "RCS structural check: " +
                "Engine=$hasRcsEngine, " +
                "RcsInDb=$hasRcsMessagesInDb, " +
                "Provider=$hasRcsProvider, " +
                "Data=$hasDataConnection, " +
                "IMS=$imsReady")

        // DEFINITE: RCS messages already exist in the SMS database
        if (hasRcsMessagesInDb) return true

        // DEFINITE: An RCS content provider is responding
        if (hasRcsProvider) return true

        // STRONG: RCS engine is present + IMS is ready + data is available
        // This means the device is provisioned for RCS and capable right now.
        if (hasRcsEngine && imsReady && hasDataConnection) return true

        // MEDIUM: RCS engine is present + data is available
        // IMS state might be hidden from us, but the stack is installed.
        if (hasRcsEngine && hasDataConnection) return true

        return false
    }

    // =================================================================
    // 1. DETECT RCS ENGINE PACKAGES (structural)
    // =================================================================
    /** Checks if any known RCS protocol engine is installed and enabled.
     *  This looks for Carrier Services / Jibe, Samsung RCS, Qualcomm RCS, etc.
     *  These packages have no UI — they are the actual SIP/MSRP stack. */
    private fun hasRcsEngineInstalled(context: Context): Boolean {
        val pm = context.packageManager
        for (pkg in RCS_ENGINE_PACKAGES) {
            try {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES)
                if (info.applicationInfo?.enabled == true) {
                    Log.d(TAG, "RCS engine found: $pkg")
                    return true
                }
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed — expected on most devices
            }
        }
        return false
    }

    // =================================================================
    // 2. DETECT RCS CONTENT PROVIDERS (structural)
    // =================================================================
    /** Some OEMs expose RCS state via content providers.
     *  If we can resolve the content type, an RCS subsystem is active. */
    private fun hasRcsContentProvider(context: Context): Boolean {
        for (uriStr in RCS_CONTENT_URIS) {
            try {
                val uri = android.net.Uri.parse(uriStr)
                val type = context.contentResolver.getType(uri)
                if (type != null) {
                    Log.d(TAG, "RCS content provider responding: $uriStr (type=$type)")
                    return true
                }
            } catch (_: Exception) {
                // Provider doesn't exist or isn't accessible
            }
        }
        return false
    }

    // =================================================================
    // 3. DETECT IMS READINESS (structural)
    // =================================================================
    /** RCS runs over IMS (IP Multimedia Subsystem).
     *  If IMS is registered, the device is on a carrier network that
     *  supports IMS — a prerequisite for RCS.
     *  This does NOT require READ_PHONE_STATE on most devices for
     *  basic voice-IMS status, but we wrap it safely. */
    private fun isImsReady(context: Context): Boolean {
        return try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            // isImsRegistered is available API 28+; it tells us if the device
            // has an active IMS registration with the carrier.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tm.isImsRegistered
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // =================================================================
    // 4. DETECT RCS MESSAGES IN SMS DATABASE (structural evidence)
    // =================================================================
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
        } catch (_: SecurityException) {
            false
        }
    }

    // =================================================================
    // 5. DATA CONNECTION CHECK (prerequisite)
    // =================================================================
    /** RCS requires mobile data or WiFi to function. */
    private fun hasActiveDataConnection(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (_: SecurityException) {
            false
        }
    }

    // =================================================================
    // UI STRINGS
    // =================================================================
    fun getRcsWarningMessage(): String {
        return "RCS (Rich Communication Services) is active on this device. " +
                "RCS messages bypass standard SMS delivery, which means Anchor may not " +
                "receive your commands.\\n\\n" +
                "For reliable lost-device recovery, please disable Chat Features:\\n" +
                "Messages app -> Settings -> Chat features -> Turn OFF"
    }

    fun getOpenGoogleMessagesIntent(context: Context): android.content.Intent {
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            intent.setClassName(
                "com.google.android.apps.messaging",
                "com.google.android.apps.messaging.ui.appsettings.GeneralSettingsActivity"
            )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            intent
        } catch (_: Exception) {
            context.packageManager.getLaunchIntentForPackage("com.google.android.apps.messaging")
                ?: android.content.Intent(android.content.Intent.ACTION_MAIN)
        }
    }
}
