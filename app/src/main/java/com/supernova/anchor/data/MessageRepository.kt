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
}