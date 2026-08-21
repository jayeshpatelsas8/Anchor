package com.supernova.anchor.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** One row in the Binary Mode thread list — a number + its most recent message. */
data class ThreadSummary(
    val threadKey: String,
    val lastMessage: ChatMessage
)

/**
 * Singleton store for the message thread shown in ChatScreen.
 * Backed by SharedPreferences (JSON array) so history survives process death,
 * matching how AppSettings persists other Anchor config.
 */
object MessageRepository {
    private const val PREFS_NAME = "anchor.messages"
    private const val KEY_MESSAGES = "messages_json"
    private const val MAX_STORED = 200

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_MESSAGES, null) ?: return
        val list = mutableListOf<ChatMessage>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    ChatMessage(
                        id = o.getString("id"),
                        text = o.getString("text"),
                        sender = o.getString("sender"),
                        timestamp = o.getLong("timestamp"),
                        isIncoming = o.getBoolean("isIncoming"),
                        isCommand = o.optBoolean("isCommand", false)
                    )
                )
            }
        } catch (_: Exception) {
            // Corrupt/legacy data — start fresh rather than crash.
        }
        _messages.value = list
    }

    @Synchronized
    fun addMessage(context: Context, message: ChatMessage) {
        init(context)
        _messages.update { current -> (current + message).takeLast(MAX_STORED) }
        persist(context)
    }

    fun clear(context: Context) {
        init(context)
        _messages.value = emptyList()
        persist(context)
    }

    /** Deletes specific messages by id — used by multi-select delete inside a thread. */
    fun deleteMessages(context: Context, ids: Set<String>) {
        init(context)
        _messages.update { current -> current.filterNot { ids.contains(it.id) } }
        persist(context)
    }

    /** Deletes every message belonging to the given threads — used by multi-select delete in the thread list. */
    fun deleteThreads(context: Context, threadKeys: Set<String>) {
        init(context)
        _messages.update { current -> current.filterNot { threadKeys.contains(normalizeNumber(it.sender)) } }
        persist(context)
    }

    /**
     * Normalizes a phone number for thread grouping so "+1 (732) 648-6789"
     * and "17326486789" land in the same thread. Keeps digits and a leading '+'.
     */
    fun normalizeNumber(raw: String): String = raw.filter { it.isDigit() || it == '+' }

    /** One row per distinct counterpart number, newest thread first. */
    fun threadsFlow(context: Context): Flow<List<ThreadSummary>> {
        init(context)
        return messages.map { list ->
            list.groupBy { normalizeNumber(it.sender) }
                .mapNotNull { (key, msgs) -> msgs.maxByOrNull { it.timestamp }?.let { ThreadSummary(key, it) } }
                .sortedByDescending { it.lastMessage.timestamp }
        }
    }

    /** Every message with a given counterpart, oldest first (chat order). */
    fun messagesForThread(context: Context, threadKey: String): Flow<List<ChatMessage>> {
        init(context)
        return messages.map { list ->
            list.filter { normalizeNumber(it.sender) == threadKey }.sortedBy { it.timestamp }
        }
    }

    private fun persist(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        _messages.value.forEach { m ->
            val o = JSONObject()
            o.put("id", m.id)
            o.put("text", m.text)
            o.put("sender", m.sender)
            o.put("timestamp", m.timestamp)
            o.put("isIncoming", m.isIncoming)
            o.put("isCommand", m.isCommand)
            arr.put(o)
        }
        prefs.edit().putString(KEY_MESSAGES, arr.toString()).apply()
    }
}```

## `app/src/main/java/com/supernova/anchor/ui/ThreadsListScreen.kt`
```kotlin
package com.supernova.anchor.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.supernova.anchor.data.MessageRepository
import com.supernova.anchor.data.ThreadSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadsListScreen(
    onBackClick: () -> Unit,
    onThreadClick: (String) -> Unit
) {
    val context = LocalContext.current
    var threads by remember { mutableStateOf<List<ThreadSummary>>(emptyList()) }
    var showNewThreadDialog by remember { mutableStateOf(false) }
    var selectedThreads by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val selectionMode = selectedThreads.isNotEmpty()

    LaunchedEffect(Unit) {
        MessageRepository.threadsFlow(context).collect { threads = it }
    }

    // If a thread being viewed gets deleted from under it (or the list simply
    // shrinks), drop any now-nonexistent keys from the selection.
    LaunchedEffect(threads) {
        val validKeys = threads.map { it.threadKey }.toSet()
        if (selectedThreads.any { it !in validKeys }) {
            selectedThreads = selectedThreads.intersect(validKeys)
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedThreads.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selectedThreads = emptySet() }) {
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
                    title = { Text("Binary Mode") },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(onClick = { showNewThreadDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New conversation")
                }
            }
        }
    ) { innerPadding ->
        if (threads.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No conversations yet.\nTap + to send a command to a number.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                items(threads, key = { it.threadKey }) { thread ->
                    ThreadRow(
                        thread = thread,
                        selected = selectedThreads.contains(thread.threadKey),
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) {
                                selectedThreads = if (selectedThreads.contains(thread.threadKey)) {
                                    selectedThreads - thread.threadKey
                                } else {
                                    selectedThreads + thread.threadKey
                                }
                            } else {
                                onThreadClick(thread.threadKey)
                            }
                        },
                        onLongClick = {
                            selectedThreads = selectedThreads + thread.threadKey
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showNewThreadDialog) {
        NewThreadDialog(
            onDismiss = { showNewThreadDialog = false },
            onConfirm = { number ->
                showNewThreadDialog = false
                onThreadClick(MessageRepository.normalizeNumber(number))
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${selectedThreads.size} conversation${if (selectedThreads.size > 1) "s" else ""}?") },
            text = { Text("All messages in the selected conversation(s) will be deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    MessageRepository.deleteThreads(context, selectedThreads)
                    selectedThreads = emptySet()
                    showDeleteConfirm = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(
    thread: ThreadSummary,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(4.dp))
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = thread.threadKey.filter { it.isDigit() }.takeLast(2).ifEmpty { "?" },
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(thread.threadKey, fontWeight = FontWeight.Medium)
            Text(
                text = (if (!thread.lastMessage.isIncoming) "You: " else "") + thread.lastMessage.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(thread.lastMessage.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Phone number entry for a new conversation — includes a contact-picker
 * button using the exact same READ_CONTACTS runtime-permission flow as
 * WhitelistActivity's "Add from Contacts" (checkSelfPermission -> request if
 * needed -> ACTION_PICK on Phone.CONTENT_TYPE -> query NUMBER column), so
 * behavior is consistent everywhere in the app that picks a contact.
 */
@Composable
private fun NewThreadDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val context = LocalContext.current
    var number by remember { mutableStateOf("") }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { contactUri ->
                val picked = queryContactPhoneNumber(context, contactUri)
                if (picked.isNotEmpty()) number = picked
            }
        }
    }

    val requestContactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            contactPickerLauncher.launch(
                Intent(Intent.ACTION_PICK).apply { type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE }
            )
        }
    }

    fun checkPermissionAndPick() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            contactPickerLauncher.launch(
                Intent(Intent.ACTION_PICK).apply { type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE }
            )
        } else {
            requestContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New conversation") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("Phone number") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { checkPermissionAndPick() }) {
                    Icon(Icons.Filled.Person, contentDescription = "Pick from contacts")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (number.isNotBlank()) onConfirm(number) },
                enabled = number.isNotBlank()
            ) { Text("Start") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun queryContactPhoneNumber(context: android.content.Context, contactUri: Uri): String {
    var phoneNumber = ""
    context.contentResolver.query(
        contactUri,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (numberIndex >= 0) phoneNumber = cursor.getString(numberIndex) ?: ""
        }
    }
    return phoneNumber
}