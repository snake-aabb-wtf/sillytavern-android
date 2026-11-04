package com.sol.sillytavern.native.vm

import com.sol.sillytavern.native.data.ChatFormatException
import com.sol.sillytavern.native.data.model.ChatSummary
import com.sol.sillytavern.native.engine.EngineError
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
    fun refreshPicksUpThePreviewWrittenByTheChatScreen() {
        var preview = "old preview"
        val vm = ChatsVM(
            AVATAR,
            ChatSource {
                listOf(ChatSummary("a.jsonl", "Alpha", preview, 5L))
            },
            noCreator,
        )
        assertEquals("old preview", (vm.state.value as ChatsUiState.Ready).chats.single().lastMessage)

        preview = "new reply"
        vm.refresh()

        assertEquals(
            "returning from a chat must not leave the stale preview",
            "new reply",
            (vm.state.value as ChatsUiState.Ready).chats.single().lastMessage,
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
