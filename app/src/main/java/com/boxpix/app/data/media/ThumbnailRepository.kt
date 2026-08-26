package com.boxpix.app.data.media

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.db.MediaDao
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.prefs.UiPrefsStore
import com.boxpix.app.data.storage.MirrorPaths
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageFolders
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.data.vault.VaultPaths
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serves thumbnails per the SPEC §5 contract: the /.thumbs/<mirror>.webp sidecar
 * is the fast path; a miss triggers on-demand generation (download original →
 * EXIF date + 512 px WebP → sidecar uploaded, index updated). Grid requests thus
 * generate visible thumbnails first, the reconciler backfills the rest.
 */
@Singleton
class ThumbnailRepository @Inject constructor(
    private val provider: StorageProvider,
    private val mediaDao: MediaDao,
    private val processor: MediaProcessor,
    private val folders: StorageFolders,
    private val env: StorageEnv,
) {

    private val inFlight = ConcurrentHashMap<String, Mutex>()

    /**
     * Sidecar bytes if present, otherwise generated on the spot (unless
     * [allowGenerate] is false — video posters are the worker's job). Null =
     * nothing to show.
     */
    suspend fun thumbnail(displayPath: String, pathB64: String, allowGenerate: Boolean = true): ByteArray? {
        // M8 invariant: vault media never produce a clear sidecar nor a Room row.
        // Their thumbnails are served from the vault's internal mirror instead.
        if (VaultPaths.isVaultPath(displayPath)) return null
        val sidecarPath = MirrorPaths.thumbPathFor(displayPath, provider.capabilities.canCreateAtRoot)
        val sidecar = provider.download(PathCodec.encode(sidecarPath)).getOrNull()
        if (sidecar != null && sidecar.isNotEmpty()) return sidecar
        if (!allowGenerate) return null
        return generate(displayPath, pathB64)
    }

    /**
     * Generates and stores the sidecar (idempotent, single-flight per file);
     * also reads the EXIF capture date — one download serves both. Returns the
     * thumbnail bytes, or null when the file cannot be processed.
     */
    suspend fun generate(displayPath: String, pathB64: String): ByteArray? {
        if (VaultPaths.isVaultPath(displayPath)) return null
        return inFlight.getOrPut(pathB64) { Mutex() }.withLock {
            try {
                val original = provider.download(pathB64).getOrNull() ?: return null
                val providerId = currentProviderId()

                processor.readTakenAtEpochSeconds(original)?.let { takenAt ->
                    mediaDao.setTakenAtFromExif(providerId, pathB64, takenAt)
                }

                val thumb = processor.makeThumbnail(original) ?: return null

                val sidecarPath = MirrorPaths.thumbPathFor(displayPath, provider.capabilities.canCreateAtRoot)
                val sidecarDir = sidecarPath.substringBeforeLast('/')
                val sidecarName = sidecarPath.substringAfterLast('/')
                folders.ensure(sidecarDir)?.let { return thumb } // thumb still usable without a sidecar
                val uploaded = provider.upload(PathCodec.encode(sidecarDir), sidecarName, thumb)
                if (uploaded is FbxResult.Ok) {
                    mediaDao.setHasThumb(providerId, pathB64, true)
                }
                thumb
            } finally {
                inFlight.remove(pathB64)
            }
        }
    }

    private suspend fun currentProviderId(): String =
        if (env.useFakeProvider.first()) TrashRepository.PROVIDER_FAKE else TrashRepository.PROVIDER_FREEBOX
}
