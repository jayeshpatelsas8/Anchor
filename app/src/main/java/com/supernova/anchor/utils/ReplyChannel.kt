package com.supernova.anchor.utils

/**
 * Which channel a command came in on. A reply always goes back out on the
 * same channel it arrived on — SmsReceiver tags TEXT, DataSmsReceiver tags
 * DATA, and that tag is threaded through SmsCommandProcessor and both
 * foreground services all the way to the actual send call. There is no
 * "try data, fall back to text if it doesn't fit" choice being made
 * anywhere; the channel is decided once, at the point a command is
 * received, not re-decided per reply.
 */
enum class ReplyChannel {
    TEXT,
    DATA
}