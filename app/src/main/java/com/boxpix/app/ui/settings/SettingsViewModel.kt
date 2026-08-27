package com.boxpix.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.data.config.ConfigBackup
import com.boxpix.app.data.config.ConfigCrypto
import com.boxpix.app.data.db.WorkQueueDao
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.download.DownloadProgress
import com.boxpix.app.data.freebox.auth.AppTokenStore
import com.boxpix.app.data.freebox.auth.FreeboxSessionManager
import com.boxpix.app.data.media.Reconciler
import com.boxpix.app.data.media.SyncStatus
import com.boxpix.app.data.media.WorkerStatusFile
import com.boxpix.app.data.media.XmpQueueProcessor
import com.boxpix.app.data.net.ConnectionMode
import com.boxpix.app.data.net.EndpointResolver
import com.boxpix.app.data.prefs.SettingsStore
import com.boxpix.app.data.prefs.UiPrefsStore
import com.boxpix.app.data.db.ExcludedFolderEntity
import com.boxpix.app.data.storage.ScanExclusionRepository
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.trash.TrashRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val uiPrefs: UiPrefsStore,
    private val env: StorageEnv,
    trashRepository: TrashRepository,
    private val tokenStore: AppTokenStore,
    private val sessions: FreeboxSessionManager,
    private val resolver: EndpointResolver,
    private val settings: SettingsStore,
    private val queueDao: WorkQueueDao,
    downloadProgress: DownloadProgress,
    syncStatus: SyncStatus,
    private val reconciler: Reconciler,
    private val xmpProcessor: XmpQueueProcessor,
    private val workerStatusFile: WorkerStatusFile,
    private val scanExclusion: ScanExclusionRepository,
    private val vaultSession: com.boxpix.app.data.vault.VaultSession,
    private val backupMirror: com.boxpix.app.data.backup.BackupMirror,
    private val storageProvider: com.boxpix.app.data.storage.StorageProvider,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val workerLastSeen = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            if (!env.useFakeProvider.first()) {
                workerLastSeen.value = workerStatusFile.read()?.updatedAtEpochSeconds
            }
        }
    }

    data class ConnectionInfo(
        val mode: ConnectionMode?,
        val latencyMs: Long?,
        val boxName: String?,
        val diskName: String?,
        val rootDisplayPath: String?,
    )

    data class UiState(
        val gridColumns: Int = UiPrefsStore.DEFAULT_COLUMNS,
        val trashCount: Int = 0,
        val useFake: Boolean = true,
        val hasFakeControls: Boolean = false,
        val themeMode: String = UiPrefsStore.THEME_SYSTEM,
        val accentPreset: String = UiPrefsStore.ACCENT_DEFAULT,
        val xmpEnabled: Boolean = false,
        val appLockEnabled: Boolean = false,
        val thumbQueue: Int = 0,
        val xmpQueue: Int = 0,
        val downloadQueue: Int = 0,
        val downloadFailed: Int = 0,
        val downloadActive: DownloadProgress.Active? = null,
        val lastPassAtEpochSeconds: Long? = null,
        val syncing: Boolean = false,
        val connection: ConnectionInfo = ConnectionInfo(null, null, null, null, null),
        val workerLastSeenEpochSeconds: Long? = null,
    )

    private data class QueueCounts(
        val thumb: Int,
        val xmp: Int,
        val download: Int,
        val downloadFailed: Int,
        val downloadActive: DownloadProgress.Active?,
    )

    private val queues = env.useFakeProvider.flatMapLatest { useFake ->
        val pid = if (useFake) TrashRepository.PROVIDER_FAKE else TrashRepository.PROVIDER_FREEBOX
        combine(
            queueDao.pendingCountByType(pid, WorkQueueEntity.TYPE_THUMB),
            queueDao.pendingCountByType(pid, WorkQueueEntity.TYPE_XMP),
            queueDao.pendingCountByType(pid, WorkQueueEntity.TYPE_DOWNLOAD),
            queueDao.failedCountByType(pid, WorkQueueEntity.TYPE_DOWNLOAD),
            downloadProgress.active,
        ) { thumb, xmp, download, downloadFailed, active ->
            QueueCounts(thumb, xmp, download, downloadFailed, active)
        }
    }

    private val syncing = MutableStateFlow(false)

    val state: StateFlow<UiState> = combine(
        combine(uiPrefs.gridColumns, trashRepository.count, env.useFakeProvider) { c, t, f -> Triple(c, t, f) },
        combine(uiPrefs.themeMode, uiPrefs.accentPreset, uiPrefs.xmpWriteEnabled, uiPrefs.appLockEnabled) { th, a, x, l ->
            listOf(th, a, x, l)
        },
        queues,
        combine(syncStatus.lastPassAtEpochSeconds, syncing, settings.snapshots, workerLastSeen) { at, busy, snap, worker ->
            listOf(at, busy, snap, worker)
        },
    ) { base, appearance, queueCounts, sync ->
        val (columns, trashCount, useFake) = base
        val snapshot = sync[2] as com.boxpix.app.data.prefs.SettingsStore.Snapshot
        UiState(
            gridColumns = columns,
            trashCount = trashCount,
            useFake = useFake,
            hasFakeControls = env.fakeControls != null,
            themeMode = appearance[0] as String,
            accentPreset = appearance[1] as String,
            xmpEnabled = appearance[2] as Boolean,
            appLockEnabled = appearance[3] as Boolean,
            thumbQueue = queueCounts.thumb,
            xmpQueue = queueCounts.xmp,
            downloadQueue = queueCounts.download,
            downloadFailed = queueCounts.downloadFailed,
            downloadActive = queueCounts.downloadActive,
            lastPassAtEpochSeconds = sync[0] as Long?,
            syncing = sync[1] as Boolean,
            workerLastSeenEpochSeconds = sync[3] as Long?,
            connection = ConnectionInfo(
                mode = resolver.current?.mode,
                latencyMs = resolver.lastLatencyMs,
                boxName = snapshot.boxName,
                diskName = snapshot.diskName,
                rootDisplayPath = snapshot.rootDisplayPath,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** V1 feedback: the "Scan" card lists excluded folders, removable in place. */
    val excludedFolders: StateFlow<List<ExcludedFolderEntity>> = scanExclusion.excludedFolders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeExclusion(pathB64: String) {
        viewModelScope.launch { scanExclusion.include(pathB64) }
    }

    private val _exportUri = MutableStateFlow<Uri?>(null)
    val exportUri: StateFlow<Uri?> = _exportUri.asStateFlow()

    fun setGridColumns(columns: Int) = launch { uiPrefs.setGridColumns(columns) }
    // Backup mirror (scheduled worker pass + manual runs)
    val backupSource = uiPrefs.backupSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val backupRoot = uiPrefs.backupRoot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lastBackupAt = uiPrefs.lastBackupAtEpochSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val backupIntervalDays = uiPrefs.backupIntervalDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 7L)
    val backupEarliestHour = uiPrefs.backupEarliestHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

    fun setBackupIntervalDays(days: Long) = launch { uiPrefs.setBackupIntervalDays(days) }
    fun setBackupEarliestHour(hour: Int) = launch { uiPrefs.setBackupEarliestHour(hour) }

    val backupRunning = backupMirror.running
    val backupReport = backupMirror.lastReport

    // Vault location (owner's decision: vault and backup are separate concerns,
    // neither follows the app root once set explicitly).
    val vaultLocation = uiPrefs.vaultRoot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Back to the historical default: probe the app root. */
    fun clearVaultLocation() = launch {
        vaultSession.lock()
        uiPrefs.setVaultRoot(null, null)
    }

    enum class FolderPickTarget { BACKUP_SOURCE, BACKUP_DESTINATION, VAULT_LOCATION }

    /** Settings folder picker (backup source/destination, vault location). */
    data class FolderBrowse(
        val target: FolderPickTarget,
        val stack: List<com.boxpix.app.data.storage.StorageEntry> = emptyList(),
        val folders: List<com.boxpix.app.data.storage.StorageEntry> = emptyList(),
        val loading: Boolean = true,
    ) {
        val current get() = stack.lastOrNull()
    }

    private val _folderBrowse =
        kotlinx.coroutines.flow.MutableStateFlow<FolderBrowse?>(null)
    val folderBrowse: kotlinx.coroutines.flow.StateFlow<FolderBrowse?> = _folderBrowse

    fun openFolderPicker(target: FolderPickTarget) {
        _folderBrowse.value = FolderBrowse(target)
        folderBrowseList(null)
    }

    fun closeFolderPicker() {
        _folderBrowse.value = null
    }

    fun folderBrowseInto(entry: com.boxpix.app.data.storage.StorageEntry) {
        _folderBrowse.value = _folderBrowse.value?.let {
            it.copy(stack = it.stack + entry, loading = true)
        }
        folderBrowseList(entry.pathB64)
    }

    fun folderBrowseUp() {
        val browse = _folderBrowse.value ?: return
        val stack = browse.stack.dropLast(1)
        _folderBrowse.value = browse.copy(stack = stack, loading = true)
        folderBrowseList(stack.lastOrNull()?.pathB64)
    }

    /** Confirms the folder currently browsed as the picker's target. */
    fun chooseFolderHere() = launch {
        val browse = _folderBrowse.value ?: return@launch
        val chosen = browse.current ?: return@launch
        when (browse.target) {
            FolderPickTarget.BACKUP_SOURCE ->
                uiPrefs.setBackupSource(chosen.pathB64, chosen.displayPath)
            FolderPickTarget.BACKUP_DESTINATION ->
                uiPrefs.setBackupRoot(chosen.pathB64, chosen.displayPath)
            FolderPickTarget.VAULT_LOCATION -> {
                // The old mount may point elsewhere: lock before re-pointing.
                vaultSession.lock()
                uiPrefs.setVaultRoot(chosen.pathB64, chosen.displayPath)
            }
        }
        _folderBrowse.value = null
    }

    private fun folderBrowseList(pathB64: String?) = launch {
        val folders = storageProvider.list(pathB64, onlyFolders = true)
            .getOrNull().orEmpty().sortedBy { it.name.lowercase() }
        _folderBrowse.value = _folderBrowse.value?.copy(folders = folders, loading = false)
    }

    fun backUpNow() = backupMirror.runAsync()

    fun setUseFake(useFake: Boolean) = launch {
        // Another tree entirely: an unlocked vault must not survive the switch.
        vaultSession.lock()
        uiPrefs.setUseFakeProvider(useFake)
    }
    fun setThemeMode(mode: String) = launch { uiPrefs.setThemeMode(mode) }
    fun setAccentPreset(preset: String) = launch { uiPrefs.setAccentPreset(preset) }
    fun setXmpEnabled(enabled: Boolean) = launch { uiPrefs.setXmpWriteEnabled(enabled) }
    fun setAppLockEnabled(enabled: Boolean) = launch { uiPrefs.setAppLockEnabled(enabled) }

    fun sleepDisk() {
        env.fakeControls?.sleepDisk()
    }

    fun resetFakeData() {
        env.fakeControls?.resetData()
    }

    fun retryFailedDownloads() {
        viewModelScope.launch {
            val pid = if (env.useFakeProvider.first()) {
                TrashRepository.PROVIDER_FAKE
            } else {
                TrashRepository.PROVIDER_FREEBOX
            }
            queueDao.retryFailedByType(pid, WorkQueueEntity.TYPE_DOWNLOAD)
        }
    }

    fun resyncNow() {
        viewModelScope.launch {
            syncing.value = true
            reconciler.runPass(maxFolders = 400, processLimit = 200)
            xmpProcessor.process(100)
            syncing.value = false
        }
    }

    /** Encrypted pairing backup, handed out through the share sheet. */
    fun exportConfig(passphrase: String) {
        viewModelScope.launch {
            val token = withContext(Dispatchers.IO) { tokenStore.appToken } ?: return@launch
            val snapshot = settings.current()
            val bytes = ConfigCrypto.encrypt(
                ConfigBackup(
                    appToken = token,
                    manualHost = snapshot.manualHost,
                    apiDomain = snapshot.apiDomain,
                    httpsPort = snapshot.httpsPort,
                    apiBaseUrl = snapshot.apiBaseUrl,
                    apiVersion = snapshot.apiVersion,
                    boxName = snapshot.boxName,
                ),
                passphrase.toCharArray(),
            )
            val uri = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(dir, "boxpix-config.bxp")
                file.writeBytes(bytes)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            _exportUri.value = uri
        }
    }

    fun consumeExport() {
        _exportUri.value = null
    }

    /** Re-pick disk/root without re-pairing: onboarding reopens on the disk step. */
    fun changeRootFolder() = launch {
        // Switching disks/roots locks the vault: an open vault pointing at a
        // tree we just left would re-enter itself on the new Explorer.
        vaultSession.lock()
        settings.clearRoot()
    }

    /** Forgets the app token and the connection config; onboarding takes over. */
    fun resetPairing() {
        viewModelScope.launch(Dispatchers.IO) {
            vaultSession.lock()
            tokenStore.clear()
            sessions.dropSession()
            resolver.invalidate()
            settings.clearAll()
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
