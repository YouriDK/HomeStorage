package com.boxpix.app.data.vault

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.media.MediaProcessor
import com.boxpix.app.data.media.MediaTypes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Thumbnails for vault media, served from the vault's INTERNAL `/.thumbs/`
 * mirror — encrypted like everything else, generated on demand for visible
 * cells only, and only while unlocked. No WorkManager, no work_queue row
 * (M8 Room invariant): the worker never sees the vault — vault VIDEO posters
 * are extracted by the phone itself through the decrypting random-access
 * handle.
 *
 * Same contract as the clear pipeline: one download of the original serves
 * both the 512 px WebP sidecar and the EXIF capture date (kept in the
 * in-vault index, manual dates never overwritten).
 */
class VaultThumbnails(
    private val session: VaultSession,
    private val meta: VaultMetaRepository,
    private val processor: MediaProcessor,
    private val frameExtractor: VaultFrameExtractor,
) {

    private val inFlight = ConcurrentHashMap<String, Mutex>()

    suspend fun thumbnail(relativePath: String, allowGenerate: Boolean): ByteArray? {
        val provider = session.provider ?: return null
        val sidecar = sidecarPathFor(relativePath)
        provider.download(PathCodec.encode(sidecar)).getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        if (!allowGenerate) return null
        return generate(provider, relativePath, sidecar)
    }

    private suspend fun generate(
        provider: CryptomatorProvider,
        relativePath: String,
        sidecarPath: String,
    ): ByteArray? = inFlight.getOrPut(relativePath) { Mutex() }.withLock {
        try {
            val thumb = if (MediaTypes.isVideo(relativePath.substringAfterLast('/'))) {
                // Vault videos: the PHONE extracts the poster (owner's decision —
                // the worker never enters the vault). Chunk-aligned decrypting
                // reads, no full download.
                val handle = provider.openFile(PathCodec.encode(relativePath)).getOrNull()
                    ?: return null
                val extraction = frameExtractor.extract(handle) ?: return null
                extraction.durationSeconds?.let { duration ->
                    meta.updateEntry(relativePath) { it.copy(durationSeconds = duration) }
                }
                extraction.thumbWebp
            } else {
                val original = provider.download(PathCodec.encode(relativePath)).getOrNull()
                    ?: return null
                processor.readTakenAtEpochSeconds(original)?.let { takenAt ->
                    meta.updateEntry(relativePath) { entry ->
                        if (entry.takenAtManual || entry.takenAtEpochSeconds != null) entry
                        else entry.copy(takenAtEpochSeconds = takenAt)
                    }
                }
                processor.makeThumbnail(original) ?: return null
            }

            val sidecarDir = sidecarPath.substringBeforeLast('/')
            mkdirs(provider, sidecarDir)
            val uploaded = provider.upload(
                PathCodec.encode(sidecarDir),
                sidecarPath.substringAfterLast('/'),
                thumb,
            )
            if (uploaded is FbxResult.Ok) {
                meta.updateEntry(relativePath) { it.copy(hasThumb = true) }
            }
            thumb
        } finally {
            inFlight.remove(relativePath)
        }
    }

    private fun sidecarPathFor(relativePath: String): String {
        val parent = relativePath.substringBeforeLast('/').ifEmpty { "" }
        val name = relativePath.substringAfterLast('/')
        return "/${VaultMetaRepository.THUMBS_DIR}$parent/$name.webp"
    }

    private suspend fun mkdirs(provider: CryptomatorProvider, displayPath: String) {
        var parent = "/"
        displayPath.trimStart('/').split('/').filter { it.isNotEmpty() }.forEach { segment ->
            provider.mkdir(PathCodec.encode(parent), segment) // conflict = already there
            parent = if (parent == "/") "/$segment" else "$parent/$segment"
        }
    }
}
