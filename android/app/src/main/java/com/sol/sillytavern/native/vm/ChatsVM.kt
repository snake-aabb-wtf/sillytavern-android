package com.sol.sillytavern.native.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sol.sillytavern.native.data.ChatRepository
import com.sol.sillytavern.native.data.model.ChatSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Everything [ChatsScreen] can render; see [CharactersUiState] for the rationale. */
sealed interface ChatsUiState {
    data object Loading : ChatsUiState
    data object Empty : ChatsUiState
    data class Ready(val chats: List<ChatSummary>) : ChatsUiState
    data class Failed(val message: String) : ChatsUiState
}

/** The data-layer seam for [ChatsVM]; see [CharacterSource]. */
fun interface ChatSource {
    suspend fun list(avatar: String): List<ChatSummary>
}

/**
 * Loads one character's chat list.
 *
 * `POST /api/characters/chats` answers in directory order, so sorting is this class's
 * job: newest `updatedAt` first, with the title as a stable tie-breaker so two chats
 * written in the same millisecond do not reorder between reloads.
 */
class ChatsVM(
    private val avatar: String,
    private val source: ChatSource,
) : ViewModel() {

    private val _state = MutableStateFlow<ChatsUiState>(ChatsUiState.Loading)
    val state: StateFlow<ChatsUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch { load() }
    }

    internal suspend fun load() {
        _state.value = ChatsUiState.Loading
        _state.value = try {
            val chats = source.list(avatar).sortedWith(NEWEST_FIRST)
            if (chats.isEmpty()) ChatsUiState.Empty else ChatsUiState.Ready(chats)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Includes ChatFormatException: a chat file that cannot be represented is
            // an error the user must see, never an empty list that looks like "no chats".
            ChatsUiState.Failed(failureText(error))
        }
    }

    companion object {
        private val NEWEST_FIRST =
            compareByDescending<ChatSummary> { it.updatedAt }.thenBy { it.title }

        fun of(avatar: String, repository: ChatRepository): ViewModelProvider.Factory =
            of(avatar, ChatSource { repository.list(it) })

        fun of(avatar: String, source: ChatSource): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatsVM(avatar, source) }
        }
    }
}
