package com.sol.sillytavern.runtime

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Outcome of [RuntimeInstaller.ensureInstalled]. */
sealed interface InstallResult {
    /** The runtime is extracted and usable at [runtimeDir]. */
    data class Ready(val runtimeDir: File) : InstallResult

    /** Installation failed; [reason] is suitable for logs and diagnostics UI. */
    data class Failed(val reason: String) : InstallResult
}

/**
 * Installs the bundled Node runtime and seeds the app data directory.
 *
 * Layout under `context.filesDir`:
 * ```
 * runtime/<version>/server   unpacked runtime (from assets/runtime.zip)
 * runtime/<version>/seed     seed data copied into data/ on first run
 * runtime/<version>/.version marker written by this installer
 * data/                      user data; never touched by runtime upgrades
 * ```
 *
 * Installs are staged into `runtime.tmp-<version>`, then renamed into place, so a crash
 * during extraction never leaves a half-written `runtime/<version>` behind.
 */
object RuntimeInstaller {
    private const val TAG = "RuntimeInstaller"

    private const val RUNTIME_DIR_NAME = "runtime"
    private const val TEMP_PREFIX = "runtime.tmp-"
    private const val VERSION_MARKER = ".version"
    private const val SERVER_DIR_NAME = "server"
    private const val SEED_DIR_NAME = "seed"
    private const val DATA_DIR_NAME = "data"
    private const val BUFFER_SIZE = 64 * 1024

    /** Version strings are also path segments, so keep them to a conservative alphabet. */
    private val VERSION_PATTERN = Regex("^[0-9A-Za-z][0-9A-Za-z._+-]*$")

    /**
     * True when the runtime of [currentVersion] must be (re)installed.
     *
     * This is the single decision used by [ensureInstalled] and by callers that only have
     * the marker text. Pure function (no Android APIs) so it can be unit-tested on the
     * JVM: only a non-blank marker whose trimmed text equals [currentVersion] skips
     * installation.
     */
    fun needsInstall(markerVersion: String?, currentVersion: String): Boolean =
        markerVersion.isNullOrBlank() || markerVersion.trim() != currentVersion

    /**
     * Extracts `assets/[assetName]` into `filesDir/runtime/[version]` and returns
     * [InstallResult.Ready] with that directory.
     *
     * Re-extraction is skipped when that directory already has both `server/` and
     * `seed/` and its `.version` marker satisfies [needsInstall]. Older
     * `runtime/<version-looking>` directories are pruned on every successful path; `data/`
     * is never touched.
     *
     * **Blocking I/O:** this performs a full asset extraction and directory rename
     * synchronously. Call it from a worker thread (`Dispatchers.IO`), never from the main
     * thread. (androidx `@WorkerThread` is not a dependency, hence this note.)
     *
     * Never throws (except `VirtualMachineError`); failures come back as
     * [InstallResult.Failed].
     */
    fun ensureInstalled(context: Context, assetName: String, version: String): InstallResult {
        if (!isValidVersion(version)) {
            return InstallResult.Failed("invalid runtime version: \"$version\"")
        }

        val filesDir = context.filesDir
        val runtimeRoot = File(filesDir, RUNTIME_DIR_NAME)
        val target = File(runtimeRoot, version)

        if (isInstalled(target, version)) {
            // Still prune: an older runtime may linger after a previous crash.
            pruneOtherVersions(runtimeRoot, version)
            Log.i(TAG, "runtime $version already installed at ${target.path}")
            return InstallResult.Ready(target)
        }

        val temp = File(filesDir, TEMP_PREFIX + version)
        val failure: String? = try {
            // Leftovers from an interrupted install of another version are garbage.
            filesDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith(TEMP_PREFIX) && it.name != temp.name }
                ?.forEach { deleteRecursively(it) }
            deleteRecursively(temp)
            if (!temp.mkdirs()) throw IOException("cannot create ${temp.path}")

            context.assets.open(assetName).use { input -> unzipInto(input, temp) }

            // A truncated or wrong asset must not be promoted to a "ready" runtime.
            require(File(temp, SERVER_DIR_NAME).isDirectory) {
                "asset $assetName has no $SERVER_DIR_NAME/ directory"
            }
            require(File(temp, SEED_DIR_NAME).isDirectory) {
                "asset $assetName has no $SEED_DIR_NAME/ directory"
            }
            File(temp, VERSION_MARKER).writeText(version)

            if (!runtimeRoot.isDirectory && !runtimeRoot.mkdirs()) {
                throw IOException("cannot create ${runtimeRoot.path}")
            }
            // Only reached when the existing target is incomplete or stale.
            if (!deleteRecursively(target)) {
                throw IOException("cannot remove stale runtime ${target.path}")
            }
            if (!temp.renameTo(target)) {
                throw IOException("cannot rename ${temp.path} to ${target.path}")
            }
            null
        } catch (t: Throwable) {
            deleteRecursively(temp)
            if (t is VirtualMachineError) throw t
            Log.e(TAG, "failed to install runtime $version from $assetName", t)
            "install $version failed: ${t.javaClass.simpleName}: ${t.message ?: "no detail"}"
        }

        if (failure != null) return InstallResult.Failed(failure)

