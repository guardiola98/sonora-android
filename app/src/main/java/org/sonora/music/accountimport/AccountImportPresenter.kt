package org.sonora.music.accountimport

import org.sonora.music.i18n.tr

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.sonora.music.discovery.CatalogKind
import org.sonora.music.discovery.MusicCatalogSource
import org.sonora.music.domain.*

/** UI state contains preview metadata only. Authorization, jobs and callbacks are never saved state. */
data class AccountImportState(val provider: ImportProvider? = null, val busy: Boolean = false, val authorizing: Boolean = false,
    val progress: String = "", val lists: List<RemotePlaylist> = emptyList(), val selected: Set<String> = emptySet(),
    val review: List<ReviewedPlaylist> = emptyList(), val error: String? = null, val skipPending: Boolean = false,
    val disconnected: Boolean = true, val transferred: Boolean = false,
    val fromFile: Boolean = false, val searchOnline: Boolean = true, val publicLink: Boolean = false) {
    val pending get() = review.sumOf { list -> list.songs.count { it.chosen == null } }
}
class AccountImportPresenter(private val scope: CoroutineScope, private val catalog: MusicCatalogSource,
    private val lookup: LibraryImportSource, private val source: (ImportProvider) -> AccountPlaylistSource,
    private val preview: (ImportPreview) -> Unit) {
    private val mutable = MutableStateFlow(AccountImportState())
    val state = mutable.asStateFlow()
    private var access: TemporaryAccess? = null
    private var job: Job? = null
    private var expiry: Job? = null
    private var generation = 0
    private var fileLists: Map<String, ReviewedPlaylist> = emptyMap()
    fun cancel() {
        generation++; job?.cancel(); job = null; expiry?.cancel(); expiry = null
        access?.close(); access = null; fileLists = emptyMap(); mutable.value = AccountImportState()
    }
    /** File metadata stays in memory and cannot trigger a provider connection. */
    fun loadSpotifyExport(loader: suspend () -> List<ReviewedPlaylist>) = loadSpotifyMetadata(loader, false)
    fun loadSpotifyPublicPlaylist(loader: suspend () -> ReviewedPlaylist) = loadSpotifyMetadata({ listOf(loader()) }, true)
    private fun loadSpotifyMetadata(loader: suspend () -> List<ReviewedPlaylist>, publicLink: Boolean) {
        cancel(); val session = generation
        mutable.value = AccountImportState(provider = ImportProvider.SPOTIFY, fromFile = true, publicLink = publicLink, busy = true,
            progress = if (publicLink) tr("Reading public Spotify playlist…") else tr("Reading Spotify export on this device…"))
        job = scope.launch {
            try {
                val lists = SpotifyExportParser.combine(loader())
                ensureActive()
                if (session != generation) return@launch
                if (lists.isEmpty()) throw ImportAccessException(tr("No playlists or saved songs found in this export."))
                fileLists = lists.associateBy { it.source.id }
                mutable.update { it.copy(busy = false, progress = "", lists = lists.map(ReviewedPlaylist::source)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (session == generation) fail(safeError(e)) }
        }
    }
    fun searchOnline(enabled: Boolean) { if (!state.value.busy) mutable.update { it.copy(searchOnline = enabled) } }
    /** A stale authorization result after cancellation must be destroyed, never adopted. */
    fun connect(provider: ImportProvider, likes: Boolean, authorize: suspend () -> TemporaryAccess) {
        cancel(); val session = generation
        mutable.value = AccountImportState(provider, busy = true, authorizing = true, progress = tr("Authorize in the provider's system screen…"))
        job = scope.launch {
            var returned: TemporaryAccess? = null
            try {
                val credential = authorize()
                returned = credential
                ensureActive()
                if (session != generation) return@launch
                access = credential
                mutable.update { it.copy(authorizing = false, disconnected = false, progress = tr("Reading accessible playlists…")) }
                val lists = collectAccountPages(2000) { source(provider).playlists(credential, it) }.distinctBy { it.id }
                val liked = if (likes) listOf(RemotePlaylist("likes", if (provider == ImportProvider.SPOTIFY) tr("Spotify liked songs") else tr("YouTube liked videos (not Music-only)"), null,
                    if (provider == ImportProvider.SPOTIFY) "https://open.spotify.com/collection/tracks" else "https://www.youtube.com/playlist?list=LL", likes = true)) else emptyList()
                ensureActive()
                mutable.update { it.copy(busy = false, lists = liked + lists, progress = "") }
                expiry = scope.launch { delay(10 * 60_000L); fail(tr("Import session timed out. Connect again; nothing was saved.")) }
            } catch (e: TimeoutCancellationException) { if (session == generation) fail(tr("Authorization timed out. Connect again when ready.")) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (session == generation) fail(safeError(e)) }
            finally { if (session != generation || !currentCoroutineContext().isActive || access !== returned) returned?.close() }
        }
    }
    private fun safeError(e: Exception) = (e as? ImportAccessException)?.message ?: tr("Import could not finish. Your connection was discarded; authorize again to retry.")
    private fun disconnect() { expiry?.cancel(); expiry = null; access?.close(); access = null; mutable.update { it.copy(disconnected = true, authorizing = false) } }
    private fun fail(message: String) { job?.cancel(); disconnect(); fileLists = emptyMap(); mutable.value = AccountImportState(error = message) }
    fun select(id: String) { if (!state.value.busy) mutable.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) } }
    fun skipPending(value: Boolean) { mutable.update { it.copy(skipPending = value) } }
    fun readSelected() {
        val initial = state.value
        val session = generation
        val credential = access
        if (credential == null && !initial.fromFile) return
        val provider = initial.provider ?: return
        val selected = initial.lists.filter { it.id in initial.selected }
        if (initial.busy || selected.isEmpty()) return
        mutable.update { it.copy(busy = true, error = null, progress = tr("Reading selected playlists…")) }
        job = scope.launch {
            try {
                var count = 0
                val imported = selected.map { list ->
                    mutable.update { it.copy(progress = tr("Reading %1\$s…", list.name)) }
                    if (initial.fromFile) fileLists.getValue(list.id)
                    else {
                        val songs = collectAccountPages(10_000 - count) { source(provider).songs(credential!!, list, it) }
                        count += songs.size
                        ReviewedPlaylist(list, songs.map(::ReviewedSong))
                    }
                }
                // No provider access is needed for matching or the confirmation screen.
                disconnect()
                fileLists = emptyMap()
                val cache = mutableMapOf<String, SpotifyMatcher.Result>(); var searched = 0; var emptySearches = 0
                val review = imported.map { list -> list.copy(songs = list.songs.map { row ->
                    ensureActive()
                    if (provider != ImportProvider.SPOTIFY || row.source.unavailable != null || !initial.searchOnline) row
                    else {
                        val song = row.source
                        val match = cache[song.id] ?: if (searched < 200 && emptySearches < 3) {
                            searched++; mutable.update { it.copy(progress = tr("Finding playable equivalents · %1\$s (up to 200 automatic searches)…", searched)) }
                            search(song).also { cache[song.id] = it; emptySearches = if (it.candidates.isEmpty()) emptySearches + 1 else 0 }; // Cache within this flow only.
                        } else SpotifyMatcher.Result(null, emptyList(), tr("Pending review: automatic search paused or limit reached. Search this entry individually."))
                        row.copy(candidates = match.candidates, chosen = match.chosen, reason = match.reason)
                    }
                }) }
                ensureActive()
                mutable.update { it.copy(busy = false, progress = "", review = review, lists = emptyList(), selected = emptySet()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(safeError(e)) }
            finally { if (session == generation) disconnect() }
        }
    }
    private suspend fun search(song: RemoteSong): SpotifyMatcher.Result {
        return try {
            delay(200)
            val tracks = withTimeout(20_000) { catalog.searchPage("${song.artists.joinToString(" ")} ${song.title}", CatalogKind.SONGS).entries.mapNotNull { it.track } }
            SpotifyMatcher.match(song, tracks)
        } catch (e: TimeoutCancellationException) { SpotifyMatcher.Result(null, emptyList(), tr("Pending review: search timed out. Try this entry again.")) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { SpotifyMatcher.Result(null, emptyList(), tr("Pending review: music search is unavailable. Try this entry again.")) }
    }
    fun choose(listId: String, index: Int, track: Track?) {
        if (state.value.busy) return
        updateRow(listId, index) { row -> if (track == null || track in row.candidates) row.copy(chosen = track, reason = if (track == null) tr("Pending review") else tr("Version selected by you")) else row }
    }
    private fun updateRow(listId: String, index: Int, change: (ReviewedSong) -> ReviewedSong) {
        mutable.update { it.copy(skipPending = false, review = it.review.map { list -> if (list.source.id == listId)
            list.copy(songs = list.songs.mapIndexed { i, row -> if (i == index) change(row) else row }) else list }) }
    }
    fun retry(listId: String, index: Int, manualUrl: String? = null) {
        if (state.value.busy) return
        val session = generation
        val row = state.value.review.find { it.source.id == listId }?.songs?.getOrNull(index) ?: return
        mutable.update { it.copy(busy = true, error = null, progress = tr("Looking up this entry…")) }
        job = scope.launch {
            try {
                if (manualUrl != null) {
                    val url = YouTubeLinks.track(manualUrl) ?: throw ImportAccessException(tr("Enter a valid YouTube video link."))
                    val track = withTimeout(20_000) { lookup.track(url) }
                    updateRow(listId, index) { it.copy(candidates = (listOf(track) + it.candidates).distinctBy(Track::url), chosen = null, reason = tr("Review the linked video, then choose Use this version.")) }
                } else {
                    val result = search(row.source)
                    updateRow(listId, index) { it.copy(candidates = result.candidates, chosen = result.chosen, reason = result.reason) }
                }
            } catch (e: TimeoutCancellationException) { mutable.update { it.copy(error = tr("Lookup timed out. Try again.")) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = (e as? ImportAccessException)?.message ?: tr("Lookup failed. Try another link.")) } }
            finally { if (session == generation) mutable.update { it.copy(busy = false, progress = "") } }
        }
    }
    fun confirm() {
        val initial = state.value
        if (initial.busy || (initial.pending > 0 && !initial.skipPending)) return
        val groups = initial.review.map { list -> ImportCollection(
            if (initial.provider == ImportProvider.YOUTUBE && !list.source.likes) "youtube:${list.source.id}"
            else "${initial.provider!!.name.lowercase()}:${list.source.id}", list.source.name,
            list.songs.mapNotNull { it.chosen }.distinctBy(Track::url), favoritesHint = list.source.likes,
            skipped = list.songs.count { it.chosen == null }, duplicates = list.songs.mapNotNull { it.chosen }.let { it.size - it.distinctBy(Track::url).size }) }
        if (groups.isEmpty()) return
        disconnect()
        fileLists = emptyMap()
        preview(ImportPreview(groups, allowEmptyCollections = true))
        mutable.value = AccountImportState(transferred = true)
    }
}
