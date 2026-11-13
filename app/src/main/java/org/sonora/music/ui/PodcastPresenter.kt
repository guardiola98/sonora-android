package org.sonora.music.ui

import org.sonora.music.i18n.tr

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.sonora.music.domain.*
import org.sonora.music.data.*
import org.sonora.music.playback.PlaybackConnection

data class PodcastState(val query: String = "", val results: List<PodcastShow> = emptyList(), val searched: Boolean = false,
    val searching: Boolean = false, val searchError: String? = null, val show: SavedPodcast? = null,
    val episodes: List<SavedEpisode> = emptyList(), val loading: Boolean = false, val error: String? = null, val saving: Boolean = false)

@UnstableApi
class PodcastPresenter(private val source: PodcastSource, private val dao: PodcastDao,
    private val playback: PlaybackConnection, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(PodcastState())
    val state = mutable.asStateFlow()
    val subscriptions = dao.subscriptions().catch { mutable.update { it.copy(searchError = tr("Could not read podcast subscriptions.")) }; emit(emptyList()) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val progress = dao.progress().map { rows -> rows.associateBy { it.reference } }.catch { emit(emptyMap()) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    private var searchJob: Job? = null
    private var feedJob: Job? = null
    fun search(query: String) {
        searchJob?.cancel()
        mutable.update { it.copy(query = query.trim(), searching = true, searched = true, searchError = null, results = emptyList()) }
        searchJob = scope.launch {
            try { val results = source.search(query); mutable.update { it.copy(results = results, searching = false) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(searching = false, searchError = error(e)) } }
        }
    }
    fun open(feedUrl: String, refresh: Boolean = false) {
        feedJob?.cancel()
        val url = PodcastLinks.https(feedUrl)
        if (url == null) { mutable.update { it.copy(searchError = tr("Enter a public HTTPS RSS feed URL.")) }; return }
        val same = mutable.value.show?.feedUrl == url
        mutable.update { it.copy(show = if (same) it.show else SavedPodcast(url, tr("Loading podcast…"), "", "", ""),
            episodes = if (same) it.episodes else emptyList(), loading = true, error = null, saving = false) }
        feedJob = scope.launch {
            try {
                dao.find(url)?.let { saved -> val episodes = dao.episodes(url); mutable.update { it.copy(show = saved, episodes = episodes) } }
                val result = source.feed(url, refresh)
                val saved = dao.cache(result)
                val episodes = dao.episodes(url)
                mutable.update { it.copy(show = saved, episodes = episodes, loading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(loading = false, error = error(e) + if (it.episodes.isNotEmpty()) tr(" Showing saved episodes.") else "") } }
        }
    }
    fun close() { feedJob?.cancel(); mutable.update { it.copy(show = null, episodes = emptyList(), loading = false, error = null) } }
    fun subscribe() {
        val show = mutable.value.show ?: return
        if (mutable.value.loading || mutable.value.saving || (!show.subscribed && show.refreshedAt == 0L)) return
        mutable.update { it.copy(saving = true) }
        scope.launch {
            try {
                dao.subscribe(show.feedUrl, !show.subscribed)
                mutable.update { if (it.show?.feedUrl == show.feedUrl) it.copy(show = it.show.copy(subscribed = !show.subscribed), saving = false) else it }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(saving = false, error = tr("Subscription could not be saved. Try again.")) } }
        }
    }
    fun play(episode: SavedEpisode) { mutable.value.show?.let { playback.play(listOf(episode.track(it)), 0) } }
    fun markPlayed(episode: SavedEpisode) {
        val completed = progress.value[episode.reference]?.completed != true
        playback.markPodcastPlayed(episode.reference, episode.durationSeconds * 1000, completed) { success ->
            if (!success) mutable.update { it.copy(error = tr("Could not update this episode. Reopen the player and try again.")) }
        }
    }

    private fun error(e: Exception): String = when (e) {
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is javax.net.ssl.SSLException -> tr("Cannot reach the podcast provider. Check your connection and try again.")
        is org.xmlpull.v1.XmlPullParserException -> tr("This feed is not valid RSS XML. Check its address.")
        is IllegalArgumentException, is java.io.IOException -> e.message?.take(180) ?: tr("Podcast could not be loaded. Try again.")
        else -> tr("Podcast could not be loaded. Check the feed and try again.")
    }
}
