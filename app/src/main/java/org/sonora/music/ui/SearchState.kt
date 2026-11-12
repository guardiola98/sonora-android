package org.sonora.music.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sonora.music.domain.MusicSource
import org.sonora.music.domain.Track
import org.sonora.music.discovery.*

data class SearchState(val query: String = "", val tracks: List<Track> = emptyList(), val loading: Boolean = false,
    val searched: Boolean = false, val error: String? = null, val kind: CatalogKind = CatalogKind.SONGS,
    val ranked: List<SearchRanker.Ranked> = emptyList(), val suggestions: List<String> = emptyList(),
    val next: String? = null, val loadingMore: Boolean = false, val allVersions: Boolean = false, val hiddenVersions: Int = 0,
    val notice: String? = null)
class SearchPresenter(private val source: MusicSource, private val scope: CoroutineScope, private val repository: SearchDataSource? = null) {
    private val mutable = MutableStateFlow(SearchState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var suggestionJob: Job? = null
    private var pageJob: Job? = null
    private var generation = 0
    private var raw = emptyList<CatalogEntry>()
    private var localIds = emptySet<String>()
    private val ranker = SearchRanker()
    private val visited = mutableSetOf<String>()
    fun edit(query: String) {
        cancel(); val request = generation
        mutable.value = mutable.value.copy(query = query.take(200), suggestions = emptyList(), error = null, loading = false, loadingMore = false, next = null)
        if (query.trim().length < 2) { raw = emptyList(); publish(); mutable.value = mutable.value.copy(searched = false, loading = false, next = null); return }
        suggestionJob = scope.launch {
            delay(350)
            try { val suggestions = repository?.suggestions(query).orEmpty(); if (generation == request) mutable.value = mutable.value.copy(suggestions = suggestions) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* Local search history remains available. */ }
        }
        job = scope.launch { delay(450); perform(query, request, false) }
    }
    private fun cancel() { generation++; job?.cancel(); suggestionJob?.cancel(); pageJob?.cancel(); visited.clear() }
    fun search(query: String, kind: CatalogKind = mutable.value.kind) {
        mutable.value = mutable.value.copy(kind = kind)
        cancel(); val request = generation
        if (query.isBlank()) { raw = emptyList(); mutable.value = SearchState(kind = mutable.value.kind); return }
        job = scope.launch { perform(query, request, true) }
    }
    fun select(kind: CatalogKind) = search(mutable.value.query, kind)
    fun songsBy(artist: String) = search(artist, CatalogKind.SONGS)
    fun showVersions(show: Boolean) { mutable.value = mutable.value.copy(allVersions = show); publish() }
    private suspend fun perform(query: String, request: Int, remember: Boolean) {
        val text = query.trim().take(200); val kind = mutable.value.kind
        raw = emptyList(); localIds = emptySet()
        mutable.value = SearchState(query = text, loading = true, kind = kind, allVersions = mutable.value.allVersions)
        try {
            if (repository == null) {
                val tracks = source.search(text); currentCoroutineContext().ensureActive()
                if (generation == request) mutable.value = SearchState(text, tracks, searched = true)
                return
            }
            val local = repository.local(text, kind); currentCoroutineContext().ensureActive()
            if (generation != request) return
            raw = local; localIds = local.map { it.url }.toSet(); publish()
            val page = repository.remote(text, kind, null); currentCoroutineContext().ensureActive()
            if (generation != request) return
            raw = local + page.entries
            mutable.value = mutable.value.copy(loading = false, searched = true, next = page.next, notice = page.notice)
            publish()
            if (remember) repository.remember(text)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (generation == request) mutable.value = mutable.value.copy(loading = false, searched = true, error = e.message ?: "Search failed. Local results remain available.")
        }
    }
    private fun publish() {
        val state = mutable.value
        val ranked = ranker.rank(state.query, raw, localIds, state.allVersions)
        mutable.value = state.copy(ranked = ranked, tracks = ranked.mapNotNull { it.entry.track }, hiddenVersions = raw.distinctBy { it.url }.size - ranked.size)
    }
    fun loadMore() {
        val state = mutable.value; val cursor = state.next ?: return
        if (state.loading || state.loadingMore || repository == null || raw.size >= 500) return
        val request = generation
        if (cursor in visited) { mutable.value = state.copy(next = null, notice = "The source repeated a page. Search again to refresh."); return }
        mutable.value = state.copy(loadingMore = true, error = null)
        pageJob = scope.launch {
            try {
                val page = repository.remote(state.query, state.kind, cursor); ensureActive()
                if (generation != request) return@launch
                visited += cursor; raw = (raw + page.entries).distinctBy { it.url }.take(500)
                mutable.value = mutable.value.copy(loadingMore = false, next = page.next.takeIf { raw.size < 500 }, notice = page.notice ?: mutable.value.notice)
                publish()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == request) mutable.value = mutable.value.copy(loadingMore = false, error = e.message ?: "Could not load the next page. Retry.") }
        }
    }
}
