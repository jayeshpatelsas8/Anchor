package com.supernova.anchor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import com.supernova.anchor.data.ChatMessage
import com.supernova.anchor.data.MessageRepository
import com.supernova.anchor.utils.AppSettings
import com.supernova.anchor.utils.DebugLogger
import com.supernova.anchor.utils.SmsCommandProcessor
import com.supernova.anchor.utils.WhitelistManager
import java.util.UUID

/**
 * Receives binary/data SMS (3GPP TS 23.040 port-addressed SMS) — no INTERNET
 * permission required, same cellular signaling channel as regular SMS.
 *
 * Every payload is logged to MessageRepository so it shows up in ChatScreen.
 * If the decoded text matches the existing command prefix, it is handed off
 * to SmsCommandProcessor exactly like a regular SMS command — same
 * prefix/password/whitelist rules apply, no separate auth path.
 */
class DataSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DataSmsReceiver"
        const val ACTION_DATA_SMS_RECEIVED = "android.intent.action.DATA_SMS_RECEIVED"
        // Must match AndroidManifest.xml <data android:port="...">
        const val DATA_SMS_PORT = 15000
    }

    override fun onReceive(context: Context, intent: Intent) {
        DebugLogger.init(context)

        if (intent.action != ACTION_DATA_SMS_RECEIVED) {
            DebugLogger.log(TAG, "Ignored intent: ${intent.action}")
            return
        }

        val pendingResult = goAsync()
        try {
            val appSettings = AppSettings(context)
            val whitelistManager = WhitelistManager(context)

            val bundle = intent.extras
            val pdus = bundle?.get("pdus") as? Array<*>
            val format = bundle?.getString("format")

            if (pdus == null) {
                DebugLogger.log(TAG, "ERROR: No pdus in intent")
                return
            }

            DebugLogger.log(TAG, "========== DATA SMS RECEIVED ==========")
            DebugLogger.log(TAG, "PDU count: ${pdus.size}")

            for (pdu in pdus) {
                val message = if (format != null) {
                    SmsMessage.createFromPdu(pdu as ByteArray, format)
                } else {
                    @Suppress("DEPRECATION")
                    SmsMessage.createFromPdu(pdu as ByteArray)
                }
                processMessage(context, message, appSettings, whitelistManager)
            }
        } finally {
            pendingResult.finish()
        }
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

        val binaryData: ByteArray = message.userData ?: run {
            DebugLogger.log(TAG, "ERROR: No user data in payload")
            return
        }
        val text = String(binaryData, Charsets.UTF_8).trim()

        DebugLogger.log(TAG, "Sender: $senderNumber")
        DebugLogger.log(TAG, "Decoded text: '$text'")

        if (!whitelistManager.isPhoneNumberAllowed(senderNumber)) {
            DebugLogger.log(TAG, "Whitelist: REJECTED — logging but not executing")
            // Still show it in the chat log (marked, not executed) so the user
            // can see rejected attempts, same visibility SMS gives you today.
            MessageRepository.addMessage(
                context,
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = text,
                    sender = senderNumber,
                    timestamp = System.currentTimeMillis(),
                    isIncoming = true,
                    isCommand = false
                )
            )
            return
        }
        DebugLogger.log(TAG, "Whitelist: ALLOWED")

        val commandPrefix = appSettings.getString(AppSettings.SMS_COMMAND_PREFIX)
        val isCommand = text.startsWith(commandPrefix, ignoreCase = true)

        MessageRepository.addMessage(
            context,
            ChatMessage(
                id = UUID.randomUUID().toString(),
                text = text,
                sender = senderNumber,
                timestamp = System.currentTimeMillis(),
                isIncoming = true,
                isCommand = isCommand
            )
        )

        if (isCommand) {
            val command = text.trim().substring(commandPrefix.length).trim()
            DebugLogger.log(TAG, "Command: '$command'")
            // Same processor, same password check, as regular SMS commands.
            SmsCommandProcessor(context).processCommand(command, senderNumber)
        } else {
            DebugLogger.log(TAG, "Prefix mismatch — stored as plain message, not executed")
        }
    }
}