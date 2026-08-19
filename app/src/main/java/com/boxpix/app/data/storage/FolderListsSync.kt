package com.boxpix.app.data.storage

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.db.ExcludedFolderDao
import com.boxpix.app.data.db.ExcludedFolderEntity
import com.boxpix.app.data.db.ProtectedFolderDao
import com.boxpix.app.data.db.ProtectedFolderEntity
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.prefs.DeviceIdentity
import com.boxpix.app.data.trash.TrashRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class FolderListsSnapshot(
    val version: Int = 1,
    @SerialName("updated_at") val updatedAtEpochSeconds: Long,
    val device: String,
    @SerialName("protected") val protectedPaths: List<String>,
    @SerialName("excluded") val excludedPaths: List<String>,
)

/**
 * SPEC §3 coordination-by-disk for the folder lists (V1 feedback): protected
 * and scan-excluded folders live in /.meta/folders.json so every client
 * applies the same rules. Room stays the local cache; each mutation exports
 * the full snapshot (debounced), each reconciler pass imports a snapshot
 * another device wrote since our last look. Last write wins — the lists are
 * short and rarely contested, so no conflict dialog here.
 */
@Singleton
class FolderListsSync @Inject constructor(
    private val provider: StorageProvider,
    private val folders: StorageFolders,
    private val rootLocator: RootLocator,
    private val protectedDao: ProtectedFolderDao,
    private val excludedDao: ExcludedFolderDao,
    private val env: StorageEnv,
    private val deviceIdentity: DeviceIdentity,
    private val clock: Clock,
    private val json: Json,
    private val scope: CoroutineScope,
) {

    @Volatile
    private var lastSeenStamp: Long = 0

    private var exportJob: Job? = null

    /** Debounced full-snapshot export — call after any protect/exclude mutation. */
    fun scheduleExport(debounceMs: Long = EXPORT_DEBOUNCE_MS) {
        exportJob?.cancel()
        exportJob = scope.launch {
            delay(debounceMs)
            exportNow()
        }
    }

    suspend fun exportNow() {
        val metaDir = metaDir() ?: return
        if (folders.ensure(metaDir) != null) return
        val pid = currentProviderId()
        val snapshot = FolderListsSnapshot(
            updatedAtEpochSeconds = clock.instant().epochSecond,
            device = deviceIdentity.get(),
            protectedPaths = protectedDao.snapshot(pid).map { it.displayPath }.sorted(),
            excludedPaths = excludedDao.snapshot(pid).map { it.displayPath }.sorted(),
        )
        val bytes = json.encodeToString(FolderListsSnapshot.serializer(), snapshot).toByteArray()
        if (provider.upload(PathCodec.encode(metaDir), FILE_NAME, bytes) is FbxResult.Ok) {
            lastSeenStamp = snapshot.updatedAtEpochSeconds
        }
    }

    /** Applies a snapshot another device exported since our last look. */
    suspend fun importIfNewer() {
        val metaDir = metaDir() ?: return
        val bytes = provider.download(PathCodec.encode("$metaDir/$FILE_NAME")).getOrNull() ?: return
        val snapshot = runCatching {
            json.decodeFromString(FolderListsSnapshot.serializer(), bytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return
        if (snapshot.updatedAtEpochSeconds <= lastSeenStamp) return
        lastSeenStamp = snapshot.updatedAtEpochSeconds
        if (snapshot.device == deviceIdentity.get()) return // our own export

        val pid = currentProviderId()
        protectedDao.clear(pid)
        snapshot.protectedPaths.forEach { path ->
            protectedDao.insert(
                ProtectedFolderEntity(providerId = pid, pathB64 = PathCodec.encode(path), displayPath = path),
            )
        }
        excludedDao.clear(pid)
        snapshot.excludedPaths.forEach { path ->
            excludedDao.insert(
                ExcludedFolderEntity(providerId = pid, pathB64 = PathCodec.encode(path), displayPath = path),
            )
        }
    }

    private suspend fun metaDir(): String? {
        val rootB64 = rootLocator.rootPathB64() ?: return null
        val rootDisplay = runCatching { PathCodec.decode(rootB64) }.getOrNull() ?: return null
        return MirrorPaths.appRootDirFor(rootDisplay, MirrorPaths.META_DIR, provider.capabilities.canCreateAtRoot)
    }

    private suspend fun currentProviderId(): String =
        if (env.useFakeProvider.first()) TrashRepository.PROVIDER_FAKE else TrashRepository.PROVIDER_FREEBOX

    companion object {
        const val FILE_NAME = "folders.json"
        private const val EXPORT_DEBOUNCE_MS = 2_000L
    }
}
