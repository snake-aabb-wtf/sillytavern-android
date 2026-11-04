package com.sol.sillytavern.native.vm

import com.sol.sillytavern.native.data.ChatFormatException
import com.sol.sillytavern.native.data.model.ChatSummary
import com.sol.sillytavern.native.engine.EngineError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pins the chat list ordering and the error contract.
 *
 * The second part matters for Task 8: `ChatRepository` raises [ChatFormatException]
 * for `{}` and for corrupt files. A view model that caught it and rendered an empty
 * list would tell the user "this character has no chats" about a character whose
 * chats exist but cannot be read.
 */
class ChatsVMTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun chatsAreSortedNewestFirstWithATitleTieBreak() {
        val older = chat("older.jsonl", "Old chat", 1_000L)
        val newest = chat("newest.jsonl", "Newest", 3_000L)
        val tieB = chat("b.jsonl", "Bravo", 2_000L)
        val tieA = chat("a.jsonl", "Alpha", 2_000L)

        val vm = ChatsVM(AVATAR, ChatSource { listOf(older, newest, tieB, tieA) }, noCreator)

        val ready = vm.state.value as ChatsUiState.Ready
        assertEquals(
            listOf("newest.jsonl", "a.jsonl", "b.jsonl", "older.jsonl"),
            ready.chats.map { it.file },
        )
    }

    @Test
    fun anEmptyListIsTheEmptyState() {
        val vm = ChatsVM(AVATAR, ChatSource { emptyList() }, noCreator)

        assertEquals(ChatsUiState.Empty, vm.state.value)
    }

    @Test
    fun aFormatExceptionIsAnErrorNotAnEmptyList() {
        val vm = ChatsVM(
            AVATAR,
            ChatSource {
                throw ChatFormatException("/api/chats/get returned {} instead of a chat array")
            },
            noCreator,
        )

        val failed = vm.state.value as ChatsUiState.Failed
        assertTrue("the diagnostic must reach the user: ${failed.message}", failed.message.contains("{}"))
    }

    @Test
    fun aFailureIsRetryable() {
        var failing = true
        val vm = ChatsVM(
            AVATAR,
            ChatSource {
                if (failing) throw ChatFormatException("corrupt chat file")
                listOf(chat("ok.jsonl", "Fixed", 10L))
            },
            noCreator,
        )
        assertTrue(vm.state.value is ChatsUiState.Failed)

        failing = false
        vm.refresh()

        val ready = vm.state.value as ChatsUiState.Ready
        assertEquals(listOf("ok.jsonl"), ready.chats.map { it.file })
    }

    @Test
    fun aSavedChatRefreshesThePreviewWithoutAnyoneCallingRefresh() {
        // The chat-list destination has no composition while the chat is on top,
        // so the trigger has to live in the view model: a save emits the avatar
        // and the list reloads itself.
        val saved = MutableSharedFlow<String>(extraBufferCapacity = 4)
        var preview = "old preview"
        val vm = ChatsVM(
            AVATAR,
            ChatSource { listOf(ChatSummary("a.jsonl", "Alpha", preview, 5L)) },
            noCreator,
            saved.filter { it == AVATAR }.map { },
        )
        assertEquals("old preview", (vm.state.value as ChatsUiState.Ready).chats.single().lastMessage)

        preview = "new reply"
        saved.tryEmit(AVATAR)

        assertEquals(
            "a saved turn must refresh the list without a direct refresh() call",
            "new reply",
            (vm.state.value as ChatsUiState.Ready).chats.single().lastMessage,
        )
    }

    @Test
    fun aRefreshWhileReadyKeepsTheRowsAndTheScrollPosition() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val vm = ChatsVM(
            AVATAR,
            ChatSource {
                calls++
                if (calls == 2) gate.await()
                listOf(ChatSummary("a.jsonl", "Alpha", "v$calls", calls.toLong()))
            },
            noCreator,
        )
        assertEquals("v1", (vm.state.value as ChatsUiState.Ready).chats.single().lastMessage)

        vm.refresh()

        // The reload is still in flight: the visible rows must survive it.
        val during = vm.state.value
        assertTrue("refresh blanked the list: $during", during is ChatsUiState.Ready)
        assertEquals("v1", (during as ChatsUiState.Ready).chats.single().lastMessage)

        gate.complete(Unit)

        assertEquals("v2", (vm.state.value as ChatsUiState.Ready).chats.single().lastMessage)
    }

    @Test
    fun aFailedRefreshKeepsTheVisibleRows() {
        var calls = 0
        val vm = ChatsVM(
            AVATAR,
            ChatSource {
                calls++
                if (calls > 1) throw ChatFormatException("transient read failure")
                listOf(ChatSummary("a.jsonl", "Alpha", "v1", 1L))
            },
            noCreator,
        )

        vm.refresh()

        assertTrue(
            "a failed background refresh must not replace the list with an error",
            vm.state.value is ChatsUiState.Ready,
        )
    }

    @Test
    fun aCreationFailureCarriesTheServerMessageNotJustTheStatus() {
        val vm = ChatsVM(
            AVATAR,
            ChatSource { emptyList() },
            NewChatCreator {
                // A read-only data directory and a full disk both answer 500;
                // only the body tells the user which one happened.
                throw EngineError.Http(500, "ENOSPC: no space left on device\n    at fs.write")
            },
        )

        vm.newChat()

        assertEquals("HTTP 500: ENOSPC: no space left on device", vm.createError.value)
        assertEquals(null, vm.created.value)
    }

    private fun chat(file: String, title: String, updatedAt: Long) =
        ChatSummary(file = file, title = title, lastMessage = "", updatedAt = updatedAt)

    /** These tests pin the list contract; the creator is never invoked. */
    private val noCreator = NewChatCreator { error("new chat is not exercised here") }

    private companion object {
        const val AVATAR = "Seraphina.png"
    }
}
