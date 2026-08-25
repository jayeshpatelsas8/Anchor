package com.supernova.anchor

// =============================================================================
// FILE: MainActivity.kt
// =============================================================================
//
// WHAT THIS FILE DOES:
// MainActivity is the single entry point users see when they open Anchor.
// It is a Jetpack Compose Activity that displays the main screen with
// buttons to Settings, Permissions, and Binary Mode (Chat). It also handles
// first-run setup: generating a default SMS password and showing a disclaimer.
//
// CRITICAL: This file was crashing on some devices (OnePlus 7, certain Samsung
// models) because the battery-optimization AlertDialog was thrown inside
// onCreate() without exception handling. If the dialog failed to inflate
// (theme mismatch, edge-to-edge conflict, or OEM-specific WindowManager bug),
// the entire app crashed before showing any UI. The fix wraps the dialog in
// try/catch so the app always opens, even if the battery prompt fails.
//
// RELATIONSHIP TO OTHER FILES:
// - AppSettings.kt        : Stores HAS_SEEN_DISCLAIMER, SMS_COMMAND_PASSWORD, etc.
// - PermissionManager.kt  : Handles runtime permission requests (SMS, Location, etc.)
// - BatteryOptimizationUtils.kt : Checks if Android Doze is ignoring our app
// - LocationSettingsUtils.kt    : Checks if system GPS is enabled
// - SettingsActivity.kt   : Opened when user taps "Settings" button
// - PermissionsActivity.kt: Opened when user taps "Permissions" button
// - ChatActivity.kt       : Opened when user taps "Binary Mode" button
// - SmsReceiver.kt        : Works even if MainActivity never opens (manifest-registered)
// - DisclaimerDialog.kt   : Shown on first launch; user must accept to continue
//
// STEP-BY-STEP FLOW:
// 1. Android launches MainActivity via LAUNCHER intent-filter (see AndroidManifest.xml)
// 2. onCreate() initializes AppSettings and PermissionManager
// 3. If first launch → show DisclaimerDialog (must accept or app exits)
// 4. Generate default SMS password if none exists (so SMS commands work immediately)
// 5. Check battery optimization status → prompt user to disable (with crash guard)
// 6. Check if system GPS is enabled → log warning if disabled
// 7. Render Compose UI: logo, status text, and three navigation buttons
//
// PERMISSIONS DECLARED IN MANIFEST (AndroidManifest.xml):
//   RECEIVE_SMS, SEND_SMS, ACCESS_FINE_LOCATION, ACCESS_BACKGROUND_LOCATION,
//   CALL_PHONE, SYSTEM_ALERT_WINDOW, READ_CONTACTS, MODIFY_AUDIO_SETTINGS,
//   WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION, POST_NOTIFICATIONS,
//   REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, SCHEDULE_EXACT_ALARM
// =============================================================================

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.supernova.anchor.ui.theme.anchorTheme
import com.supernova.anchor.utils.AppSettings
import com.supernova.anchor.utils.Utils
import com.supernova.anchor.dialogs.DisclaimerDialog
import com.supernova.anchor.utils.PermissionManager
import com.supernova.anchor.utils.BatteryOptimizationUtils
import com.supernova.anchor.utils.LocationSettingsUtils


class MainActivity : ComponentActivity() {
    private lateinit var appSettings: AppSettings
    private lateinit var permissionManager: PermissionManager
    private val showDisclaimerDialogState = mutableStateOf(false)

