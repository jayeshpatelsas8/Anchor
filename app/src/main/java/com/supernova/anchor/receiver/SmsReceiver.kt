package com.supernova.anchor.receiver

// =============================================================================
// FILE: SmsReceiver.kt
// =============================================================================
//
// WHAT THIS FILE DOES:
// This BroadcastReceiver is the front door for all standard text-SMS commands.
// When someone sends an SMS to this device, the Android Telephony stack fires
// an ordered broadcast with action "android.provider.Telephony.SMS_RECEIVED".
// This receiver catches that broadcast, validates the sender, checks the password
// prefix, and hands the command off to SmsCommandProcessor for execution.
//
// REQUIREMENTS THIS CODE ADDRESSES:
// 1. RECEIVE_SMS permission must be declared in AndroidManifest.xml
// 2. Receiver must be exported=true so the system SMS dispatcher can reach it
// 3. Deep sleep / Doze mode: CPU must be forced awake or command parsing dies
// 4. Ordered broadcast behavior: SMS_RECEIVED is ordered; slow receivers get killed
// 5. Aggressive OEM survival (OnePlus 7, Xiaomi, OPPO): finish() must be immediate
// 6. Debug logging: every SMS event must be written to a file log for diagnostics
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
//       <intent-filter android:priority="999">
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
//   internal `initialized` boolean guard. Keeping init() inside the receiver
//   ensures logs are captured even when the app process was killed and is
//   being cold-started by the SMS broadcast.
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
// =============================================================================

// Import the base class for all broadcast receivers.
// Android delivers SMS_RECEIVED broadcasts to any class extending BroadcastReceiver
// that is declared in AndroidManifest.xml with the matching intent-filter.
import android.content.BroadcastReceiver

// Import Context, the gateway to all Android system services.
// Used here to access PowerManager (for WakeLock) and to pass context down
// to AppSettings, WhitelistManager, SmsCommandProcessor, and DebugLogger.
import android.content.Context

// Import Intent, the message object carrying the broadcast.
// The intent.action tells us what event fired (we only care about SMS_RECEIVED).
// The intent.extras carry the raw SMS PDUs (Protocol Data Units).
import android.content.Intent

// Import PowerManager, the system service that controls CPU wake state.
// We use it to acquire a PARTIAL_WAKE_LOCK so the CPU stays awake during
// deep sleep (Doze mode) long enough to parse the SMS and execute commands.
import android.os.PowerManager

// Import Telephony, which contains the standard SMS_RECEIVED_ACTION string constant.
// This avoids hard-coding "android.provider.Telephony.SMS_RECEIVED" and ensures
// we always match the exact action the Android Telephony stack broadcasts.
import android.provider.Telephony

// Import SmsMessage, the class that decodes raw SMS PDUs into readable data.
// getMessagesFromIntent() returns an array of SmsMessage objects, each containing
// originatingAddress (sender phone number) and messageBody (text content).
import android.telephony.SmsMessage

// Import AppSettings, our wrapper around SharedPreferences.
// Stores user configuration: command prefix, command password, whitelist, etc.
// See AppSettings.kt for the full list of keys and default values.
import com.supernova.anchor.utils.AppSettings

// Import DebugLogger, our custom file-based logger.
// Writes every log line to a text file on external storage (SD card or shared storage)
// so we can diagnose SMS delivery issues without needing adb logcat.
// init() is idempotent — safe to call multiple times thanks to an internal flag.
import com.supernova.anchor.utils.DebugLogger

// Import SmsCommandProcessor, the brain that parses and executes commands.
// After this receiver validates the sender and prefix, the actual work
// (locate, ring, info, trace, sound, callme, ping, help) is delegated here.
import com.supernova.anchor.utils.SmsCommandProcessor

// Import WhitelistManager, the security gatekeeper.
// Checks if the sender's phone number is in the user's allowed list.
// If whitelist is enabled and the sender is not on it, the SMS is silently dropped.
import com.supernova.anchor.utils.WhitelistManager

