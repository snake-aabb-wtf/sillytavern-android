package com.sol.sillytavern.native.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sol.sillytavern.native.ui.common.EmptyState
import com.sol.sillytavern.native.ui.common.ErrorState
import com.sol.sillytavern.native.ui.common.LoadingState
import com.sol.sillytavern.native.ui.common.SolTopBar
import com.sol.sillytavern.native.vm.ChatLine
import com.sol.sillytavern.native.vm.ChatUiState

/**
 * One chat's transcript plus the composer.
 *
 * [title] is the route's file title; once the file is loaded the header's
 * `character_name` replaces it. The list is keyed by index on purpose: turns only
 * ever append (and the streaming reply replaces the last element in place), so an
 * index is stable while being cheaper than building an identity for every line on
 * each delta.
 */
@Composable
fun ChatScreen(
    title: String,
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val ready = state as? ChatUiState.Ready
    val shownTitle = ready?.title?.takeIf { it.isNotBlank() } ?: title

    // Leaving mid-turn used to cancel the view model scope with the user's line
    // still only in memory. Every exit path stops first: stop() persists the user
    // line in a NonCancellable job, so the pop and onCleared() cannot lose it.
    val turnInFlight = ready != null && (ready.streaming || ready.finalizing)
    val leave = {
        if (turnInFlight) onStop()
        onBack()
    }
    BackHandler(enabled = turnInFlight) { leave() }

    Scaffold(
        topBar = { SolTopBar(shownTitle, onBack = leave) },
        bottomBar = {
            if (ready != null) {
                Composer(
                    streaming = ready.streaming,
                    finalizing = ready.finalizing,
                    onSend = onSend,
                    onStop = onStop,
                    // The root pads the system bars (nav bar included), so only the
                    // IME inset *above* the nav bar is still missing here; padding
                    // the full IME inset as well would lift the composer one
                    // nav-bar height off the keyboard.
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.ime.exclude(WindowInsets.navigationBars),
                    ),
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding)
        when (state) {
            ChatUiState.Loading -> LoadingState(contentModifier)
            is ChatUiState.Failed -> ErrorState(
                message = state.message,
                onRetry = onRetry,
                modifier = contentModifier,
            )
            is ChatUiState.Ready ->
                if (state.messages.isEmpty()) {
                    EmptyState("还没有消息，说点什么吧", contentModifier)
                } else {
                    Transcript(state = state, modifier = contentModifier)
                }
        }
    }
}

@Composable
private fun Transcript(state: ChatUiState.Ready, modifier: Modifier) {
    val listState = rememberLazyListState()
    val lastTextLength = state.messages.lastOrNull()?.text?.length ?: 0

    // New turn: jump to the bubble that just appeared. While streaming, follow the
    // last line as it grows; both effects are cheap and only run on a real change.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }
    LaunchedEffect(lastTextLength) {
        if (state.streaming && state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(state.messages, key = { index, _ -> index }) { index, line ->
            MessageBubble(
                line = line,
                streaming = state.streaming && index == state.messages.lastIndex,
            )
        }
    }
}

@Composable
private fun MessageBubble(line: ChatLine, streaming: Boolean) {
    if (line.error) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(vertical = 4.dp),
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (line.isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (line.isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!line.isUser && line.name.isNotBlank()) {
                    Text(
                        text = line.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val text = when {
                    line.text.isNotBlank() -> line.text
                    streaming -> "…"
                    else -> ""
                }
                if (text.isNotEmpty()) {
                    Text(
                        text = renderMarkdown(text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (line.isUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (line.stopped) {
                    Text(
                        text = "已停止",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    streaming: Boolean,
    finalizing: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }

    fun submit() {
        val message = text.trim()
        if (message.isEmpty() || streaming || finalizing) return
        onSend(message)
        text = ""
    }

    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息…") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Spacer(modifier = Modifier.width(8.dp))
            when {
                // `Done` has arrived: the reply is final, so there is nothing to
                // stop. Showing 停止 here let a tap discard a completed turn.
                finalizing -> Button(onClick = {}, enabled = false) { Text("发送") }
                streaming -> FilledTonalButton(onClick = onStop) { Text("停止") }
                else -> Button(onClick = { submit() }, enabled = text.isNotBlank()) { Text("发送") }
            }
        }
    }
}
