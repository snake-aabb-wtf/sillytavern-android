package com.sol.sillytavern.native.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sol.sillytavern.native.data.CharacterRepository
import com.sol.sillytavern.native.data.ChatRepository
import com.sol.sillytavern.native.data.SettingsRepository
import com.sol.sillytavern.native.engine.EngineClient
import com.sol.sillytavern.native.ui.chat.ChatScreen
import com.sol.sillytavern.native.ui.characters.CharactersScreen
import com.sol.sillytavern.native.ui.chats.ChatsScreen
import com.sol.sillytavern.native.ui.settings.SettingsScreen
import com.sol.sillytavern.native.vm.CharacterSource
import com.sol.sillytavern.native.vm.CharactersVM
import com.sol.sillytavern.native.vm.ChatSource
import com.sol.sillytavern.native.vm.ChatTurn
import com.sol.sillytavern.native.vm.ChatVM
import com.sol.sillytavern.native.vm.ChatsVM
import com.sol.sillytavern.native.vm.SettingsVM
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Route patterns and builders. Arguments are percent-encoded, never concatenated raw. */
object Routes {
    const val CHARACTERS = "characters"
    const val SETTINGS = "settings"
    const val CHATS_PATTERN = "chats/{avatar}"
    const val CHAT_PATTERN = "chat/{avatar}/{file}"

    const val ARG_AVATAR = "avatar"
    const val ARG_FILE = "file"

    fun chats(avatar: String): String = "chats/${Uri.encode(avatar)}"

    fun chat(avatar: String, file: String): String =
        "chat/${Uri.encode(avatar)}/${Uri.encode(file)}"
}

/**
 * Root of the native client.
 *
 * [port] is the loopback port of the running Node server.
 *
 * [onFirstFrame] fires once after the first frame of this composition has been drawn.
 * `MainActivity` keeps its splash overlay up until then: hiding the overlay when the
 * state turns Ready would reveal an unpainted surface for a frame.
 */
@Composable
fun SolApp(port: Int, onFirstFrame: () -> Unit = {}) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        onFirstFrame()
    }

    val client = remember(port) { SolClient(port) }
    val navController = rememberNavController()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(navController = navController, startDestination = Routes.CHARACTERS) {
                composable(Routes.CHARACTERS) {
                    // The view model is owned by this nav entry: it survives rotation
                    // and re-entering the list does not refetch characters.
                    val charactersVM: CharactersVM = viewModel(
                        factory = CharactersVM.of(client.characters),
                    )
                    val state by charactersVM.state.collectAsState()
                    CharactersScreen(
                        state = state,
                        avatarUrl = client::avatarUrl,
                        onCharacterClick = { character ->
                            navController.navigate(Routes.chats(character.avatar))
                        },
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                        onRetry = charactersVM::refresh,
                    )
                }

                composable(Routes.SETTINGS) {
                    // Owned by this entry: leaving the form and coming back reloads from
                    // disk, so a failed save is never shown as an unsaved success.
                    val settingsVM: SettingsVM = viewModel(
                        factory = client.settingsVMFactory(),
                    )
                    val state by settingsVM.state.collectAsState()
                    SettingsScreen(
                        state = state,
                        onProviderChange = settingsVM::selectProvider,
                        onBaseUrlChange = settingsVM::setBaseUrl,
                        onCustomModelChange = settingsVM::setCustomModel,
                        onOpenRouterModelChange = settingsVM::setOpenRouterModel,
                        onOpenAiModelChange = settingsVM::setOpenAiModel,
                        onApiKeyChange = settingsVM::setApiKey,
                        onSave = settingsVM::save,
                        onRetry = settingsVM::refresh,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(
                    route = Routes.CHATS_PATTERN,
                    arguments = listOf(
                        navArgument(Routes.ARG_AVATAR) { type = NavType.StringType },
                    ),
                ) { entry ->
                    val avatar = entry.arguments?.getString(Routes.ARG_AVATAR).orEmpty()
                    val chatsVM: ChatsVM = viewModel(
                        factory = ChatsVM.of(avatar, client.chats),
                    )
                    val state by chatsVM.state.collectAsState()
                    ChatsScreen(
                        title = characterName(avatar),
                        state = state,
                        onChatClick = { chat ->
                            navController.navigate(Routes.chat(avatar, chat.file))
                        },
                        onRetry = chatsVM::refresh,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(
                    route = Routes.CHAT_PATTERN,
                    arguments = listOf(
                        navArgument(Routes.ARG_AVATAR) { type = NavType.StringType },
                        navArgument(Routes.ARG_FILE) { type = NavType.StringType },
                    ),
                ) { entry ->
                    val avatar = entry.arguments?.getString(Routes.ARG_AVATAR).orEmpty()
                    val file = entry.arguments?.getString(Routes.ARG_FILE).orEmpty()
                    val chatVM: ChatVM = viewModel(
                        factory = client.chatVMFactory(avatar, file),
                    )
                    val state by chatVM.state.collectAsState()
                    ChatScreen(
                        title = file.removeSuffix(".jsonl"),
                        state = state,
                        onSend = chatVM::send,
                        onStop = chatVM::stop,
                        onRetry = chatVM::refresh,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/** `Seraphina.png` → `Seraphina`; the list route only carries the avatar file name. */
internal fun characterName(avatar: String): String =
    avatar.substringBeforeLast('.', avatar).ifBlank { avatar }

/**
 * Per-port wiring for the bundled server.
 *
 * One [EngineClient] is shared by both repositories because it caches the CSRF token
 * and the cookie session; a second client would mean a second token fetch.
 */
private class SolClient(port: Int) {
    private val baseUrl = "http://127.0.0.1:$port"
    private val engine = EngineClient(baseUrl)
    private val characterRepository = CharacterRepository(engine)
    private val chatRepository = ChatRepository(engine)
    private val settingsRepository = SettingsRepository(engine)

    val characters = CharacterSource { characterRepository.list() }
    val chats = ChatSource { avatar -> chatRepository.list(avatar) }

    fun settingsVMFactory(): ViewModelProvider.Factory = SettingsVM.of(settingsRepository)

    /**
     * One chat turn: the posted body is fixed by `native/endpoints/native-chat.mjs`
     * (only `send` is implemented server-side today). The `.jsonl` suffix is
     * stripped because both `/api/chats/get` and the prompt builder append it.
     */
    fun chatVMFactory(avatar: String, file: String): ViewModelProvider.Factory {
        val turn = ChatTurn { input ->
            engine.stream(
                "/api/native/chat",
                buildJsonObject {
                    put("avatar_url", avatar)
                    put("file_name", file.removeSuffix(".jsonl"))
                    put("user_input", input)
                    put("type", "send")
                },
            )
        }
        return ChatVM.of(avatar, file, chatRepository, turn)
    }

    /**
     * Avatar bytes come from the public thumbnail route, not `POST /api/avatars/get`
     * (that endpoint returns the list of file names). `GET /thumbnail?type=avatar&file=`
     * is mounted on the public router (`thumbnails.js:249`), needs no CSRF token, and
     * falls back to the original file when thumbnails are disabled.
     */
    fun avatarUrl(avatar: String): String =
        "$baseUrl/thumbnail?type=avatar&file=${Uri.encode(avatar)}"
}
