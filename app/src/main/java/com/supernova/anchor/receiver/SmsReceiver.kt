package com.supernova.anchor.receiver

// ============================================================================
// FILE: SmsReceiver.kt
// ============================================================================
//
// WHAT THIS FILE DOES:
// This BroadcastReceiver is the front door for all standard text-SMS commands.
// When someone sends an SMS to this device, the Android system broadcasts
// android.provider.Telephony.SMS_RECEIVED. This receiver catches that broadcast,
// validates the sender, checks the password prefix, and hands the command off to
// SmsCommandProcessor for execution (locate, ring, info, trace, etc.).
//
// REQUIREMENTS THIS CODE ADDRESSES:
// 1. RECEIVE_SMS permission must be declared in AndroidManifest.xml
// 2. Receiver must be exported=true so the system SMS dispatcher can reach it
// 3. Deep sleep / Doze mode: CPU must be forced awake or command parsing dies
// 4. Ordered broadcast behavior: SMS_RECEIVED is ordered; slow receivers get killed
// 5. Aggressive OEM survival (OnePlus 7, Xiaomi, OPPO): finish() must be immediate
//
// STEP-BY-STEP FLOW:
// 1. Android Telephony stack detects incoming SMS → fires SMS_RECEIVED broadcast
// 2. System looks up manifest-declared receivers (see AndroidManifest.xml)
// 3. If Anchor was killed, Android cold-starts the app; if alive, uses existing proc
// 4. onReceive() fires on the main thread of a temporary broadcast-bound process
// 5. goAsync() tells Android "we need more than the default 10s window"
// 6. PARTIAL_WAKE_LOCK forces CPU awake even if device is in deep Doze sleep
// 7. Parse SMS pdus → SmsMessage[] → extract sender number + message body
// 8. Whitelist check (see WhitelistManager.kt) — drop unauthorized senders silently
// 9. Command prefix + password check (see AppSettings.kt)
// 10. Hand off to SmsCommandProcessor.kt which may start:
//     - LocationForegroundService (for "locate")
//     - TraceForegroundService   (for "trace")
//     - RingtonePlayer            (for "ring")
//     - OverlayDisplayService     (for on-screen alerts)
// 11. finally{} MUST call pendingResult.finish() IMMEDIATELY — no delays.
//     OxygenOS on OnePlus 7 kills the process if finish() is delayed > ~1-2s.
// 12. Release WakeLock so the device can return to deep sleep.
//
// RELATIONSHIP TO OTHER FILES IN THIS REPO:
// - AndroidManifest.xml
//   Declares this receiver with:
//   <receiver android:name=".receiver.SmsReceiver" android:exported="true"
//             android:permission="android.permission.BROADCAST_SMS">
//       <intent-filter android:priority="999">   <!-- RECOMMENDED: prevents default SMS app from aborting before we see it -->
//           <action android:name="android.provider.Telephony.SMS_RECEIVED" />
//       </intent-filter>
//   </receiver>
//
// - DataSmsReceiver.kt (sister file)
//   Handles binary/data SMS on port 15000. THIS FILE MUST MIRROR its goAsync()
//   + try/finally + immediate finish() pattern exactly. DataSmsReceiver works
//   on OnePlus 7 because it already uses immediate finish(); SmsReceiver was
//   broken because it used Handler.postDelayed(5000) instead.
//
// - SmsCommandProcessor.kt
//   The command parser and executor. Receives (commandString, senderNumber, ReplyChannel).
//   This file calls SmsCommandProcessor.processCommand(..., ReplyChannel.TEXT)
//   because regular SMS always replies via standard text SMS.
//
// - AppSettings.kt
//   Stores SMS_COMMAND_PREFIX, SMS_COMMAND_PASSWORD, TRACE_ACTIVE, etc.
//   Used here to validate commands and auto-generate a password if missing.
//
// - WhitelistManager.kt
//   Checks if the sender's phone number is in the user's allowed list.
//   If not allowed, the SMS is silently dropped (no reply, no log spam).
//
// - DebugLogger.kt
//   File-based logger that writes to SD card / external storage.
//   init() is called here; it is safe to call repeatedly because it has an
//   internal `initialized` guard. IDEALLY it should be initialized once in
//   Application.onCreate(), but keeping it here matches DataSmsReceiver.
//
// - LocationForegroundService.kt / TraceForegroundService.kt
//   Foreground services started by SmsCommandProcessor for "locate" and "trace".
//   These require WAKE_LOCK and FOREGROUND_SERVICE_LOCATION permissions.
//
// KNOWN OEM ISSUES DOCUMENTED HERE:
// - OnePlus 7 / OxygenOS 11: goAsync() finish() delayed past ~1-2 seconds causes
//   the system to permanently blacklist Anchor from SMS_RECEIVED broadcasts.
//   The old Handler.postDelayed(5000) was the exact bug.
// - Xiaomi (MIUI), OPPO (ColorOS), vivo (Funtouch): Require "Auto Launch" or
//   "Allow background activity" in OEM app settings — standard Android battery
//   optimization exemption is NOT enough on these devices.
// - Samsung One UI: May need "Never sleeping apps" in battery settings.
// ============================================================================

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.SmsMessage
import com.supernova.anchor.utils.AppSettings
import com.supernova.anchor.utils.DebugLogger
import com.supernova.anchor.utils.SmsCommandProcessor
import com.supernova.anchor.utils.WhitelistManager

