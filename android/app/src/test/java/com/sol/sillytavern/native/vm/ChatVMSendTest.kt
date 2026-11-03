package com.sol.sillytavern.native.vm

import com.sol.sillytavern.native.data.model.Chat
import com.sol.sillytavern.native.data.model.ChatMessage
import com.sol.sillytavern.native.engine.ChatEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
 * Pins the cross-turn write contract of the chat screen.
 *
 * The bundled prompt builder reloads the chat from `/api/chats/get` on every
 * turn, so a reply that exists only in the view model is invisible to the next
 * request. `Done` is the only point where the assistant text is final and must
 * produce exactly one save.
 *
 * The write is also destructive by nature: `save` replaces the file with the
 * list it is given. Every loaded line therefore has to survive, including one
 * whose `mes` is empty because this server stores attachments in `extra.media`.
 *
 * `stop()` and a failed turn are different: the user's own line is real and must
 * reach disk, the assistant fragment is not and must never do so.
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

    /** An image-only line: SillyTavern keeps attachments outside `mes`. */
    private val attachmentOnly = ChatMessage(
        buildJsonObject {
            put("name", "Seraphina")
            put("is_user", false)
            put("is_system", false)
            put("mes", "")
            put("extra", buildJsonObject { put("media", JsonArray(listOf(JsonPrimitive("Seraphina.png")))) })
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
    fun aLoadedMessageWithEmptyTextSurvivesTheSave() = runTest {
        val store = FakeStore(Chat(header, listOf(greeting, attachmentOnly)))
        val vm = doneVm(store, "Hello")

        vm.send("Hi")

        val saved = store.saves.single()
        // greeting + image-only line + user + reply. The blank `mes` must not be
        // mistaken for the streaming placeholder and deleted from the file.
        assertEquals(4, saved.messages.size)
        assertEquals(attachmentOnly.raw, saved.messages[1].raw)
        assertEquals(listOf(false, false, true, false), saved.isUserFlags())
    }

    @Test
    fun aSecondSendDuringTheSaveCannotInterleave() = runTest {
        val store = FakeStore(Chat(header, listOf(greeting)))
        val gate = CompletableDeferred<Unit>()
        store.onSave = { gate.await() }
        val vm = doneVm(store, "Hello")

        vm.send("Hi")

        // The write is still in flight: the composer must stay locked out and a
        // second send must not append a line or start a competing job.
        val during = vm.state.value as ChatUiState.Ready
        assertTrue("streaming must cover the save, not just the network", during.streaming)
        assertEquals(1, store.saves.size)

        vm.send("Second")
        assertEquals(1, store.saves.size)
        assertEquals(
            listOf("Hello there.", "Hi", "Hello"),
            (vm.state.value as ChatUiState.Ready).messages.map { it.text },
        )

        gate.complete(Unit)

        val after = vm.state.value as ChatUiState.Ready
        assertFalse(after.streaming)
        assertEquals(1, store.saves.size)
    }

    @Test
    fun stopCancelsAndSavesOnlyTheUserLine() = runTest {
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

        // The user's own message is real and must not vanish with the screen; the
        // partial reply must never be written.
        val saved = store.saves.single()
        assertEquals(listOf("Hello there.", "Hi"), saved.texts())
        assertEquals(header, saved.metadata)
    }

    @Test
    fun aStoppedFragmentIsNeverPersistedLater() = runTest {
        val store = FakeStore(Chat(header, listOf(greeting)))
        val gate = CompletableDeferred<Unit>()
        val vm = ChatVM(
            AVATAR,
            FILE,
            store,
            ChatTurn { input ->
                flow {
                    if (input == "Hi") {
                        emit(ChatEvent.Delta("Hel"))
                        gate.await()
                    } else {
                        emit(ChatEvent.Delta("Answer"))
                        emit(ChatEvent.Done)
                    }
                }
            },
        )

        vm.send("Hi")
        vm.stop()
        vm.send("Next")

        // The fragment stays on screen for this session, but the next save is
        // history + both user lines + the real reply, with no truncated turn.
        assertEquals(
            listOf("Hello there.", "Hi", "Hel", "Next", "Answer"),
            (vm.state.value as ChatUiState.Ready).messages.map { it.text },
        )
        assertEquals(2, store.saves.size)
        assertEquals(
            listOf("Hello there.", "Hi", "Next", "Answer"),
            store.saves.last().texts(),
        )
    }

    @Test
    fun aFailureSavesTheUserLineAndShowsTheError() = runTest {
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

        // The failed turn is not a reply: only the user line reaches disk, and
        // the error notice is never a chat message.
        val saved = store.saves.single()
        assertEquals(listOf("Hello there.", "Hi"), saved.texts())
    }

    @Test
    fun aNewChatSavesARealHeaderInsteadOfAnEmptyObject() = runTest {
        // `/api/chats/get` answers [] for a chat that does not exist yet; the
        // repository surfaces that as an empty metadata object. Writing `{}` back
        // would permanently disable the server's integrity guard for the file.
        val store = FakeStore(Chat(JsonObject(emptyMap()), emptyList()))
        val vm = doneVm(store, "Hello")

        vm.send("Hi")

        val metadata = store.saves.single().metadata
        assertEquals("User", (metadata["user_name"] as? JsonPrimitive)?.content)
        assertEquals("Seraphina", (metadata["character_name"] as? JsonPrimitive)?.content)
        assertTrue((metadata["create_date"] as? JsonPrimitive)?.content.orEmpty().isNotBlank())
        val integrity = ((metadata["chat_metadata"] as? JsonObject)?.get("integrity") as? JsonPrimitive)
            ?.content
            .orEmpty()
        assertTrue("a new chat needs an integrity slug", integrity.isNotBlank())
    }

    private fun doneVm(store: ChatStore, reply: String): ChatVM = ChatVM(
        AVATAR,
        FILE,
        store,
        ChatTurn {
            flow {
                emit(ChatEvent.Delta(reply))
                emit(ChatEvent.Done)
            }
        },
    )

    /** An in-memory chat file; `save` records what the real repository would write. */
    private class FakeStore(
        initial: Chat,
        private val existsValue: Boolean = true,
    ) : ChatStore {
        var current = initial
        val saves = mutableListOf<Chat>()
        var onSave: (suspend () -> Unit)? = null
        var loadCalls = 0
        var existsCalls = 0

        override suspend fun load(): Chat {
            loadCalls++
            return current
        }

        override suspend fun save(chat: Chat) {
            saves += chat
            current = chat
            onSave?.invoke()
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
