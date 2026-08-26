package com.supernova.anchor.receiver

// =============================================================================
// FILE: SmsContentObserver.kt
// =============================================================================
//
// WHAT THIS FILE DOES:
// Monitors the SMS content provider (content://sms/) for new incoming messages.
// This is a BACKUP path for RCS messages that bypass the SMS_RECEIVED broadcast.
// When Google Messages receives an RCS message, it writes it to the SMS database
// with type=1 (inbox) even though it never fires the broadcast. This observer
// detects those writes and processes them as if they were regular SMS.
//
// HOW IT WORKS:
//   1. Registered in MainActivity.onCreate() or Application.onCreate()
//   2. Listens for ANY change to the SMS content provider
//   3. On change, queries for the most recent unread inbox message
//   4. If the message body starts with the command prefix, processes it
//   5. Marks the message as "read" so it isn't processed again
//
// CRITICAL LIMITATIONS:
//   - Requires READ_SMS permission (must be granted by user)
//   - Fires for ALL SMS changes (outgoing, read status, deletions) — must filter
//   - Has a small delay (~100-500ms) compared to instant broadcast
//   - May miss messages if the database is queried before the write completes
//   - On some OEMs, RCS messages may NOT be written to the SMS database at all
//
// RELATIONSHIP TO OTHER FILES:
// - SmsReceiver.kt        : PRIMARY path — instant broadcast for true SMS
// - DataSmsReceiver.kt    : PRIMARY path for binary/data SMS
// - SmsCommandProcessor.kt: Processes commands (same as SmsReceiver)
// - AppSettings.kt        : Reads SMS_COMMAND_PREFIX for filtering
// - MainActivity.kt       : Registers this observer on app launch
//
// RECOMMENDATION:
//   This is a FALLBACK, not a replacement for SmsReceiver. The user should
//   STILL disable RCS/Chat Features for maximum reliability. This observer
//   only catches messages that happen to be written to the database.
// =============================================================================

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.supernova.anchor.utils.AppSettings
import com.supernova.anchor.utils.DebugLogger
import com.supernova.anchor.utils.SmsCommandProcessor
import com.supernova.anchor.utils.WhitelistManager

class SmsContentObserver(private val context: Context) : ContentObserver(Handler(Looper.getMainLooper())) {

    companion object {
        private const val TAG = "SmsContentObserver"
        // Prevent processing the same message ID twice
        private var lastProcessedId = -1L
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)

        // Only process if READ_SMS is granted
        if (context.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            // Query the most recent unread inbox message
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.READ
                ),
                "${Telephony.Sms.READ} = ?",
                arrayOf("0"),  // unread only
                "${Telephony.Sms.DATE} DESC"  // most recent first
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return

                val id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID))
                val address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: return
                val body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: return

                // Deduplicate: don't process the same message twice
                if (id == lastProcessedId) return
                lastProcessedId = id

                DebugLogger.log(TAG, "RCS/DB message detected: id=$id from=$address body='$body'")

                val appSettings = AppSettings(context)
                val whitelistManager = WhitelistManager(context)
                val commandPrefix = appSettings.getString(AppSettings.SMS_COMMAND_PREFIX)

                // Check whitelist
                if (!whitelistManager.isPhoneNumberAllowed(address)) {
                    DebugLogger.log(TAG, "RCS/DB: Whitelist REJECTED")
                    markAsRead(id)
                    return
                }

                // Check command prefix
                if (!body.trim().startsWith(commandPrefix, ignoreCase = true)) {
                    DebugLogger.log(TAG, "RCS/DB: Prefix mismatch")
                    markAsRead(id)
                    return
                }

                // Extract command and process
                val command = body.trim().substring(commandPrefix.length).trim()
                DebugLogger.log(TAG, "RCS/DB: Processing command='$command'")

                SmsCommandProcessor(context).processCommand(
                    command,
                    address,
                    com.supernova.anchor.utils.ReplyChannel.TEXT
                )

                // Mark as read so we don't process again
                markAsRead(id)
            }
        } catch (e: Exception) {
            DebugLogger.log(TAG, "RCS/DB observer error: ${e.message}")
        }
    }

    private fun markAsRead(id: Long) {
        try {
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.READ, 1)
            }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms._ID} = ?",
                arrayOf(id.toString())
            )
        } catch (e: Exception) {
            DebugLogger.log(TAG, "Failed to mark message as read: ${e.message}")
        }
    }

    /** Call this from MainActivity.onCreate() or Application.onCreate() */
    fun register() {
        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,  // notify for descendants (inbox, sent, etc.)
            this
        )
        DebugLogger.log(TAG, "SMS ContentObserver registered")
    }

    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
        DebugLogger.log(TAG, "SMS ContentObserver unregistered")
    }
}