class SmsReceiver : BroadcastReceiver() {

    companion object {
        // Tag used for both DebugLogger file output and Android system logcat.
        // Keep this in sync with any logcat filters you use for debugging.
        private const val TAG = "SmsReceiver"
    }

    // ------------------------------------------------------------------------
    // onReceive() — Android calls this on the main thread when an SMS arrives.
    // ------------------------------------------------------------------------
    // CRITICAL RULE: This method must return quickly. If we need more than
    // ~10 seconds of processing, we use goAsync(). If the device is in deep
    // sleep (Doze), we must acquire a WakeLock or the CPU will freeze mid-parse.
    // ------------------------------------------------------------------------
    override fun onReceive(context: Context, intent: Intent) {
        // Initialize the file logger. This is safe to call repeatedly because
        // DebugLogger has an internal `initialized` boolean guard that returns
        // early on subsequent calls. See DebugLogger.kt for implementation.
        DebugLogger.init(context)

        // --------------------------------------------------------------------
        // STEP 1: Validate the broadcast action.
        // --------------------------------------------------------------------
        // SMS_RECEIVED_ACTION is the only action we care about. If this receiver
        // is ever registered for other intents (it shouldn't be), drop them.
        // This matches the guard in DataSmsReceiver.kt.
        // --------------------------------------------------------------------
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            DebugLogger.log(TAG, "Ignored intent: ${intent.action}")
            return
        }

        // --------------------------------------------------------------------
        // STEP 2: Tell Android we need extended time beyond the default window.
        // --------------------------------------------------------------------
        // goAsync() returns a PendingResult that we MUST finish() later.
        // Without this, the system may ANR or kill us if processing takes >10s.
        // NOTE: OxygenOS 11 (OnePlus 7) is aggressive — it may kill us even
        // with goAsync() if finish() is not called within ~1-2 seconds.
        // --------------------------------------------------------------------
        val pendingResult = goAsync()

