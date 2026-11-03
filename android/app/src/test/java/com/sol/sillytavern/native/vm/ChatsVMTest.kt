package com.sol.sillytavern.native.vm

import com.sol.sillytavern.native.data.ChatFormatException
import com.sol.sillytavern.native.data.model.ChatSummary
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

        val vm = ChatsVM(AVATAR, ChatSource { listOf(older, newest, tieB, tieA) })

        val ready = vm.state.value as ChatsUiState.Ready
        assertEquals(
            listOf("newest.jsonl", "a.jsonl", "b.jsonl", "older.jsonl"),
            ready.chats.map { it.file },
        )
    }

    @Test
    fun anEmptyListIsTheEmptyState() {
        val vm = ChatsVM(AVATAR, ChatSource { emptyList() })

        assertEquals(ChatsUiState.Empty, vm.state.value)
    }

    @Test
    fun aFormatExceptionIsAnErrorNotAnEmptyList() {
        val vm = ChatsVM(
            AVATAR,
            ChatSource {
                throw ChatFormatException("/api/chats/get returned {} instead of a chat array")
            },
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
        )
        assertTrue(vm.state.value is ChatsUiState.Failed)

        failing = false
        vm.refresh()

        val ready = vm.state.value as ChatsUiState.Ready
        assertEquals(listOf("ok.jsonl"), ready.chats.map { it.file })
    }

    private fun chat(file: String, title: String, updatedAt: Long) =
        ChatSummary(file = file, title = title, lastMessage = "", updatedAt = updatedAt)

    private companion object {
        const val AVATAR = "Seraphina.png"
    }
}
