package com.supernova.anchor

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.supernova.anchor.ui.theme.anchorTheme
import com.supernova.anchor.utils.AppSettings
import com.supernova.anchor.utils.DebugLogger

class SettingsActivity : ComponentActivity() {
    
    private lateinit var appSettings: AppSettings
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appSettings = AppSettings(this)
        enableEdgeToEdge()
        
        setContent {
            anchorTheme {
                SettingsScreen(
                    onBackClick = { finish() },
                    appSettings = appSettings
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    appSettings: AppSettings
) {
    var whitelistEnabled by remember { mutableStateOf(appSettings.getBoolean(AppSettings.WHITELIST_ENABLED)) }
    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        // Adding a ScrollableColumn to contain all settings
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Security Section
            SettingsSection(
                title = stringResource(R.string.security)
            ) {
                // Whitelist Card
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.enable_whitelist),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.whitelist_description),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = whitelistEnabled,
                                onCheckedChange = { isChecked ->
                                    whitelistEnabled = isChecked
                                    appSettings.setBoolean(AppSettings.WHITELIST_ENABLED, isChecked)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Manage whitelist button - only enabled if whitelist is enabled
                        Button(
                            onClick = {
                                context.startActivity(Intent(context, WhitelistActivity::class.java))
                            },
                            enabled = whitelistEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.manage_whitelist))
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Command Settings
                SettingsItem(
                    title = stringResource(R.string.command_settings),
                    subtitle = stringResource(R.string.command_settings_description),
                    onClick = {
                        val intent = Intent(context, CommandSettingsActivity::class.java)
                        context.startActivity(intent)
                    }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Permissions
                SettingsItem(
                    title = stringResource(R.string.permissions),
                    subtitle = stringResource(R.string.app_permission),
                    onClick = {
                        val intent = Intent(context, PermissionsActivity::class.java)
                        context.startActivity(intent)
                    }
                )
            }
            
            // App Information Section
            SettingsSection(
                title = stringResource(R.string.app_information)
            ) {
                // About
                SettingsItem(
                    title = stringResource(R.string.about),
                    subtitle = stringResource(R.string.click_here_for_more_info),
                    onClick = {
                        val intent = Intent(context, AboutActivity::class.java)
                        context.startActivity(intent)
                    }
                )
            }

                        // RCS Warning Section
            SettingsSection(title = "RCS Warning") {
                var showRcsWarning by remember {
                    mutableStateOf(appSettings.getBoolean(AppSettings.SHOW_RCS_WARNING))
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show on launch",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Warn about Chat Features (RCS) when the app opens",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showRcsWarning,
                            onCheckedChange = { checked ->
                                showRcsWarning = checked
                                appSettings.setBoolean(AppSettings.SHOW_RCS_WARNING, checked)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Debug Section — several ways to find the log, on purpose:
            // (1) the full path shown as copyable text, (2) a direct "open
            // in file manager" button, (3) a "share the file" button that
            // sidesteps needing a file manager at all, and (4) the ability
            // to pick a different folder entirely if the default location
            // isn't convenient on this device.
            DebugSection()

            // Add spacer at bottom to ensure the last item isn't cut off
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        content()
    }
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DebugSection() {
    val context = LocalContext.current
    // Bump this to force the path text + button states to recompute after
    // a folder is chosen/cleared (DebugLogger itself has no observable state).
    var refreshKey by remember { mutableStateOf(0) }
    val hasCustomFolder = remember(refreshKey) { DebugLogger.getCustomDebugFolderUri(context) != null }
    val currentPathLabel = remember(refreshKey) { DebugLogger.describeCurrentLocation(context) }

    val chooseFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            DebugLogger.setCustomDebugFolder(context, uri)
            refreshKey++
            Toast.makeText(context, "Debug folder updated", Toast.LENGTH_SHORT).show()
        }
    }

    SettingsSection(title = "Debug") {
        // Redundancy #1: full path, shown as plain selectable text so it can
        // be copy-pasted into any file manager or "go to path" field.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Current debug log location",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        text = currentPathLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Redundancy #2: direct "open it for me" button.
        SettingsItem(
            title = "Open debug folder",
            subtitle = "View log files in your file manager",
            onClick = { openDebugFolder(context) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Redundancy #3: share the file directly — works even if the device
        // has no usable file manager app at all.
        SettingsItem(
            title = "Share latest log",
            subtitle = "Send today's log file (email, Drive, etc.)",
            onClick = { shareLatestLog(context) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Task: let the user pick the directory themselves.
        SettingsItem(
            title = "Choose debug folder",
            subtitle = if (hasCustomFolder)
                "Custom folder selected — tap to change"
            else
                "Pick exactly where logs are saved (SD card, Downloads, etc.)",
            onClick = { chooseFolderLauncher.launch(null) }
        )

        if (hasCustomFolder) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsItem(
                title = "Use default location",
                subtitle = "Stop using the custom folder, go back to auto-detected storage",
                onClick = {
                    DebugLogger.clearCustomDebugFolder(context)
                    refreshKey++
                    Toast.makeText(context, "Reverted to default location", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

/**
 * Opens the debug folder directly in whatever file manager the device has.
 * If a custom SAF folder is set, its tree Uri is opened directly (fully
 * supported system-wide, since that's exactly what SAF tree Uris are for).
 * Otherwise falls back to the auto-detected folder via FileProvider. If
 * nothing on the device can handle either, the full path is shown as a
 * fallback so the user can navigate there manually.
 */
private fun openDebugFolder(context: android.content.Context) {
    val customUri = DebugLogger.getCustomDebugFolderUri(context)
    if (customUri != null) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(customUri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // fall through to the default-folder attempt below
        }
    }

    val dir = DebugLogger.getDebugDir(context)
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dir)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "resource/folder")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "No file manager found. Path: ${dir.absolutePath}", Toast.LENGTH_LONG).show()
    }
}

private fun shareLatestLog(context: android.content.Context) {
    val customUri = DebugLogger.getCustomDebugFolderUri(context)
    val shareUri: android.net.Uri? = if (customUri != null) {
        DebugLogger.getLatestCustomLogUri(context)
    } else {
        DebugLogger.getLatestLogFile(context)?.let { file ->
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }

    if (shareUri == null) {
        Toast.makeText(context, "No debug log yet.", Toast.LENGTH_SHORT).show()
        return
    }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share debug log"))
}
