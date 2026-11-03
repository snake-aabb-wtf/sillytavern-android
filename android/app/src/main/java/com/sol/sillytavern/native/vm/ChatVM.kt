package com.sol.sillytavern.native.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sol.sillytavern.native.data.model.Chat
import com.sol.sillytavern.native.engine.ChatEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// RED-phase skeleton: the shape the test pins, with no behaviour yet.

data class ChatLine(
    val text: String,
    val isUser: Boolean,
    val name: String = "",
    val stopped: Boolean = false,
    val error: Boolean = false,
    internal val raw: kotlinx.serialization.json.JsonObject? = null,
)

sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Failed(val message: String) : ChatUiState
    data class Ready(
        val title: String,
        val messages: List<ChatLine>,
        val streaming: Boolean = false,
    ) : ChatUiState
}

interface ChatStore {
    suspend fun load(): Chat
    suspend fun save(chat: Chat)
    suspend fun exists(): Boolean
}

fun interface ChatTurn {
    fun stream(userInput: String): Flow<ChatEvent>
}

class ChatVM(
    private val avatar: String,
    private val file: String,
    private val store: ChatStore,
    private val turn: ChatTurn,
) : ViewModel() {

    private val _state = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun send(text: String) {
        // TODO(Task 8): stream the reply.
    }

    fun stop() {
        // TODO(Task 8): cancel the turn.
    }

    internal suspend fun load() {
        _state.value = ChatUiState.Ready(file.removeSuffix(".jsonl"), emptyList())
    }
}
