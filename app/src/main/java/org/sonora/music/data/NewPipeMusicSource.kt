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
import org.sonora.music.discovery.*
import java.io.IOException
import java.util.concurrent.CancellationException

class NewPipeMusicSource : MusicSource, LibraryImportSource, MusicCatalogSource {
    private data class Continuation(val query: String, val filter: String, val page: Page, val collection: Boolean, val expires: Long)
    private val continuations = linkedMapOf<String, Continuation>()
    @Synchronized private fun token(query: String, filter: String, page: Page?, collection: Boolean = false): String? {
        if (!Page.isValid(page)) return null
        continuations.entries.removeAll { it.value.expires < System.currentTimeMillis() }
        fun signature(item: Page) = "${item.url}|${item.id}|${item.ids}|${item.body?.contentHashCode()}"
        continuations.entries.firstOrNull { it.value.query == query && it.value.filter == filter && it.value.collection == collection && signature(it.value.page) == signature(page!!) }?.let { return it.key }
        while (continuations.size >= 64) continuations.remove(continuations.keys.first())
        return java.util.UUID.randomUUID().toString().also { continuations[it] = Continuation(query, filter, page!!, collection, System.currentTimeMillis() + 600000) }
    }
    @Synchronized private fun continuation(token: String, query: String, collection: Boolean): Continuation =
        requireNotNull(continuations[token]?.takeIf { it.query == query && it.collection == collection && it.expires > System.currentTimeMillis() }) { "Search page expired. Search again." }
    private fun streamTrack(item: StreamInfoItem): Track? {
        if (item.streamType !in setOf(StreamType.VIDEO_STREAM, StreamType.AUDIO_STREAM)) return null
        val url = YouTubeLinks.track(item.url) ?: return null
        return Track(url, item.name, item.uploaderName.orEmpty(), item.thumbnails.maxByOrNull { it.height }?.url.orEmpty(), item.duration.coerceAtLeast(0))
    }
    private fun catalog(items: List<org.schabi.newpipe.extractor.InfoItem>, kind: CatalogKind, musical: Boolean): List<CatalogEntry> = items.mapNotNull { item ->
        when (item) {
            is StreamInfoItem -> streamTrack(item)?.let { CatalogEntry(it.url, it.title, it.artist, it.artwork, CatalogKind.SONGS, it, musical) }
            is org.schabi.newpipe.extractor.playlist.PlaylistInfoItem -> YouTubeLinks.playlist(item.url)?.let { url ->
                CatalogEntry(url, item.name, item.uploaderName.orEmpty(), item.thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
                    if (kind == CatalogKind.ALBUMS && musical) CatalogKind.ALBUMS else CatalogKind.PLAYLISTS, musical = musical) }
            is org.schabi.newpipe.extractor.channel.ChannelInfoItem -> CatalogEntry(item.url, item.name, "YouTube channel", item.thumbnails.maxByOrNull { it.height }?.url.orEmpty(), CatalogKind.CHANNELS)
            else -> null
        }
    }.distinctBy { it.url }
    override suspend fun searchPage(query: String, kind: CatalogKind, cursor: String?): CatalogPage {
        val text = query.trim().take(200)
        if (text.isBlank()) return CatalogPage(emptyList())
        val desired = when (kind) { CatalogKind.ARTISTS -> return CatalogPage(emptyList(), notice = "Artists shown here have verified MusicBrainz credits in your library. Unverified source names appear under Channels."); CatalogKind.SONGS -> "music_songs"; CatalogKind.ALBUMS -> "music_albums"; CatalogKind.PLAYLISTS -> "music_playlists"; CatalogKind.CHANNELS -> "channels" }
        suspend fun load(filter: String, notice: String? = null): CatalogPage = extract {
            val service = ServiceList.YouTube
            val next = cursor?.let { continuation(it, text, false) }
            val actual = next?.filter ?: filter
            val handler = service.searchQHFactory.fromQuery(text, listOf(actual), "")
            val items: List<org.schabi.newpipe.extractor.InfoItem>; val page: Page?
            if (next != null) { val result = SearchInfo.getMoreItems(service, handler, next.page); items = result.items; page = result.nextPage }
            else { val result = SearchInfo.getInfo(service, handler); items = result.relatedItems; page = result.nextPage }
            CatalogPage(catalog(items, kind, actual.startsWith("music_")), token(text, actual, page), notice)
        }
        return try { load(desired) } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (kind != CatalogKind.SONGS || cursor != null) throw e
            load("videos", "Music search is unavailable; showing ranked public video results. Credits may be channel names.")
        }
    }
    override suspend fun suggestions(query: String): List<String> = extract {
        if (query.trim().length < 2) emptyList() else ServiceList.YouTube.suggestionExtractor.suggestionList(query.trim().take(200)).take(8)
    }
    override suspend fun related(track: Track): List<Track> = extract {
        val extractor = ServiceList.YouTube.getStreamExtractor(track.url)
        extractor.fetchPage()
        extractor.relatedItems?.items.orEmpty().filterIsInstance<StreamInfoItem>().mapNotNull(::streamTrack).distinctBy { it.url }.take(40)
    }
    override suspend fun collection(url: String, cursor: String?): CatalogPage = extract {
        val canonical = requireNotNull(YouTubeLinks.playlist(url)) { "Unsupported public playlist URL." }
        val next = cursor?.let { continuation(it, canonical, true) }
        val items: List<StreamInfoItem>; val page: Page?
        if (next == null) { val info = PlaylistInfo.getInfo(ServiceList.YouTube, canonical); items = info.relatedItems; page = info.nextPage }
        else { val result = PlaylistInfo.getMoreItems(ServiceList.YouTube, canonical, next.page); items = result.items; page = result.nextPage }
        CatalogPage(catalog(items, CatalogKind.SONGS, true), token(canonical, "playlist", page, true))
    }

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
