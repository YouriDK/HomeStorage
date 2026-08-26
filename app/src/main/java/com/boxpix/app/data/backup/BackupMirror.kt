package com.boxpix.app.data.backup

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.time.Clock

/**
 * Disk-to-disk backup (owner's request: the cloud disk is old — every new
 * file must reach the second disk so nothing is ever lost). The box copies
 * the bytes itself (`fs/cp`, one async task per batch): the phone only walks
 * the two trees and diffs them.
 *
 * Contract:
 * - ADDITIVE ONLY: nothing is ever deleted on the backup side — a mistake on
 *   the source must not propagate to the copy.
 * - Missing entries are copied (a missing folder = ONE recursive server-side
 *   copy); same-name files with a different size are re-copied with
 *   overwrite; same size = assumed identical (mtimes are not comparable
 *   across copies).
 * - `.trash` stays out; `.vault` (already encrypted), `.thumbs` and `.meta`
 *   are mirrored as-is — the backup needs no unlock, ever.
 * - The mirror talks to the RAW disk provider (dot-entries included via the
 *   hidden listing); the destination is `<backup root>/<source folder name>`.
 *
 * Scheduled weekly by the worker; "Back up now" in Settings runs it on demand.
 */
/** Where the mirror's configuration lives (DataStore in prod, memory in tests). */
interface BackupConfig {
    suspend fun backupRoot(): Pair<String, String>?
    suspend fun lastBackupAtEpochSeconds(): Long?
    suspend fun setLastBackupAt(epochSeconds: Long)
}

class BackupMirror(
    private val disk: StorageProvider,
    private val config: BackupConfig,
    private val rootLocator: RootLocator,
    private val clock: Clock,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {

    /** "Back up now": survives leaving the Settings screen. */
    fun runAsync() {
        scope.launch { run() }
    }

    data class Report(
        val copiedEntries: Int,
        val overwrittenFiles: Int,
        val failures: Int,
        val scannedFolders: Int,
        val finishedAtEpochSeconds: Long,
    )

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastReport = MutableStateFlow<Report?>(null)
    val lastReport: StateFlow<Report?> = _lastReport.asStateFlow()

    private val mutex = Mutex()

    /** Worker entry point: runs when configured and the last pass is a week old. */
    suspend fun runIfDue(): Boolean {
        config.backupRoot() ?: return false
        val last = config.lastBackupAtEpochSeconds() ?: 0L
        if (clock.instant().epochSecond - last < WEEKLY_SECONDS) return false
        return run() != null
    }

    /** One full mirror pass. Null when unconfigured, misconfigured or busy. */
    suspend fun run(): Report? {
        if (!mutex.tryLock()) return null
        _running.value = true
        try {
            val backup = config.backupRoot() ?: return null
            val sourceB64 = rootLocator.rootPathB64() ?: return null
            val sourceDisplay = runCatching { PathCodec.decode(sourceB64) }.getOrNull() ?: return null
            val backupDisplay = backup.second

            // Nested roots would copy the copy: refuse outright.
            if (sourceDisplay == backupDisplay ||
                backupDisplay.startsWith("$sourceDisplay/") ||
                sourceDisplay.startsWith("$backupDisplay/")
            ) {
                return null
            }

            val destBase = "$backupDisplay/${sourceDisplay.trimEnd('/').substringAfterLast('/')}"
            disk.mkdir(PathCodec.encode(backupDisplay), destBase.substringAfterLast('/'))

            val counters = Counters()
            mirrorDir(sourceDisplay, destBase, counters, depth = 0)

            val report = Report(
                copiedEntries = counters.copied,
                overwrittenFiles = counters.overwritten,
                failures = counters.failures,
                scannedFolders = counters.scanned,
                finishedAtEpochSeconds = clock.instant().epochSecond,
            )
            _lastReport.value = report
            config.setLastBackupAt(report.finishedAtEpochSeconds)
            return report
        } finally {
            _running.value = false
            mutex.unlock()
        }
    }

    private class Counters {
        var copied = 0
        var overwritten = 0
        var failures = 0
        var scanned = 0
    }

    private suspend fun mirrorDir(srcDisplay: String, dstDisplay: String, c: Counters, depth: Int) {
        if (depth > MAX_DEPTH || c.scanned >= MAX_FOLDERS_PER_PASS) return
        val source = when (val listed = disk.list(PathCodec.encode(srcDisplay), includeHidden = true)) {
            is FbxResult.Ok -> listed.value
            is FbxResult.Err -> {
                c.failures++
                return
            }
        }
        c.scanned++
        val existing = disk.list(PathCodec.encode(dstDisplay), includeHidden = true)
            .getOrNull().orEmpty().associateBy { it.name }

        val missing = ArrayList<String>()
        val changed = ArrayList<String>()
        val recurse = ArrayList<Pair<String, String>>()
        source.forEach { entry ->
            if (entry.name == TRASH_DIR) return@forEach // deleted things stay out
            val dest = existing[entry.name]
            when {
                entry.isDirectory && dest == null -> missing += entry.pathB64
                entry.isDirectory -> recurse += entry.displayPath to "$dstDisplay/${entry.name}"
                dest == null -> missing += entry.pathB64
                dest.sizeBytes != entry.sizeBytes -> changed += entry.pathB64
            }
        }

        if (missing.isNotEmpty()) {
            when (disk.copy(missing, PathCodec.encode(dstDisplay))) {
                is FbxResult.Ok -> c.copied += missing.size
                is FbxResult.Err -> c.failures += missing.size
            }
        }
        if (changed.isNotEmpty()) {
            when (disk.copy(changed, PathCodec.encode(dstDisplay), overwrite = true)) {
                is FbxResult.Ok -> c.overwritten += changed.size
                is FbxResult.Err -> c.failures += changed.size
            }
        }
        recurse.forEach { (src, dst) -> mirrorDir(src, dst, c, depth + 1) }
    }

    companion object {
        const val WEEKLY_SECONDS = 7L * 24 * 3600
        private const val TRASH_DIR = ".trash"
        private const val MAX_DEPTH = 24
        private const val MAX_FOLDERS_PER_PASS = 20_000
    }
}
