package com.sol.sillytavern.backup

import java.util.Date
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure parts of [DataBackup]. Archive I/O needs Android's
 * ContentResolver, so it is only covered on-device.
 */
class DataBackupTest {

    @Test
    fun plainRelativePathIsSafe() {
        assertTrue(DataBackup.isSafeEntry("a/b.txt"))
    }

    @Test
    fun parentTraversalIsRejected() {
        assertFalse(DataBackup.isSafeEntry("../x"))
    }

    @Test
    fun absolutePathIsRejected() {
        assertFalse(DataBackup.isSafeEntry("/abs"))
    }

    @Test
    fun nestedTraversalIsRejected() {
        assertFalse(DataBackup.isSafeEntry("a/../../b"))
    }

    @Test
    fun windowsDriveLetterIsRejected() {
        assertFalse(DataBackup.isSafeEntry("C:/x"))
    }

    @Test
    fun emptyNameIsRejected() {
        assertFalse(DataBackup.isSafeEntry(""))
    }

    @Test
    fun backslashTraversalIsRejected() {
        assertFalse(DataBackup.isSafeEntry("a\\..\\b"))
    }

    @Test
    fun nestedPlainPathIsSafe() {
        assertTrue(DataBackup.isSafeEntry("default-user/settings.json"))
    }

    @Test
    fun suggestedNameHasTimestampFormat() {
        val pattern = Regex("^sillytavern-backup-\\d{8}-\\d{4}\\.zip$")
        assertTrue(pattern.matches(DataBackup.suggestedExportName(Date())))
    }
}
