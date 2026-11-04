package com.sol.sillytavern.native.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A double tap on a chat row must not push the same destination twice: two
 * `ChatVM`s on one file each save their own snapshot, and the later save wins.
 */
class ClickDebouncerTest {

    @Test
    fun aSecondTapInsideTheWindowIsIgnored() {
        val debouncer = ClickDebouncer(300)

        assertTrue(debouncer.shouldAccept(1_000))
        assertFalse("a double tap must open one chat", debouncer.shouldAccept(1_150))
    }

    @Test
    fun aTapAfterTheWindowIsAccepted() {
        val debouncer = ClickDebouncer(300)

        assertTrue(debouncer.shouldAccept(1_000))
        assertTrue(debouncer.shouldAccept(1_301))
    }

    @Test
    fun theWindowBoundaryItselfIsAccepted() {
        val debouncer = ClickDebouncer(300)

        assertTrue(debouncer.shouldAccept(1_000))
        assertTrue(debouncer.shouldAccept(1_300))
    }

    @Test
    fun aRejectedTapDoesNotExtendTheWindow() {
        val debouncer = ClickDebouncer(300)

        assertTrue(debouncer.shouldAccept(1_000))
        assertFalse(debouncer.shouldAccept(1_100))
        // 300 ms after the accepted tap, not after the rejected one.
        assertTrue(debouncer.shouldAccept(1_300))
    }
}
