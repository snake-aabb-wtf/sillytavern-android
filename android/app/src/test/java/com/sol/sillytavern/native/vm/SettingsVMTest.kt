package com.sol.sillytavern.native.vm

import com.sol.sillytavern.native.data.ChatProvider
import com.sol.sillytavern.native.data.ProviderConfig
import com.sol.sillytavern.native.data.SettingsHttpException
import com.sol.sillytavern.native.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pins the form's two safety rules: a key typed for one provider must never be
 * written under another provider's secret key, and a blank model or relative
 * custom URL must be stopped before “已保存”, because both produce a request the
 * provider rejects (`model: null`, or the client refusing to build at all).
 */
class SettingsVMTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun selectingAnotherProviderClearsTheTypedKey() {
        val store = FakeStore()
        val vm = SettingsVM(store)
        vm.setApiKey("sk-openai")

        vm.selectProvider(ChatProvider.OPENROUTER)

        val ready = vm.state.value as SettingsUiState.Ready
        assertEquals(ChatProvider.OPENROUTER, ready.config.provider)
        assertEquals("a key typed for the previous provider must not follow it", "", ready.apiKey)
    }

    @Test
    fun selectingTheSameProviderKeepsTheTypedKey() {
        val store = FakeStore()
        val vm = SettingsVM(store)
        vm.setApiKey("sk-custom")

        vm.selectProvider(ChatProvider.CUSTOM)

        val ready = vm.state.value as SettingsUiState.Ready
        assertEquals("sk-custom", ready.apiKey)
    }

    @Test
    fun blankModelsAreRejectedForEveryProvider() {
        val cases = listOf(
            ProviderConfig(provider = ChatProvider.CUSTOM, baseUrl = "https://api.example.com/v1", customModel = " "),
            ProviderConfig(provider = ChatProvider.OPENROUTER, openRouterModel = ""),
            ProviderConfig(provider = ChatProvider.OPENAI, openAiModel = ""),
        )

        for (config in cases) {
            val store = FakeStore(config)
            val vm = SettingsVM(store)

            vm.save()

            val ready = vm.state.value as SettingsUiState.Ready
            assertFalse("${config.provider} must not report success", ready.saved)
            assertTrue(
                "${config.provider} must explain the missing model: ${ready.error}",
                ready.error!!.contains("模型名"),
            )
            assertNull("nothing may be written for ${config.provider}", store.saved)
        }
    }

    @Test
    fun aCustomBaseUrlWithoutASchemeIsRejected() {
        val store = FakeStore(
            ProviderConfig(provider = ChatProvider.CUSTOM, baseUrl = "api.example.com/v1", customModel = "m"),
        )
        val vm = SettingsVM(store)

        vm.save()

        val ready = vm.state.value as SettingsUiState.Ready
        assertFalse(ready.saved)
        assertTrue(ready.error!!.contains("http://"))
        assertNull(store.saved)
    }

    @Test
    fun aSuccessfulSaveClearsTheKeyAndReportsSaved() {
        val store = FakeStore()
        val vm = SettingsVM(store)
        vm.setApiKey("sk-custom")

        vm.save()

        val ready = vm.state.value as SettingsUiState.Ready
        assertTrue(ready.saved)
        assertNull(ready.error)
        assertEquals("the typed key must not linger after it was stored", "", ready.apiKey)
        assertEquals("sk-custom", store.saved?.second)
        assertEquals(ChatProvider.CUSTOM, store.saved?.first?.provider)
    }

    @Test
    fun aFailedSaveKeepsTheKeyAndShowsTheDiagnostic() {
        val store = FakeStore().apply { saveError = SettingsHttpException(500, "settings write failed") }
        val vm = SettingsVM(store)
        vm.setApiKey("sk-custom")

        vm.save()

        val ready = vm.state.value as SettingsUiState.Ready
        assertFalse(ready.saved)
        assertEquals("HTTP 500: settings write failed", ready.error)
        assertEquals("the user should not have to paste the key again", "sk-custom", ready.apiKey)
    }

    private class FakeStore(
        private val loaded: ProviderConfig = ProviderConfig(
            provider = ChatProvider.CUSTOM,
            baseUrl = "https://api.example.com/v1",
            customModel = "my-model",
        ),
        var loadError: Throwable? = null,
        var saveError: Throwable? = null,
    ) : SettingsStore {
        var saved: Pair<ProviderConfig, String?>? = null

        override suspend fun load(): ProviderConfig = loadError?.let { throw it } ?: loaded

        override suspend fun save(config: ProviderConfig, apiKey: String?) {
            saveError?.let { throw it }
            saved = config to apiKey
        }
    }
}
