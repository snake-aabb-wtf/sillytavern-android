package com.sol.sillytavern.native.ui.chats

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sol.sillytavern.native.data.model.ChatSummary
import com.sol.sillytavern.native.ui.common.ClickDebouncer
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
 *
 * "新建会话" exists in both the top bar and the empty state: on a fresh install the
 * empty state is the first screen a user sees, and without this button the character
 * would be a dead end (Ruling R10).
 */
@Composable
fun ChatsScreen(
    title: String,
    state: ChatsUiState,
    creating: Boolean,
    createError: String?,
    onChatClick: (ChatSummary) -> Unit,
    onNewChat: () -> Unit,
    onDismissCreateError: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // A fast double tap on a row would push the same chat twice; two ChatVMs on
    // one file then overwrite each other's snapshot on save.
    val rowDebouncer = remember { ClickDebouncer() }
    // A creation failure is reported beside the list, not instead of it: the existing
    // chats are still readable and the button stays available for a retry.
    LaunchedEffect(createError) {
        val message = createError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onDismissCreateError()
    }

    Scaffold(
        topBar = {
            SolTopBar(title, onBack = onBack) {
                if (creating) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 12.dp).size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
                TextButton(onClick = onNewChat, enabled = !creating) {
                    Text("新建会话")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding)
        when (state) {
            ChatsUiState.Loading -> LoadingState(contentModifier)
            ChatsUiState.Empty -> NewChatEmpty(creating, onNewChat, contentModifier)
            is ChatsUiState.Failed -> ErrorState(
                message = state.message,
                onRetry = onRetry,
                modifier = contentModifier,
            )
            is ChatsUiState.Ready -> LazyColumn(modifier = contentModifier) {
                items(state.chats, key = { it.file }) { chat ->
                    ChatRow(
                        chat = chat,
                        onClick = {
                            if (rowDebouncer.shouldAccept(SystemClock.uptimeMillis())) {
                                onChatClick(chat)
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun NewChatEmpty(creating: Boolean, onNewChat: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "还没有会话", style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onNewChat, enabled = !creating) {
            if (creating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (creating) "正在新建…" else "新建会话")
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
