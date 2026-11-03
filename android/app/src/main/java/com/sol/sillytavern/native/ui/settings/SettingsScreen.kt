package com.sol.sillytavern.native.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sol.sillytavern.native.data.ChatProvider
import com.sol.sillytavern.native.ui.common.ErrorState
import com.sol.sillytavern.native.ui.common.LoadingState
import com.sol.sillytavern.native.ui.common.SolTopBar
import com.sol.sillytavern.native.vm.SettingsUiState

/**
 * Provider configuration for the native client.
 *
 * Stateless like the other screens: the caller owns [SettingsUiState]. The form is
 * the only text-heavy surface outside the chat composer, so it applies the same
 * IME treatment as the composer (`WindowInsets.ime` minus the navigation bar,
 * which `MainActivity` already pads) and scrolls, otherwise the last field and the
 * save button would sit under the keyboard on a short screen.
 *
 * The API key is a password field and is never populated from the server: the
 * secrets endpoints return labels/ids only. After a successful save the field is
 * cleared and the only confirmation is “已保存”.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onProviderChange: (ChatProvider) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onCustomModelChange: (String) -> Unit,
    onOpenRouterModelChange: (String) -> Unit,
    onOpenAiModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { SolTopBar("连接设置", onBack = onBack) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        when (state) {
            SettingsUiState.Loading -> LoadingState(Modifier.fillMaxSize().padding(padding))
            is SettingsUiState.Failed -> ErrorState(
                message = state.message,
                onRetry = onRetry,
                modifier = Modifier.padding(padding),
            )

            is SettingsUiState.Ready -> SettingsForm(
                state = state,
                onProviderChange = onProviderChange,
                onBaseUrlChange = onBaseUrlChange,
                onCustomModelChange = onCustomModelChange,
                onOpenRouterModelChange = onOpenRouterModelChange,
                onOpenAiModelChange = onOpenAiModelChange,
                onApiKeyChange = onApiKeyChange,
                onSave = onSave,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
            )
        }
    }
}

@Composable
private fun SettingsForm(
    state: SettingsUiState.Ready,
    onProviderChange: (ChatProvider) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onCustomModelChange: (String) -> Unit,
    onOpenRouterModelChange: (String) -> Unit,
    onOpenAiModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = state.config
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("提供商", style = MaterialTheme.typography.titleMedium)

        Column(modifier = Modifier.selectableGroup()) {
            ProviderOption(
                provider = ChatProvider.CUSTOM,
                title = "OpenAI 兼容",
                hint = "任意兼容 /v1/chat/completions 的端点",
                selected = config.provider == ChatProvider.CUSTOM,
                enabled = !state.saving,
                onSelect = onProviderChange,
            )
            ProviderOption(
                provider = ChatProvider.OPENROUTER,
                title = "OpenRouter",
                hint = "openrouter.ai 的聚合模型",
                selected = config.provider == ChatProvider.OPENROUTER,
                enabled = !state.saving,
                onSelect = onProviderChange,
            )
            ProviderOption(
                provider = ChatProvider.OPENAI,
                title = "OpenAI 官方",
                hint = "api.openai.com",
                selected = config.provider == ChatProvider.OPENAI,
                enabled = !state.saving,
                onSelect = onProviderChange,
            )
        }

        HorizontalDivider()

        when (config.provider) {
            ChatProvider.CUSTOM -> {
                OutlinedTextField(
                    value = config.baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    enabled = !state.saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = config.customModel,
                    onValueChange = onCustomModelChange,
                    label = { Text("模型名") },
                    placeholder = { Text("gpt-4o-mini") },
                    singleLine = true,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ChatProvider.OPENROUTER -> OutlinedTextField(
                value = config.openRouterModel,
                onValueChange = onOpenRouterModelChange,
                label = { Text("模型名") },
                placeholder = { Text("anthropic/claude-3.5-sonnet") },
                singleLine = true,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )

            ChatProvider.OPENAI -> OutlinedTextField(
                value = config.openAiModel,
                onValueChange = onOpenAiModelChange,
                label = { Text("模型名") },
                placeholder = { Text("gpt-4o-mini") },
                singleLine = true,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = state.apiKey,
            onValueChange = onApiKeyChange,
            label = { Text("API Key") },
            placeholder = { Text("留空则保留已保存的密钥") },
            singleLine = true,
            enabled = !state.saving,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { Text("只写入服务器的密钥文件，不会存进 settings.json") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = onSave,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(8.dp))
                Text("保存中…")
            } else {
                Text("保存")
            }
        }

        when {
            state.saved -> Text(
                text = "已保存",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            state.error != null -> Text(
                text = state.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            // The prompt builder boots the web client once per server process and
            // reads settings.json at that point, so a new provider takes effect on
            // the next app start, not on the next message.
            text = "保存后重启应用，新的连接设置才会生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderOption(
    provider: ChatProvider,
    title: String,
    hint: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (ChatProvider) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { onSelect(provider) },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null, // the whole row is the target
            enabled = enabled,
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
