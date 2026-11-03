package com.sol.sillytavern.native.vm

import com.sol.sillytavern.native.data.model.Character
import com.sol.sillytavern.native.engine.EngineError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pins the four states of the character screen. The key case is the last one: a
 * repository failure must be a visible, retryable error, never an empty grid that
 * looks like the user has no characters.
 */
class CharactersVMTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val seraphina = Character("Seraphina.png", "Seraphina", "A dragon.", emptyList())
    private val maria = Character("Maria.png", "Maria", "", listOf("modern"))

    @Test
    fun charactersBecomeTheReadyState() {
        val vm = CharactersVM(CharacterSource { listOf(seraphina, maria) })

        assertEquals(CharactersUiState.Ready(listOf(seraphina, maria)), vm.state.value)
    }

    @Test
    fun anEmptyListIsTheEmptyState() {
        val vm = CharactersVM(CharacterSource { emptyList() })

        assertEquals(CharactersUiState.Empty, vm.state.value)
    }

    @Test
    fun aFailureIsVisibleAndRetryLoadsAgain() {
        var failing = true
        val vm = CharactersVM(
            CharacterSource {
                if (failing) throw EngineError.Http(500, "boom")
                listOf(seraphina)
            },
        )

        val failed = vm.state.value as CharactersUiState.Failed
        assertEquals("HTTP 500", failed.message)

        failing = false
        vm.refresh()

        assertEquals(CharactersUiState.Ready(listOf(seraphina)), vm.state.value)
    }

    @Test
    fun anExceptionWithoutAMessageStillRendersAsAnError() {
        val vm = CharactersVM(CharacterSource { throw RuntimeException() })

        val failed = vm.state.value as CharactersUiState.Failed
        assertTrue("a nameless failure must not become an empty state: $failed", failed.message.isNotBlank())
    }
}
