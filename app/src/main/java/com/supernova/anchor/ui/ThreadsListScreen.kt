package com.supernova.anchor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import com.supernova.anchor.data.MessageRepository
import com.supernova.anchor.data.ThreadSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadsListScreen(
    onBackClick: () -> Unit,
    onThreadClick: (String) -> Unit
) {
    val context = LocalContext.current
    var threads by remember { mutableStateOf<List<ThreadSummary>>(emptyList()) }
    var showNewThreadDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        MessageRepository.threadsFlow(context).collect { threads = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Binary Mode") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewThreadDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New conversation")
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
                    ThreadRow(thread, onClick = { onThreadClick(thread.threadKey) })
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
}

@Composable
private fun ThreadRow(thread: ThreadSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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

@Composable
private fun NewThreadDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var number by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New conversation") },
        text = {
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text("Phone number") },
                singleLine = true
            )
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