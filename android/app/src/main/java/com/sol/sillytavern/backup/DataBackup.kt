package com.sol.sillytavern.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sol.sillytavern.R
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup and restore of the user data living under `filesDir`.
 *
 * Layout of an exported archive (root, no wrapper directory):
 *
 * ```
 * default-user/settings.json   <- contents of filesDir/data
 * characters/...
 * config.yaml                  <- filesDir/config.yaml, when present
 * ```
 *
 * Import is deliberately split in two:
 *
 * 1. [stageImport] unpacks the archive into `filesDir/data.import` and writes
 *    `filesDir/data.import.pending`. It never touches `data/`, so it is safe while Node is
 *    running and holds files open there.
 * 2. [applyPendingImport] runs on the next process start, before seeding and before Node is
 *    launched, and swaps `data.import` in for `data` (keeping `data.old` as a rollback copy
 *    until the swap succeeded).
 *
 * Why not swap immediately after import? libnode cannot be stopped in-process, so a rename
 * of `data/` underneath a live Node would leave the running server writing into the
 * detached `data.old` directory.
 */
object DataBackup {

    private const val TAG = "DataBackup"

    private const val DATA_DIR_NAME = "data"
    private const val STAGING_DIR_NAME = "data.import"
    private const val BACKUP_DIR_NAME = "data.old"
    private const val MARKER_NAME = "data.import.pending"
    private const val CONFIG_NAME = "config.yaml"
    private const val OLD_CONFIG_NAME = "config.yaml.old"

    /** A backup without this file is not a SillyTavern data directory. */
    private const val REQUIRED_IMPORT_FILE = "default-user/settings.json"

    private const val BUFFER_SIZE = 64 * 1024
    private const val NAME_TIMESTAMP_PATTERN = "yyyyMMdd-HHmm"

    /** Result of an export, stage or apply operation. */
    sealed interface Result {
        /** [files] entries written and [bytes] of archive data; both 0 for a no-op apply. */
        data class Success(val files: Int = 0, val bytes: Long = 0L) : Result

        /** [reason] is user-facing and is shown in a Toast or the error panel. */
        data class Failure(val reason: String) : Result
    }

    /** Thrown when an archive entry would escape the extraction root. */
    private class UnsafeEntryException(val entryName: String) : IOException(entryName)

    /**
     * Suggested archive name, e.g. `sillytavern-backup-20261103-0145.zip`.
     *
     * Local time, matching what the user sees in the picker.
     */
    fun suggestedExportName(now: Date = Date()): String =
        "sillytavern-backup-${SimpleDateFormat(NAME_TIMESTAMP_PATTERN, Locale.US).format(now)}.zip"

    /**
     * Zips `filesDir/data` (contents only, at the archive root) and `filesDir/config.yaml`
     * into [uri], a document created through SAF.
     */
    fun export(context: Context, uri: Uri): Result {
        val dataDir = File(context.filesDir, DATA_DIR_NAME)
        if (!dataDir.isDirectory) {
            return Result.Failure(context.getString(R.string.backup_error_no_data))
        }

        return try {
            val raw = context.contentResolver.openOutputStream(uri, "wt")
                ?: return Result.Failure(context.getString(R.string.backup_error_open))
            val counting = CountingOutputStream(raw)
            var files = 0

            ZipOutputStream(BufferedOutputStream(counting, BUFFER_SIZE)).use { zip ->
                val config = File(context.filesDir, CONFIG_NAME)
                if (config.isFile) {
                    zip.putNextEntry(ZipEntry(CONFIG_NAME))
                    config.inputStream().buffered(BUFFER_SIZE).use { it.copyTo(zip, BUFFER_SIZE) }
                    zip.closeEntry()
                    files++
                }
                files += addDirectory(zip, dataDir, dataDir)
            }

            // The archive stream is closed by now: counting.count is final.
            Result.Success(files, counting.count)
        } catch (e: Exception) {
            Log.w(TAG, "export failed", e)
            Result.Failure(e.message ?: context.getString(R.string.backup_error_unknown))
        }
    }

