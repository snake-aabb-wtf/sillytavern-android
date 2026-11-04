package com.sol.sillytavern.native.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sol.sillytavern.native.data.CharacterRepository
import com.sol.sillytavern.native.data.model.Character
import com.sol.sillytavern.native.engine.EngineError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything [CharactersScreen] can render. There is deliberately no "content plus
 * error" state: a failed load must replace the grid, otherwise a stale list would
 * look like a successful refresh that returned the same characters.
 */
sealed interface CharactersUiState {
    data object Loading : CharactersUiState
    data object Empty : CharactersUiState
    data class Ready(val characters: List<Character>) : CharactersUiState
    data class Failed(val message: String) : CharactersUiState
}

/**
 * The one thing [CharactersVM] needs from the data layer. Keeping it an interface
 * (rather than depending on [CharacterRepository] directly) lets the state machine be
 * unit-tested on the JVM without an HTTP socket.
 */
fun interface CharacterSource {
    suspend fun list(): List<Character>
}

/**
 * Loads the character list once and exposes loading/empty/error/ready states.
 *
 * The view model is owned by the `characters` nav destination, so it survives
 * configuration changes and screen rotation without re-fetching. [refresh] is the
 * retry entry point for the error state and re-runs the same single-flight load.
 */
class CharactersVM(private val source: CharacterSource) : ViewModel() {

    private val _state = MutableStateFlow<CharactersUiState>(CharactersUiState.Loading)
    val state: StateFlow<CharactersUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch { load() }
    }

    /**
     * Runs exactly one load and publishes its outcome.
     *
     * Any failure becomes [CharactersUiState.Failed] with the exception's own message:
     * a repository error must be visible and retryable, never silently rendered as an
     * empty character list. Cancellation is rethrown so a superseded refresh cannot
     * overwrite the state of the load that replaced it.
     */
    internal suspend fun load() {
        _state.value = CharactersUiState.Loading
        _state.value = try {
            val characters = source.list()
            if (characters.isEmpty()) {
                CharactersUiState.Empty
            } else {
                CharactersUiState.Ready(characters)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            CharactersUiState.Failed(failureText(error))
        }
    }

    companion object {
        /** Adapts the repository so callers never see the test seam. */
        fun of(repository: CharacterRepository): ViewModelProvider.Factory =
            of(CharacterSource { repository.list() })

        fun of(source: CharacterSource): ViewModelProvider.Factory = viewModelFactory {
            initializer { CharactersVM(source) }
        }
    }
}

/**
 * The text shown under the error panel's title. The repositories raise English,
 * diagnostic messages (`HTTP 500`, `... returned {} instead of a chat array`); showing
 * them verbatim is more useful than a generic "load failed" with no cause.
 */
internal fun failureText(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "未知错误"

/**
 * A server failure with the first line of the response body, matching what a failed
 * chat turn shows: `HTTP 500: <server message>` is actionable where `HTTP 500` is not
 * (a read-only data directory and a full disk otherwise look identical).
 */
internal fun serverFailureText(error: Throwable): String = when (error) {
    is EngineError.Http -> {
        val first = error.body.lineSequence().firstOrNull().orEmpty().trim().take(MAX_SERVER_ERROR_CHARS)
        if (first.isEmpty()) "HTTP ${error.code}" else "HTTP ${error.code}: $first"
    }
    else -> failureText(error)
}

private const val MAX_SERVER_ERROR_CHARS = 200