    // Permission launcher using the modern Activity Result API.
    // This replaces the deprecated onRequestPermissionsResult() pattern.
    // When the user responds to a permission dialog, this callback fires
    // and delegates to PermissionManager for handling.
    private val requestPermissionsLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissionManager.handlePermissionResult(permissions)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge drawing (status bar and navigation bar become
        // part of the app's canvas). This is a Compose best practice but can
        // conflict with AlertDialog on some OEM skins — hence the try/catch
        // around the battery dialog below.
        enableEdgeToEdge()

        // Initialize settings and permission manager.
        // AppSettings wraps SharedPreferences ("anchor.settings" file).
        appSettings = AppSettings(this)
        permissionManager = PermissionManager(this)

        // ----------------------------------------------------------------
        // FIRST-RUN: Show disclaimer if user has never accepted it.
        // ----------------------------------------------------------------
        // The disclaimer explains that Anchor is for locating YOUR OWN device
        // only. If the user declines, finish() is called and the app exits.
        // This must happen before any permission requests.
        // ----------------------------------------------------------------
        if (!appSettings.getBoolean(AppSettings.HAS_SEEN_DISCLAIMER)) {
            showDisclaimerDialogState.value = true
        }

        // ----------------------------------------------------------------
        // AUTO-GENERATE DEFAULT PASSWORD on first run.
        // ----------------------------------------------------------------
        // If the user has never set a password, we generate one randomly
        // (format: "password" + 4 digits) and store it in SharedPreferences.
        // The user can see it in Command Settings and change it.
        // This ensures SMS commands work immediately after install.
        // ----------------------------------------------------------------
        if (appSettings.getString(AppSettings.SMS_COMMAND_PASSWORD).isEmpty()) {
            val defaultPassword = "password" + (1000..9999).random()
            appSettings.setString(AppSettings.SMS_COMMAND_PASSWORD, defaultPassword)
            Log.d("MainActivity", "Generated default SMS command password: $defaultPassword")
        }

        // ----------------------------------------------------------------
        // BATTERY OPTIMIZATION CHECK (with crash guard).
        // ----------------------------------------------------------------
        // Android Doze mode can kill background services (LocationForegroundService,
        // TraceForegroundService) to save battery. We prompt the user to add
        // Anchor to the "not optimized" list. This is critical for lost-device
        // recovery — if Doze kills the app, SMS commands won't work.
        //
        // THE BUG WE FIXED:
        //   android.app.AlertDialog.Builder(this) can crash inside a
        //   ComponentActivity with enableEdgeToEdge() on some OEM skins
        //   (OnePlus 7, Samsung One UI). The crash was an unhandled
        //   WindowManager$BadTokenException or theme inflation error.
        //   Because this code runs in onCreate() BEFORE setContent(),
        //   the crash killed the app before any UI appeared.
        //
        // THE FIX:
        //   Wrap the entire dialog block in try/catch. If the dialog fails,
        //   we log the error and continue — the app opens normally, and the
        //   user can manually disable battery optimization later via
        //   Settings > Apps > Anchor > Battery.
        // ----------------------------------------------------------------
        try {
            if (BatteryOptimizationUtils.isBatteryOptimizationEnabled(this)) {
                Log.w("MainActivity", "Battery optimization is ON — may kill background GPS")
                android.app.AlertDialog.Builder(this)
                    .setTitle("Battery Optimization")
                    .setMessage("Anchor needs to run in the background for GPS tracking and SMS commands. Please disable battery optimization for this app.")
                    .setPositiveButton("Disable") { _, _ ->
                        BatteryOptimizationUtils.requestExemption(this)
                    }
                    .setNegativeButton("Later", null)
                    .setCancelable(false)
                    .show()
            }
        } catch (e: Exception) {
            // Log the error but DO NOT crash. The app is still usable;
            // the user just won't see the battery prompt this time.
            Log.e("MainActivity", "Battery dialog failed to show (OEM compatibility): ${e.message}")
        }

        // ----------------------------------------------------------------
        // GPS STATUS CHECK.
        // ----------------------------------------------------------------
        // If system GPS is disabled, location commands ("locate", "trace")
        // will fail silently or return stale cache. We log a warning.
        // We do NOT force the user to enable it — they may want SMS-only
        // functionality (ring, info, sound) without GPS.
        // ----------------------------------------------------------------
        if (!LocationSettingsUtils.isGpsEnabled(this)) {
            Log.w("MainActivity", "GPS is disabled in system settings")
        }

        // ----------------------------------------------------------------
        // RENDER THE COMPOSE UI.
        // ----------------------------------------------------------------
        setContent {
            anchorTheme {
                // Show disclaimer dialog if needed (first launch only)
                if (showDisclaimerDialogState.value) {
                    DisclaimerDialog(
                        onDismiss = {
                            // User declined — exit the app entirely
                            finish()
                        },
                        onAccept = {
                            // User accepted — persist flag and continue
                            appSettings.setBoolean(AppSettings.HAS_SEEN_DISCLAIMER, true)
                            showDisclaimerDialogState.value = false
                        }
                    )
                }

                MainScreen(
                    onSettingsClick = { navigateToSettings() },
                    onPermissionsClick = { navigateToPermissions() },
                    onMessagesClick = { navigateToMessages() },
                    onOverlayPermissionRequest = { permissionManager.requestOverlayPermission() }
                )
            }
        }
    }

