package com.boxpix.app.ui.common

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.storage.StorageProvider
import okio.Buffer
import javax.inject.Inject

/** Full-resolution request for the viewer; Coil downsamples to the view size. */
data class HdRequest(
    val pathB64: String,
    val mtime: Long,
)

class HdKeyer @Inject constructor() : Keyer<HdRequest> {
    override fun key(data: HdRequest, options: Options): String =
        "hd:${data.pathB64}:${data.mtime}"
}

class HdFetcher(
    private val request: HdRequest,
    private val provider: StorageProvider,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bytes = when (val downloaded = provider.download(request.pathB64)) {
            is FbxResult.Ok -> downloaded.value
            is FbxResult.Err -> return null
        }
        return SourceResult(
            source = ImageSource(Buffer().write(bytes), options.context),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory @Inject constructor(
        private val provider: StorageProvider,
    ) : Fetcher.Factory<HdRequest> {
        override fun create(data: HdRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            HdFetcher(data, provider, options)
    }
}