// =============================================================================
// CLASS: SmsReceiver
// =============================================================================
// Declared in AndroidManifest.xml as a manifest-registered receiver.
// This means Android can start the app process solely to deliver this broadcast,
// even if the user has never opened the app UI. Critical for lost-device recovery.
// =============================================================================
class SmsReceiver : BroadcastReceiver() {

    // =========================================================================
    // COMPANION OBJECT
    // =========================================================================
    // Kotlin companion object = Java static members.
    // TAG is used for both DebugLogger file output and Android system logcat.
    // Keep this in sync with any logcat filters you use for debugging:
    //   adb logcat -s SmsReceiver:D
    // =========================================================================
    companion object {
        private const val TAG = "SmsReceiver"
    }

    // =========================================================================
    // onReceive() — Android calls this on the main thread when an SMS arrives.
    // =========================================================================
    // CRITICAL RULE: This method must return quickly. If we need more than
    // ~10 seconds of processing, we use goAsync(). If the device is in deep
    // sleep (Doze), we must acquire a WakeLock or the CPU will freeze mid-parse.
    //
    // PARAMETER context:
    //   The Context of the receiver. On a cold start this is the Application
    //   context; on a warm start it may be the Activity context. We always use
    //   context.applicationContext when passing down to long-lived objects.
    //
    // PARAMETER intent:
    //   The broadcast Intent. intent.action tells us the event type.
    //   intent.extras contains "pdus" (raw SMS byte arrays) and "format" (3GPP vs 3GPP2).
    // =========================================================================
    override fun onReceive(context: Context, intent: Intent) {

        // --------------------------------------------------------------------
        // Initialize the file logger.
        // --------------------------------------------------------------------
        // DebugLogger.init() checks an internal `initialized` boolean.
        // First call: creates the debug/ directory, opens today's log file.
        // Subsequent calls: returns immediately (no-op).
        //
        // WHY THIS IS HERE (not in Application.onCreate):
        // If the app process was killed by the OS, Android cold-starts it
        // solely to deliver this SMS broadcast. Application.onCreate() may
        // not have run yet, or may not run at all in some OEM paths.
        // Calling init() here guarantees the log file is ready before any
        // SMS processing happens, so we never lose diagnostic data.
        //
        // TRADE-OFF: This does a small amount of file I/O (mkdirs + FileWriter)
        // on the broadcast thread. On aggressive OEMs this adds ~50-200ms.
        // The WakeLock below ensures the CPU stays awake during this time.
        // See DebugLogger.kt for the full implementation.
        // --------------------------------------------------------------------
        DebugLogger.init(context)

        // --------------------------------------------------------------------
        // STEP 1: Validate the broadcast action.
        // --------------------------------------------------------------------
        // SMS_RECEIVED_ACTION is the only action we care about. If this receiver
        // is ever registered for other intents (it shouldn't be), drop them.
        // This matches the guard in DataSmsReceiver.kt.
        //
        // Telephony.Sms.Intents.SMS_RECEIVED_ACTION resolves to:
        // "android.provider.Telephony.SMS_RECEIVED"
        // --------------------------------------------------------------------
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            // Log the unexpected action so we can debug misconfigured intent-filters.
            DebugLogger.log(TAG, "Ignored intent: ${intent.action}")
            // Return immediately. Do NOT call goAsync() or acquire WakeLock
            // for events we don't care about — that would waste battery.
            return
        }

        // --------------------------------------------------------------------
        // STEP 2: Tell Android we need extended time beyond the default window.
        // --------------------------------------------------------------------
        // goAsync() returns a PendingResult that we MUST finish() later.
        // Without this, the system may ANR or kill us if processing takes >10s.
        //
        // HOW IT WORKS:
        //   - goAsync() extends the broadcast deadline from ~10s to ~60s (varies by OEM).
        //   - It returns a PendingResult object.
        //   - We MUST call pendingResult.finish() when done, or Android will
        //     eventually ANR us and may blacklist the app from future broadcasts.
        //
        // ONEPLUS 7 WARNING:
        //   OxygenOS 11 is aggressive — it may kill us even with goAsync()
        //   if finish() is not called within ~1-2 seconds. That is why the
        //   old Handler.postDelayed(5000) was fatal: it delayed finish() past
        //   the OEM kill window. The fix is immediate finish() in finally{}.
        // --------------------------------------------------------------------
        val pendingResult = goAsync()

