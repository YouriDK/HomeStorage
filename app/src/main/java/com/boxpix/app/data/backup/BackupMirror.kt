package com.boxpix.app.data.backup

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.freebox.api.PathCodec
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
 * - The SOURCE is a fixed, explicitly chosen folder — it deliberately does NOT
 *   follow the app's configured root, so re-pointing the app never silently
 *   changes what gets backed up.
 *
 * Scheduled by the worker (cadence + earliest start hour); "Back up now" in
 * Settings runs it on demand regardless of the schedule.
 */
/** Where the mirror's configuration lives (DataStore in prod, memory in tests). */
interface BackupConfig {
    /** The folder being mirrored (b64 path to display path). */
    suspend fun backupSource(): Pair<String, String>?
    suspend fun backupRoot(): Pair<String, String>?
    suspend fun lastBackupAtEpochSeconds(): Long?
    suspend fun setLastBackupAt(epochSeconds: Long)

    /** Cadence in days (1 = daily, 7 = weekly, 30 = monthly). */
    suspend fun intervalDays(): Long = 7L

    /** Local hour of day the scheduled pass waits for; -1 = any time. */
    suspend fun earliestStartHour(): Int = -1
}

class BackupMirror(
    private val disk: StorageProvider,
    private val config: BackupConfig,
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

    /** Worker entry point: runs when configured, due, and past the start hour. */
    suspend fun runIfDue(): Boolean {
        config.backupSource() ?: return false
        config.backupRoot() ?: return false
        val now = clock.instant()
        val last = config.lastBackupAtEpochSeconds() ?: 0L
        if (now.epochSecond - last < config.intervalDays() * 86_400) return false
        val earliest = config.earliestStartHour()
        if (earliest >= 0 && now.atZone(clock.zone).hour < earliest) return false
        return run() != null
    }

    /** One full mirror pass. Null when unconfigured, misconfigured or busy. */
    suspend fun run(): Report? {
        if (!mutex.tryLock()) return null
        _running.value = true
        try {
            val backup = config.backupRoot() ?: return null
            val sourceDisplay = config.backupSource()?.second ?: return null
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
