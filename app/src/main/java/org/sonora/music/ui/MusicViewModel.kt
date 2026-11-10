package org.sonora.music.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.sonora.music.SonoraApplication
import org.sonora.music.domain.Track
import org.sonora.music.playback.PlaybackConnection

@UnstableApi
class MusicViewModel(app: SonoraApplication) : ViewModel() {
    private val graph = app.graph
    val search = SearchPresenter(graph.source, viewModelScope)
    val playback = PlaybackConnection(app, viewModelScope, graph.playbackStatus, graph.streams)
    val playerError = graph.playbackStatus.error
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    val favorites = graph.library.favorites().catch { mutableMessage.value = "Could not read favorites."; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history = graph.library.history().catch { mutableMessage.value = "Could not read history."; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun favorite(track: Track) = viewModelScope.launch {
        try { graph.library.toggleFavorite(track) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessage.value = "Favorite could not be saved. Please try again." }
    }
    fun enqueue(track: Track) { playback.enqueue(track); mutableMessage.value = "Added to queue" }
    fun clearHistory() = viewModelScope.launch {
        try { graph.library.clearHistory() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessage.value = "History could not be cleared." }
    }
    fun dismissMessage() { mutableMessage.value = null }
    override fun onCleared() { playback.release() }
    class Factory(private val app: SonoraApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = MusicViewModel(app) as T
    }
}
