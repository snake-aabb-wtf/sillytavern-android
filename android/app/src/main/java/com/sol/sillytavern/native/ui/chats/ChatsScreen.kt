package com.sol.sillytavern.native.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.sol.sillytavern.native.data.model.ChatSummary
import com.sol.sillytavern.native.ui.common.EmptyState
import com.sol.sillytavern.native.ui.common.ErrorState
import com.sol.sillytavern.native.ui.common.LoadingState
import com.sol.sillytavern.native.ui.common.SolTopBar
import com.sol.sillytavern.native.vm.ChatsUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One character's chats, newest first (the ordering is applied by [ChatsVM] because
 * the server returns directory order).
 */
@Composable
fun ChatsScreen(
    title: String,
    state: ChatsUiState,
    onChatClick: (ChatSummary) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { SolTopBar(title, onBack = onBack) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding)
        when (state) {
            ChatsUiState.Loading -> LoadingState(contentModifier)
            ChatsUiState.Empty -> EmptyState("还没有会话", contentModifier)
            is ChatsUiState.Failed -> ErrorState(
                message = state.message,
                onRetry = onRetry,
                modifier = contentModifier,
            )
            is ChatsUiState.Ready -> LazyColumn(modifier = contentModifier) {
                items(state.chats, key = { it.file }) { chat ->
                    ChatRow(chat = chat, onClick = { onChatClick(chat) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: ChatSummary, onClick: () -> Unit) {
    val preview = chat.lastMessage.takeIf { it.isNotBlank() }
    // Declared as a composable slot: a lambda inside `let` would not infer that type.
    val previewContent: (@Composable () -> Unit)? = if (preview != null) {
        {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        null
    }
    ListItem(
        headlineContent = {
            Text(text = chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = previewContent,
        trailingContent = {
            val time = formatUpdatedAt(chat.updatedAt)
            if (time.isNotEmpty()) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private val CHAT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** `updatedAt` is epoch millis; 0 means the server sent no parseable timestamp. */
internal fun formatUpdatedAt(epochMillis: Long): String =
    if (epochMillis <= 0L) {
        ""
    } else {
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(CHAT_TIME_FORMAT)
    }
