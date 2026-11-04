package com.sol.sillytavern.native.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sol.sillytavern.native.data.ChatFormatException
import com.sol.sillytavern.native.data.ChatRepository
import com.sol.sillytavern.native.data.model.Chat
import com.sol.sillytavern.native.data.model.ChatMessage
import com.sol.sillytavern.native.data.model.string
import com.sol.sillytavern.native.engine.ChatEvent
import com.sol.sillytavern.native.engine.EngineError
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One rendered line of the transcript. */
data class ChatLine(
    val text: String,
    val isUser: Boolean,
    val name: String = "",
    /** The turn was cancelled by [ChatVM.stop] and holds only the text that arrived. */
    val stopped: Boolean = false,
    /** A transport or save failure shown in the transcript; never written to the file. */
    val error: Boolean = false,
    /** The exact object loaded from the chat file; new lines are materialised on save. */
    internal val raw: JsonObject? = null,
)

/** Everything `ChatScreen` renders. */
sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Failed(val message: String) : ChatUiState
    data class Ready(
        val title: String,
        val messages: List<ChatLine>,
        val streaming: Boolean = false,
        /**
         * The turn is complete and its write is in flight. Distinct from
         * [streaming] so the composer can stop offering 停止 once there is
         * nothing left to cancel, while still refusing a new turn that would
         * cancel the write.
         */
        val finalizing: Boolean = false,
    ) : ChatUiState
}

/** The chat-file seam for [ChatVM]; mirrors the same rationale as `ChatSource`. */
interface ChatStore {
    suspend fun load(): Chat
    suspend fun save(chat: Chat)

    /** Whether the server lists this chat; a listed file must never be replaced by an empty one. */
    suspend fun exists(): Boolean
}

/** The network seam for one turn; the real one posts to `/api/native/chat`. */
fun interface ChatTurn {
    fun stream(userInput: String): Flow<ChatEvent>
}

/**
 * Loads a chat and streams one turn at a time into its last message.
 *
 * The bundled prompt builder reloads the chat from disk for every turn and then
 * appends the posted `user_input` itself, so the client must write the finished
 * turn — user line and reply together — exactly once, when the server closes the
 * stream with [ChatEvent.Done]. Writing the user line before the stream would put
 * it on disk and the builder would append it a second time, duplicating the turn
 * in the prompt.
 *
 * The header line is carried through every save unchanged: it holds `user_name`,
 * `character_name`, `create_date` and `chat_metadata.integrity`, and dropping the
 * integrity slug makes the server reject the next save.
 *
 * `stop()` cancels the socket and keeps the fragment on screen marked as stopped;
 * the partial reply is deliberately not persisted.
 */