        // --------------------------------------------------------------------
        // STEP 3: Force the CPU awake during deep sleep / Doze mode.
        // --------------------------------------------------------------------
        // PowerManager provides WakeLocks — system-level locks that prevent
        // the CPU from entering deep sleep. We use PARTIAL_WAKE_LOCK because:
        //   - It keeps the CPU running.
        //   - It does NOT turn the screen on (saves battery, stealth mode).
        //
        // ACQUIRE_CAUSES_WAKEUP is added so the device fully wakes from deep
        // sleep to process the command. Without this, Doze mode may freeze
        // the CPU mid-parse and the "locate" or "trace" command never executes.
        //
        // THE TAG STRING:
        //   "Anchor::SmsReceiver" identifies this WakeLock in dumpsys.
        //   On API 17+ the tag is technically ignored for unreference-counted
        //   locks, but it still appears in `adb shell dumpsys power` for debugging.
        //
        // THE TIMEOUT (10_000 ms = 10 seconds):
        //   This is a SAFETY NET. Even if release() is never reached due to a
        //   crash or OEM kill, the WakeLock auto-releases after 10 seconds.
        //   Without a timeout, a bug could drain the battery by holding the
        //   CPU awake forever.
        // --------------------------------------------------------------------
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Anchor::SmsReceiver"
        )
        // Acquire the lock with a 10-second automatic timeout.
        // The timeout prevents battery drain if the app crashes before release().
        wakeLock.acquire(10_000)

        // --------------------------------------------------------------------
        // STEP 4: Parse and process the SMS inside try/finally.
        // --------------------------------------------------------------------
        // The try block contains all actual work. The finally block guarantees
        // that pendingResult.finish() and wakeLock.release() ALWAYS run,
        // even if an exception is thrown during parsing or command execution.
        //
        // WHY try/finally AND NOT Handler.postDelayed():
        //   The old code used:
        //     Handler(Looper.getMainLooper()).postDelayed({ finish() }, 5_000)
        //   This delayed finish() by 5 seconds. On OnePlus 7 OxygenOS 11,
        //   the system kills the process before 5s, causing Android to
        //   permanently blacklist Anchor from SMS_RECEIVED broadcasts.
        //   DataSmsReceiver.kt already used immediate finally{}; this change
        //   makes SmsReceiver match it exactly.
        //
        // EXCEPTION SAFETY:
        //   If processMessage() throws (e.g., NullPointerException from a
        //   malformed SMS), the exception propagates up, hits the finally
        //   block, releases the WakeLock, and calls finish(). The app does
        //   NOT leak resources or get blacklisted.
        // --------------------------------------------------------------------
        try {

            // ----------------------------------------------------------------
            // STEP 4a: Load settings and whitelist validator.
            // ----------------------------------------------------------------
            // AppSettings is backed by SharedPreferences. It is safe to construct
            // on the broadcast thread because SharedPreferences is thread-safe
            // and loads from an in-memory cache after first access.
            //
            // AppSettings reads these keys (see AppSettings.kt for definitions):
            //   - SMS_COMMAND_PREFIX    : default "PIN", user-configurable
            //   - SMS_COMMAND_PASSWORD  : auto-generated if empty
            //   - WHITELIST_ENABLED     : boolean, off by default
            //   - WHITELIST_NUMBERS     : JSON array of allowed phone numbers
            // ----------------------------------------------------------------
            val appSettings = AppSettings(context)

            // WhitelistManager wraps the whitelist logic.
            // It normalizes phone numbers (strips +, country codes, spaces)
            // before comparison so "+1 555 123 4567" matches "15551234567".
            val whitelistManager = WhitelistManager(context)

            // ----------------------------------------------------------------
            // STEP 4b: Extract raw SMS PDUs from the broadcast intent.
            // ----------------------------------------------------------------
            // PDU = Protocol Data Unit — the raw binary SMS packet.
            // getMessagesFromIntent() is a platform helper that:
            //   1. Reads "pdus" from intent.extras (Array of byte[])
            //   2. Reads "format" ("3gpp" for GSM, "3gpp2" for CDMA)
            //   3. Calls SmsMessage.createFromPdu() for each PDU
            //   4. Handles concatenated (multi-part) SMS automatically
            //
            // For a standard 160-character SMS, this returns 1 SmsMessage.
            // For a longer concatenated SMS, it returns multiple segments.
            // ----------------------------------------------------------------
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            // ----------------------------------------------------------------
            // STEP 4c: Log receipt for debugging.
            // ----------------------------------------------------------------
            // These lines write to the debug log file (see DebugLogger.kt).
            // The log file path is typically:
            //   /sdcard/Android/data/com.supernova.anchor/files/debug/YYYY-MM-DD.txt
            // If the SD card is not mounted, it falls back to internal storage.
            //
            // If logging fails (e.g., storage full, permission denied), the
            // exception is caught inside DebugLogger.write() and falls back
            // to Android Log.d() so we never lose diagnostic data.
            // ----------------------------------------------------------------
            DebugLogger.log(TAG, "========== SMS RECEIVED ==========")
            DebugLogger.log(TAG, "Message count: ${messages.size}")

            // ----------------------------------------------------------------
            // STEP 4d: Process each SMS segment.
            // ----------------------------------------------------------------
            // For standard single-part SMS this loop runs once.
            // For concatenated SMS it runs once per segment; processMessage()
            // handles prefix/password checking independently per segment.
            //
            // In practice, SMS commands are short enough to fit in one segment
            // (160 chars for 7-bit GSM alphabet, 70 for Unicode).
            // A typical command: "PIN locate mypassword" fits easily.
            // ----------------------------------------------------------------
            for (message in messages) {
                // Delegate to processMessage() for validation and execution.
                // processMessage() may call SmsCommandProcessor which may start
                // a foreground service — that is why we hold the WakeLock.
                processMessage(context, message, appSettings, whitelistManager)
            }

        } finally {
            // ----------------------------------------------------------------
            // STEP 5: Cleanup — RELEASE RESOURCES IMMEDIATELY.
            // ----------------------------------------------------------------
            // This finally{} block is the entire OnePlus 7 fix. It runs the
            // instant the try block completes, with zero delay. This satisfies
            // OxygenOS's aggressive broadcast timeout and prevents Android from
            // blacklisting Anchor.
            //
            // ORDER OF OPERATIONS:
            //   1. Log that we are finishing (for debugging trace gaps).
            //      If this log never appears, we know the OEM killed us mid-flight.
            //   2. Release WakeLock so the device can return to deep sleep.
            //      We check isHeld first to avoid IllegalStateException on double-release.
            //   3. Call pendingResult.finish() to tell Android we are done.
            //      After this, the broadcast-bound process may be killed.
            // ----------------------------------------------------------------

            // Log the finish event. If this line is missing from the log file,
            // it means the app was killed before reaching finally{} — useful
            // for diagnosing OEM-specific broadcast timeouts.
            DebugLogger.log(TAG, "goAsync finishing")

            // Release the WakeLock. The isHeld guard prevents an
            // IllegalStateException if the lock was already auto-released
            // by the 10-second timeout (e.g., during a very slow command).
            if (wakeLock.isHeld) {
                wakeLock.release()
            }

            // Tell Android the broadcast is complete. THIS IS MANDATORY.
            // Without finish(), Android will eventually ANR the receiver
            // and may stop delivering SMS_RECEIVED broadcasts to this app.
            pendingResult.finish()
        }
    }

    // =========================================================================
    // processMessage() — validates one SMS segment and executes the command.
    // =========================================================================
    // This is called once per SMS segment (usually once total). It performs
    // all security checks before handing off to SmsCommandProcessor.
    //
    // SECURITY LAYERS (in order):
    //   1. Sender must have an originating address (anti-spoof guard)
    //   2. Message must have a body
    //   3. Sender must be in the whitelist (see WhitelistManager.kt)
    //   4. Message must start with the user's command prefix (e.g., "PIN")
    //   5. Password must match (checked inside SmsCommandProcessor.kt)
    //
    // PARAMETER context:
    //   Application context, passed down to SmsCommandProcessor for service starts.
    //
    // PARAMETER message:
    //   One SmsMessage segment containing originatingAddress and messageBody.
    //
    // PARAMETER appSettings:
    //   SharedPreferences wrapper, already loaded in onReceive().
    //
    // PARAMETER whitelistManager:
    //   Whitelist validator, already loaded in onReceive().
    // =========================================================================
    private fun processMessage(
        context: Context,              // Context for downstream service starts
        message: SmsMessage,             // One SMS PDU segment
        appSettings: AppSettings,       // Already-loaded settings
        whitelistManager: WhitelistManager  // Already-loaded whitelist
    ) {

        // ----------------------------------------------------------------
        // GUARD 1: Reject SMS with no sender number.
        // ----------------------------------------------------------------
        // originatingAddress can be null for some carrier/system messages
        // (e.g., voicemail alerts, emergency broadcasts). We drop these
        // silently — they cannot be whitelisted anyway.
        //
        // The Elvis operator (?:) returns the left side if non-null,
        // otherwise executes the right side (run block) and returns early.
        // ----------------------------------------------------------------
        val senderNumber = message.originatingAddress ?: run {
            // Log the null sender for diagnostics. This usually means a
            // system-generated SMS that should be ignored.
            DebugLogger.log(TAG, "ERROR: No originating address")
            // Return from processMessage(), NOT from onReceive().
            // Other segments (if any) will still be processed.
            return
        }

        // ----------------------------------------------------------------
        // GUARD 2: Reject SMS with empty body.
        // ----------------------------------------------------------------
        // messageBody can be null for binary SMS or empty PDUs.
        // Standard text SMS always has a body, but we guard anyway.
        // ----------------------------------------------------------------
        val messageBody = message.messageBody ?: run {
            DebugLogger.log(TAG, "ERROR: No body")
            return
        }

        // ----------------------------------------------------------------
        // DIAGNOSTIC LOG: Record sender and body for debugging.
        // ----------------------------------------------------------------
        // NOTE: In production deployments, consider truncating or hashing
        // the body in logs if privacy is a concern. Currently logs the full
        // body to aid debugging "why didn't my command work?" issues.
        //
        // senderNumber is the raw string from the PDU — may include + prefix
        // and country code depending on the carrier.
        // ----------------------------------------------------------------
        DebugLogger.log(TAG, "Sender: $senderNumber")
        DebugLogger.log(TAG, "Body: '$messageBody'")

        // ----------------------------------------------------------------
        // GUARD 3: Whitelist check.
        // ----------------------------------------------------------------
        // WhitelistManager reads from SharedPreferences and normalizes phone
        // numbers before comparison. Normalization strips:
        //   - Leading + signs
        //   - Country codes (if both numbers have them)
        //   - Spaces, dashes, parentheses
        //
        // If the whitelist feature is disabled in settings, this always
        // returns true and the guard is effectively a no-op.
        //
        // If the sender is NOT allowed, we return silently — NO reply is sent.
        // This is a security feature: attackers should not know the app exists.
        // ----------------------------------------------------------------
        if (!whitelistManager.isPhoneNumberAllowed(senderNumber)) {
            // Log the rejection for the owner's diagnostics.
            // The sender never knows they were blocked.
            DebugLogger.log(TAG, "Whitelist: REJECTED")
            return
        }
        // Sender passed the whitelist. Log success.
        DebugLogger.log(TAG, "Whitelist: ALLOWED")

        // ----------------------------------------------------------------
        // AUTO-GENERATE PASSWORD if none exists yet.
        // ----------------------------------------------------------------
        // On first install, the user has not set a password. We generate one
        // randomly and store it in AppSettings. The user must open the app
        // to see it. This matches the behavior in MainActivity.onCreate().
        //
        // The password format is "password" + 4 random digits, e.g. "password7392".
        // This is not cryptographically strong but is sufficient for SMS
        // command protection (the attack surface is limited to physical device
        // access + knowing the phone number).
        //
        // If a password already exists, this block is skipped.
        // ----------------------------------------------------------------
        if (appSettings.getString(AppSettings.SMS_COMMAND_PASSWORD).isEmpty()) {
            // Generate a random 4-digit suffix (1000-9999).
            val defaultPassword = "password" + (1000..9999).random()
            // Persist to SharedPreferences so it survives reboots.
            appSettings.setString(AppSettings.SMS_COMMAND_PASSWORD, defaultPassword)
            // Log the generated password so the owner can find it in the log file.
            DebugLogger.log(TAG, "Generated password: $defaultPassword")
        }

        // ----------------------------------------------------------------
        // EXTRACT COMMAND PREFIX.
        // ----------------------------------------------------------------
        // The prefix is user-configurable in CommandSettingsActivity.kt.
        // Default is typically "PIN". The SMS must start with this prefix
        // (case-insensitive) to be treated as a command.
        //
        // Example valid command: "PIN locate mypassword"
        // Example invalid message: "Hey, where are you?" (no prefix)
        // ----------------------------------------------------------------
        val commandPrefix = appSettings.getString(AppSettings.SMS_COMMAND_PREFIX)

        // ----------------------------------------------------------------
        // GUARD 4 + EXECUTE: Check prefix and hand off to command processor.
        // ----------------------------------------------------------------
        // If the message starts with the prefix (case-insensitive), strip the
        // prefix and pass the remainder to SmsCommandProcessor.
        //
        // ReplyChannel.TEXT is passed explicitly so the processor knows to
        // reply via standard text SMS (not binary/data SMS — that path is
        // handled by DataSmsReceiver.kt).
        //
        // SmsCommandProcessor will:
        //   1. Split the command string by whitespace
        //   2. Validate the password (second token)
        //   3. Route to the appropriate handler:
        //      - "locate"  → LocationForegroundService.start()
        //      - "ring"    → RingtonePlayer.playRingtone() + OverlayDisplayService
        //      - "info"    → BatteryUtils + PowerManager query, then SMS reply
        //      - "trace"   → TraceForegroundService.start() or .stop()
        //      - "sound"   → SoundModeManager (normal/vibrate/silent)
        //      - "callme"  → Intent.ACTION_CALL
        //      - "ping"    → Immediate SMS reply "Anchor is running"
        //      - "help"    → SMS reply with command list
        //   4. Send the reply SMS back to senderNumber
        // ----------------------------------------------------------------
        if (messageBody.trim().startsWith(commandPrefix, ignoreCase = true)) {
            // Strip the prefix and any leading/trailing whitespace.
            // "PIN locate mypassword" → "locate mypassword"
            val command = messageBody.trim().substring(commandPrefix.length).trim()
            // Log the extracted command for diagnostics.
            DebugLogger.log(TAG, "Command: '$command'")

            // Fully qualified ReplyChannel to avoid import ambiguity.
            // See ReplyChannel.kt enum definition:
            //   TEXT = standard SMS (this file)
            //   DATA = binary/port-addressed SMS (DataSmsReceiver.kt)
            SmsCommandProcessor(context).processCommand(
                command,           // The command string minus prefix
                senderNumber,      // Where to send the reply
                com.supernova.anchor.utils.ReplyChannel.TEXT  // Reply via text SMS
            )
        } else {
            // The message did not start with the command prefix.
            // This is a normal SMS from a whitelisted contact, not a command.
            // We do NOT send a reply — that would spam the contact.
            DebugLogger.log(TAG, "Prefix mismatch")
        }
    }
}
