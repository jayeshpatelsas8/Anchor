package com.supernova.anchor.utils

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import com.supernova.anchor.receiver.DataSmsReceiver

/**
 * Sends binary/data SMS on Anchor's command port (DataSmsReceiver.DATA_SMS_PORT).
 * No INTERNET permission involved — rides the same cellular signaling channel
 * as regular SEND_SMS, just port-addressed instead of plain text.
 *
 * A single data SMS PDU has a ~140-byte user-data ceiling (port addressing
 * consumes ~7 of those), leaving MAX_PAYLOAD_BYTES for content. Android's
 * sendDataMessage() has no built-in multipart/concatenation the way
 * sendMultipartTextMessage() does for regular SMS — so this implements its
 * own: a message too long for one segment is split into several data SMS
 * messages, each tagged "(id/i/N) chunk", and DataSmsReceiver buffers +
 * reassembles them on the other end. The id disambiguates two multi-part
 * sends to the same number in flight at once. The order they physically
 * arrive in doesn't matter — the header carries the sequence, so
 * DataSmsReceiver reassembles correctly however they show up.
 *
 * Two ways to split:
 *  - send(): give it one string, it byte-chops at the limit if needed.
 *  - sendParts(): give it a list of natural fields (e.g. the maps link,
 *    the recorded time, the source+accuracy) and each one becomes its own
 *    part as-is — smaller parts, split at meaningful boundaries instead of
 *    an arbitrary byte cut through the middle of a field. Only a field
 *    that's still too long on its own falls back to byte-chopping.
 * Both funnel into the same "(id/i/N)" wire format, so DataSmsReceiver
 * doesn't need to know or care which one was used.
 */
object DataSmsSender {
    private const val TAG = "DataSmsSender"

    // Conservative estimate: 140-byte PDU user-data limit minus ~7 bytes of
    // port-addressing UDH overhead.
    const val MAX_PAYLOAD_BYTES = 133

    // Reserved per part for the "(id/i/N) " header — sized generously
    // (3-digit id, 2-digit part/total) so it stays correct with headroom.
    // 133 - 16 = 117 usable content bytes per part.
    private const val PART_PREFIX_RESERVE_BYTES = 16

    // Sanity ceiling: if a message would need more parts than this, refuse
    // rather than silently firing off dozens of SMS — that many parts means
    // something upstream generated runaway content, not a legitimate reply.
    private const val MAX_PARTS = 12

    sealed class Result {
        data class Sent(val parts: Int) : Result()
        data class PartialFailure(val sentParts: Int, val totalParts: Int, val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /** Sends [text] as-is if it fits, otherwise byte-chops it into parts. */
    fun send(context: Context, phoneNumber: String, text: String): Result =
        sendParts(context, phoneNumber, listOf(text))

    /**
     * Sends [parts] — natural fields the caller already knows the shape of
     * (a link, a date, a source line, ...). Each field that already fits in
     * one segment is sent as its own part, untouched. A field that's still
     * too big on its own is byte-chopped as a fallback. Empty fields are
     * dropped. If everything collapses to a single physical part, it's sent
     * with no "(id/i/N)" header at all — identical to the old single-part
     * behavior, zero overhead for the common short-reply case.
     */
    fun sendParts(context: Context, phoneNumber: String, parts: List<String>): Result {
        val fields = parts.filter { it.isNotEmpty() }
        if (fields.isEmpty()) return Result.Failed("Nothing to send")

        val maxFieldBytes = MAX_PAYLOAD_BYTES - PART_PREFIX_RESERVE_BYTES
        val physical = mutableListOf<String>()
        for (field in fields) {
            val bytes = field.toByteArray(Charsets.UTF_8).size
            if (bytes <= maxFieldBytes) {
                physical.add(field)
            } else {
                DebugLogger.log(TAG, "Field '${field.take(20)}...' ($bytes B) doesn't fit in one part on its own, byte-chopping it")
                physical.addAll(chunkByUtf8Bytes(field, maxFieldBytes))
            }
        }

        if (physical.size > MAX_PARTS) {
            DebugLogger.log(TAG, "REJECTED: would need ${physical.size} parts, exceeds $MAX_PARTS-part sanity limit")
            return Result.Failed("Message too long even for multi-part data SMS (${physical.size} parts needed)")
        }

        if (physical.size == 1) {
            return sendSinglePart(context, phoneNumber, physical[0].toByteArray(Charsets.UTF_8))
        }

        val total = physical.size
        val msgId = (0..999).random()
        DebugLogger.log(TAG, "Sending ${fields.size} field(s) as $total physical part(s) (id=$msgId)")

        for ((index, chunk) in physical.withIndex()) {
            val partNumber = index + 1
            val prefixed = "($msgId/$partNumber/$total) $chunk"
            val result = sendSinglePart(context, phoneNumber, prefixed.toByteArray(Charsets.UTF_8))
            if (result is Result.Failed) {
                DebugLogger.log(TAG, "Multi-part send stopped at part $partNumber/$total: ${result.reason}")
                return Result.PartialFailure(sentParts = index, totalParts = total, reason = result.reason)
            }
        }
        DebugLogger.log(TAG, "SENT: To=$phoneNumber as $total data-SMS parts (id=$msgId)")
        return Result.Sent(total)
    }

    private fun sendSinglePart(context: Context, phoneNumber: String, bytes: ByteArray): Result {
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            smsManager.sendDataMessage(
                phoneNumber,
                null,
                DataSmsReceiver.DATA_SMS_PORT.toShort(),
                bytes,
                null,
                null
            )
            DebugLogger.log(TAG, "SENT: To=$phoneNumber Bytes=${bytes.size} Port=${DataSmsReceiver.DATA_SMS_PORT}")
            Result.Sent(1)
        } catch (e: Exception) {
            DebugLogger.log(TAG, "ERROR: ${e.message}")
            Result.Failed(e.message ?: "unknown error")
        }
    }

    /**
     * Splits [text] into chunks whose UTF-8 byte length never exceeds
     * [maxBytesPerChunk], iterating by Unicode codepoint (not by Char/UTF-16
     * unit) so a surrogate pair is never split, and by definition a
     * single-codepoint UTF-8 sequence is never split either.
     */
    internal fun chunkByUtf8Bytes(text: String, maxBytesPerChunk: Int): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentBytes = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val piece = text.substring(i, i + charCount)
            val pieceBytes = piece.toByteArray(Charsets.UTF_8).size

            if (currentBytes + pieceBytes > maxBytesPerChunk && current.isNotEmpty()) {
                chunks.add(current.toString())
                current.clear()
                currentBytes = 0
            }
            current.append(piece)
            currentBytes += pieceBytes
            i += charCount
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }
}