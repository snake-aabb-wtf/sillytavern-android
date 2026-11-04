package com.sol.sillytavern.native.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sol.sillytavern.native.data.model.ChatSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
 * The data-layer seam for [ChatsVM.newChat]: greeting lookup, unique name and save,
 * returning the file that was written. A failed creation must throw; the view model
 * turns that into a visible error.
 */
fun interface NewChatCreator {
    suspend fun create(characterName: String): String
}

/**
 * Loads one character's chat list and starts new chats.
 *
 * `POST /api/characters/chats` answers in directory order, so sorting is this class's
 * job: newest `updatedAt` first, with the title as a stable tie-breaker so two chats
 * written in the same millisecond do not reorder between reloads.
 */
class ChatsVM(
    private val avatar: String,
    private val source: ChatSource,
    private val creator: NewChatCreator,
    /**
     * Fires when a turn for this character was written to disk. The list is
     * usually still on the back stack while the chat screen is on top, and the
     * navigation entry's composition is gone there; a view-model-owned collector
     * is therefore the only place that can reliably refresh the preview. `App`
     * wires the shared signal to both factories; empty for tests that never save.
     */
    signals: Flow<Unit> = emptyFlow(),
) : ViewModel() {

    private val _state = MutableStateFlow<ChatsUiState>(ChatsUiState.Loading)
    val state: StateFlow<ChatsUiState> = _state.asStateFlow()

    /** True while a chat is being written; the button is disabled so one tap makes one file. */
    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    /** The file just created; `App` navigates on it and calls [consumeCreated]. */
    private val _created = MutableStateFlow<String?>(null)
    val created: StateFlow<String?> = _created.asStateFlow()

    /** A creation failure the screen must show; cleared by [dismissCreateError]. */
    private val _createError = MutableStateFlow<String?>(null)
    val createError: StateFlow<String?> = _createError.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            signals.collect { refresh() }
        }
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch { load() }
    }

    /**
     * Writes a chat that opens with the character's greeting. Without one a fresh
     * install has characters but no way to say the first word (Ruling R10).
     */
    fun newChat() {
        // The UI disables the button, but a second tap can still race the first
        // recomposition; one in-flight creation per view model is enough.
        if (_creating.value) return
        _creating.value = true
        _createError.value = null
        viewModelScope.launch {
            try {
                _created.value = creator.create(characterName())
                // The list predates the file; reloading now means backing out of the
                // new chat shows it without the user asking for a refresh.
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Never silent: the user must know the chat was not created.
                _createError.value = serverFailureText(error)
            } finally {
                _creating.value = false
            }
        }
    }

    /** Called after navigation so the same file cannot be opened twice. */
    fun consumeCreated() {
        _created.value = null
    }

    fun dismissCreateError() {
        _createError.value = null
    }

    internal suspend fun load() {
        // A refresh triggered while the user is looking at the list must not
        // replace the rows with a spinner (or blank them on a transient failure):
        // Loading is only for the first load, when there is nothing to show yet.
        val previous = _state.value
        if (previous !is ChatsUiState.Ready) _state.value = ChatsUiState.Loading
        _state.value = try {
            val chats = source.list(avatar).sortedWith(NEWEST_FIRST)
            if (chats.isEmpty()) ChatsUiState.Empty else ChatsUiState.Ready(chats)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Includes ChatFormatException: a chat file that cannot be represented is
            // an error the user must see, never an empty list that looks like "no chats".
            // With rows already on screen, a failed background refresh keeps them:
            // the next refresh can recover and the user loses no scroll position.
            if (previous is ChatsUiState.Ready) previous else ChatsUiState.Failed(failureText(error))
        }
    }

    /** `Seraphina.png` → `Seraphina`; the chat file name is built from the card's name. */
    private fun characterName(): String = avatar.substringBeforeLast('.', avatar)

    companion object {
        private val NEWEST_FIRST =
            compareByDescending<ChatSummary> { it.updatedAt }.thenBy { it.title }

        fun of(
            avatar: String,
            source: ChatSource,
            creator: NewChatCreator,
            signals: Flow<Unit> = emptyFlow(),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatsVM(avatar, source, creator, signals) }
        }
    }
}
