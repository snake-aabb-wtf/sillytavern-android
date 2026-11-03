package com.sol.sillytavern.native.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sol.sillytavern.native.data.ChatProvider
import com.sol.sillytavern.native.data.ProviderConfig
import com.sol.sillytavern.native.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything [com.sol.sillytavern.native.ui.settings.SettingsScreen] renders. */
sealed interface SettingsUiState {
    data object Loading : SettingsUiState

    data class Failed(val message: String) : SettingsUiState

    /**
     * The loaded provider block plus the unsaved edits.
     *
     * [apiKey] is only the text the user is typing in this session. It is cleared
     * after a successful save and is never populated from the server: the secrets
     * endpoints return no value, so a stored key can be neither shown nor read back.
     */
    data class Ready(
        val config: ProviderConfig,
        val apiKey: String = "",
        val saving: Boolean = false,
        /** One-shot success flag; the screen renders “已保存”. */
        val saved: Boolean = false,
        /** Readable failure text (`HTTP 500: ...`), cleared on the next edit or save. */
        val error: String? = null,
    ) : SettingsUiState
}

/**
 * Loads the provider block and writes it back on demand.
 *
 * Preset switching only changes the selected source; the other presets' fields
 * stay in [ProviderConfig] and in `settings.json`, so switching back does not lose
 * the URL or model that was typed before.
 */
class SettingsVM(private val repository: SettingsRepository) : ViewModel() {

    private val _state = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var saveJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load() }
    }

    internal suspend fun load() {
        _state.value = SettingsUiState.Loading
        _state.value = try {
            SettingsUiState.Ready(repository.load())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SettingsUiState.Failed(failureText(error))
        }
    }

    fun selectProvider(provider: ChatProvider) = edit { copy(config = config.copy(provider = provider)) }

    fun setBaseUrl(value: String) = edit { copy(config = config.copy(baseUrl = value)) }

    fun setCustomModel(value: String) = edit { copy(config = config.copy(customModel = value)) }

    fun setOpenRouterModel(value: String) = edit { copy(config = config.copy(openRouterModel = value)) }

    fun setOpenAiModel(value: String) = edit { copy(config = config.copy(openAiModel = value)) }

    fun setApiKey(value: String) = edit { copy(apiKey = value) }

    /**
     * Saves the current edits.
     *
     * A second tap while a save is in flight is ignored. Success clears the key
     * field and sets [SettingsUiState.Ready.saved]; failure keeps the typed key so
     * the user does not have to paste it again, and shows the repository's
     * diagnostic instead of a silent no-op.
     */
    fun save() {
        val current = _state.value as? SettingsUiState.Ready ?: return
        if (current.saving) return

        // The web client refuses a custom URL that is not absolute before it ever
        // posts, which would leave the native turn waiting on a request that is
        // never built. Reject it here with a readable reason instead.
        if (current.config.provider == ChatProvider.CUSTOM) {
            val url = current.config.baseUrl.trim()
            if (!url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true)
            ) {
                _state.value = current.copy(
                    saved = false,
                    error = "Base URL 必须以 http:// 或 https:// 开头",
                )
                return
            }
        }

        _state.value = current.copy(saving = true, saved = false, error = null)
        saveJob = viewModelScope.launch {
            try {
                repository.save(current.config, current.apiKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val now = _state.value as? SettingsUiState.Ready ?: return@launch
                _state.value = now.copy(saving = false, saved = false, error = failureText(error))
                return@launch
            }
            val now = _state.value as? SettingsUiState.Ready ?: return@launch
            _state.value = now.copy(saving = false, saved = true, error = null, apiKey = "")
        }
    }

    /**
     * Applies an edit unless the form is loading, failed, or mid-save: letting a
     * change land during a save would make the UI disagree with what was posted.
     */
    private fun edit(block: SettingsUiState.Ready.() -> SettingsUiState.Ready) {
        _state.update { current ->
            if (current is SettingsUiState.Ready && !current.saving) {
                current.block().copy(saved = false, error = null)
            } else {
                current
            }
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        loadJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun of(repository: SettingsRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsVM(repository) }
        }
    }
}