class ChatVM(
    private val avatar: String,
    private val file: String,
    private val store: ChatStore,
    private val turn: ChatTurn,
    /**
     * Fires after a transcript was written. The chat list may be alive on the
     * back stack with its composition disposed, so it cannot refresh itself on
     * return; `App` wires this to the same signal its `ChatsVM` collects.
     */
    private val onSaved: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** The loaded header; null until [load] succeeds (or a new chat is constructed). */
    private var header: JsonObject? = null
    private var characterName: String = ""
    private var userName: String = USER_NAME

    private var loadJob: Job? = null
    private var turnJob: Job? = null

    /**
     * Serialises writes. The stop/failure save and a completed turn's save can be
     * requested from different jobs; the mutex plus [persistLatest]'s snapshot of
     * the current state means the last writer always writes the newest transcript,
     * never an older snapshot over a newer one.
     */
    private val saveLock = Mutex()

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load() }
    }

    /** Appends the user line and streams the reply into a new assistant line. */
    fun send(text: String) {
        val input = text.trim()
        if (input.isEmpty()) return
        val current = _state.value as? ChatUiState.Ready ?: return
        if (current.streaming || current.finalizing) return

        val withUser = current.messages +
            ChatLine(
                text = input,
                isUser = true,
                name = userName,
                raw = messageRaw(input, isUser = true, name = userName),
            ) +
            ChatLine(text = "", isUser = false, name = characterName.ifBlank { current.title })
        _state.value = current.copy(messages = withUser, streaming = true)

        turnJob = viewModelScope.launch { runTurn(input) }
    }

    /**
     * Cancels the in-flight turn immediately, keeping whatever text already arrived.
     *
     * A turn that already reached [ChatEvent.Done] is not cancellable: it has no
     * `raw`-less line left to mark, and treating a finished reply as a stopped
     * fragment would write the file without the turn the user can see.
     */
    fun stop() {
        val current = _state.value as? ChatUiState.Ready ?: return
        if (!current.streaming || current.finalizing) return
        turnJob?.cancel()
        turnJob = null

        val messages = current.messages.toMutableList()
        val last = messages.lastOrNull()
        if (last != null && !last.isUser) {
            if (last.text.isBlank()) {
                messages.removeAt(messages.lastIndex)
            } else {
                messages[messages.lastIndex] = last.copy(stopped = true)
            }
        }
        _state.value = current.copy(messages = messages, streaming = false)

        // The user's line already exists and must survive the screen; the stopped
        // fragment is filtered out by [persisted]. This runs in its own job because
        // stop() must return immediately, and the job is [NonCancellable] because
        // the screen pops right after stop(): ViewModel.onCleared() cancels
        // viewModelScope, and a plain child job would be cancelled before the
        // write reached disk.
        viewModelScope.launch(NonCancellable) { reportSaveFailure(persistLatest()) }
    }

    internal suspend fun load() {
        _state.value = ChatUiState.Loading
        _state.value = try {
            val chat = loadChat()
            header = chat.metadata
            characterName = chat.metadata.string("character_name")
                .ifBlank { avatar.substringBeforeLast('.', avatar) }
            userName = chat.metadata.string("user_name").ifBlank { USER_NAME }
            ChatUiState.Ready(
                title = characterName.ifBlank { fileTitle() },
                messages = chat.messages.map { message ->
                    ChatLine(
                        text = message.raw.string("mes"),
                        isUser = message.isUser(),
                        name = message.raw.string("name"),
                        raw = message.raw,
                    )
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ChatUiState.Failed(failureText(error))
        }
    }

    /**
     * Reads the chat, tolerating the one `{}` a freshly created chats directory
     * answers with: `/api/chats/get` creates the directory, then answers before the
     * file exists. A second failure only becomes an empty chat when the server does
     * not list the file; a listed-but-unreadable chat stays an error, because a save
     * over it would delete the history that could not be read.
     */
    private suspend fun loadChat(): Chat {
        return try {
            store.load()
        } catch (first: ChatFormatException) {
            // The directory was created in the same request; give the next read a
            // moment to see the real file before concluding it is new.
            delay(RETRY_DELAY_MS)
            try {
                store.load()
            } catch (second: ChatFormatException) {
                if (store.exists()) throw first
                Chat(newHeader(), emptyList())
            }
        }
    }

    private suspend fun runTurn(input: String) {
        val reply = StringBuilder()
        var terminal = false
        try {
            turn.stream(input).collect { event ->
                when (event) {
                    is ChatEvent.Delta -> {
                        reply.append(event.text)
                        replaceLastAssistant(ChatLine(text = reply.toString(), isUser = false, name = assistantName()))
                    }
                    ChatEvent.Done -> {
                        terminal = true
                        finishReply(reply.toString())
                    }
                    is ChatEvent.Failure -> {
                        terminal = true
                        failTurn(event.message, reply.toString())
                    }
                }
            }
            // EngineClient already reports a truncated stream as Failure; this is a
            // last line of defence so a silent end can never look like an answer.
            if (!terminal) failTurn("stream ended without [DONE]", reply.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failTurn(turnFailureText(error), reply.toString())
        }
    }

    /**
     * Persists the completed turn once; the header is carried through untouched.
     *
     * `streaming` stays true across the write so the composer cannot start a new
     * turn (and cancel this job) while the file is being rewritten.
     */
    private suspend fun finishReply(text: String) {
        val current = _state.value as? ChatUiState.Ready ?: return
        val messages = current.messages.toMutableList()
        val index = messages.indexOfLast { !it.isUser }
        if (index < 0) return
        messages[index] = messages[index].copy(
            text = text,
            stopped = false,
            raw = messageRaw(text, isUser = false, name = messages[index].name),
        )
        _state.value = current.copy(messages = messages, finalizing = true)

        reportSaveFailure(persistLatest())
        finishStreaming()
    }

    private suspend fun failTurn(message: String, partial: String) {
        val current = _state.value as? ChatUiState.Ready ?: return
        val messages = current.messages.toMutableList()
        val last = messages.lastOrNull()
        if (last != null && !last.isUser) {
            if (partial.isBlank()) {
                messages.removeAt(messages.lastIndex)
            } else {
                messages[messages.lastIndex] = last.copy(text = partial, stopped = true)
            }
        }
        // An error is a UI notice, not a chat message: [persisted] filters it out.
        messages += ChatLine(text = message, isUser = false, error = true)
        _state.value = current.copy(messages = messages, finalizing = true)

        // The user's line is real even though the reply is not; persist it alone.
        reportSaveFailure(persistLatest())
        finishStreaming()
    }

    private fun replaceLastAssistant(line: ChatLine) {
        val current = _state.value as? ChatUiState.Ready ?: return
        val index = current.messages.indexOfLast { !it.isUser && !it.error }
        if (index < 0) return
        val messages = current.messages.toMutableList()
        messages[index] = line
        _state.value = current.copy(messages = messages)
    }

    private fun appendLine(line: ChatLine) {
        val current = _state.value as? ChatUiState.Ready ?: return
        _state.value = current.copy(messages = current.messages + line)
    }

    private fun assistantName(): String =
        characterName.ifBlank { (_state.value as? ChatUiState.Ready)?.title.orEmpty() }

    /**
     * Writes the newest transcript under one lock. Returns the failure text when
     * the write failed, null on success. The save is [NonCancellable]: leaving the
     * screen must not leave a half-written chat file behind.
     */
    private suspend fun persistLatest(): String? = withContext(NonCancellable) {
        // The whole block is NonCancellable, not just the `store.save` call:
        // `saveLock.withLock` can be cancelled while it is *waiting*, and a save
        // queued behind another writer (a stop followed by a completed turn) must
        // still land after the screen is gone.
        saveLock.withLock {
            val ready = _state.value as? ChatUiState.Ready ?: return@withLock null
            try {
                store.save(Chat(currentHeader(), ready.messages.persisted()))
                // Only after a successful write: a failed save leaves the preview
                // as it was and must not trigger a list reload.
                onSaved()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failureText(error)
            }
        }
    }

    private fun reportSaveFailure(error: String?) {
        if (error != null) {
            appendLine(ChatLine(text = "保存失败: $error", isUser = false, error = true))
        }
    }

    private fun finishStreaming() {
        val ready = _state.value as? ChatUiState.Ready ?: return
        _state.value = ready.copy(streaming = false, finalizing = false)
    }

    /**
     * The header from disk, or a freshly constructed one for a chat that does not
     * exist yet. An empty object is never written back: it would permanently
     * disable the server's integrity check for the file.
     */
    private fun currentHeader(): JsonObject {
        val existing = header
        if (!existing.isNullOrEmpty()) return existing
        return newHeader().also { header = it }
    }

    private fun fileTitle(): String = file.substringAfterLast('/').removeSuffix(CHAT_EXTENSION)

    /**
     * The lines a save may write: errors are notices, blank pending bubbles are not
     * messages, and every other line is materialised from its loaded raw object or
     * built from the text the user actually saw.
     */
    private fun List<ChatLine>.persisted(): List<ChatMessage> = mapNotNull { line ->
        when {
            // A notice, never a message.
            line.error -> null
            // A cancelled turn's fragment is display-only; it is not history.
            line.stopped -> null
            // Only a never-loaded line may be dropped for being blank. A loaded
            // line with `mes: ""` is real (attachments live in extra.media) and
            // deleting it would silently destroy the file.
            line.raw == null && line.text.isBlank() -> null
            line.raw != null -> ChatMessage(line.raw)
            else -> ChatMessage(messageRaw(line.text, line.isUser, line.name))
        }
    }

    private fun ChatMessage.isUser(): Boolean =
        (raw["is_user"] as? JsonPrimitive)?.content?.toBoolean() ?: false

    private fun newHeader(): JsonObject = buildJsonObject {
        put("user_name", USER_NAME)
        put("character_name", avatar.substringBeforeLast('.', avatar))
        put("create_date", Instant.now().toString())
        put(
            "chat_metadata",
            buildJsonObject { put("integrity", UUID.randomUUID().toString()) },
        )
    }

    companion object {
        private const val RETRY_DELAY_MS = 250L
        private const val USER_NAME = "User"
        private const val CHAT_EXTENSION = ".jsonl"

        private fun messageRaw(text: String, isUser: Boolean, name: String): JsonObject = buildJsonObject {
            put("name", name)
            put("is_user", isUser)
            put("is_system", false)
            put("send_date", Instant.now().toString())
            put("mes", text)
            put("extra", JsonObject(emptyMap()))
            if (!isUser) {
                // SillyTavern's swipe UI reads these on the first render; writing a
                // reply without them makes the message unswipeable in the web client.
                put("swipes", JsonArray(listOf(JsonPrimitive(text))))
                put("swipe_id", 0)
                put("swipe_info", JsonArray(emptyList()))
            }
        }

        private fun turnFailureText(error: Throwable): String = serverFailureText(error)

        fun of(
            avatar: String,
            file: String,
            repository: ChatRepository,
            turn: ChatTurn,
            onSaved: () -> Unit = {},
        ): ViewModelProvider.Factory =
            of(avatar, file, RepositoryStore(avatar, file, repository), turn, onSaved)

        fun of(
            avatar: String,
            file: String,
            store: ChatStore,
            turn: ChatTurn,
            onSaved: () -> Unit = {},
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatVM(avatar, file, store, turn, onSaved) }
        }
    }
}

/** Adapts [ChatRepository] to the view model's seam. */
private class RepositoryStore(
    private val avatar: String,
    private val file: String,
    private val repository: ChatRepository,
) : ChatStore {
    override suspend fun load(): Chat = repository.loadChat(avatar, file)

    override suspend fun save(chat: Chat) =
        repository.save(avatar, file, chat.messages, chat.metadata)

    override suspend fun exists(): Boolean {
        val name = file.removeSuffix(".jsonl")
        return repository.list(avatar).any { it.file.removeSuffix(".jsonl") == name }
    }
}
