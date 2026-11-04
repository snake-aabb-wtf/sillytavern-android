package com.sol.sillytavern.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure parts of [DataBackup]: entry safety and the export
 * tree (which entries a backup may contain). Copying the finished zip to a SAF
 * document still needs Android''s ContentResolver, so that step is on-device only.
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
    fun nulByteIsRejected() {
        assertFalse(DataBackup.isSafeEntry("a\u0000b"))
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
    fun exportNeverCarriesPlaintextKeys() {
        val root = File(System.getProperty("java.io.tmpdir"), "data-backup-${System.nanoTime()}")
        val data = File(root, "data")
        val user = File(data, "default-user")
        user.mkdirs()
        File(user, "settings.json").writeText("{}")
        File(user, "secrets.json").writeText(
            """{"api_key_openai":[{"id":"1","value":"sk-secret","active":true}]}""",
        )
        File(data, "characters").mkdirs()
        File(data, "characters/Seraphina.png").writeText("png")
        val config = File(root, "config.yaml").apply { writeText("port: 8000") }

        val bytes = ByteArrayOutputStream()
        val count = ZipOutputStream(bytes).use { zip ->
            DataBackup.writeArchive(data, config, zip)
        }
        val entries = ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            val names = mutableListOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
            }
            names
        }

        assertFalse(
            "a shared backup must not contain plaintext keys: $entries",
            entries.any { it.endsWith("secrets.json") },
        )
        assertTrue(entries.contains("default-user/settings.json"))
        assertTrue(entries.contains("default-user/characters/Seraphina.png"))
        assertTrue(entries.contains("config.yaml"))
        assertEquals(3, count)

        root.deleteRecursively()
    }

    @Test
    fun suggestedNameHasTimestampFormat() {
        val pattern = Regex("^sillytavern-backup-\\d{8}-\\d{4}\\.zip$")
        assertTrue(pattern.matches(DataBackup.suggestedExportName(Date())))
    }
}
