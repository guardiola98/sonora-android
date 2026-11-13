package org.sonora.music.ui

import org.sonora.music.i18n.tr

import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.sonora.music.data.*
import kotlinx.coroutines.withContext
import org.sonora.music.domain.ImportPreview
import org.sonora.music.accountimport.*
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
import org.sonora.music.discovery.*

@UnstableApi
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MusicViewModel(private val app: SonoraApplication) : ViewModel() {
    private val graph = app.graph
    val updates = graph.updates
    val downloads = graph.downloads.state
    fun download(tracks: List<Track>) = viewModelScope.launch {
        try {
            for (batch in tracks.distinctBy { it.url }.chunked(20)) {
                graph.downloads.add(batch)
                kotlinx.coroutines.yield()
            }
            mutableMessage.value = tr("Added to Downloads. Manage progress in Library → Downloads.")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessage.value = tr("Some downloads could not be queued. Check storage and retry from Downloads.") }
    }
    fun removeDownload(reference: String) = downloadAction { graph.downloads.remove(reference) }
    fun pauseDownload(reference: String) = downloadAction { graph.downloads.pause(reference) }
    fun resumeDownload(reference: String) = downloadAction { graph.downloads.resume(reference) }
    fun downloadWifiOnly(value: Boolean) = downloadAction { graph.downloads.wifiOnly(value) }
    fun reconnectDownloads() = downloadAction { graph.downloads.reconnect() }
    private fun downloadAction(action: () -> Unit) {
        try { action() } catch (_: Exception) { mutableMessage.value = tr("Could not update downloads. Check storage and reopen Sonora to retry.") }
    }
    private val mutableMessage = MutableStateFlow<String?>(null)
    val search = SearchPresenter(graph.source, viewModelScope, graph.searchRepository)
    val playback = PlaybackConnection(app, viewModelScope, graph.playbackStatus, graph.streams, graph.podcastLibrary, graph.preparation)
    val homeDiscovery = HomeDiscoveryPresenter(graph.discovery, viewModelScope)
    val collection = CollectionPresenter(graph.source, viewModelScope)
    val discoveryOptions = graph.discoverySettings.state
    val radio = graph.radioStatus.state
    val searchHistory = graph.discoveryDao.searches().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val enrichedMetadata = graph.discoveryDao.metadata(System.currentTimeMillis()).map { rows -> rows.mapNotNull { row ->
        runCatching { MetadataCodec.decode(row.payload) }.getOrNull() }.associateBy { it.reference } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    fun discoveryOptions(value: DiscoveryOptions) { graph.discoverySettings.update(value); homeDiscovery.invalidate() }
    fun refreshHome(seed: Track?, force: Boolean = false) = homeDiscovery.refresh(seed, force)
    fun resetPreferences() = viewModelScope.launch {
        try { graph.discoverySettings.update(discoveryOptions.value.copy(resetAt = System.currentTimeMillis())); graph.discoveryDao.resetPreferences(); homeDiscovery.invalidate(); mutableMessage.value = tr("Learned preferences and hidden tracks reset. Your library is kept.") }
        catch (e: CancellationException) { throw e } catch (_: Exception) { mutableMessage.value = tr("Could not reset preferences.") }
    }
    fun clearSearches() = viewModelScope.launch { graph.discoveryDao.clearSearches() }
    fun notInterested(track: Track) = viewModelScope.launch {
        try { graph.discoveryDao.feedback(track.url, "hide"); playback.hideFromRadio(track); homeDiscovery.refresh(homeDiscovery.state.value.seed, true); mutableMessage.value = tr("Hidden from recommendations. Reset preferences to undo.") }
        catch (e: CancellationException) { throw e } catch (_: Exception) { mutableMessage.value = tr("Could not save this preference.") }
    }
    fun moreLike(track: Track) = viewModelScope.launch {
        try { graph.discoveryDao.feedback(track.url, "more"); playback.retryRadio(); homeDiscovery.refresh(track, true); mutableMessage.value = tr("Preference saved. Updating recommendations.") }
        catch (e: CancellationException) { throw e } catch (_: Exception) { mutableMessage.value = tr("Could not save this preference.") }
    }
    fun removeQueue(index: Int) { playback.state.value.queue.getOrNull(index)?.let { graph.feedback(it, "remove") }; playback.remove(index) }
    fun saveCollection() = viewModelScope.launch {
        val current = collection.state.value
        try { if (current.tracks.isNotEmpty()) { graph.library.createPlaylistWithTracks(current.entry?.title ?: tr("Saved collection"), current.tracks); mutableMessage.value = tr("Saved %1\$s loaded tracks as a playlist", current.tracks.size) } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { mutableMessage.value = tr("Could not save this collection.") }
    }
    val preparationEnabled = graph.preparation.enabled
    fun setPreparationEnabled(enabled: Boolean) = graph.preparation.setEnabled(enabled)
    fun prepareBrowsing(tracks: List<Track>) = graph.preparation.browse(tracks.map { it.url })
    fun stopBrowsingPreparation() = graph.preparation.cancelBrowse()
    val playerError = graph.playbackStatus.error
    val podcasts = PodcastPresenter(graph.podcasts, graph.podcastLibrary, playback, viewModelScope)
    val importer = ImportPresenter(graph.importer, viewModelScope)
    private val publicSpotify = PublicSpotifyPlaylist()
    fun importPublicPlaylist(link: String) {
        if (PublicSpotifyPlaylist.accepts(link)) accountImporter.loadSpotifyPublicPlaylist { publicSpotify.read(link) }
        else if (org.sonora.music.domain.YouTubeLinks.playlist(link) != null) importer.loadPlaylist(link)
        else importer.load { throw IllegalArgumentException(tr("Paste a public Spotify or YouTube Music playlist link.")) }
    }
    private val accountHttp = AccountHttp()
    val spotifyAuthorization = SpotifyAuthorization(accountHttp)
    val googleImportAuthorization = GoogleImportAuthorization(org.sonora.music.BuildConfig.GOOGLE_CLIENT_ID, org.sonora.music.BuildConfig.GOOGLE_REDIRECT_URI) { code, verifier -> accountHttp.googleToken(org.sonora.music.BuildConfig.GOOGLE_TOKEN_ENDPOINT, code, verifier) }
    val accountImporter = AccountImportPresenter(viewModelScope, graph.source, graph.source,
        { provider -> if (provider == ImportProvider.SPOTIFY) SpotifyPlaylistSource(accountHttp) else YouTubePlaylistSource(accountHttp) },
        { preview -> importer.load { preview } })
    val playlists = graph.library.playlists().catch { mutableMessage.value = tr("Could not read playlists."); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val selection = MutableStateFlow<Long?>(null)
    val selectedPlaylist = selection.asStateFlow()
    val playlistTracks = selection.flatMapLatest { id -> if (id == null) flowOf(null to emptyList<org.sonora.music.data.LibraryTrack>()) else graph.library.playlistTracks(id).map { id to it } }
        .catch { mutableMessage.value = tr("Could not read playlist tracks."); emit(null to emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to emptyList())
    fun selectPlaylist(id: Long?) { selection.value = id }
    fun deletePlaylist(id: Long) = viewModelScope.launch {
        try { graph.library.deletePlaylist(id); if (selection.value == id) selection.value = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessage.value = tr("Could not delete this playlist.") }
    }
    private val mutableBackup = MutableStateFlow(BackupState())
    val backup = mutableBackup.asStateFlow()
    fun exportBackup(uri: Uri) {
        if (mutableBackup.value.busy) return
        mutableBackup.value = BackupState(busy = true)
        viewModelScope.launch {
            try {
                val snapshot = graph.library.exportBackup()
                withContext(Dispatchers.IO) {
                    val bytes = BackupCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
                    app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                        ?: error(tr("No writable document"))
                }
                mutableBackup.value = BackupState(message = tr("Backup saved: %1\$s tracks and %2\$s playlists, %3\$s podcast subscriptions and %4\$s episode positions.", snapshot.tracks.size, snapshot.playlists.size, snapshot.podcasts.size, snapshot.progress.size))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableBackup.value = BackupState(error = tr("Backup could not be saved. Check available storage and choose another location. Delete any incomplete file.")) }
        }
    }
    fun previewBackup(uri: Uri) {
        if (mutableBackup.value.busy) return
        mutableBackup.value = BackupState(busy = true)
        viewModelScope.launch {
            try {
                val preview = runInterruptible(Dispatchers.IO) { app.contentResolver.openInputStream(uri)?.use(BackupCodec::read) ?: error(tr("No readable document")) }
                mutableBackup.value = BackupState(preview = preview)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableBackup.value = BackupState(error = tr("This is not a supported Sonora backup, is incomplete, or exceeds the import limits. Your library was not changed.")) }
        }
    }
    fun dismissBackupPreview() { if (!mutableBackup.value.busy) mutableBackup.value = BackupState() }
    fun restoreBackup() {
        val initial = mutableBackup.value
        val preview = initial.preview ?: return
        if (initial.busy) return
        mutableBackup.value = initial.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = graph.library.restoreBackup(preview)
                mutableBackup.value = BackupState(message = tr("Restored %1\$s new tracks, %2\$s favorites and %3\$s playlists (%4\$s new playlist entries), plus podcast subscriptions and progress.", result.tracks, result.favorites, result.playlists, result.entries))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableBackup.value = initial.copy(error = tr("Restore failed. No partial changes were saved. Check storage and retry.")) }
        }
    }
    fun createPlaylist(name: String, track: Track? = null, onCreated: (Long) -> Unit = {}) = viewModelScope.launch {
        try {
            val id = graph.library.createPlaylistWithTracks(name, listOfNotNull(track))
            mutableMessage.value = if (track == null) tr("Playlist created") else tr("Playlist created and song added")
            onCreated(id)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessage.value = tr("Could not create playlist. Use a name of 1–180 characters and check storage.") }
    }
    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch {
        try { graph.library.renamePlaylist(id, name) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessage.value = tr("Could not rename playlist.") }
    }
    fun addToPlaylist(id: Long, track: Track, done: () -> Unit) = viewModelScope.launch {
        try { graph.library.addToPlaylist(id, listOf(track)); mutableMessage.value = tr("Saved to playlist"); done() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessage.value = tr("Could not add song to playlist.") }
    }
    fun removeFromPlaylist(id: Long, url: String) = viewModelScope.launch {
        try { graph.library.removeFromPlaylist(id, url) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessage.value = tr("Could not remove song from playlist.") }
    }
    fun reorderPlaylist(id: Long, order: List<String>, done: (Boolean) -> Unit) = viewModelScope.launch {
        try { graph.library.reorderPlaylist(id, order); done(true) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessage.value = tr("Could not save playlist order."); done(false) }
    }
    fun importSpotifyFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        accountImporter.loadSpotifyExport {
            runInterruptible(Dispatchers.IO) {
                if (uris.size > 20) throw ImportAccessException(tr("Select up to 20 playlist/library files at a time."))
                val result = mutableListOf<ReviewedPlaylist>()
                for (uri in uris) {
                    val resolver = app.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "export.json"
                    val parsed = resolver.openInputStream(uri)?.use { SpotifyExportParser().read(name, it) }
                        ?: throw ImportAccessException(tr("Could not open the export. Download it to this device first."))
                    result.addAll(parsed)
                    if (result.sumOf { it.songs.size } > 10_000) throw ImportAccessException(tr("Import up to 10,000 entries at a time."))
                }
                SpotifyExportParser.combine(result)
            }
        }
    }
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importer.load {
            runInterruptible(Dispatchers.IO) {
                require(uris.size <= 20) { tr("Select up to 20 files at a time.") }
                val collections = mutableListOf<org.sonora.music.domain.ImportCollection>()
                var ignored = 0
                for (uri in uris) {
                    val resolver = app.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment ?: "export.csv"
                    val parsed = resolver.openInputStream(uri)?.use { TakeoutParser().read(name, it) }
                        ?: throw IllegalArgumentException(tr("Could not open %1\$s. Download it to your device and retry.", name))
                    collections.addAll(parsed.collections); ignored += parsed.ignoredFiles
                    require(collections.sumOf { it.tracks.size } <= 10_000) { tr("Import up to 10,000 track entries at a time.") }
                }
                ImportPreview(collections, ignored)
            }
        }
    }
    val message = mutableMessage.asStateFlow()
    val favorites = graph.library.favorites().catch { mutableMessage.value = tr("Could not read favorites."); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history = graph.library.history().catch { mutableMessage.value = tr("Could not read history."); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun favorite(track: Track) = viewModelScope.launch {
        try { graph.library.toggleFavorite(track) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessage.value = tr("Favorite could not be saved. Please try again.") }
    }
    fun enqueue(track: Track) { playback.enqueue(track); mutableMessage.value = tr("Added to queue") }
    fun clearHistory() = viewModelScope.launch {
        try { graph.library.clearHistory() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessage.value = tr("History could not be cleared.") }
    }
    fun dismissMessage() { mutableMessage.value = null }
    override fun onCleared() { accountImporter.cancel(); playback.release() }
    class Factory(private val app: SonoraApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = MusicViewModel(app) as T
    }
}
