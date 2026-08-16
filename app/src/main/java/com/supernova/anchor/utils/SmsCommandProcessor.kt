package com.supernova.anchor.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.supernova.anchor.BuildConfig
import com.supernova.anchor.R
import com.supernova.anchor.service.LocationForegroundService
import com.supernova.anchor.service.OverlayDisplayService
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.Manifest
import android.annotation.SuppressLint
import com.supernova.anchor.utils.SoundModeManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.supernova.anchor.utils.RingtonePlayer
import android.content.SharedPreferences

class SmsCommandProcessor(private val context: Context) {

    companion object {
        private const val TAG = "SmsCommandProcessor"
    }

    private val appSettings = AppSettings(context)
    private val soundModeManager = SoundModeManager(context)

    fun processCommand(rawCommand: String, senderNumber: String) {
        DebugLogger.init(context)
        DebugLogger.log(TAG, "---------- START ----------")
        DebugLogger.log(TAG, "Raw: '$rawCommand' | Sender: $senderNumber")

        val commandPassword = appSettings.getString(AppSettings.SMS_COMMAND_PASSWORD)
        val commandPrefix = appSettings.getString(AppSettings.SMS_COMMAND_PREFIX)

        val parts = rawCommand.trim().split("\\s+".toRegex())
        DebugLogger.log(TAG, "Parts: $parts")

        if (parts.size < 2) {
            DebugLogger.log(TAG, "ERROR: Missing password")
            sendSmsResponse(senderNumber, context.getString(R.string.missing_password, commandPrefix))
            return
        }

        val command = parts[0].lowercase(Locale.ROOT)
        val password = parts[1]
        DebugLogger.log(TAG, "Command='$command' | Password attempt='$password'")

        if (password != commandPassword) {
            DebugLogger.log(TAG, "ERROR: Invalid password")
            sendSmsResponse(senderNumber, context.getString(R.string.invalid_password))
            return
        }
        DebugLogger.log(TAG, "Password OK")

        val params = if (parts.size > 2) parts.subList(2, parts.size) else emptyList()
        DebugLogger.log(TAG, "Params: $params")

        when (command) {
            "locate" -> {
                DebugLogger.log(TAG, ">>> LOCATE: Starting foreground service")
                LocationForegroundService.start(context, senderNumber)
            }
            "ring" -> handleRingCommand()
            "info" -> handleInfoCommand(senderNumber)
            "help" -> handleHelpCommand(senderNumber)
            "callme" -> handleCallMeCommand(senderNumber)
            "sound" -> handleSoundCommand(senderNumber, params)
            "ping" -> handlePingCommand(senderNumber)
            else -> {
                DebugLogger.log(TAG, "ERROR: Unknown command '$command'")
                sendSmsResponse(senderNumber, context.getString(R.string.unknown_command))
            }
        }
        DebugLogger.log(TAG, "---------- END ----------")
    }

    private fun handleRingCommand() {
        DebugLogger.log(TAG, ">>> RING")
        try {
            RingtonePlayer.playRingtone(context)
            val intent = Intent(context, OverlayDisplayService::class.java)
            intent.putExtra("message", context.getString(R.string.ring_command_received))
            intent.putExtra("isRingCommand", true)
            context.startService(intent)
            DebugLogger.log(TAG, ">>> RING: Started")
        } catch (e: Exception) {
            DebugLogger.log(TAG, ">>> RING: ERROR ${e.message}")
        }
    }

    @SuppressLint("StringFormatMatches")
    private fun handleInfoCommand(senderNumber: String) {
        DebugLogger.log(TAG, ">>> INFO")
        val batteryInfo = BatteryUtils.getBatteryPercentage(context)
        val isCharging = BatteryUtils.isCharging(context)
        DebugLogger.log(TAG, ">>> INFO: Battery=$batteryInfo% Charging=$isCharging")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val currentDateTime = dateFormat.format(Date())

        val infoMessage = context.getString(
            R.string.device_info,
            batteryInfo,
            if (isCharging) context.getString(R.string.yes) else context.getString(R.string.no),
            if (isScreenOn) context.getString(R.string.yes) else context.getString(R.string.no),
            currentDateTime
        )

        sendSmsResponse(senderNumber, infoMessage)
    }

    private fun handleHelpCommand(senderNumber: String) {
        DebugLogger.log(TAG, ">>> HELP")
        val commandPrefix = appSettings.getString(AppSettings.SMS_COMMAND_PREFIX)
        val helpMessage = """
            Available commands:
            $commandPrefix locate [password] - Get device location
            $commandPrefix ring [password] - Ring device at max volume
            $commandPrefix info [password] - Get battery & device info
            $commandPrefix callme [password] - Device calls you back
            $commandPrefix sound [password] [normal/vibrate/silent] - Change sound mode
            $commandPrefix ping [password] - Check if service is running
        """.trimIndent()
        sendSmsResponse(senderNumber, helpMessage)
    }

    private fun handleCallMeCommand(senderNumber: String) {
        DebugLogger.log(TAG, ">>> CALLME")
        try {
            val callIntent = Intent(Intent.ACTION_CALL)
            callIntent.data = Uri.parse("tel:$senderNumber")
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                context.startActivity(callIntent)
                DebugLogger.log(TAG, ">>> CALLME: Started")
            } else {
                DebugLogger.log(TAG, ">>> CALLME: Permission denied")
                sendSmsResponse(senderNumber, context.getString(R.string.call_permission_required))
            }
        } catch (e: Exception) {
            DebugLogger.log(TAG, ">>> CALLME: ERROR ${e.message}")
            sendSmsResponse(senderNumber, context.getString(R.string.error_making_call))
        }
    }

    private fun handleSoundCommand(senderNumber: String, params: List<String>) {
        DebugLogger.log(TAG, ">>> SOUND | Params: $params")
        when {
            params.contains("normal") -> {
                soundModeManager.setNormalMode()
                sendSmsResponse(senderNumber, "Sound mode set to normal")
            }
            params.contains("vibrate") -> {
                soundModeManager.setVibrateMode()
                sendSmsResponse(senderNumber, "Sound mode set to vibrate")
            }
            params.contains("silent") -> {
                soundModeManager.setSilentMode()
                sendSmsResponse(senderNumber, "Sound mode set to silent")
            }
            else -> {
                val currentMode = soundModeManager.getCurrentModeName()
                sendSmsResponse(senderNumber, "Current sound mode: $currentMode")
            }
        }
    }

    private fun handlePingCommand(senderNumber: String) {
        DebugLogger.log(TAG, ">>> PING")
        sendSmsResponse(senderNumber, context.getString(R.string.anchor_ping_response))
    }

    private fun sendSmsResponse(phoneNumber: String, message: String) {
        DebugLogger.log(TAG, "SMS SEND: To=$phoneNumber Len=${message.length} Preview='${message.take(50)}...'")
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            if (message.length > 160) {
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
                DebugLogger.log(TAG, "SMS SEND: Multipart (${parts.size} parts)")
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                DebugLogger.log(TAG, "SMS SEND: Single part")
            }
        } catch (e: Exception) {
            DebugLogger.log(TAG, "SMS SEND: ERROR ${e.message}")
        }
    }
}
