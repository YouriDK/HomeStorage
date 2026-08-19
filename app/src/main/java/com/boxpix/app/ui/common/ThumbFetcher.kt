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

/** Bridges Coil to the StorageProvider-backed thumbnail pipeline. */
class ThumbFetcher(
    private val request: ThumbRequest,
    private val thumbnails: ThumbnailRepository,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bytes = thumbnails.thumbnail(
            request.displayPath,
            request.pathB64,
            allowGenerate = !request.isVideo,
        ) ?: return null
        return SourceResult(
            source = ImageSource(Buffer().write(bytes), options.context),
            mimeType = "image/webp",
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory @Inject constructor(
        private val thumbnails: ThumbnailRepository,
    ) : Fetcher.Factory<ThumbRequest> {
        override fun create(data: ThumbRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbFetcher(data, thumbnails, options)
    }
}
