package com.sol.sillytavern.native.vm

import com.sol.sillytavern.native.data.model.Chat
import com.sol.sillytavern.native.data.model.ChatMessage
import com.sol.sillytavern.native.engine.ChatEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pins the one behaviour that makes the chat work across turns: the reply must
 * reach disk.
 *
 * The bundled prompt builder reloads the chat from `/api/chats/get` on every
 * turn. A reply that only exists in the view model is therefore invisible to the
 * next request, and the conversation "forgets" everything after the first
 * message. `Done` is also the only point where the assistant text is final, so it
 * must produce exactly one save.
 *
 * `stop()` is the opposite contract: the socket is cancelled immediately, the
 * text that already arrived stays on screen marked as stopped, and no partial
 * reply is written to the chat file.
 */
class ChatVMSendTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val header = buildJsonObject {
        put("user_name", "User")
        put("character_name", "Seraphina")
        put("create_date", "2024-05-01T10:00:00.000Z")
        put(
            "chat_metadata",
            buildJsonObject { put("integrity", "4d3c2b1a-0000-4000-8000-000000000000") },
        )
    }

    private val greeting = ChatMessage(
        buildJsonObject {
            put("name", "Seraphina")
            put("is_user", false)
            put("mes", "Hello there.")
        },
    )

    @Test
    fun deltasAccumulateAndDoneSavesTheTurnOnce() = runTest {
        val store = FakeStore(Chat(header, listOf(greeting)))
        val inputs = mutableListOf<String>()
        val vm = ChatVM(
            AVATAR,
            FILE,
            store,
            ChatTurn { input ->
                inputs += input
                flow {
                    emit(ChatEvent.Delta("Hel"))
                    emit(ChatEvent.Delta("lo"))
                    emit(ChatEvent.Done)
                }
            },
        )

        vm.send("Hi")

        val ready = vm.state.value as ChatUiState.Ready
        assertEquals(listOf("Hello there.", "Hi", "Hello"), ready.messages.map { it.text })
        assertFalse(ready.streaming)
        assertEquals(listOf("Hi"), inputs)

        // Exactly one save, and it carries the whole turn: the next build reads
        // this file, so both the user line and the reply must be in it.
        assertEquals(1, store.saves.size)
        val saved = store.saves.single()
        assertEquals(header, saved.metadata)
        assertEquals(listOf("Hello there.", "Hi", "Hello"), saved.texts())
        assertEquals(listOf(false, true, false), saved.isUserFlags())
    }

    @Test
    fun stopCancelsImmediatelyAndSavesNothing() = runTest {
        val store = FakeStore(Chat(header, listOf(greeting)))
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        val vm = ChatVM(
            AVATAR,
            FILE,
            store,
            ChatTurn {
                flow {
                    try {
                        emit(ChatEvent.Delta("Hel"))
                        gate.await()
                    } finally {
                        cancelled = true
                    }
                }
            },
        )

        vm.send("Hi")

        val streaming = vm.state.value as ChatUiState.Ready
        assertTrue(streaming.streaming)
        assertEquals("Hel", streaming.messages.last().text)

        vm.stop()

        val stopped = vm.state.value as ChatUiState.Ready
        assertFalse(stopped.streaming)
        assertTrue("the stream must be cancelled, not left running", cancelled)
        assertEquals("Hel", stopped.messages.last().text)
        assertTrue("the surviving fragment must be marked stopped", stopped.messages.last().stopped)
        assertTrue("a partial reply must never be persisted", store.saves.isEmpty())

        // A later send must still be able to save the stopped fragment as history
        // rather than losing the text the user saw.
        assertTrue(stopped.messages.last().raw == null || stopped.messages.last().raw.toString().contains("Hel"))
    }

    @Test
    fun aFailureBecomesAReadableMessageInsteadOfASilentStop() = runTest {
        val store = FakeStore(Chat(header, listOf(greeting)))
        val vm = ChatVM(
            AVATAR,
            FILE,
            store,
            ChatTurn {
                flow {
                    emit(ChatEvent.Delta("Par"))
                    emit(ChatEvent.Failure("HTTP 500: prompt build failed"))
                }
            },
        )

        vm.send("Hi")

        val ready = vm.state.value as ChatUiState.Ready
        assertFalse(ready.streaming)
        val last = ready.messages.last()
        assertTrue("the failure must be visible: ${last.text}", last.error)
        assertTrue("the status code must survive: ${last.text}", last.text.contains("HTTP 500"))
        assertEquals("Par", ready.messages[ready.messages.lastIndex - 1].text)
        assertTrue("a failed turn is not a reply and must not be saved", store.saves.isEmpty())
    }

    /** An in-memory chat file; `save` records what the real repository would write. */
    private class FakeStore(initial: Chat) : ChatStore {
        var current = initial
        val saves = mutableListOf<Chat>()
        var loadCalls = 0
        var existsCalls = 0
        var existsValue = true

        override suspend fun load(): Chat {
            loadCalls++
            return current
        }

        override suspend fun save(chat: Chat) {
            saves += chat
            current = chat
        }

        override suspend fun exists(): Boolean {
            existsCalls++
            return existsValue
        }
    }

    private fun Chat.texts(): List<String> =
        messages.map { (it.raw["mes"] as? JsonPrimitive)?.contentOrNull.orEmpty() }

    private fun Chat.isUserFlags(): List<Boolean> =
        messages.map { (it.raw["is_user"] as? JsonPrimitive)?.content?.toBoolean() ?: false }

    private companion object {
        const val AVATAR = "Seraphina.png"
        const val FILE = "Seraphina - 2024-05-01.jsonl"
    }
}