        // Outside the install try/catch: a prune problem must not fail a good install.
        pruneOtherVersions(runtimeRoot, version)
        Log.i(TAG, "installed runtime $version at ${target.path}")
        return InstallResult.Ready(target)
    }

    /**
     * Copies `[runtimeDir]/seed` to `filesDir/data` when no data directory exists yet.
     *
     * No-op when `data/` already exists (even if it is empty) or the runtime ships no seed
     * directory. **Callers must seed before creating `data/` themselves**, otherwise
     * seeding is silently suppressed. The copy is staged in `data.tmp` and renamed into
     * place, so an interrupted seed never leaves a partial `data/`.
     *
     * **Blocking I/O:** performs a full recursive copy synchronously; call from a worker
     * thread (`Dispatchers.IO`), never from the main thread.
     *
     * @throws IOException when the copy fails; the staged directory is removed first.
     */
    fun seedDataIfMissing(filesDir: File, runtimeDir: File) {
        val dataDir = File(filesDir, DATA_DIR_NAME)
        if (dataDir.exists()) return

        val seedDir = File(runtimeDir, SEED_DIR_NAME)
        if (!seedDir.isDirectory) {
            Log.w(TAG, "no seed directory at ${seedDir.path}; leaving data absent")
            return
        }

        val temp = File(filesDir, "$DATA_DIR_NAME.tmp")
        try {
            deleteRecursively(temp)
            copyRecursively(seedDir, temp)
            if (dataDir.exists()) {
                // Another writer won the race; keep its data.
                deleteRecursively(temp)
                return
            }
            if (!temp.renameTo(dataDir)) {
                throw IOException("cannot rename ${temp.path} to ${dataDir.path}")
            }
            Log.i(TAG, "seeded ${dataDir.path} from ${seedDir.path}")
        } catch (t: Throwable) {
            deleteRecursively(temp)
            if (t is VirtualMachineError) throw t
            Log.e(TAG, "failed to seed ${dataDir.path} from ${seedDir.path}", t)
            throw IOException("seed copy failed: ${t.message}", t)
        }
    }

    /** Version strings are path segments: no separators, no `..`, conservative charset. */
    private fun isValidVersion(version: String): Boolean =
        version.isNotBlank() &&
            !version.contains('/') &&
            !version.contains('\\') &&
            VERSION_PATTERN.matches(version)

    /**
     * Installed means: directory present, payload present, and the marker says this
     * version. Uses [needsInstall] so the unit-tested predicate is the shipped one.
     */
    private fun isInstalled(dir: File, version: String): Boolean =
        dir.isDirectory && hasPayload(dir) && !needsInstall(readMarker(dir), version)

    private fun hasPayload(dir: File): Boolean =
        File(dir, SERVER_DIR_NAME).isDirectory && File(dir, SEED_DIR_NAME).isDirectory

    /** Marker text, or null when missing/unreadable (both mean "install"). */
    private fun readMarker(dir: File): String? {
        val marker = File(dir, VERSION_MARKER)
        return runCatching { if (marker.isFile) marker.readText() else null }.getOrNull()
    }

    /**
     * Extracts every entry of [input] below [root], rejecting entries whose canonical
     * path would escape it (zip-slip).
     */
    private fun unzipInto(input: InputStream, root: File) {
        val rootCanonical = root.canonicalFile
        val rootPrefix = rootCanonical.path + File.separator
        val zip = ZipInputStream(BufferedInputStream(input))
        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name
            if (name.isNotBlank()) {
                val out = File(rootCanonical, name).canonicalFile
                if (!out.path.startsWith(rootPrefix)) {
                    throw IOException("zip entry escapes target directory: $name")
                }
                if (entry.isDirectory) {
                    if (!out.isDirectory && !out.mkdirs()) {
                        throw IOException("cannot create ${out.path}")
                    }
                } else {
                    out.parentFile?.let { parent ->
                        if (!parent.isDirectory && !parent.mkdirs()) {
                            throw IOException("cannot create ${parent.path}")
                        }
                    }
                    FileOutputStream(out).use { output -> zip.copyTo(output, BUFFER_SIZE) }
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    private fun copyRecursively(src: File, dst: File) {
        if (src.isDirectory) {
            if (!dst.isDirectory && !dst.mkdirs()) throw IOException("cannot create ${dst.path}")
            src.listFiles()?.forEach { child -> copyRecursively(child, File(dst, child.name)) }
        } else {
            dst.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs()) {
                    throw IOException("cannot create ${parent.path}")
                }
            }
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
            }
        }
    }

    /** Removes `runtime/<other-version>` directories; best effort, never throws. */
    private fun pruneOtherVersions(runtimeRoot: File, keep: String) {
        runtimeRoot.listFiles()
            ?.filter {
                it.isDirectory &&
                    it.name != keep &&
                    !it.name.startsWith(TEMP_PREFIX) &&
                    VERSION_PATTERN.matches(it.name)
            }
            ?.forEach { stale ->
                if (deleteRecursively(stale)) {
                    Log.i(TAG, "removed old runtime ${stale.path}")
                } else {
                    Log.w(TAG, "could not fully remove old runtime ${stale.path}")
                }
            }
    }

    /** Best-effort recursive delete; returns false when something could not be removed. */
    private fun deleteRecursively(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        return file.delete() || !file.exists()
    }
}
