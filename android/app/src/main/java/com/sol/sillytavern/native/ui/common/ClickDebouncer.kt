package com.sol.sillytavern.native.ui.common

/**
 * One-tap-one-action guard for list rows.
 *
 * Compose delivers every tap of a fast double-tap to [androidx.compose.foundation.clickable];
 * navigating twice from the same row pushes two destinations for the same chat. A pure
 * clock-driven check keeps the rule testable on the JVM, and [rememberClickDebouncer]
 * binds one instance to a composition.
 */
internal class ClickDebouncer(private val windowMillis: Long = DEFAULT_WINDOW_MS) {
    private var lastAccepted: Long? = null

    /** True when a tap at [nowMillis] is far enough from the previously accepted one. */
    fun shouldAccept(nowMillis: Long): Boolean = true

    companion object {
        const val DEFAULT_WINDOW_MS = 300L
    }
}
