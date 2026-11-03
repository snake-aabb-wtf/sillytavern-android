package com.sol.sillytavern.native.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sol.sillytavern.native.ui.common.SolTopBar

/**
 * Placeholder for `chat/{avatar}/{file}`.
 *
 * The route exists now so [com.sol.sillytavern.native.ui.chats.ChatsScreen] can
 * navigate and the back stack is already correct; Task 8 replaces this file with the
 * message list and composer. It shows the arguments it received so a misrouted chat
 * is visible instead of looking like an empty conversation.
 */
@Composable
fun ChatPlaceholderScreen(avatar: String, file: String, onBack: () -> Unit) {
    Scaffold(
        topBar = { SolTopBar(title = file.removeSuffix(".jsonl"), onBack = onBack) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "聊天界面将在后续任务中接入",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "$avatar\n$file",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
