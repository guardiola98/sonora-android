package org.sonora.music.data

import org.sonora.music.i18n.tr

import kotlinx.coroutines.*
import org.sonora.music.domain.*
import java.util.concurrent.atomic.AtomicInteger

class ImportRepository(private val source: LibraryImportSource, private val library: LibraryDao) {
    suspend fun playlist(url: String, progress: (Int) -> Unit): ImportPreview {
        val canonical = YouTubeLinks.playlist(url)
            ?: throw IllegalArgumentException(tr("Paste a public or unlisted YouTube playlist link. For private playlists, Likes or mixes, use a Takeout ZIP/CSV export."))
        return ImportPreview(listOf(source.playlist(canonical, progress)))
    }
    suspend fun prepare(selections: List<ImportSelection>, enrich: Boolean, progress: (Int, Int) -> Unit): Pair<List<ImportSelection>, Int> = coroutineScope {
        val unique = selections.flatMap { it.collection.tracks }.associateBy { it.url }
        val resolved = mutableMapOf<String, Track>()
        var completed = 0
        val networkFailures = AtomicInteger()
        for (batch in unique.values.chunked(3)) {
            ensureActive()
            val results = batch.map { track -> async {
                val saved = library.find(track.url)?.track()
                val current = if (YouTubeLinks.needsDetails(track) && saved != null && !YouTubeLinks.needsDetails(saved)) saved else track
                if (!enrich || !YouTubeLinks.needsDetails(current) || networkFailures.get() >= 3) current
                else try { withTimeoutOrNull(20_000) { source.track(current.url) } ?: current.also { networkFailures.incrementAndGet() } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (e !is MusicSourceException || e.kind == MusicSourceException.Kind.NETWORK || e.kind == MusicSourceException.Kind.RESTRICTED) networkFailures.incrementAndGet()
                    current
                }
            } }.awaitAll()
            results.forEach { resolved[it.url] = it }
            completed += batch.size
            progress(completed, unique.size)
        }
        val updated = selections.map { it.copy(collection = it.collection.copy(tracks = it.collection.tracks.map { track -> resolved.getValue(track.url) })) }
        updated to resolved.values.count(YouTubeLinks::needsDetails)
    }
    suspend fun save(selections: List<ImportSelection>, unresolved: Int) =
        library.importCollections(selections, System.currentTimeMillis()).copy(unresolved = unresolved)
}
