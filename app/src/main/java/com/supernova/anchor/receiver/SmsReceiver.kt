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
import com.supernova.anchor.utils.SmsCommandProcessor
import com.supernova.anchor.utils.WhitelistManager

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val pendingResult = goAsync()

        val appSettings = AppSettings(context)
        val whitelistManager = WhitelistManager(context)

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

        for (message in messages) {
            processMessage(context, message, appSettings, whitelistManager)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            pendingResult.finish()
        }, 12_000)
    }

    private fun processMessage(
        context: Context,
        message: SmsMessage,
        appSettings: AppSettings,
        whitelistManager: WhitelistManager
    ) {
        val senderNumber = message.originatingAddress ?: return
        val messageBody = message.messageBody ?: return

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "SMS received from: $senderNumber")
        }

        if (!whitelistManager.isPhoneNumberAllowed(senderNumber)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Sender not in whitelist, ignoring message")
            }
            return
        }

        if (appSettings.getString(AppSettings.SMS_COMMAND_PASSWORD).isEmpty()) {
            val defaultPassword = "password" + (1000..9999).random()
            appSettings.setString(AppSettings.SMS_COMMAND_PASSWORD, defaultPassword)
            Log.w(TAG, "Generated secure password. Please check app settings to retrieve it.")
        }

        val commandPrefix = appSettings.getString(AppSettings.SMS_COMMAND_PREFIX)

        if (messageBody.trim().startsWith(commandPrefix, ignoreCase = true)) {
            val command = messageBody.trim().substring(commandPrefix.length).trim()
            SmsCommandProcessor(context).processCommand(command, senderNumber)
        }
    }
}