        // --------------------------------------------------------------------
        // STEP 3: Force the CPU awake during deep sleep / Doze mode.
        // --------------------------------------------------------------------
        // PARTIAL_WAKE_LOCK keeps the CPU running without turning the screen on.
        // ACQUIRE_CAUSES_WAKEUP is added so the device fully wakes from deep
        // sleep to process the command (critical for "locate" and "trace").
        // The 10_000ms (10s) timeout is a safety net — even if release() is
        // never reached due to a crash, the lock auto-releases after 10s.
        // --------------------------------------------------------------------
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Anchor::SmsReceiver"
        )
        wakeLock.acquire(10_000)

        // --------------------------------------------------------------------
        // STEP 4: Parse and process the SMS inside try/finally.
        // --------------------------------------------------------------------
        // The try block contains all actual work. The finally block guarantees
        // that pendingResult.finish() and wakeLock.release() ALWAYS run,
        // even if an exception is thrown during parsing or command execution.
        //
        // THIS IS THE ONEPLUS 7 FIX. The old code used:
        //   Handler(Looper.getMainLooper()).postDelayed({ finish() }, 5_000)
        // which delayed finish() by 5 seconds. OxygenOS kills the process
        // before then, causing Android to blacklist Anchor from future SMS
        // broadcasts. DataSmsReceiver.kt already used immediate finally{};
        // this change makes SmsReceiver match it exactly.
        // --------------------------------------------------------------------
        try {
            // ----------------------------------------------------------------
            // STEP 4a: Load settings and whitelist validator.
            // ----------------------------------------------------------------
            // AppSettings is backed by SharedPreferences. It is safe to construct
            // on the broadcast thread because SharedPreferences is thread-safe
            // and loads from an in-memory cache after first access.
            // ----------------------------------------------------------------
            val appSettings = AppSettings(context)
            val whitelistManager = WhitelistManager(context)

            // ----------------------------------------------------------------
            // STEP 4b: Extract raw SMS PDUs from the broadcast intent.
            // ----------------------------------------------------------------
            // getMessagesFromIntent() handles both single-part and multi-part
            // (concatenated) SMS automatically. It returns one SmsMessage per
            // segment; for multi-part SMS we iterate and process each segment.
            // ----------------------------------------------------------------
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            // ----------------------------------------------------------------
            // STEP 4c: Log receipt for debugging.
            // ----------------------------------------------------------------
            // These lines write to the debug log file (see DebugLogger.kt).
            // If logging fails (e.g., SD card not mounted), the exception is
            // caught inside DebugLogger.write() and falls back to Log.d().
            // ----------------------------------------------------------------
            DebugLogger.log(TAG, "========== SMS RECEIVED ==========")
            DebugLogger.log(TAG, "Message count: ${messages.size}")

            // ----------------------------------------------------------------
            // STEP 4d: Process each SMS segment.
            // ----------------------------------------------------------------
            // For standard single-part SMS this loop runs once.
            // For concatenated SMS it runs once per segment; processMessage()
            // handles prefix/password checking independently per segment.
            // In practice, commands are short enough to fit in one segment.
            // ----------------------------------------------------------------
            for (message in messages) {
                processMessage(context, message, appSettings, whitelistManager)
            }
        } finally {
            // ----------------------------------------------------------------
            // STEP 5: Cleanup — RELEASE RESOURCES IMMEDIATELY.
            // ----------------------------------------------------------------
            // This finally{} block is the entire fix. It runs the instant the
            // try block completes, with zero delay. This satisfies OxygenOS's
            // aggressive broadcast timeout and prevents Android from
            // blacklisting Anchor.
            //
            // Order matters:
            //   1. Log that we are finishing (for debugging trace gaps)
            //   2. Release WakeLock so the device can sleep again
            //   3. Call pendingResult.finish() to tell Android we are done
            // ----------------------------------------------------------------
            DebugLogger.log(TAG, "goAsync finishing")
            if (wakeLock.isHeld) wakeLock.release()
            pendingResult.finish()
        }
    }

    // ------------------------------------------------------------------------
    // processMessage() — validates one SMS segment and executes the command.
    // ------------------------------------------------------------------------
    // This is called once per SMS segment (usually once total). It performs
    // all security checks before handing off to SmsCommandProcessor.
    //
    // Security layers (in order):
    //   1. Sender must have an originating address (anti-spoof guard)
    //   2. Message must have a body
    //   3. Sender must be in the whitelist (see WhitelistManager.kt)
    //   4. Message must start with the user's command prefix (e.g., "PIN")
    //   5. Password must match (checked inside SmsCommandProcessor.kt)
    // ------------------------------------------------------------------------
    private fun processMessage(
        context: Context,
        message: SmsMessage,
        appSettings: AppSettings,
        whitelistManager: WhitelistManager
    ) {
        // ----------------------------------------------------------------
        // GUARD 1: Reject SMS with no sender number.
        // ----------------------------------------------------------------
        // originatingAddress can be null for some carrier/system messages.
        // We drop these silently — they cannot be whitelisted anyway.
        // ----------------------------------------------------------------
        val senderNumber = message.originatingAddress ?: run {
            DebugLogger.log(TAG, "ERROR: No originating address")
            return
        }

        // ----------------------------------------------------------------
        // GUARD 2: Reject SMS with empty body.
        // ----------------------------------------------------------------
        val messageBody = message.messageBody ?: run {
            DebugLogger.log(TAG, "ERROR: No body")
            return
        }

        // ----------------------------------------------------------------
        // DIAGNOSTIC LOG: Record sender and body for debugging.
        // ----------------------------------------------------------------
        // NOTE: In production, consider truncating or hashing the body in logs
        // if privacy is a concern. Currently logs full body for debugging.
        // ----------------------------------------------------------------
        DebugLogger.log(TAG, "Sender: $senderNumber")
        DebugLogger.log(TAG, "Body: '$messageBody'")

        // ----------------------------------------------------------------
        // GUARD 3: Whitelist check.
        // ----------------------------------------------------------------
        // WhitelistManager reads from SharedPreferences and normalizes phone
        // numbers before comparison. If the whitelist is disabled, this
        // always returns true. See WhitelistManager.kt for logic.
        // ----------------------------------------------------------------
        if (!whitelistManager.isPhoneNumberAllowed(senderNumber)) {
            DebugLogger.log(TAG, "Whitelist: REJECTED")
            return
        }
        DebugLogger.log(TAG, "Whitelist: ALLOWED")

        // ----------------------------------------------------------------
        // AUTO-GENERATE PASSWORD if none exists yet.
        // ----------------------------------------------------------------
        // On first run, the user has not set a password. We generate one
        // randomly and store it in AppSettings. The user must open the app
        // to see it. This matches the behavior in MainActivity.onCreate().
        // ----------------------------------------------------------------
        if (appSettings.getString(AppSettings.SMS_COMMAND_PASSWORD).isEmpty()) {
            val defaultPassword = "password" + (1000..9999).random()
            appSettings.setString(AppSettings.SMS_COMMAND_PASSWORD, defaultPassword)
            DebugLogger.log(TAG, "Generated password: $defaultPassword")
        }

        // ----------------------------------------------------------------
        // EXTRACT COMMAND PREFIX.
        // ----------------------------------------------------------------
        // The prefix is user-configurable in CommandSettingsActivity.kt.
        // Default is typically "PIN". The SMS must start with this prefix
        // (case-insensitive) to be treated as a command.
        // ----------------------------------------------------------------
        val commandPrefix = appSettings.getString(AppSettings.SMS_COMMAND_PREFIX)

        // ----------------------------------------------------------------
        // GUARD 4 + EXECUTE: Check prefix and hand off to command processor.
        // ----------------------------------------------------------------
        // If the message starts with the prefix, strip the prefix and pass
        // the remainder to SmsCommandProcessor. ReplyChannel.TEXT is passed
        // explicitly so the processor knows to reply via standard text SMS
        // (not binary/data SMS — that path is handled by DataSmsReceiver.kt).
        //
        // SmsCommandProcessor will:
        //   - Split command and password
        //   - Validate password
        //   - Route to handleLocateCommand(), handleRingCommand(), etc.
        //   - Send reply SMS back to senderNumber
        // ----------------------------------------------------------------
        if (messageBody.trim().startsWith(commandPrefix, ignoreCase = true)) {
            val command = messageBody.trim().substring(commandPrefix.length).trim()
            DebugLogger.log(TAG, "Command: '$command'")

            // Fully qualified ReplyChannel to avoid import ambiguity.
            // See ReplyChannel.kt enum definition: TEXT = standard SMS, DATA = binary SMS.
            SmsCommandProcessor(context).processCommand(
                command,
                senderNumber,
                com.supernova.anchor.utils.ReplyChannel.TEXT
            )
        } else {
            // Message did not start with the prefix — treat as normal SMS,
            // not a command. No reply is sent.
            DebugLogger.log(TAG, "Prefix mismatch")
        }
    }
}
