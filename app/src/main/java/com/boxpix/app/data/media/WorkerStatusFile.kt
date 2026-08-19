package com.boxpix.app.data.media

import com.boxpix.app.data.db.WorkQueueDao
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.prefs.DeviceIdentity
import com.boxpix.app.data.storage.MirrorPaths
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageFolders
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.data.trash.TrashRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** SPEC §5 /.meta/worker-status.json — the worker's heartbeat on the shared disk. */
@Serializable
data class WorkerStatus(
    @SerialName("updated_at") val updatedAtEpochSeconds: Long,
    val device: String,
    @SerialName("cycles") val cycleCount: Int,
    @SerialName("pending_thumbs") val pendingThumbs: Int,
    @SerialName("pending_video_thumbs") val pendingVideoThumbs: Int,
    @SerialName("pending_xmp") val pendingXmp: Int,
)

@Singleton
class WorkerStatusFile @Inject constructor(
    private val provider: StorageProvider,
    private val folders: StorageFolders,
    private val rootLocator: RootLocator,
    private val queueDao: WorkQueueDao,
    private val deviceIdentity: DeviceIdentity,
    private val clock: Clock,
    private val json: Json,
) {

    suspend fun write(cycleCount: Int) {
        val metaDir = metaDir() ?: return
        folders.ensure(metaDir)?.let { return }
        val pid = TrashRepository.PROVIDER_FREEBOX
        val status = WorkerStatus(
            updatedAtEpochSeconds = clock.instant().epochSecond,
            device = deviceIdentity.get(),
            cycleCount = cycleCount,
            pendingThumbs = queueDao.pendingCountByType(pid, WorkQueueEntity.TYPE_THUMB).first(),
            pendingVideoThumbs = queueDao.pendingCountByType(pid, WorkQueueEntity.TYPE_VIDEO_THUMB).first(),
            pendingXmp = queueDao.pendingCountByType(pid, WorkQueueEntity.TYPE_XMP).first(),
        )
        provider.upload(
            PathCodec.encode(metaDir),
            FILE_NAME,
            json.encodeToString(WorkerStatus.serializer(), status).toByteArray(),
        )
    }

    /** For the daily phone's Settings card; null when absent or unreadable. */
    suspend fun read(): WorkerStatus? {
        val metaDir = metaDir() ?: return null
        return provider.download(PathCodec.encode("$metaDir/$FILE_NAME")).getOrNull()?.let { bytes ->
            runCatching {
                json.decodeFromString(WorkerStatus.serializer(), bytes.toString(Charsets.UTF_8))
            }.getOrNull()
        }
    }

    private suspend fun metaDir(): String? {
        val rootB64 = rootLocator.rootPathB64() ?: return null
        val rootDisplay = runCatching { PathCodec.decode(rootB64) }.getOrNull() ?: return null
        return MirrorPaths.appRootDirFor(rootDisplay, MirrorPaths.META_DIR, provider.capabilities.canCreateAtRoot)
    }

    companion object {
        const val FILE_NAME = "worker-status.json"
    }
}
