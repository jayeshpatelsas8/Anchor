package com.supernova.anchor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        
        val appSettings = AppSettings(context)
        val whitelistManager = WhitelistManager(context)
        
        // Get SMS messages from the intent
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        
        for (message in messages) {
            processMessage(context, message, appSettings, whitelistManager)
        }
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
        
        // Check if the sender is allowed (whitelist)
        if (!whitelistManager.isPhoneNumberAllowed(senderNumber)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Sender not in whitelist, ignoring message")
            }
            return
        }
        
        // Get the command prefix from settings
        val commandPrefix = appSettings.getString(AppSettings.SMS_COMMAND_PREFIX)
        
        // Ensure a password exists
        if (appSettings.getString(AppSettings.SMS_COMMAND_PASSWORD).isEmpty()) {
            // Generate a default password if none exists
            val defaultPassword = "password" + (1000..9999).random()
            appSettings.setString(AppSettings.SMS_COMMAND_PASSWORD, defaultPassword)
            Log.w(TAG, "Generated secure password. Please check app settings to retrieve it.")
        }
        
        // Check if message starts with the command prefix
        if (messageBody.trim().startsWith(commandPrefix, ignoreCase = true)) {
            // Process the command
            val command = messageBody.trim().substring(commandPrefix.length).trim()
            SmsCommandProcessor(context).processCommand(command, senderNumber)
        }
    }
} 