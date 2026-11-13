package org.sonora.music.offline

import org.sonora.music.i18n.tr

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.Requirements
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.sonora.music.data.BackupCodec
import org.sonora.music.data.ExtractorDownloader
import org.sonora.music.data.StreamResolver
import org.sonora.music.domain.Track
import org.sonora.music.domain.isPersistentTrack
import java.io.File

@UnstableApi
data class OfflineEntry(val track: Track, val state: Int, val bytes: Long, val percent: Float, val ready: Boolean, val stopReason: Int) {
    val busy get() = state in setOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING, Download.STATE_REMOVING)
    val status: String get() = when {
        ready -> tr("Downloaded")
        state == Download.STATE_COMPLETED -> tr("File missing · download again")
        state == Download.STATE_FAILED -> tr("Download failed · retry")
        state == Download.STATE_STOPPED -> if (stopReason == 2) tr("Paused by Android · resume") else tr("Paused")
        state == Download.STATE_REMOVING -> tr("Removing…")
        state == Download.STATE_DOWNLOADING -> if (percent >= 0) tr("Downloading · %1\$s%%", percent.toInt()) else tr("Downloading…")
        else -> tr("Queued · waiting for network or a free slot")
    }
}
@UnstableApi
data class OfflineState(val items: List<OfflineEntry> = emptyList(), val wifiOnly: Boolean = true, val waitingForNetwork: Boolean = false, val error: String? = null)

/** Separate Media3 index/cache: no destructive migration of playlists, favourites or podcast progress. */
@UnstableApi
class OfflineRepository(private val context: Context, streams: StreamResolver) {
    private val preferences = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val provider = StandaloneDatabaseProvider(context)
    private val cache = SimpleCache(File(context.filesDir, "offline-audio"), NoOpCacheEvictor(), provider)
    private val index = DefaultDownloadIndex(provider, "sonora_offline")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var completed: Set<String> = emptySet()
    @Volatile private var indexLoaded = false
    private val mutable = MutableStateFlow(OfflineState(wifiOnly = preferences.getBoolean("wifi_only", true)))
    val state = mutable.asStateFlow()
    private val http = DefaultHttpDataSource.Factory().setUserAgent(ExtractorDownloader.USER_AGENT)
        .setConnectTimeoutMs(15_000).setReadTimeoutMs(25_000)
    val manager = DownloadManager(context, index, DownloaderFactory { request ->
        OfflineAudioDownloader(request, cache, http) { reference -> streams.invalidate(reference); streams.resolve(reference).url }
    }).apply {
        maxParallelDownloads = 2
        minRetryCount = 3
        requirements = networkRequirements(mutable.value.wifiOnly)
    }
    private var refreshJob: Job? = null
    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) { refresh() }
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                if (download.state != Download.STATE_COMPLETED) completed = completed - download.request.id
                refresh()
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) { completed = completed - download.request.id; refresh() }
            override fun onRequirementsStateChanged(downloadManager: DownloadManager, requirements: Requirements, notMetRequirements: Int) { refresh() }
        })
        scope.launch {
            while (isActive) {
                if (mutable.subscriptionCount.value > 0 || manager.currentDownloads.isNotEmpty()) refreshNow()
                delay(1_000)
            }
        }
        refresh()
    }
    private fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch { refreshNow() }
    }
    private suspend fun refreshNow() {
        val current = manager.currentDownloads.associateBy { it.request.id }
        try {
            val rows = withContext(Dispatchers.IO) {
                index.getDownloads().use { cursor -> buildList {
                    while (cursor.moveToNext()) {
                        val download = current[cursor.download.request.id] ?: cursor.download
                        val track = runCatching { BackupCodec.parseTrack(JSONObject(String(download.request.data, Charsets.UTF_8))) }.getOrNull() ?: continue
                        val ready = download.state == Download.STATE_COMPLETED && completeAudio(cache, download.request.id)
                        add(OfflineEntry(track, download.state, download.bytesDownloaded, download.percentDownloaded, ready, download.stopReason))
                    }
                } }
            }
            completed = rows.filter { it.ready }.map { it.track.url }.toSet()
            indexLoaded = true
            mutable.value = OfflineState(rows, preferences.getBoolean("wifi_only", true), manager.notMetRequirements != 0)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(error = tr("Downloads could not be read. Check available storage and reopen Sonora.")) }
    }
    fun add(tracks: List<Track>) {
        val unique = tracks.distinctBy { it.url }
        require(unique.all { isPersistentTrack(it.url) }) { tr("Only supported music and podcast episodes can be downloaded.") }
        if (unique.isEmpty()) return
        // A dataSync timeout pauses the manager as well as individual requests. A new
        // explicit action can resume the manager; other items retain their stop reasons.
        DownloadService.sendResumeDownloads(context, AudioDownloadService::class.java, false)
        unique.forEach { track ->
            if (mutable.value.items.any { it.track.url == track.url && (it.ready || it.busy) }) return@forEach
            val request = DownloadRequest.Builder(track.url, Uri.parse(track.url)).setCustomCacheKey(track.url)
                .setData(BackupCodec.trackJson(track).toString().toByteArray(Charsets.UTF_8)).build()
            DownloadService.sendAddDownload(context, AudioDownloadService::class.java, request, false)
        }
    }
    fun remove(reference: String) = DownloadService.sendRemoveDownload(context, AudioDownloadService::class.java, reference, false)
    fun pause(reference: String) = DownloadService.sendSetStopReason(context, AudioDownloadService::class.java, reference, 1, false)
    fun resume(reference: String) {
        DownloadService.sendSetStopReason(context, AudioDownloadService::class.java, reference, Download.STOP_REASON_NONE, false)
        DownloadService.sendResumeDownloads(context, AudioDownloadService::class.java, false)
    }
    fun wifiOnly(value: Boolean) {
        preferences.edit().putBoolean("wifi_only", value).apply()
        DownloadService.sendSetRequirements(context, AudioDownloadService::class.java, networkRequirements(value), false)
        mutable.value = mutable.value.copy(wifiOnly = value)
    }
    fun reconnect() { DownloadService.start(context, AudioDownloadService::class.java) }
    fun hasDownloaded(reference: String) = completed.contains(reference) || !indexLoaded &&
        runCatching { index.getDownload(reference)?.state == Download.STATE_COMPLETED }.getOrDefault(false)
    fun playbackFactory(online: DataSource.Factory) = DataSource.Factory {
        OfflinePlaybackDataSource(cache, { reference ->
            // This runs on ExoPlayer's loader thread, including immediately after a process restart.
            val saved = index.getDownload(reference)
            saved?.state == Download.STATE_COMPLETED
        }, online)
    }
    companion object {
        fun networkRequirements(wifiOnly: Boolean) = Requirements(Requirements.NETWORK or if (wifiOnly) Requirements.NETWORK_UNMETERED else 0)
    }
}
