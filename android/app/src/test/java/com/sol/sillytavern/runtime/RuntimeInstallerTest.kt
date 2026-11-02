package com.sol.sillytavern.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure part of [RuntimeInstaller]; no Android APIs or Robolectric.
 * Asset extraction is exercised on-device by Task 7's smoke run.
 */
class RuntimeInstallerTest {
    private val current = "1.0.1001"

    @Test
    fun blankMarkerNeedsInstall() {
        assertTrue(RuntimeInstaller.needsInstall("", current))
    }

    @Test
    fun nullMarkerNeedsInstall() {
        assertTrue(RuntimeInstaller.needsInstall(null, current))
    }

    @Test
    fun whitespaceMarkerNeedsInstall() {
        assertTrue(RuntimeInstaller.needsInstall("   ", current))
    }

    @Test
    fun equalMarkerSkipsInstall() {
        assertFalse(RuntimeInstaller.needsInstall(current, current))
    }

    @Test
    fun markerWithSurroundingWhitespaceSkipsInstall() {
        // The shipped predicate trims the marker text, so a trailing newline from a
        // diagnostics dump or shell echo does not force a reinstall.
        assertFalse(RuntimeInstaller.needsInstall("  $current\n", current))
    }

    @Test
    fun mismatchedMarkerNeedsInstall() {
        assertTrue(RuntimeInstaller.needsInstall("1.0.1000", current))
    }

    @Test
    fun markerFromDifferentSchemeNeedsInstall() {
        assertTrue(RuntimeInstaller.needsInstall("dev", current))
    }
}
