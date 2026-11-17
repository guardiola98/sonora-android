package org.sonora.music.importing

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.sonora.music.discovery.CatalogKind
import org.sonora.music.discovery.MusicCatalogSource
import org.sonora.music.domain.*
import org.sonora.music.i18n.tr
import java.util.concurrent.atomic.AtomicInteger

data class SpotifyImportState(
    val active: Boolean = false, val busy: Boolean = false, val committing: Boolean = false,
    val progress: String = "", val publicLink: Boolean = false, val error: String? = null,
    val entries: Int = 0, val matched: Int = 0, val skipped: Int = 0,
    val duplicates: Int = 0, val failedSearches: Int = 0, val summary: ImportSummary? = null
)

/** One action reads metadata, picks playable equivalents and atomically merges the library. */
class SpotifyImportPresenter(
    private val scope: CoroutineScope, private val catalog: MusicCatalogSource,
    private val save: suspend (List<ImportSelection>) -> ImportSummary
) {
    private val mutable = MutableStateFlow(SpotifyImportState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private data class SearchKey(val title: String, val artists: List<String>, val duration: Long)
    private fun RemoteSong.key() = SearchKey(title, artists, durationSeconds)
    private data class SearchResult(val chosen: Track?, val failed: Boolean = false)

    fun cancel() {
        // Room owns the final transaction; do not claim cancellation once saving starts.
        if (state.value.committing) return
        generation++; job?.cancel(); job = null; mutable.value = SpotifyImportState()
    }

    fun loadSpotifyExport(loader: suspend () -> List<ReviewedPlaylist>) = load(loader, false)
    fun loadSpotifyPublicPlaylist(loader: suspend () -> ReviewedPlaylist) = load({ listOf(loader()) }, true)

    private fun load(loader: suspend () -> List<ReviewedPlaylist>, publicLink: Boolean) {
        if (state.value.busy) return
        cancel()
        val session = generation
        mutable.value = SpotifyImportState(active = true, busy = true, publicLink = publicLink,
            progress = if (publicLink) tr("Reading public Spotify playlist…") else tr("Reading Spotify export on this device…"))
        job = scope.launch {
            try {
                val lists = SpotifyExportParser.combine(loader())
                ensureActive()
                if (lists.isEmpty()) throw ImportAccessException(tr("No playlists or saved songs found in this export."))
                val songs = lists.flatMap { it.songs }
                if (songs.size > 10_000) throw ImportAccessException(tr("Import up to 10,000 track entries at a time."))
                mutable.update { it.copy(entries = songs.size) }
                val unique = songs.filter { it.source.unavailable == null && it.chosen == null }
                    .map { it.source }.distinctBy { it.key() }
                // Bounded parallelism, one lookup per distinct song, and source order when saving.
                // Empty results do not stop later songs; long playlists have no 200-search cutoff.
                val next = AtomicInteger()
                val completed = AtomicInteger()
                mutable.update { it.copy(progress = tr("Finding songs · %1\$s / %2\$s", 0, unique.size)) }
                val results = coroutineScope {
                    List(minOf(3, unique.size)) {
                        async {
                            buildList {
                                while (true) {
                                    ensureActive()
                                    val song = unique.getOrNull(next.getAndIncrement()) ?: break
                                    add(song.key() to search(song))
                                    val count = completed.incrementAndGet()
                                    mutable.update { it.copy(progress = tr("Finding songs · %1\$s / %2\$s", count, unique.size)) }
                                }
                            }
                        }
                    }.awaitAll().flatten().toMap()
                }
                ensureActive()
                val groups = lists.map { list ->
                    val chosen = list.songs.mapNotNull { row ->
                        if (row.source.unavailable != null) null else row.chosen ?: results[row.source.key()]?.chosen
                    }
                    val tracks = chosen.distinctBy(Track::url)
                    ImportCollection("spotify:${list.source.id}", list.source.name, tracks,
                        favoritesHint = list.source.likes, skipped = list.songs.size - chosen.size,
                        duplicates = chosen.size - tracks.size)
                }
                val skipped = groups.sumOf { it.skipped }
                mutable.update { it.copy(matched = songs.size - skipped, skipped = skipped,
                    duplicates = groups.sumOf { group -> group.duplicates }, failedSearches = results.values.count { result -> result.failed }) }
                if (songs.isNotEmpty() && skipped == songs.size) throw ImportAccessException(
                    if (results.values.any { it.failed }) tr("Could not find playable songs. Check your connection and import again. Nothing was saved.")
                    else tr("No playable songs were found. Nothing was saved. Try another playlist or file."))
                if (session != generation) return@launch
                mutable.update { it.copy(committing = true, progress = tr("Saving your library…")) }
                val summary = save(groups.map { ImportSelection(it, playlist = !it.favoritesHint, favorites = it.favoritesHint) })
                mutable.update { it.copy(busy = false, committing = false, progress = "", summary = summary) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ensureActive()
                if (session == generation) mutable.update { it.copy(busy = false, committing = false, progress = "",
                    error = (e as? ImportAccessException)?.message ?: tr("Import could not finish. Check the link or file and try again.")) }
            }
        }
    }

    private suspend fun search(song: RemoteSong): SearchResult = try {
        val tracks = withTimeout(20_000) {
            catalog.searchPage("${song.artists.joinToString(" ")} ${song.title}", CatalogKind.SONGS).entries.mapNotNull { it.track }
        }
        SearchResult(SpotifyMatcher.match(song, tracks).chosen)
    } catch (_: TimeoutCancellationException) { SearchResult(null, failed = true) }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { SearchResult(null, failed = true) }
}
