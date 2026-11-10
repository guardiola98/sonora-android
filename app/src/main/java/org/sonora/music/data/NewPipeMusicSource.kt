package org.sonora.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.sonora.music.domain.*
import java.io.IOException
import java.util.concurrent.CancellationException

class NewPipeMusicSource : MusicSource {
    override suspend fun search(query: String): List<Track> = extract {
        if (query.isBlank()) return@extract emptyList()
        val service = ServiceList.YouTube
        val handler = service.searchQHFactory.fromQuery(query.trim(), listOf("videos"), "")
        SearchInfo.getInfo(service, handler).relatedItems.filterIsInstance<StreamInfoItem>()
            .filter { it.streamType == StreamType.VIDEO_STREAM || it.streamType == StreamType.AUDIO_STREAM }
            .map { Track(it.url, it.name, it.uploaderName.orEmpty(), it.thumbnails.maxByOrNull { image -> image.height }?.url.orEmpty(), it.duration.coerceAtLeast(0)) }
            .distinctBy { it.url }
    }

    override suspend fun resolve(url: String): AudioStream = extract {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        // Progressive audio only; no DRM, cookies, credentials, proxy or restriction overrides.
        val stream = info.audioStreams.filter { it.isUrl && it.deliveryMethod.name == "PROGRESSIVE_HTTP" }
            .maxByOrNull { it.averageBitrate }
            ?: throw MusicSourceException(MusicSourceException.Kind.UNAVAILABLE)
        AudioStream(stream.content)
    }

    private suspend fun <T> extract(block: () -> T): T = runInterruptible(Dispatchers.IO) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: MusicSourceException) { throw e }
        catch (e: ReCaptchaException) { throw MusicSourceException(MusicSourceException.Kind.RESTRICTED, e) }
        catch (e: ContentNotAvailableException) { throw MusicSourceException(MusicSourceException.Kind.UNAVAILABLE, e) }
        catch (e: IOException) { throw MusicSourceException(MusicSourceException.Kind.NETWORK, e) }
        catch (e: Exception) { throw MusicSourceException(MusicSourceException.Kind.EXTRACTION, e) }
    }
}