    // Navigate to Settings screen (command prefix, password, whitelist toggle)
    private fun navigateToSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    // Navigate to Permissions screen (SMS, Location, Phone, Overlay, etc.)
    private fun navigateToPermissions() {
        val intent = Intent(this, PermissionsActivity::class.java)
        startActivity(intent)
    }

    // Navigate to Binary Mode chat screen (data SMS thread view)
    private fun navigateToMessages() {
        val intent = Intent(this, ChatActivity::class.java)
        startActivity(intent)
    }
}

// =============================================================================
// COMPOSABLE: MainScreen
// =============================================================================
// The primary UI of the app. Displays:
//   - Anchor logo (adaptive size via dimensionResource)
//   - Status text: "Locate My Device is running"
//   - Three buttons: Permissions, Binary Mode, Settings
//
// All content is wrapped in a verticalScroll() so it works on small screens.
// The Scaffold provides the TopAppBar with a settings icon.
// =============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onSettingsClick: () -> Unit,
    onPermissionsClick: () -> Unit,
    onMessagesClick: () -> Unit,
    onOverlayPermissionRequest: () -> Unit
) {
    val context = LocalContext.current
    val appSettings = remember { AppSettings(context) }

    val showOverlayDialog = remember { mutableStateOf(false) }
    val secureMode = remember { 
        mutableStateOf(appSettings.getBoolean(AppSettings.SECURE_MODE_ENABLED))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anchor") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // App logo — size comes from res/values/dimens.xml for adaptability
            Icon(
                painter = painterResource(id = R.drawable.ic_anchor_logo),
                contentDescription = "Anchor Logo",
                modifier = Modifier
                    .size(dimensionResource(id = R.dimen.logo_size))
                    .padding(bottom = 24.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Text(
                text = stringResource(R.string.locate_my_device_is_running),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onPermissionsClick,
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(0.8f)
            ) {
                Text(stringResource(R.string.permissions))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onMessagesClick,
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(0.8f)
            ) {
                Text("Binary Mode")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onSettingsClick,
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(0.8f)
            ) {
                Text(stringResource(R.string.settings))
            }

            Spacer(modifier = Modifier.height(64.dp))
        }
    }

    // Overlay permission dialog — only shown when explicitly triggered
    if (showOverlayDialog.value) {
        OverlayPermissionDialog(
            onConfirm = {
                onOverlayPermissionRequest()
                showOverlayDialog.value = false
            },
            onDismiss = { showOverlayDialog.value = false }
        )
    }
}

// =============================================================================
// COMPOSABLE: OverlayPermissionDialog
// =============================================================================
// Prompts the user to grant SYSTEM_ALERT_WINDOW permission ("Display over
// other apps"). This is required for OverlayDisplayService to show the
// full-screen alert during the "ring" command. Without it, the ringtone
// plays but no visual overlay appears.
//
// The "Don't Show Again" button persists a flag so the dialog doesn't
// nag the user on every launch.
// =============================================================================
@Composable
fun OverlayPermissionDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val appSettings = remember { AppSettings(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Display Over Other Apps") },
        text = { Text("This permission is required for the app to display messages and alerts when your device is lost.") },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                appSettings.setBoolean(AppSettings.DO_NOT_SHOW_OVERLAY_PERMISSION_AGAIN, true)
                onDismiss()
            }) {
                Text("Don't Show Again")
            }
        }
    )
}
