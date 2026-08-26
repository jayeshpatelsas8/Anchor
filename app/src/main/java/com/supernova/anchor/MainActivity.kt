package com.supernova.anchor

// =============================================================================
// FILE: MainActivity.kt
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

    private val requestPermissionsLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissionManager.handlePermissionResult(permissions)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appSettings = AppSettings(this)
        permissionManager = PermissionManager(this)

        // ----------------------------------------------------------------
        // FIRST-RUN: Show disclaimer
        // ----------------------------------------------------------------
        if (!appSettings.getBoolean(AppSettings.HAS_SEEN_DISCLAIMER)) {
            showDisclaimerDialogState.value = true
        }

        // ----------------------------------------------------------------
        // AUTO-GENERATE DEFAULT PASSWORD on first run
        // ----------------------------------------------------------------
        if (appSettings.getString(AppSettings.SMS_COMMAND_PASSWORD).isEmpty()) {
            val defaultPassword = "password" + (1000..9999).random()
            appSettings.setString(AppSettings.SMS_COMMAND_PASSWORD, defaultPassword)
            Log.d("MainActivity", "Generated default SMS command password: $defaultPassword")
        }

        // ----------------------------------------------------------------
        // BATTERY OPTIMIZATION CHECK (with crash guard)
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
            Log.e("MainActivity", "Battery dialog failed to show (OEM compatibility): ${e.message}")
        }

        // ----------------------------------------------------------------
        // RCS / CHAT FEATURES WARNING
        // ----------------------------------------------------------------
        if (appSettings.getBoolean(AppSettings.SHOW_RCS_WARNING)) {
            try {
                val checkBox = android.widget.CheckBox(this).apply {
                    text = "Do not show this warning again"
                }

                android.app.AlertDialog.Builder(this)
                    .setTitle("RCS / Chat Features Warning")
                    .setMessage(
                        "This app does NOT work with RCS (Chat Features).\n\n" +
                        "If Chat Features is turned ON in your messaging app, " +
                        "SMS commands will be silently dropped and Anchor will not work.\n\n" +
                        "Please disable Chat Features for reliable lost-device recovery:\n" +
                        "Open your messaging app → Settings → Chat features → Turn OFF"
                    )
                    .setView(checkBox)
                    .setPositiveButton("OK") { _, _ ->
                        if (checkBox.isChecked) {
                            appSettings.setBoolean(AppSettings.SHOW_RCS_WARNING, false)
                        }
                    }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                Log.e("MainActivity", "RCS warning dialog failed: ${e.message}")
            }
        }

        // ----------------------------------------------------------------
        // GPS STATUS CHECK
        // ----------------------------------------------------------------
        if (!LocationSettingsUtils.isGpsEnabled(this)) {
            Log.w("MainActivity", "GPS is disabled in system settings")
        }

        // ----------------------------------------------------------------
        // RENDER THE COMPOSE UI
        // ----------------------------------------------------------------
        setContent {
            anchorTheme {
                if (showDisclaimerDialogState.value) {
                    DisclaimerDialog(
                        onDismiss = { finish() },
                        onAccept = {
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

    private fun navigateToSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun navigateToPermissions() {
        startActivity(Intent(this, PermissionsActivity::class.java))
    }

    private fun navigateToMessages() {
        startActivity(Intent(this, ChatActivity::class.java))
    }
}

// =============================================================================
// COMPOSABLE: MainScreen
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
