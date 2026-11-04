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

    @Test
    fun aProviderWithoutAStoredKeyAndABlankFieldIsRejected() {
        for (provider in listOf(ChatProvider.OPENAI, ChatProvider.OPENROUTER)) {
            val config = ProviderConfig(
                provider = provider,
                openRouterModel = "openai/gpt-4o-mini",
                openAiModel = "gpt-4o-mini",
            )
            val store = FakeStore(config, hasStoredKey = false)
            val vm = SettingsVM(store)

            vm.save()

            val ready = vm.state.value as SettingsUiState.Ready
            assertFalse("$provider must not report 已保存 without a key", ready.saved)
            assertTrue(
                "$provider must ask for the key: ${ready.error}",
                ready.error!!.contains("API Key"),
            )
            assertNull("nothing may be written for $provider", store.saved)
            assertEquals("the stored-key state must be consulted once", 1, store.keyChecks)
        }
    }

    @Test
    fun anExistingStoredKeyIsEnoughAfterSwitchingProvider() {
        for (provider in listOf(ChatProvider.OPENAI, ChatProvider.OPENROUTER)) {
            val config = ProviderConfig(
                provider = provider,
                openRouterModel = "openai/gpt-4o-mini",
                openAiModel = "gpt-4o-mini",
            )
            val store = FakeStore(config, hasStoredKey = true)
            val vm = SettingsVM(store)

            vm.save()

            val ready = vm.state.value as SettingsUiState.Ready
            assertTrue("$provider has a key on the server and must save", ready.saved)
            assertNull(ready.error)
            assertEquals("", ready.apiKey)
        }
    }

    @Test
    fun aCustomEndpointMaySaveWithoutAKey() {
        val store = FakeStore(
            ProviderConfig(
                provider = ChatProvider.CUSTOM,
                baseUrl = "http://127.0.0.1:5000/v1",
                customModel = "local",
            ),
            hasStoredKey = false,
        )
        val vm = SettingsVM(store)

        vm.save()

        val ready = vm.state.value as SettingsUiState.Ready
        assertTrue("CUSTOM is allowed to have no key", ready.saved)
        assertNull(ready.error)
        assertEquals("CUSTOM must not be blocked by a key lookup", 0, store.keyChecks)
    }

    @Test
    fun aTypedKeyIsSavedWithoutALookup() {
        val store = FakeStore(
            ProviderConfig(provider = ChatProvider.OPENAI, openAiModel = "gpt-4o-mini"),
            hasStoredKey = false,
        )
        val vm = SettingsVM(store)
        vm.setApiKey("sk-openai")

        vm.save()

        val ready = vm.state.value as SettingsUiState.Ready
        assertTrue(ready.saved)
        assertEquals("a typed key needs no server read", 0, store.keyChecks)
        assertEquals("sk-openai", store.saved?.second)
    }

    private class FakeStore(
        private val loaded: ProviderConfig = ProviderConfig(
            provider = ChatProvider.CUSTOM,
            baseUrl = "https://api.example.com/v1",
            customModel = "my-model",
        ),
        var loadError: Throwable? = null,
        var saveError: Throwable? = null,
        var hasStoredKey: Boolean = true,
    ) : SettingsStore {
        var saved: Pair<ProviderConfig, String?>? = null
        var keyChecks = 0

        override suspend fun load(): ProviderConfig = loadError?.let { throw it } ?: loaded

        override suspend fun save(config: ProviderConfig, apiKey: String?) {
            saveError?.let { throw it }
            saved = config to apiKey
        }

        override suspend fun hasApiKey(provider: ChatProvider): Boolean {
            keyChecks++
            return hasStoredKey
        }
    }
}
