package org.sonora.music.ui

import org.sonora.music.i18n.tr

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.sonora.music.data.DiscoveryDao
import org.sonora.music.discovery.*
import org.sonora.music.domain.Track

data class HomeDiscoveryState(val seed: Track? = null, val recommendations: List<Recommendation> = emptyList(), val loading: Boolean = false, val notice: String? = null)
class HomeDiscoveryPresenter(private val repository: RecommendationSource, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(HomeDiscoveryState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var loadedAt = 0L
    private var generation = 0
    fun refresh(seed: Track?, force: Boolean = false) {
        if (seed == null) { generation++; job?.cancel(); mutable.value = HomeDiscoveryState(); return }
        if (!force && mutable.value.seed?.url == seed.url && (job?.isActive == true || System.currentTimeMillis() - loadedAt < 10 * 60000)) return
        generation++; val request = generation; job?.cancel()
        mutable.value = mutable.value.copy(seed = seed, loading = true, notice = null)
        job = scope.launch {
            try {
                val result = repository.recommend(seed, setOf(seed.url), listOf(seed), 12); ensureActive()
                if (request == generation) { loadedAt = System.currentTimeMillis(); mutable.value = HomeDiscoveryState(seed, result.recommendations, notice = result.notice) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (request == generation) mutable.value = mutable.value.copy(loading = false, notice = tr("Discovery is unavailable. Your library is still ready to play.")) }
        }
    }
    fun invalidate() { cancel(); loadedAt = 0; mutable.value = HomeDiscoveryState() }
    fun cancel() { generation++; job?.cancel(); mutable.value = mutable.value.copy(loading = false) }
}
data class CollectionState(val entry: CatalogEntry? = null, val tracks: List<Track> = emptyList(), val next: String? = null,
    val loading: Boolean = false, val error: String? = null)
class CollectionPresenter(private val source: MusicCatalogSource, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(CollectionState()); val state = mutable.asStateFlow()
    private var job: Job? = null; private var generation = 0; private val visited = mutableSetOf<String>()
    fun local(title: String, tracks: List<Track>) { close(); mutable.value = CollectionState(CatalogEntry("local:collection", title, tr("Your library"), tracks.firstOrNull()?.artwork.orEmpty(), CatalogKind.PLAYLISTS), tracks) }
    fun open(entry: CatalogEntry) { close(); mutable.value = CollectionState(entry = entry); load() }
    fun close() { generation++; job?.cancel(); visited.clear(); mutable.value = CollectionState() }
    fun load() {
        val before = mutable.value; val entry = before.entry ?: return
        if (before.loading || entry.url.startsWith("local:") || before.tracks.size >= 500) return
        val cursor = before.next
        if (cursor != null && !visited.add(cursor)) { mutable.value = before.copy(next = null, error = tr("The source repeated a page.")); return }
        val request = generation
        mutable.value = before.copy(loading = true, error = null)
        job = scope.launch {
            try {
                val page = source.collection(entry.url, cursor); ensureActive()
                if (request == generation) mutable.value = CollectionState(entry, (before.tracks + page.entries.mapNotNull { it.track }).distinctBy { it.url }.take(500), page.next, false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (request == generation) { cursor?.let(visited::remove); mutable.value = before.copy(error = e.message ?: tr("Could not load this collection.")) } }
        }
    }
}
