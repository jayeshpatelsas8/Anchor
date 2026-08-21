package com.supernova.anchor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supernova.anchor.data.ChatMessage
import com.supernova.anchor.data.MessageRepository
import com.supernova.anchor.utils.AppSettings
import com.supernova.anchor.utils.CommandValidator
import com.supernova.anchor.utils.DataSmsSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

private val urlPattern: Pattern = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatThreadScreen(threadKey: String, onBackClick: () -> Unit) {
    val context = LocalContext.current
    val appSettings = remember { AppSettings(context) }
    val commandPrefix = remember { appSettings.getString(AppSettings.SMS_COMMAND_PREFIX) }

    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty()
    val listState = rememberLazyListState()

    LaunchedEffect(threadKey) {
        MessageRepository.messagesForThread(context, threadKey).collect { messages = it }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LaunchedEffect(messages) {
        val validIds = messages.map { it.id }.toSet()
        if (selectedIds.any { it !in validIds }) selectedIds = selectedIds.intersect(validIds)
    }

    val validation = remember(input, commandPrefix) { CommandValidator.validate(input, commandPrefix) }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(threadKey) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        },
        bottomBar = {
            Composer(
                input = input,
                onInputChange = { input = it; sendError = null },
                validation = validation,
                sendError = sendError,
                onSend = {
                    val result = DataSmsSender.send(context, threadKey, input)
                    when (result) {
                        is DataSmsSender.Result.Sent -> {
                            MessageRepository.addMessage(
                                context,
                                ChatMessage(
                                    id = UUID.randomUUID().toString(),
                                    text = input,
                                    sender = threadKey,
                                    timestamp = System.currentTimeMillis(),
                                    isIncoming = false,
                                    isCommand = true
                                )
                            )
                            input = ""
                            sendError = null
                        }
                        is DataSmsSender.Result.TooLong ->
                            sendError = "Too long for a binary message (${result.actualBytes}B, limit ${DataSmsSender.MAX_PAYLOAD_BYTES}B)."
                        is DataSmsSender.Result.Failed ->
                            sendError = "Send failed: ${result.reason}"
                    }
                }
            )
        }
    ) { innerPadding ->
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No messages with $threadKey yet.\nType a command below, e.g. \"$commandPrefix locate mypassword\".",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        selected = selectedIds.contains(message.id),
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) {
                                selectedIds = if (selectedIds.contains(message.id)) {
                                    selectedIds - message.id
                                } else {
                                    selectedIds + message.id
                                }
                            }
                        },
                        onLongClick = { selectedIds = selectedIds + message.id }
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${selectedIds.size} message${if (selectedIds.size > 1) "s" else ""}?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    MessageRepository.deleteMessages(context, selectedIds)
                    selectedIds = emptySet()
                    showDeleteConfirm = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    validation: CommandValidator.Result,
    sendError: String?,
    onSend: () -> Unit
) {
    val invalid = validation as? CommandValidator.Result.Invalid
    val canSend = validation is CommandValidator.Result.Valid

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp)
    ) {
        // Inline error: what's wrong, where (via red highlight in the field below), and an example.
        if (invalid != null) {
            Text(
                text = "⚠ ${invalid.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
            Text(
                text = "Example: ${invalid.example}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
        }
        if (sendError != null) {
            Text(
                text = "⚠ $sendError",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
        }

        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a command, e.g. PIN locate mypassword") },
                isError = invalid != null,
                visualTransformation = ErrorHighlightTransformation(invalid?.highlightRange),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                maxLines = 3
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .background(
                        if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        androidx.compose.foundation.shape.CircleShape
                    )
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Underlines the invalid token (if any) in red, directly inside the input field. */
private class ErrorHighlightTransformation(private val range: IntRange?) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (range == null) return TransformedText(text, OffsetMapping.Identity)
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        val styled = buildAnnotatedString {
            append(text)
            if (end > start) {
                addStyle(
                    SpanStyle(color = Color.Red, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Bold),
                    start, end
                )
            } else {
                // Nothing typed yet where the missing token should go — mark the caret spot.
                addStyle(SpanStyle(background = Color(0x33FF0000)), start.coerceAtMost(text.length), text.length.coerceAtLeast(start))
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val alignment = if (message.isIncoming) Alignment.CenterStart else Alignment.CenterEnd
    val bubbleColor = when {
        !message.isIncoming -> MaterialTheme.colorScheme.primaryContainer
        message.isCommand -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.error.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = bubbleColor,
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // SelectionContainer (text copy) is disabled while in message
                // multi-select mode so a tap/long-press hits the bubble, not
                // the text selection handles underneath it.
                if (selectionMode) {
                    LinkAwareText(text = message.text, context = context)
                } else {
                    SelectionContainer { LinkAwareText(text = message.text, context = context) }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (message.isCommand) {
                        Text("CMD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkAwareText(text: String, context: android.content.Context) {
    val matcher = urlPattern.matcher(text)
    val ranges = mutableListOf<IntRange>()
    while (matcher.find()) ranges.add(matcher.start() until matcher.end())

    if (ranges.isEmpty()) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
        return
    }

    val annotated = buildAnnotatedString {
        append(text)
        ranges.forEach { r ->
            addStyle(SpanStyle(color = Color(0xFF1565C0), textDecoration = TextDecoration.Underline), r.first, r.last + 1)
        }
    }

    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        onClick = { offset ->
            val hit = ranges.firstOrNull { offset in it } ?: return@ClickableText
            val url = text.substring(hit.first, hit.last + 1)
            try {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (_: Exception) { /* no handler; link stays copyable via long-press */ }
        }
    )
}```

## `app/src/main/java/com/supernova/anchor/utils/AppSettings.kt`
```kotlin
package com.supernova.anchor.utils

import android.content.Context
import android.content.SharedPreferences

class AppSettings(private val context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        "anchor.settings", Context.MODE_PRIVATE
    )
    
    companion object {
        const val SMS_COMMAND_PREFIX = "sms_command_prefix"
        const val WHITELIST_ENABLED = "whitelist_enabled"
        const val DO_NOT_SHOW_OVERLAY_PERMISSION_AGAIN = "do_not_show_overlay_permission_again"
        const val DO_NOT_SHOW_DEVICE_ADMIN_PERMISSION_AGAIN = "do_not_show_device_admin_permission_again"
        const val SECURE_MODE_ENABLED = "secure_mode_enabled"
        const val SMS_COMMAND_PASSWORD_ENABLED = "sms_command_password_enabled"
        const val SMS_COMMAND_PASSWORD = "sms_command_password"
        const val WHITELISTED_NUMBERS = "whitelisted_numbers"
        const val USE_WHITELIST = "use_whitelist"
        const val HAS_SEEN_DISCLAIMER = "has_seen_disclaimer"
        // Port that binary/data SMS commands are addressed to.
        // NOTE: This value is for display/reference only. The actual port a
        // manifest-declared receiver listens on is fixed at compile time
        // (see DataSmsReceiver.DATA_SMS_PORT + AndroidManifest.xml <data android:port>).
        // Changing this setting alone will NOT change what port is received on.
        const val DATA_SMS_PORT = "data_sms_port"
        // SAF tree Uri (as a string) for a user-chosen debug log folder.
        // Empty = use DebugLogger's auto-detected default location.
        const val DEBUG_FOLDER_TREE_URI = "debug_folder_tree_uri"
    }
    
    fun setString(key: String, value: String) {
        sharedPreferences.edit().putString(key, value).apply()
    }
    
    fun getString(key: String): String {
        return sharedPreferences.getString(key, defaultValues(key) as String) ?: defaultValues(key) as String
    }
    
    fun setBoolean(key: String, value: Boolean) {
        sharedPreferences.edit().putBoolean(key, value).apply()
    }
    
    fun getBoolean(key: String): Boolean {
        return try {
            sharedPreferences.getBoolean(key, defaultValues(key) as Boolean)
        } catch (e: ClassCastException) {
            try {
                val stringValue = sharedPreferences.getString(key, defaultValues(key).toString())
                stringValue?.toBoolean() ?: defaultValues(key) as Boolean
            } catch (e: Exception) {
                defaultValues(key) as Boolean
            }
        }
    }
    
    fun setStringSet(key: String, values: Set<String>) {
        sharedPreferences.edit().putStringSet(key, values).apply()
    }
    
    fun getStringSet(key: String, defaultValue: Set<String>): Set<String> {
        return sharedPreferences.getStringSet(key, defaultValue) ?: defaultValue
    }
    
    private fun defaultValues(key: String): Any {
        return when (key) {
            SMS_COMMAND_PREFIX -> "PIN" // Anchor
            WHITELIST_ENABLED -> false
            DO_NOT_SHOW_OVERLAY_PERMISSION_AGAIN -> false
            DO_NOT_SHOW_DEVICE_ADMIN_PERMISSION_AGAIN -> false
            SECURE_MODE_ENABLED -> false
            SMS_COMMAND_PASSWORD_ENABLED -> false
            SMS_COMMAND_PASSWORD -> ""
            WHITELISTED_NUMBERS -> emptySet<String>()
            USE_WHITELIST -> false
            HAS_SEEN_DISCLAIMER -> false
            DATA_SMS_PORT -> "15000"
            DEBUG_FOLDER_TREE_URI -> ""
            else -> ""
        }
    }
}```

## `app/src/main/java/com/supernova/anchor/utils/DebugLogger.kt`
```kotlin
package com.supernova.anchor.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private const val TAG = "AnchorDebug"
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var logFile: File? = null
    private var initialized = false
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        initialized = true
        val baseDir = resolveLogBaseDir(context)
        val debugDir = File(baseDir, "debug")
        if (!debugDir.exists()) debugDir.mkdirs()
        logFile = File(debugDir, "${fileNameFormat.format(Date())}.txt")
        write("SYSTEM", "Logger started. Path: ${logFile?.absolutePath}")
    }

    /**
     * Picks where the debug/ folder actually lives, in priority order:
     *   1. A genuine removable SD card, if the device has one and it's mounted.
     *   2. Primary shared/external storage (what a file manager shows under
     *      Android/data/<pkg>/files) — this is what getExternalFilesDir(null)
     *      alone gives you, and on phones with no physical SD card this IS
     *      the "external" storage, just not removable media.
     *   3. Internal app-private storage (filesDir) — only if neither above is
     *      available (e.g. storage not mounted yet at boot).
     *
     * This is the fallback path used when no custom folder (see below) is
     * configured, or when writing to a configured custom folder fails.
     */
    private fun resolveLogBaseDir(context: Context): File {
        val volumes = context.getExternalFilesDirs(null)

        val removableSdCard = volumes.drop(1).firstOrNull { dir ->
            dir != null &&
                Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED &&
                Environment.isExternalStorageRemovable(dir)
        }
        if (removableSdCard != null) return removableSdCard

        val primary = volumes.getOrNull(0)
        if (primary != null && Environment.getExternalStorageState(primary) == Environment.MEDIA_MOUNTED) {
            return primary
        }

        return context.filesDir
    }

    fun log(tag: String, message: String) {
        write(tag, message)
    }

    private fun write(tag: String, message: String) {
        val ts = timeFormat.format(Date())
        val line = "[$ts] [$tag] $message"
        Log.d(TAG, line)

        val ctx = appContext
        val customUri = if (ctx != null) getCustomDebugFolderUri(ctx) else null
        if (ctx != null && customUri != null) {
            try {
                writeToCustomFolder(ctx, customUri, line + "\n")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Custom debug folder write failed, falling back to default location", e)
                // falls through to the default file-based write below
            }
        }

        try {
            logFile?.let { f -> FileWriter(f, true).use { it.appendLine(line) } }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }

    // ---------------------------------------------------------------------
    // Custom debug folder (user-picked via Settings -> "Choose debug folder")
    // ---------------------------------------------------------------------
    // Uses the platform's Storage Access Framework directly through
    // DocumentsContract (no extra androidx.documentfile dependency needed).
    // A user-granted folder can be anywhere: internal shared storage, a real
    // SD card, or (on API 29+) even another app's exposed storage — the tree
    // URI carries its own permanent grant, independent of DebugLogger's own
    // auto-detection logic above.

    /** Returns the saved custom folder's tree Uri, or null if none is set or the grant is no longer valid. */
    fun getCustomDebugFolderUri(context: Context): Uri? {
        val stored = AppSettings(context).getString(AppSettings.DEBUG_FOLDER_TREE_URI)
        if (stored.isBlank()) return null
        val uri = Uri.parse(stored)
        val stillGranted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return if (stillGranted) uri else null
    }

    /** Called after the user picks a folder via ActivityResultContracts.OpenDocumentTree(). */
    fun setCustomDebugFolder(context: Context, treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist permission for custom debug folder", e)
        }
        AppSettings(context).setString(AppSettings.DEBUG_FOLDER_TREE_URI, treeUri.toString())
        write("SYSTEM", "Custom debug folder set: $treeUri")
    }

    /** Reverts to the auto-detected default location (SD card / shared storage / internal). */
    fun clearCustomDebugFolder(context: Context) {
        val uri = getCustomDebugFolderUri(context)
        AppSettings(context).setString(AppSettings.DEBUG_FOLDER_TREE_URI, "")
        if (uri != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { /* already released or never held — fine either way */ }
        }
        write("SYSTEM", "Custom debug folder cleared, reverted to default location")
    }

    private fun writeToCustomFolder(context: Context, treeUri: Uri, line: String) {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val fileName = "${fileNameFormat.format(Date())}.txt"

        val existing = findChildDocument(context, treeUri, treeDocId, fileName)
        val targetUri = existing ?: DocumentsContract.createDocument(context.contentResolver, treeDocUri, "text/plain", fileName)
            ?: throw IllegalStateException("Could not create log file in custom folder")

        context.contentResolver.openOutputStream(targetUri, "wa")?.use { out ->
            out.write(line.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Could not open custom debug file for writing")
    }

    private fun findChildDocument(context: Context, treeUri: Uri, treeDocId: String, displayName: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (nameIdx >= 0 && cursor.getString(nameIdx) == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIdx))
                }
            }
        }
        return null
    }

    /** Most recently modified log document in the custom folder, if one is set and has entries. */
    fun getLatestCustomLogUri(context: Context): Uri? {
        val treeUri = getCustomDebugFolderUri(context) ?: return null
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        var latestUri: Uri? = null
        var latestModified = -1L
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val modified = if (modIdx >= 0) cursor.getLong(modIdx) else 0L
                if (modified > latestModified) {
                    latestModified = modified
                    latestUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIdx))
                }
            }
        }
        return latestUri
    }

    // ---------------------------------------------------------------------
    // Accessors for Settings (path display, open folder, share log)
    // ---------------------------------------------------------------------

    /** The default (auto-detected) debug directory — used only when no custom folder is set. */
    fun getDebugDir(context: Context): File {
        val dir = File(resolveLogBaseDir(context), "debug")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Most recently modified log file in the default debug dir, if any exist yet. */
    fun getLatestLogFile(context: Context): File? =
        getDebugDir(context).listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }

    /** Human-readable "where are logs going right now" string for Settings — one line, copyable. */
    fun describeCurrentLocation(context: Context): String {
        val customUri = getCustomDebugFolderUri(context)
        if (customUri != null) {
            return "Custom folder: ${Uri.decode(customUri.toString())}"
        }
        return getDebugDir(context).absolutePath
    }
}