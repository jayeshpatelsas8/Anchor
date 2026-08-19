package com.supernova.anchor.data

/**
 * A single entry in the WhatsApp-style message log.
 * Covers both incoming binary/data SMS payloads and outgoing SMS responses
 * sent by SmsCommandProcessor, so the UI shows a full conversation thread.
 */
data class ChatMessage(
    val id: String,
    val text: String,
    val sender: String,      // phone number for incoming, or the recipient number for outgoing
    val timestamp: Long,
    val isIncoming: Boolean,
    val isCommand: Boolean = false // true if this message matched the command prefix
)