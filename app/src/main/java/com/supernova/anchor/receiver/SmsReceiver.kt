package com.supernova.anchor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.supernova.anchor.BuildConfig
import com.supernova.anchor.utils.AppSettings
import com.supernova.anchor.utils.DebugLogger
import com.supernova.anchor.utils.SmsCommandProcessor
import com.supernova.anchor.utils.WhitelistManager

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        DebugLogger.init(context)

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            DebugLogger.log(TAG, "Ignored intent: ${intent.action}")
            return
        }

         val pendingResult = goAsync()

        // Force-wake CPU from deep sleep so command parsing + execution completes
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Anchor::SmsReceiver"
        )
        wakeLock.acquire(10_000)

        val appSettings = AppSettings(context)
        val whitelistManager = WhitelistManager(context)
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

        DebugLogger.log(TAG, "========== SMS RECEIVED ==========")
        DebugLogger.log(TAG, "Message count: ${messages.size}")

        for (message in messages) {
            processMessage(context, message, appSettings, whitelistManager)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            DebugLogger.log(TAG, "goAsync finishing")
            if (wakeLock.isHeld) wakeLock.release()
            pendingResult.finish()
        }, 5_000)
    }

    private fun processMessage(
        context: Context,
        message: SmsMessage,
        appSettings: AppSettings,
        whitelistManager: WhitelistManager
    ) {
        val senderNumber = message.originatingAddress ?: run {
            DebugLogger.log(TAG, "ERROR: No originating address")
            return
        }
        val messageBody = message.messageBody ?: run {
            DebugLogger.log(TAG, "ERROR: No body")
            return
        }

        DebugLogger.log(TAG, "Sender: $senderNumber")
        DebugLogger.log(TAG, "Body: '$messageBody'")

        if (!whitelistManager.isPhoneNumberAllowed(senderNumber)) {
            DebugLogger.log(TAG, "Whitelist: REJECTED")
            return
        }
        DebugLogger.log(TAG, "Whitelist: ALLOWED")

        if (appSettings.getString(AppSettings.SMS_COMMAND_PASSWORD).isEmpty()) {
            val defaultPassword = "password" + (1000..9999).random()
            appSettings.setString(AppSettings.SMS_COMMAND_PASSWORD, defaultPassword)
            DebugLogger.log(TAG, "Generated password: $defaultPassword")
        }

        val commandPrefix = appSettings.getString(AppSettings.SMS_COMMAND_PREFIX)

        if (messageBody.trim().startsWith(commandPrefix, ignoreCase = true)) {
            val command = messageBody.trim().substring(commandPrefix.length).trim()
            DebugLogger.log(TAG, "Command: '$command'")
            SmsCommandProcessor(context).processCommand(command, senderNumber, com.supernova.anchor.utils.ReplyChannel.TEXT)
        } else {
            DebugLogger.log(TAG, "Prefix mismatch")
        }
    }
}
