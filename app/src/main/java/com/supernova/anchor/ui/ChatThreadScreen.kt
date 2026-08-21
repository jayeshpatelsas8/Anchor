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
}