    /** Recursively adds [dir] to [zip]; entry names are relative to [base]. */
    private fun addDirectory(zip: ZipOutputStream, base: File, dir: File): Int {
        val children = dir.listFiles() ?: return 0
        var files = 0
        for (child in children) {
            val name = child.relativeTo(base).path.replace(File.separatorChar, '/')
            when {
                child.isDirectory -> {
                    // Keep empty directories (and their structure) in the archive.
                    zip.putNextEntry(ZipEntry("$name/"))
                    zip.closeEntry()
                    files += addDirectory(zip, base, child)
                }
                child.isFile -> {
                    zip.putNextEntry(ZipEntry(name))
                    child.inputStream().buffered(BUFFER_SIZE).use { it.copyTo(zip, BUFFER_SIZE) }
                    zip.closeEntry()
                    files++
                }
                else -> Log.w(TAG, "skipping non-regular file ${child.path}")
            }
        }
        return files
    }

    /**
     * Unpacks [uri] into `filesDir/data.import` and marks it pending.
     *
     * The live `data/` directory is never opened for writing here. Every entry name is
     * validated by [isSafeEntry] and its canonical path must stay under the staging root
     * (zip-slip guard). The staging area is replaced wholesale; a failure leaves no partial
     * staging and no marker, and any previously staged import is invalidated.
     */
    @Synchronized
    fun stageImport(context: Context, uri: Uri): Result {
        val filesDir = context.filesDir
        val staging = File(filesDir, STAGING_DIR_NAME)
        val marker = File(filesDir, MARKER_NAME)

        return try {
            // A new selection replaces whatever was staged before; the old marker must not
            // survive a failed attempt and point at a deleted directory.
            marker.delete()
            if (staging.exists() && !staging.deleteRecursively()) {
                throw IOException(context.getString(R.string.backup_error_stage_clear))
            }
            if (!staging.mkdirs() && !staging.isDirectory) {
                throw IOException(context.getString(R.string.backup_error_stage_clear))
            }

            val root = staging.canonicalFile
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException(context.getString(R.string.backup_error_open))
            var files = 0

            ZipInputStream(BufferedInputStream(input, BUFFER_SIZE)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.orEmpty()
                    if (!isSafeEntry(name)) throw UnsafeEntryException(name)

                    val target = File(staging, name)
                    // Belt and braces: isSafeEntry already rejects `..` and absolute paths,
                    // but canonicalisation also catches links and odd separators.
                    if (!isUnder(root, target.canonicalFile)) {
                        throw UnsafeEntryException(name)
                    }

                    if (entry.isDirectory) {
                        if (!target.mkdirs() && !target.isDirectory) {
                            throw IOException("cannot create ${target.path}")
                        }
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered(BUFFER_SIZE).use { out ->
                            zip.copyTo(out, BUFFER_SIZE)
                        }
                        files++
                    }
                    zip.closeEntry()
                }
            }

            if (!File(staging, REQUIRED_IMPORT_FILE).isFile) {
                staging.deleteRecursively()
                return Result.Failure(context.getString(R.string.backup_error_missing_settings))
            }

            marker.writeText(System.currentTimeMillis().toString())
            Result.Success(files)
        } catch (e: UnsafeEntryException) {
            Log.w(TAG, "rejected archive entry ${e.entryName}", e)
            discardStaging(staging, marker)
            Result.Failure(context.getString(R.string.backup_error_unsafe_entry, e.entryName))
        } catch (e: Exception) {
            Log.w(TAG, "import staging failed", e)
            discardStaging(staging, marker)
            Result.Failure(e.message ?: context.getString(R.string.backup_error_unknown))
        }
    }

    /**
     * Applies a staged import. Must be called on a fresh process, before Node starts.
     *
     * Flow: validate staging, `data` -> `data.old` (when present), `data.import` -> `data`,
     * move a staged `config.yaml` next to the live one, delete `data.old`, remove the marker.
     * Any failure restores the previous `data/` and `config.yaml` and returns
     * [Result.Failure].
     *
     * Safe to call when nothing is pending (returns [Result.Success]).
     */
    @Synchronized
    fun applyPendingImport(context: Context): Result {
        val filesDir = context.filesDir
        val marker = File(filesDir, MARKER_NAME)
        if (!marker.exists()) return Result.Success()

        val staging = File(filesDir, STAGING_DIR_NAME)
        val live = File(filesDir, DATA_DIR_NAME)
        val backup = File(filesDir, BACKUP_DIR_NAME)
        val stagedConfig = File(staging, CONFIG_NAME)
        val liveConfig = File(filesDir, CONFIG_NAME)
        val oldConfig = File(filesDir, OLD_CONFIG_NAME)

        // A marker without staging means the previous run died after the rename but before
        // the marker was removed: the import is already in place. Drop the marker instead of
        // failing every subsequent start.
        if (!staging.isDirectory) {
            Log.w(TAG, "import marker without staging directory; assuming it was applied")
            marker.delete()
            return Result.Success()
        }

        if (!File(staging, REQUIRED_IMPORT_FILE).isFile) {
            staging.deleteRecursively()
            marker.delete()
            return Result.Failure(context.getString(R.string.backup_error_missing_settings))
        }

        var liveMoved = false
        var swapped = false
        var configBackedUp = false
        var configSwapped = false

        return try {
            // A crash between `data` -> `data.old` and `data.import` -> `data` leaves the
            // old data only in `data.old`; put it back before deleting anything.
            if (!live.exists() && backup.isDirectory) {
                if (!backup.renameTo(live)) {
                    throw IOException(context.getString(R.string.backup_error_backup))
                }
            }
            if (backup.exists() && !backup.deleteRecursively()) {
                throw IOException(context.getString(R.string.backup_error_backup))
            }
            if (live.exists()) {
                if (!live.renameTo(backup)) {
                    throw IOException(context.getString(R.string.backup_error_backup))
                }
                liveMoved = true
            }
            if (!staging.renameTo(live)) {
                throw IOException(context.getString(R.string.backup_error_swap))
            }
            swapped = true

            if (stagedConfig.isFile) {
                if (liveConfig.exists()) {
                    if (!liveConfig.renameTo(oldConfig)) {
                        throw IOException(context.getString(R.string.backup_error_config))
                    }
                    configBackedUp = true
                }
                if (!stagedConfig.renameTo(liveConfig)) {
                    throw IOException(context.getString(R.string.backup_error_config))
                }
                configSwapped = true
            }

            if (liveMoved) backup.deleteRecursively()
            if (configBackedUp) oldConfig.delete()
            if (!marker.delete()) Log.w(TAG, "could not remove import marker")
            Result.Success()
        } catch (e: Exception) {
            Log.e(TAG, "applying import failed; rolling back", e)
            rollback(live, staging, backup, liveConfig, oldConfig, swapped, liveMoved, configSwapped, configBackedUp)
            Result.Failure(
                context.getString(
                    R.string.backup_error_apply,
                    e.message ?: context.getString(R.string.backup_error_unknown),
                ),
            )
        }
    }

    /** Restores the pre-import directory layout as far as the failures allow. */
    private fun rollback(
        live: File,
        staging: File,
        backup: File,
        liveConfig: File,
        oldConfig: File,
        swapped: Boolean,
        liveMoved: Boolean,
        configSwapped: Boolean,
        configBackedUp: Boolean,
    ) {
        if (configSwapped) {
            liveConfig.delete()
        }
        if (configBackedUp) {
            // Put the user's original config back; a no-op when it was never moved.
            oldConfig.renameTo(liveConfig)
        }
        if (swapped) {
            // Return the rejected data to staging so a later retry can re-apply it.
            if (!live.renameTo(staging)) live.deleteRecursively()
        }
        if (liveMoved) {
            if (!backup.renameTo(live)) {
                Log.e(TAG, "could not restore ${live.path} from ${backup.path}")
            }
        }
    }

    /** Removes staging and the marker after a failed stage attempt. */
    private fun discardStaging(staging: File, marker: File) {
        staging.deleteRecursively()
        marker.delete()
    }

    /** True when [target]'s canonical path is [root] or a descendant of it. */
    private fun isUnder(root: File, target: File): Boolean {
        val rootPath = root.path
        val targetPath = target.path
        return targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
    }

    /**
     * Pure zip-entry name check, independent of any filesystem.
     *
     * Rejects empty names, absolute paths (POSIX and Windows), Windows drive letters, NUL
     * bytes and any `..` segment (both separators). Legitimate names such as `a/b.txt`
     * pass.
     */
    fun isSafeEntry(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.contains('\u0000')) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) return false

        val normalized = name.replace('\\', '/')
        return normalized.split('/').none { it == ".." }
    }

    /** Counts the bytes actually written through to the SAF stream. */
    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
