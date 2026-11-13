package org.sonora.music.offline

import org.sonora.music.i18n.tr

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.sonora.music.domain.isPersistentTrack
import java.io.IOException

/** Report the stable reference to Media3 so signed/redirected audio URLs never enter its disk index. */
@UnstableApi
internal class PrivateStreamDataSource(private val upstream: DataSource, private val stream: Uri) : DataSource {
    private var reference: Uri? = null
    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)
    override fun open(dataSpec: DataSpec): Long {
        reference = dataSpec.uri
        return upstream.open(dataSpec.withUri(stream))
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = upstream.read(buffer, offset, length)
    override fun getUri(): Uri? = reference
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() { try { upstream.close() } finally { reference = null } }
}

/** One stream per transfer. A new task starts clean instead of joining bytes from different formats. */
@UnstableApi
internal class OfflineAudioDownloader(
    private val request: DownloadRequest,
    private val cache: Cache,
    private val upstream: DataSource.Factory,
    private val resolveFresh: suspend (String) -> String,
) : Downloader {
    @Volatile private var cancelled = false
    @Volatile private var delegate: ProgressiveDownloader? = null
    override fun download(progressListener: Downloader.ProgressListener?) {
        if (cancelled) throw InterruptedException()
        if (delegate == null) {
            require(isPersistentTrack(request.id)) { tr("Unsupported download reference") }
            // Incomplete bytes may belong to an expired URL or a different rendition after extraction.
            cache.removeResource(request.id)
            cache.applyContentMetadataMutations(request.id, ContentMetadataMutations().apply {
                ContentMetadataMutations.setContentLength(this, C.LENGTH_UNSET.toLong())
                ContentMetadataMutations.setRedirectedUri(this, null)
            })
            val stream = runBlocking(Dispatchers.IO) { resolveFresh(request.id) }
            if (cancelled) throw InterruptedException()
            val source = DataSource.Factory { PrivateStreamDataSource(upstream.createDataSource(), Uri.parse(stream)) }
            val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(source)
            delegate = ProgressiveDownloader(MediaItem.Builder().setUri(request.uri).setCustomCacheKey(request.id).build(), factory)
        }
        val downloader = checkNotNull(delegate)
        if (cancelled) { downloader.cancel(); throw InterruptedException() }
        try { downloader.download(progressListener) }
        catch (error: IOException) {
            val expired = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .any { it.responseCode in setOf(401, 403, 410) }
            if (expired) delegate = null // Retry resolves again and discards incompatible partial bytes.
            throw error
        }
    }
    override fun cancel() { cancelled = true; delegate?.cancel() }
    override fun remove() { cache.removeResource(request.id) }
}

@UnstableApi
internal fun completeAudio(cache: Cache, reference: String): Boolean {
    val length = ContentMetadata.getContentLength(cache.getContentMetadata(reference))
    return length > 0 && cache.isCached(reference, 0, length)
}

/** Completed downloads are read cache-only: no extractor, RSS refresh or network fallback. */
@UnstableApi
internal class OfflinePlaybackDataSource(
    private val cache: Cache,
    private val isDownloaded: (String) -> Boolean,
    private val online: DataSource.Factory,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var active: DataSource? = null
    override fun addTransferListener(listener: TransferListener) { listeners += listener; active?.addTransferListener(listener) }
    override fun open(dataSpec: DataSpec): Long {
        val reference = dataSpec.uri.toString()
        val offline = isDownloaded(reference)
        val source = if (offline) CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(null).createDataSource() else online.createDataSource()
        active = source
        listeners.forEach(source::addTransferListener)
        return source.open(if (offline) dataSpec.buildUpon().setKey(reference).build() else dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(active).read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
    override fun close() { try { active?.close() } finally { active = null } }
}
