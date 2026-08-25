package com.supernova.anchor.utils

// =============================================================================
// FILE: SmsCommandProcessor.kt
// =============================================================================
//
// WHAT THIS FILE DOES:
// This is the brain of Anchor. After SmsReceiver or DataSmsReceiver validates
// the sender and strips the command prefix, this class takes over. It:
//   1. Parses the command string into (command, password, params)
//   2. Validates the password against AppSettings
//   3. Routes to the appropriate handler (locate, ring, info, trace, etc.)
//   4. Sends the reply back to the sender via the SAME channel it arrived on
//
// CRITICAL FIXES APPLIED:
// 1. SEND_SMS permission check added before every SmsManager call — prevents
//    SecurityException crash if user denied SMS permission.
// 2. handleRingCommand() now catches IllegalStateException from startService()
//    on Android 8+ (API 26+). Background startService() is restricted; we
//    gracefully degrade instead of crashing.
// 3. handleCallMeCommand() already had permission check — preserved.
// 4. handleTraceCommand() already had background location check — preserved.
//
// RELATIONSHIP TO OTHER FILES:
// - SmsReceiver.kt        : Calls processCommand(..., ReplyChannel.TEXT)
// - DataSmsReceiver.kt    : Calls processCommand(..., ReplyChannel.DATA)
// - AppSettings.kt        : Stores/reads SMS_COMMAND_PASSWORD, SMS_COMMAND_PREFIX
// - LocationForegroundService.kt : Started by "locate" command
// - TraceForegroundService.kt    : Started/stopped by "trace" command
// - OverlayDisplayService.kt     : Started by "ring" command
// - RingtonePlayer.kt            : Plays alarm sound for "ring" command
// - SoundModeManager.kt          : Changes ringer mode for "sound" command
// - BatteryUtils.kt              : Queried by "info" command
// - DataSmsSender.kt             : Used for DATA channel replies
// - ReplyChannel.kt              : Enum TEXT/DATA — reply always goes back
//                                   on the same channel the command arrived on
//
// COMMAND FORMAT:
//   <PREFIX> <COMMAND> <PASSWORD> [PARAMS...]
//   Example: "PIN locate mypassword"
//   Example: "PIN trace stop mypassword"
//   Example: "PIN sound normal mypassword"
//
// SECURITY:
// - Password is checked BEFORE any command executes
// - Whitelist check happens in the RECEIVER, not here (defense in depth)
// - If password is wrong, a generic "Invalid password" reply is sent
//   (no hint about what the correct password looks like)
// =============================================================================

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
import androidx.core.content.ContextCompat
import com.supernova.anchor.BuildConfig
import com.supernova.anchor.R
import com.supernova.anchor.service.LocationForegroundService
import com.supernova.anchor.service.OverlayDisplayService
import com.supernova.anchor.service.TraceForegroundService
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
        // Command keyword constants — used in the when() block and help text.
        // Keep these in sync with the README documentation.
        const val COMMAND_LOCATE = "locate"
        const val COMMAND_RING = "ring"
        const val COMMAND_INFO = "info"
        const val COMMAND_HELP = "help"
        const val COMMAND_CALLME = "callme"
        const val COMMAND_SOUND = "sound"
        const val COMMAND_PING = "ping"
        const val COMMAND_TRACE = "trace"
    }

    // AppSettings wrapper around SharedPreferences — loaded once per processor instance.
    // A fresh SmsCommandProcessor is constructed for every incoming SMS command.
    private val appSettings = AppSettings(context)
    private val soundModeManager = SoundModeManager(context)

    // replyChannel is set once at the top of processCommand() and read by every
    // handler and sendSmsResponse(). It is safe as instance state because a fresh
    // SmsCommandProcessor is constructed per command (see both receivers).
    private var replyChannel: ReplyChannel = ReplyChannel.TEXT

    // =================================================================
    // processCommand() — main entry point called by both receivers.
    // =================================================================
    // PARAMETER rawCommand:
    //   The full command string MINUS the prefix.
    //   Example: "locate mypassword" or "trace 15 mypassword"
    //
    // PARAMETER senderNumber:
    //   The phone number that sent the SMS. Replies go back to this number.
    //
    // PARAMETER replyChannel:
    //   TEXT = reply via standard SMS (SmsManager)
    //   DATA = reply via binary/port-addressed SMS (DataSmsSender)
    // =================================================================
    fun processCommand(rawCommand: String, senderNumber: String, replyChannel: ReplyChannel) {
        this.replyChannel = replyChannel
        DebugLogger.init(context)
        DebugLogger.log(TAG, "---------- START ----------")
        DebugLogger.log(TAG, "Raw: '$rawCommand' | Sender: $senderNumber | Channel: $replyChannel")

        val commandPassword = appSettings.getString(AppSettings.SMS_COMMAND_PASSWORD)
        val commandPrefix = appSettings.getString(AppSettings.SMS_COMMAND_PREFIX)

        // Split by whitespace: parts[0] = command, parts[1] = password, parts[2+] = params
        val parts = rawCommand.trim().split("\s+".toRegex())
        DebugLogger.log(TAG, "Parts: $parts")

        // ----------------------------------------------------------------
        // GUARD: Must have at least command + password.
        // ----------------------------------------------------------------
        if (parts.size < 2) {
            DebugLogger.log(TAG, "ERROR: Missing password")
            sendSmsResponse(senderNumber, context.getString(R.string.missing_password, commandPrefix))
            return
        }

        val command = parts[0].lowercase(Locale.ROOT)
        val password = parts[1]
        DebugLogger.log(TAG, "Command='$command' | Password attempt='$password'")

        // ----------------------------------------------------------------
        // GUARD: Password must match exactly.
        // ----------------------------------------------------------------
        if (password != commandPassword) {
            DebugLogger.log(TAG, "ERROR: Invalid password")
            sendSmsResponse(senderNumber, context.getString(R.string.invalid_password))
            return
        }
        DebugLogger.log(TAG, "Password OK")

        // Everything after the password is treated as parameters.
        val params = if (parts.size > 2) parts.subList(2, parts.size) else emptyList()
        DebugLogger.log(TAG, "Params: $params")

        // ----------------------------------------------------------------
        // ROUTER: Dispatch to the appropriate handler.
        // ----------------------------------------------------------------
        when (command) {
            "locate" -> {
                // "locate" requires "Allow all the time" location permission.
                // Without it, Android throws SecurityException on startForegroundService().
                if (!hasBackgroundLocationAccess()) {
                    DebugLogger.log(TAG, ">>> LOCATE: blocked — ACCESS_BACKGROUND_LOCATION not granted")
                    sendSmsResponse(senderNumber, "Can't locate: background location isn't granted. Open Anchor > Permissions and allow location "All the time".")
                    return
                }
                DebugLogger.log(TAG, ">>> LOCATE: Starting foreground service")
                LocationForegroundService.start(context, senderNumber, replyChannel)
            }
            "ring" -> handleRingCommand()
            "info" -> handleInfoCommand(senderNumber)
            "help" -> handleHelpCommand(senderNumber)
            "callme" -> handleCallMeCommand(senderNumber)
            "sound" -> handleSoundCommand(senderNumber, params)
            "ping" -> handlePingCommand(senderNumber)
            "trace" -> handleTraceCommand(senderNumber, params)
            else -> {
                DebugLogger.log(TAG, "ERROR: Unknown command '$command'")
                sendSmsResponse(senderNumber, context.getString(R.string.unknown_command))
            }
        }
        DebugLogger.log(TAG, "---------- END ----------")
    }

    // =================================================================
    // handleRingCommand() — plays alarm + shows overlay alert.
    // =================================================================
    // CRITICAL FIX: Android 8+ (API 26) restricts starting services from
    // the background. OverlayDisplayService is not a foreground service,
    // so startService() can throw IllegalStateException. We catch it and
    // continue — the ringtone still plays, which is the primary purpose.
    //
    // RELATIONSHIP: Calls RingtonePlayer.kt and OverlayDisplayService.kt
    // =================================================================
    private fun handleRingCommand() {
        DebugLogger.log(TAG, ">>> RING")
        try {
            // Play the device's default ringtone at max volume.
            // See RingtonePlayer.kt for volume manipulation and WakeLock handling.
            RingtonePlayer.playRingtone(context)

            // Show a full-screen overlay alert so the user sees WHY the phone is ringing.
            // This requires SYSTEM_ALERT_WINDOW permission ("Display over other apps").
            val intent = Intent(context, OverlayDisplayService::class.java)
            intent.putExtra("message", context.getString(R.string.ring_command_received))
            intent.putExtra("isRingCommand", true)

            // CRITICAL FIX: Wrap startService() in try/catch.
            // On Android 8+, starting a non-foreground Service from background
            // (e.g., from a BroadcastReceiver after goAsync() finishes) throws
            // IllegalStateException. We catch it so the ringtone still works
            // even if the overlay can't show.
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                DebugLogger.log(TAG, ">>> RING: Overlay service start blocked by Android (background restriction): ${e.message}")
                // Ringtone is already playing — that's the important part.
                // The overlay is a nice-to-have visual indicator.
            }
            DebugLogger.log(TAG, ">>> RING: Started")
        } catch (e: Exception) {
            DebugLogger.log(TAG, ">>> RING: ERROR ${e.message}")
        }
    }

    // =================================================================
    // handleInfoCommand() — returns battery, charging, screen state.
    // =================================================================
    // Queries BatteryUtils and PowerManager, then sends an immediate SMS reply.
    // No foreground service needed — all data is available synchronously.
    // =================================================================
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

    // =================================================================
    // handleHelpCommand() — sends list of all commands.
    // =================================================================
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
            $commandPrefix trace [password] [minutes] - Continuous GPS trace
            $commandPrefix trace [password] stop - Stop trace
        """.trimIndent()
        sendSmsResponse(senderNumber, helpMessage)
    }

    // =================================================================
    // handleCallMeCommand() — initiates a phone call back to sender.
    // =================================================================
    // Uses Intent.ACTION_CALL which requires CALL_PHONE permission.
    // If permission is denied, sends an SMS explaining the issue.
    // =================================================================
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

    // =================================================================
    // handleTraceCommand() — starts or stops periodic GPS trace.
    // =================================================================
    // "trace 15" = send location every 15 minutes.
    // "trace stop" = stop the trace session.
    // Requires ACCESS_BACKGROUND_LOCATION (same guard as "locate").
    //
    // RELATIONSHIP: Calls TraceForegroundService.start() / .stop()
    //               Reads/writes AppSettings.TRACE_ACTIVE for persistence.
    // =================================================================
    private fun handleTraceCommand(senderNumber: String, params: List<String>) {
        DebugLogger.log(TAG, ">>> TRACE | Params: $params")

        val firstParamEarly = params.getOrNull(0)?.lowercase()
        if (firstParamEarly != "stop" && !hasBackgroundLocationAccess()) {
            DebugLogger.log(TAG, ">>> TRACE: blocked — ACCESS_BACKGROUND_LOCATION not granted")
            sendSmsResponse(senderNumber, "Can't start trace: background location isn't granted. Open Anchor > Permissions and allow location "All the time".")
            return
        }

        if (params.isEmpty()) {
            // No interval provided — use default 15 minutes.
            TraceForegroundService.start(context, senderNumber, 15, replyChannel)
            sendSmsResponse(senderNumber, "Trace starting with default 15 min interval.")
            return
        }

        val firstParam = params[0].lowercase()

        if (firstParam == "stop") {
            if (TraceForegroundService.isRunning(context)) {
                TraceForegroundService.stop(context)
                sendSmsResponse(senderNumber, "Trace stopping...")
            } else {
                sendSmsResponse(senderNumber, "Trace is not running.")
            }
            return
        }

        val interval = firstParam.toIntOrNull()
        if (interval == null || interval < 1 || interval > 1440) {
            sendSmsResponse(senderNumber, "Invalid interval. Use 1-1440 minutes, or 'stop'.")
            return
        }

        TraceForegroundService.start(context, senderNumber, interval, replyChannel)
        sendSmsResponse(senderNumber, "Trace starting. Location every $interval min.")
    }

    // =================================================================
    // handleSoundCommand() — changes device ringer mode.
    // =================================================================
    // "sound normal"  → RINGER_MODE_NORMAL
    // "sound vibrate" → RINGER_MODE_VIBRATE
    // "sound silent"  → RINGER_MODE_SILENT
    // "sound" (no param) → replies with current mode
    //
    // RELATIONSHIP: Uses SoundModeManager.kt
    // =================================================================
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

    // =================================================================
    // handlePingCommand() — immediate health check reply.
    // =================================================================
    private fun handlePingCommand(senderNumber: String) {
        DebugLogger.log(TAG, ">>> PING")
        sendSmsResponse(senderNumber, context.getString(R.string.anchor_ping_response))
    }

    // =================================================================
    // hasBackgroundLocationAccess() — guard for location-dependent commands.
    // =================================================================
    // Location-type foreground services (LocationForegroundService,
    // TraceForegroundService) cannot be started from a background context
    // unless ACCESS_BACKGROUND_LOCATION is granted ("Allow all the time").
    // Without it, Android throws SecurityException before any of our own
    // error handling runs — the command silently fails with no reply.
    // Checking first turns that into a clear, actionable SMS reply.
    // =================================================================
    private fun hasBackgroundLocationAccess(): Boolean =
        ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    // =================================================================
    // sendSmsResponse() — sends reply on the same channel command arrived on.
    // =================================================================
    // CRITICAL FIX: Added SEND_SMS permission check. If the user denied
    // SMS permission, SmsManager calls throw SecurityException. We now
    // check first and log a clear error instead of crashing.
    //
    // CHANNEL POLICY:
    //   - TEXT channel: sends via SmsManager (regular text SMS)
    //   - DATA channel: sends via DataSmsSender (binary/port-addressed SMS)
    //     If data SMS fails (partial or full), falls back to text SMS so
    //     the reply is never silently dropped.
    //
    // MessageRepository (Binary Mode chat log) only gets a local echo on
    // the DATA branch. TEXT-channel replies are real regular SMS with no
    // data-SMS involvement, so they don't appear in the binary chat thread.
    // =================================================================
    private fun sendSmsResponse(phoneNumber: String, message: String) {
        DebugLogger.log(TAG, "RESPONSE: To=$phoneNumber Len=${message.length} Channel=$replyChannel Preview='${message.take(50)}...'")

        // CRITICAL FIX: Check SEND_SMS permission before attempting to send.
        // If denied, log the error and return — no crash, no silent failure.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            DebugLogger.log(TAG, "RESPONSE: BLOCKED — SEND_SMS permission not granted. Cannot reply to $phoneNumber")
            return
        }

        when (replyChannel) {
            ReplyChannel.TEXT -> sendRegularTextSmsFallback(phoneNumber, message)
            ReplyChannel.DATA -> {
                // Log outgoing data SMS in the Binary Mode chat thread.
                com.supernova.anchor.data.MessageRepository.addMessage(
                    context,
                    com.supernova.anchor.data.ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        text = message,
                        sender = phoneNumber,
                        timestamp = System.currentTimeMillis(),
                        isIncoming = false
                    )
                )
                when (val result = DataSmsSender.send(context, phoneNumber, message)) {
                    is DataSmsSender.Result.Sent -> {
                        DebugLogger.log(TAG, "RESPONSE: sent as data SMS (${result.parts} part(s))")
                    }
                    is DataSmsSender.Result.PartialFailure -> {
                        DebugLogger.log(TAG, "RESPONSE: only ${result.sentParts}/${result.totalParts} parts sent (${result.reason}) — sending full text SMS as a clean retry")
                        sendRegularTextSmsFallback(phoneNumber, message)
                    }
                    is DataSmsSender.Result.Failed -> {
                        DebugLogger.log(TAG, "RESPONSE: data SMS send failed (${result.reason}), sending as text SMS instead so it isn't dropped")
                        sendRegularTextSmsFallback(phoneNumber, message)
                    }
                }
            }
        }
    }

    // =================================================================
    // sendRegularTextSmsFallback() — sends text SMS via SmsManager.
    // =================================================================
    // Automatically splits long messages (>160 chars) into multipart SMS.
    // Uses the modern SmsManager retrieval API on Android 12+ (API 31),
    // falling back to the deprecated getDefault() on older versions.
    //
    // CRITICAL FIX: This method is now ONLY called after the SEND_SMS
    // permission check in sendSmsResponse(), so it will never crash with
    // SecurityException. The try/catch here handles network/carrier errors.
    // =================================================================
    private fun sendRegularTextSmsFallback(phoneNumber: String, message: String) {
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
                DebugLogger.log(TAG, "FALLBACK SMS: Multipart (${parts.size} parts)")
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                DebugLogger.log(TAG, "FALLBACK SMS: Single part")
            }
        } catch (e: Exception) {
            DebugLogger.log(TAG, "FALLBACK SMS: ERROR ${e.message}")
        }
    }
}
