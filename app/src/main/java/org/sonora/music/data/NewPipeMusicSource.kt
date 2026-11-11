package org.sonora.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.sonora.music.domain.*
import java.io.IOException
import java.util.concurrent.CancellationException

class NewPipeMusicSource : MusicSource, LibraryImportSource {
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
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        // Public API only: do not extract unused video formats, subtitles or related metadata.
        // The external extractor retains responsibility for all source access/playability checks.
        // Progressive audio only; no DRM, cookies, credentials, proxy or restriction overrides.
        val stream = extractor.audioStreams.filter { it.isUrl && it.deliveryMethod.name == "PROGRESSIVE_HTTP" }
            .maxByOrNull { it.averageBitrate }
            ?: throw MusicSourceException(MusicSourceException.Kind.UNAVAILABLE)
        AudioStream(stream.content)
    }


    override suspend fun track(url: String): Track = extract {
        val canonical = YouTubeLinks.track(url) ?: throw IllegalArgumentException("Invalid YouTube track URL")
        val info = StreamInfo.getInfo(ServiceList.YouTube, canonical)
        Track(canonical, info.name, info.uploaderName.orEmpty(), info.thumbnails.maxByOrNull { it.height }?.url.orEmpty(), info.duration.coerceAtLeast(0))
    }

    override suspend fun playlist(url: String, progress: (Int) -> Unit): ImportCollection = extract {
        val canonical = YouTubeLinks.playlist(url) ?: throw IllegalArgumentException("A public or unlisted playlist URL is required.")
        val service = ServiceList.YouTube
        val info = PlaylistInfo.getInfo(service, canonical)
        val tracks = linkedMapOf<String, Track>()
        var skipped = 0; var duplicates = 0
        fun append(items: List<StreamInfoItem>) {
            items.forEach { item ->
                val trackUrl = YouTubeLinks.track(item.url)
                if (trackUrl == null || item.streamType !in setOf(StreamType.VIDEO_STREAM, StreamType.AUDIO_STREAM)) skipped++
                else {
                    val track = Track(trackUrl, item.name, item.uploaderName.orEmpty(), item.thumbnails.maxByOrNull { it.height }?.url.orEmpty(), item.duration.coerceAtLeast(0))
                    if (tracks.putIfAbsent(trackUrl, track) != null) duplicates++
                }
            }
            require(tracks.size <= 10_000) { "This playlist exceeds 10,000 tracks. Import smaller CSV files." }
            progress(tracks.size)
        }
        append(info.relatedItems)
        var next = info.nextPage
        val visited = mutableSetOf<String>()
        var pages = 0
        while (Page.isValid(next)) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("Import cancelled")
            val token = "${next.id}|${next.url}|${next.ids}|${next.body?.contentHashCode()}"
            check(visited.add(token) && ++pages <= 500) { "The source repeated or exceeded pagination limits. Nothing was imported; try a Takeout export." }
            val page = PlaylistInfo.getMoreItems(service, canonical, next)
            append(page.items)
            next = page.nextPage
        }
        require(tracks.isNotEmpty()) { "No accessible audio tracks in this playlist. Private lists and Likes can be imported from a Takeout export." }
        if (info.streamCount > tracks.size + duplicates + skipped) skipped += (info.streamCount - tracks.size - duplicates - skipped).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        ImportCollection("youtube:${canonical.substringAfter("list=")}", info.name, tracks.values.toList(), skipped = skipped, duplicates = duplicates)
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
