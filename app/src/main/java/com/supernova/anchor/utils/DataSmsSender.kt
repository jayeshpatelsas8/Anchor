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
 * LIMITATION (real, not a bug): unlike sendMultipartTextMessage() for regular
 * SMS, Android's SmsManager.sendDataMessage() has no built-in concatenation
 * across multiple segments. A single data SMS PDU has a 140-byte user-data
 * ceiling; port addressing itself consumes ~7 of those bytes, leaving roughly
 * MAX_PAYLOAD_BYTES for the actual payload. Anything longer here is REJECTED
 * up front rather than silently truncated or corrupted on the wire — callers
 * should fall back to a regular multipart text SMS for long messages (see
 * usage in SmsCommandProcessor / LocationForegroundService / TraceForegroundService).
 */
object DataSmsSender {
    private const val TAG = "DataSmsSender"

    // Conservative estimate: 140-byte PDU user-data limit minus ~7 bytes of
    // port-addressing UDH overhead.
    const val MAX_PAYLOAD_BYTES = 133

    sealed class Result {
        object Sent : Result()
        data class TooLong(val actualBytes: Int) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun send(context: Context, phoneNumber: String, text: String): Result {
        val bytes = text.toByteArray(Charsets.UTF_8)

        if (bytes.size > MAX_PAYLOAD_BYTES) {
            DebugLogger.log(
                TAG,
                "REJECTED: ${bytes.size}B exceeds $MAX_PAYLOAD_BYTES-byte single-segment limit"
            )
            return Result.TooLong(bytes.size)
        }

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
            Result.Sent
        } catch (e: Exception) {
            DebugLogger.log(TAG, "ERROR: ${e.message}")
            Result.Failed(e.message ?: "unknown error")
        }
    }
}