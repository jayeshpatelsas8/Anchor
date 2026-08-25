package com.supernova.anchor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.telephony.SmsMessage
import com.supernova.anchor.data.ChatMessage
import com.supernova.anchor.data.MessageRepository
import com.supernova.anchor.utils.AppSettings
import com.supernova.anchor.utils.DebugLogger
import com.supernova.anchor.utils.ReplyChannel
import com.supernova.anchor.utils.SmsCommandProcessor
import com.supernova.anchor.utils.WhitelistManager
import java.util.UUID

/**
 * Receives binary/data SMS (3GPP TS 23.040 port-addressed SMS) — no INTERNET
 * permission required, same cellular signaling channel as regular SMS.
 *
 * A message may arrive as a single part, or as several parts tagged
 * "(id/i/N) chunk" by DataSmsSender when the original text didn't fit in one
 * ~133-byte data-SMS segment. Parts are buffered here and reassembled before
 * anything is shown in the chat log or handed to the command processor — the
 * rest of the app never sees a partial message.
 *
 * If the decoded (and, if needed, reassembled) text matches the existing
 * command prefix, it is handed off to SmsCommandProcessor exactly like a
 * regular SMS command — same prefix/password/whitelist rules apply, no
 * separate auth path.
 */
class DataSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DataSmsReceiver"
        const val ACTION_DATA_SMS_RECEIVED = "android.intent.action.DATA_SMS_RECEIVED"
        // Must match AndroidManifest.xml <data android:port="...">
        const val DATA_SMS_PORT = 15000

        // Matches DataSmsSender's "(id/i/N) " part header. Must stay in sync
        // with that format.
        private val PART_HEADER = Regex(
            """^\((\d{1,3})/(\d{1,2})/(\d{1,2})\)\s(.*)$""",
            RegexOption.DOT_MATCHES_ALL
        )

        // Keyed by "sender|msgId" so two multi-part sends to the same number
        // in flight at once can't have their chunks mixed together.
        // In-memory only — not persisted across process death, since data
        // SMS gives no delivery guarantee anyway and a stuck transfer would
        // need to be resent regardless.
        private val partBuffer = mutableMapOf<String, MutableMap<Int, String>>()
        private val partBufferTotals = mutableMapOf<String, Int>()
        private val partBufferFirstSeen = mutableMapOf<String, Long>()
        private const val PART_BUFFER_MAX_AGE_MS = 2 * 60 * 1000L // 2 minutes
    }

    override fun onReceive(context: Context, intent: Intent) {
        DebugLogger.init(context)

        if (intent.action != ACTION_DATA_SMS_RECEIVED) {
            DebugLogger.log(TAG, "Ignored intent: ${intent.action}")
            return
        }

       val pendingResult = goAsync()

        // Force-wake CPU from deep sleep so binary SMS processing completes
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Anchor::DataSmsReceiver"
        )
        wakeLock.acquire(10_000)

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
            if (wakeLock.isHeld) wakeLock.release()
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
        val rawText = String(binaryData, Charsets.UTF_8).trim()

        DebugLogger.log(TAG, "Sender: $senderNumber")
        DebugLogger.log(TAG, "Decoded raw part: '$rawText'")

        if (!whitelistManager.isPhoneNumberAllowed(senderNumber)) {
            DebugLogger.log(TAG, "Whitelist: REJECTED — logging but not executing")
            // Still show it in the chat log (marked, not executed) so the
            // user can see rejected attempts, same visibility SMS gives you
            // today. Not reassembled — no reason to buffer parts for a
            // sender that's rejected outright.
            MessageRepository.addMessage(
                context,
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = rawText,
                    sender = senderNumber,
                    timestamp = System.currentTimeMillis(),
                    isIncoming = true,
                    isCommand = false
                )
            )
            return
        }
        DebugLogger.log(TAG, "Whitelist: ALLOWED")

        val text = reassemble(senderNumber, rawText) ?: run {
            DebugLogger.log(TAG, "Buffered one part of a multi-part message, waiting for the rest")
            return
        }
        DebugLogger.log(TAG, "Complete text: '$text'")

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
            // Same processor, same password check, as regular SMS commands —
            // tagged DATA so every reply for this command goes back out as
            // data SMS too, matching how it arrived.
            SmsCommandProcessor(context).processCommand(command, senderNumber, ReplyChannel.DATA)
        } else {
            DebugLogger.log(TAG, "Prefix mismatch — stored as plain message, not executed")
        }
    }

    /**
     * If [rawText] carries a "(id/i/N) chunk" header, buffers it and returns
     * null until all N parts for this (sender, id) have arrived, then
     * returns the reassembled full text. A message with no header is
     * already complete on its own and is returned as-is immediately —
     * this keeps single-part messages (the common case) working exactly as
     * before, with zero overhead.
     */
    private fun reassemble(senderNumber: String, rawText: String): String? {
        pruneStaleParts()

        val match = PART_HEADER.matchEntire(rawText) ?: return rawText

        val msgId = match.groupValues[1]
        val partNumber = match.groupValues[2].toIntOrNull()
        val total = match.groupValues[3].toIntOrNull()
        val chunk = match.groupValues[4]

        if (partNumber == null || total == null || partNumber < 1 || total < 1 || partNumber > total) {
            DebugLogger.log(TAG, "Malformed part header on '$rawText', treating as plain text")
            return rawText
        }

        val key = "$senderNumber|$msgId"
        val parts = partBuffer.getOrPut(key) { mutableMapOf() }
        partBufferTotals[key] = total
        partBufferFirstSeen.putIfAbsent(key, System.currentTimeMillis())
        parts[partNumber] = chunk

        DebugLogger.log(TAG, "Buffered part $partNumber/$total (id=$msgId) from $senderNumber — ${parts.size}/$total received so far")

        if (parts.size < total) return null

        val reassembled = (1..total).joinToString("") { parts[it] ?: "" }
        partBuffer.remove(key)
        partBufferTotals.remove(key)
        partBufferFirstSeen.remove(key)
        return reassembled
    }

    /** Drops incomplete part sets where a part never arrived, so memory doesn't grow unbounded. */
    private fun pruneStaleParts() {
        val now = System.currentTimeMillis()
        val staleKeys = partBufferFirstSeen.filterValues { now - it > PART_BUFFER_MAX_AGE_MS }.keys.toList()
        for (key in staleKeys) {
            val have = partBuffer[key]?.size ?: 0
            val total = partBufferTotals[key] ?: 0
            DebugLogger.log(TAG, "Dropping incomplete multi-part buffer for $key ($have/$total parts, timed out after ${PART_BUFFER_MAX_AGE_MS / 1000}s)")
            partBuffer.remove(key)
            partBufferTotals.remove(key)
            partBufferFirstSeen.remove(key)
        }
    }
}
