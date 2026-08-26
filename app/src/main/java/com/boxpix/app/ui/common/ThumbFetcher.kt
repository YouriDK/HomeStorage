package com.boxpix.app.ui.common

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import com.boxpix.app.data.media.ThumbnailRepository
import com.boxpix.app.data.vault.VaultPaths
import com.boxpix.app.data.vault.VaultSession
import com.boxpix.app.data.vault.VaultThumbnails
import okio.Buffer
import javax.inject.Inject

/** What a grid cell asks Coil for; mtime is part of the cache key (staleness). */
data class ThumbRequest(
    val pathB64: String,
    val displayPath: String,
    val mtime: Long,
    /** Videos are sidecar-only: generation is the worker's job (SPEC M7). */
    val isVideo: Boolean = false,
)

class ThumbKeyer @Inject constructor() : Keyer<ThumbRequest> {
    override fun key(data: ThumbRequest, options: Options): String =
        "thumb:${data.pathB64}:${data.mtime}"
}

/**
 * Bridges Coil to the StorageProvider-backed thumbnail pipeline. Vault media
 * route to the vault's internal encrypted mirror instead.
 *
 * Decrypted vault thumbnails never reach Coil's DISK cache: a custom fetcher's
 * SourceResult is decoded straight to a bitmap and only the bitmap enters the
 * MEMORY cache (Coil 2 writes its disk cache inside the HTTP fetcher only),
 * so a lock leaves no readable thumbnail behind on the device.
 */
class ThumbFetcher(
    private val request: ThumbRequest,
    private val thumbnails: ThumbnailRepository,
    private val vaultSession: VaultSession,
    private val vaultThumbnails: VaultThumbnails,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bytes = if (VaultPaths.isVaultPath(request.displayPath)) {
            // Vault: photos AND videos generate on demand — the phone extracts
            // video posters itself, the worker never enters the vault.
            vaultSession.mountDisplayPath
                ?.let { VaultPaths.vaultRelative(request.displayPath, it) }
                ?.let { vaultThumbnails.thumbnail(it, allowGenerate = true) }
        } else {
            thumbnails.thumbnail(
                request.displayPath,
                request.pathB64,
                allowGenerate = !request.isVideo,
            )
        } ?: return null
        return SourceResult(
            source = ImageSource(Buffer().write(bytes), options.context),
            mimeType = "image/webp",
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory @Inject constructor(
        private val thumbnails: ThumbnailRepository,
        private val vaultSession: VaultSession,
        private val vaultThumbnails: VaultThumbnails,
    ) : Fetcher.Factory<ThumbRequest> {
        override fun create(data: ThumbRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbFetcher(data, thumbnails, vaultSession, vaultThumbnails, options)
    }
}
