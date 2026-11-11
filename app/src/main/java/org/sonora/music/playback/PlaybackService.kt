package org.sonora.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.core.net.toUri
import kotlinx.coroutines.*
import org.sonora.music.MainActivity
import org.sonora.music.SonoraApplication
import org.sonora.music.data.ExtractorDownloader
import org.sonora.music.data.SavedQueue
import org.sonora.music.domain.MusicSourceException
import org.sonora.music.domain.PodcastLinks

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as SonoraApplication).graph
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var refreshed = false
    private var recorded = false
    private var loadingQueue = true
    private var changedDuringRestore = false
    private var applyingRestore = false
    private var destroying = false

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory().setUserAgent(ExtractorDownloader.USER_AGENT)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(25_000)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, androidx.media3.datasource.DataSource.Factory { PrefixDataSource(http.createDataSource(), graph.audioPrefixes) })) { spec ->
            val host = spec.uri.host.orEmpty()
            if (PodcastLinks.isEpisode(spec.uri.toString()) || host == "www.youtube.com" || host == "youtube.com" || host == "m.youtube.com" || host == "youtu.be") {
                val stream = runBlocking(Dispatchers.IO) { graph.streams.resolve(spec.uri.toString()) }
                spec.withUri(stream.url.toUri())
            } else spec
        }
        player = ExoPlayer.Builder(this).setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 250, 2_000).setBackBuffer(10_000, false).build()).setMediaSourceFactory(DefaultMediaSourceFactory(resolving)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (destroying) return
                    if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                        if (loadingQueue) { if (!applyingRestore) changedDuringRestore = true }
                        else saveQueue()
                    }
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    val old = oldPosition.mediaItem ?: return
                    if (recorded && oldPosition.positionMs > 0) graph.podcastProgress.save(old.mediaId, oldPosition.positionMs,
                        old.toTrack().durationSeconds * 1000, reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    refreshed = false; recorded = false
                    if (!applyingRestore) graph.preparation.prioritize(mediaItem?.mediaId)
                    graph.playbackStatus.report(null)
                    if (mediaItem != null && !PodcastLinks.isEpisode(mediaItem.mediaId)) setPlaybackSpeed(1f)
                    // Automatic/queue transitions resume podcasts too. An explicit seek or restored queue wins.
                    if (!applyingRestore && mediaItem != null && PodcastLinks.isEpisode(mediaItem.mediaId) && currentPosition == 0L) {
                        val index = currentMediaItemIndex
                        scope.launch {
                            val position = try { graph.podcastLibrary.progressFor(mediaItem.mediaId)?.resumeMs ?: 0 }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { 0L }
                            if (position > 0 && currentMediaItemIndex == index && currentMediaItem?.mediaId == mediaItem.mediaId && currentPosition < 500) seekTo(position)
                        }
                    }
                    recordIfPlaying()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { recordIfPlaying() }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) graph.playbackStatus.report(null)
                    if (playbackState == Player.STATE_ENDED) saveEpisodeProgress(completed = true)
                }
                override fun onPlayerError(error: PlaybackException) {
                    val causes = generateSequence<Throwable>(error) { it.cause }.toList()
                    val expired = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                        .any { it.responseCode == 403 || it.responseCode == 410 }
                    val url = currentMediaItem?.mediaId
                    if (expired && !refreshed && url != null) {
                        refreshed = true
                        scope.launch {
                            graph.streams.invalidate(url)
                            if (currentMediaItem?.mediaId == url && playerError === error) prepare()
                        }
                    } else {
                        val sourceError = causes.filterIsInstance<MusicSourceException>().firstOrNull()
                        graph.playbackStatus.report(sourceError?.message ?: when {
                            expired -> "The stream expired or is unavailable. Retry to request a fresh stream."
                            error.errorCode in 2000..2999 -> "Audio could not be loaded. Check your connection and retry."
                            PodcastLinks.isEpisode(url.orEmpty()) -> "Podcast audio is unavailable or unsupported. Refresh the feed or try another episode."
                            else -> "Playback failed. Retry or choose another track."
                        })
                    }
                }
            })
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                    val base = super.onConnect(session, controller)
                    if (controller.packageName != packageName) return base
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(base.availablePlayerCommands)
                        .setAvailableSessionCommands(base.availableSessionCommands.buildUpon().add(SessionCommand(MARK_PODCAST_PLAYED, Bundle.EMPTY)).build()).build()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (customCommand.customAction != MARK_PODCAST_PLAYED) return super.onCustomCommand(session, controller, customCommand, args)
                    val reference = args.getString("reference").orEmpty()
                    if (controller.packageName != packageName || !PodcastLinks.isEpisode(reference))
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    val duration = args.getLong("durationMs").coerceIn(0, 604800000)
                    val completed = args.getBoolean("completed")
                    // Execute on the real player, not a controller's optimistic queue mask.
                    // Its discontinuity callbacks enqueue their checkpoints before this explicit write.
                    if (player.currentMediaItem?.mediaId == reference) {
                        for (index in player.mediaItemCount - 1 downTo 0) if (player.getMediaItemAt(index).mediaId == reference) player.removeMediaItem(index)
                    }
                    graph.podcastProgress.save(reference, if (completed) duration else 0, duration, completed)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }).build()
        scope.launch {
            val saved = try { graph.queueStore.read() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { graph.playbackStatus.report("Previous queue could not be restored."); SavedQueue() }
            if (!changedDuringRestore && player.mediaItemCount == 0 && saved.tracks.isNotEmpty()) {
                applyingRestore = true
                player.setMediaItems(saved.tracks.map { it.toMediaItem() }, saved.index, saved.positionMs)
                player.playWhenReady = false // Restoring never starts audio or contacts the source.
                applyingRestore = false
            }
            loadingQueue = false
            saveQueue()
            while (isActive) {
                delay(2_000)
                if (player.isPlaying) {
                    saveQueue()
                    val remaining = player.duration.takeIf { it > 0 }?.minus(player.currentPosition) ?: Long.MAX_VALUE
                    if (player.totalBufferedDuration >= 5_000 && (player.currentPosition < 10_000 || remaining < 30_000)) {
                        val next = player.nextMediaItemIndex
                        if (next != C.INDEX_UNSET) graph.preparation.next(player.getMediaItemAt(next).mediaId)
                    }
                }
            }
        }

    }
    private fun recordIfPlaying() {
        if (!player.isPlaying || recorded) return
        val track = player.currentMediaItem?.toTrack() ?: return
        recorded = true
        scope.launch {
            try { graph.library.recordPlay(track, System.currentTimeMillis()) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { graph.playbackStatus.report("Playing, but history could not be saved.") }
        }
    }
    private fun saveEpisodeProgress(completed: Boolean = player.playbackState == Player.STATE_ENDED) {
        if (!recorded) return
        val track = player.currentMediaItem?.toTrack() ?: return
        graph.podcastProgress.save(track.url, player.currentPosition, player.duration.takeIf { it > 0 } ?: track.durationSeconds * 1000, completed)
    }
    private fun saveQueue() {
        if (loadingQueue) return
        saveEpisodeProgress()
        graph.queueStore.save(SavedQueue((0 until player.mediaItemCount).map { player.getMediaItemAt(it).toTrack() },
            player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0)))
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) stopSelf()
    }
    override fun onDestroy() {
        saveQueue()
        destroying = true
        graph.preparation.prioritize(null)
        session?.release(); session = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }
}
