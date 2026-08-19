package com.supernova.anchor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.supernova.anchor.ui.ChatThreadScreen
import com.supernova.anchor.ui.ThreadsListScreen
import com.supernova.anchor.ui.theme.anchorTheme

/**
 * Hosts Binary Mode: a conversation-list screen and a per-number chat screen,
 * switched via simple in-Activity state (consistent with the rest of the app,
 * which uses one Activity per screen rather than Compose Navigation).
 */
class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            anchorTheme {
                var openThreadKey by remember { mutableStateOf<String?>(null) }

                val currentThread = openThreadKey
                if (currentThread == null) {
                    ThreadsListScreen(
                        onBackClick = { finish() },
                        onThreadClick = { threadKey -> openThreadKey = threadKey }
                    )
                } else {
                    ChatThreadScreen(
                        threadKey = currentThread,
                        onBackClick = { openThreadKey = null }
                    )
                }
            }
        }
    }
}