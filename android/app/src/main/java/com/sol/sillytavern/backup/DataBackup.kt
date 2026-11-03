package com.sol.sillytavern.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.sol.sillytavern.R
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    private const val PENDING_CONFIG_NAME = "config.import"

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
     *
     * The archive is built in `cacheDir` first and only copied to the SAF document once it
     * is complete. A failure deletes both the temp file and the partial document, so an
     * interrupted export can never be mistaken for a usable backup on the next import.
     */
    fun export(context: Context, uri: Uri): Result {
        val dataDir = File(context.filesDir, DATA_DIR_NAME)
        if (!dataDir.isDirectory) {
            return Result.Failure(context.getString(R.string.backup_error_no_data))
        }

        val temp = File(context.cacheDir, "sillytavern-export-${System.currentTimeMillis()}.zip")
        return try {
            var files = 0
            val emitted = mutableSetOf<String>()
            FileOutputStream(temp).use { fileOut ->
                ZipOutputStream(BufferedOutputStream(fileOut, BUFFER_SIZE)).use { zip ->
                    val config = File(context.filesDir, CONFIG_NAME)
                    if (config.isFile) {
                        emitted.add(CONFIG_NAME)
                        zip.putNextEntry(ZipEntry(CONFIG_NAME))
                        config.inputStream().buffered(BUFFER_SIZE).use {
                            it.copyTo(zip, BUFFER_SIZE)
                        }
                        zip.closeEntry()
                        files++
                    }
                    files += addDirectory(zip, dataDir, dataDir, emitted)
                }
            }

            val bytes = temp.length()
            val target = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException(context.getString(R.string.backup_error_open))
            target.use { out ->
                temp.inputStream().buffered(BUFFER_SIZE).use { it.copyTo(out, BUFFER_SIZE) }
            }
            Result.Success(files, bytes)
        } catch (e: Exception) {
            Log.w(TAG, "export failed", e)
            discardDocument(context, uri)
            Result.Failure(internalReason(context, e))
        } finally {
            temp.delete()
        }
    }

    /**
     * Recursively adds [dir] to [zip]; entry names are relative to [base].
     *
     * [emitted] is a belt-and-braces duplicate guard: a zip with two `config.yaml` entries
     * is rejected by Android's `ZipInputStream` and unreadable by most unzip tools. A
     * `config.yaml` directly under `data/` is a stray from an older build and is skipped so
     * it can never shadow the real root config.
     */
    private fun addDirectory(
        zip: ZipOutputStream,
        base: File,
        dir: File,
        emitted: MutableSet<String>,
    ): Int {
        val children = dir.listFiles() ?: return 0
        var files = 0
        for (child in children) {
            val name = child.relativeTo(base).path.replace(File.separatorChar, '/')
            if (dir == base && name == CONFIG_NAME) {
                Log.w(TAG, "skipping stray ${child.path}; root config is exported separately")
                continue
            }
            when {
                child.isDirectory -> {
                    // Keep empty directories (and their structure) in the archive.
                    val entryName = "$name/"
                    if (emitted.add(entryName)) {
                        zip.putNextEntry(ZipEntry(entryName))
                        zip.closeEntry()
                    }
                    files += addDirectory(zip, base, child, emitted)
                }
                child.isFile -> {
                    if (!emitted.add(name)) {
                        Log.w(TAG, "skipping duplicate entry $name")
                        continue
                    }
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
            Result.Failure(internalReason(context, e))
        }
    }

    /**
     * Applies a staged import. Must be called on a fresh process, before Node starts.
     *
     * Flow: validate staging, move `data.import/config.yaml` out to `config.import` (the
     * staging directory is about to be renamed), `data` -> `data.old` (when present),
     * `data.import` -> `data`, `config.yaml` -> `config.yaml.old`, `config.import` ->
     * `config.yaml`, delete `data.old`/`config.yaml.old`, remove the marker. Any failure
     * restores the previous `data/` and `config.yaml` and returns [Result.Failure].
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
        val pendingConfig = File(filesDir, PENDING_CONFIG_NAME)
        val liveConfig = File(filesDir, CONFIG_NAME)
        val oldConfig = File(filesDir, OLD_CONFIG_NAME)

        // A marker without staging means the previous run died after the rename but before
        // the marker was removed: the import is already in place. Clean up every other
        // leftover of the protocol instead of failing on each start.
        if (!staging.isDirectory) {
            Log.w(TAG, "import marker without staging directory; assuming it was applied")
            File(live, CONFIG_NAME).delete()
            backup.deleteRecursively()
            marker.delete()
            return Result.Success()
        }

        if (!File(staging, REQUIRED_IMPORT_FILE).isFile) {
            staging.deleteRecursively()
            marker.delete()
            return Result.Failure(context.getString(R.string.backup_error_missing_settings))
        }

        var configStaged = false
        var liveMoved = false
        var swapped = false
        var configBackedUp = false
        var configSwapped = false

        return try {
            // The staged config must leave the directory before the rename, otherwise the
            // path becomes stale (`data.import/...` no longer exists once it is `data/`)
            // and the config lands inside `data/`, colliding with the root entry on export.
            if (stagedConfig.isFile) {
                if (pendingConfig.exists() && !pendingConfig.delete()) {
                    throw IOException(context.getString(R.string.backup_error_config))
                }
                if (!stagedConfig.renameTo(pendingConfig)) {
                    throw IOException(context.getString(R.string.backup_error_config))
                }
                configStaged = true
            }

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

            // Older builds could leave a config inside data/; it must not shadow the root
            // config or be exported a second time.
            val stray = File(live, CONFIG_NAME)
            if (stray.exists() && !stray.delete()) {
                Log.w(TAG, "could not remove stray ${stray.path}")
            }

            if (configStaged) {
                if (liveConfig.exists()) {
                    if (!liveConfig.renameTo(oldConfig)) {
                        throw IOException(context.getString(R.string.backup_error_config))
                    }
                    configBackedUp = true
                }
                if (!pendingConfig.renameTo(liveConfig)) {
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
            rollback(
                live,
                staging,
                backup,
                pendingConfig,
                liveConfig,
                oldConfig,
                swapped,
                liveMoved,
                configStaged,
                configSwapped,
                configBackedUp,
            )
            Result.Failure(internalReason(context, e))
        }
    }

    /** Restores the pre-import directory layout as far as the failures allow. */
    private fun rollback(
        live: File,
        staging: File,
        backup: File,
        pendingConfig: File,
        liveConfig: File,
        oldConfig: File,
        swapped: Boolean,
        liveMoved: Boolean,
        configStaged: Boolean,
        configSwapped: Boolean,
        configBackedUp: Boolean,
    ) {
        if (swapped) {
            // Return the rejected data to staging so a later retry can re-apply it.
            if (!live.renameTo(staging)) live.deleteRecursively()
        }
        if (liveMoved) {
            if (!backup.renameTo(live)) {
                Log.e(TAG, "could not restore ${live.path} from ${backup.path}")
            }
        }
        if (configSwapped) {
            // Preserve the archive's config for a retry, then put the user's config back.
            val stagedConfig = File(staging, CONFIG_NAME)
            if (!staging.isDirectory || !liveConfig.renameTo(stagedConfig)) {
                liveConfig.delete()
            }
        } else if (configStaged && staging.isDirectory) {
            // Keep the archive's config with the staged data for a retry.
            pendingConfig.renameTo(File(staging, CONFIG_NAME))
        }
        if (configBackedUp) {
            // Put the user's original config back; a no-op when it was never moved.
            oldConfig.renameTo(liveConfig)
        }
    }

    /** Removes staging and the marker after a failed stage attempt. */
    private fun discardStaging(staging: File, marker: File) {
        staging.deleteRecursively()
        marker.delete()
    }

    /** Removes a partial SAF document after a failed export; best effort. */
    private fun discardDocument(context: Context, uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            // Non-document providers reject this; the empty file is all we can leave.
            Log.w(TAG, "could not delete partial export document", e)
        }
    }

    /** Chinese lead sentence with the technical detail kept in parentheses. */
    private fun internalReason(context: Context, e: Exception): String =
        context.getString(
            R.string.backup_error_internal,
            e.message ?: context.getString(R.string.backup_error_unknown),
        )

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
}
