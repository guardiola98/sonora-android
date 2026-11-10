package org.sonora.music.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sonora.music.domain.MusicSource
import org.sonora.music.domain.Track

data class SearchState(val query: String = "", val tracks: List<Track> = emptyList(), val loading: Boolean = false,
    val searched: Boolean = false, val error: String? = null)
class SearchPresenter(private val source: MusicSource, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(SearchState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun search(query: String) {
        job?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) { mutable.value = SearchState(); return }
        mutable.value = SearchState(query = trimmed, loading = true)
        job = scope.launch {
            try {
                val tracks = source.search(trimmed)
                ensureActive()
                mutable.value = SearchState(trimmed, tracks, searched = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.value = SearchState(query = trimmed, searched = true, error = e.message ?: "Search failed. Try again.")
            }
        }
    }
}
