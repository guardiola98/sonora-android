package org.sonora.music.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.core.net.toUri
import kotlinx.coroutines.*
import org.sonora.music.MainActivity
import org.sonora.music.SonoraApplication
import org.sonora.music.data.ExtractorDownloader
import org.sonora.music.domain.MusicSourceException

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as SonoraApplication).graph
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var refreshed = false
    private var recorded = false

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory().setUserAgent(ExtractorDownloader.USER_AGENT)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(25_000)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            val host = spec.uri.host.orEmpty()
            if (host == "www.youtube.com" || host == "youtube.com" || host == "m.youtube.com" || host == "youtu.be") {
                val stream = runBlocking(Dispatchers.IO) { graph.streams.resolve(spec.uri.toString()) }
                spec.withUri(stream.url.toUri())
            } else spec
        }
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(resolving)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    refreshed = false; recorded = false
                    graph.playbackStatus.report(null)
                    recordIfPlaying()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { recordIfPlaying() }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) graph.playbackStatus.report(null)
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
                    return if (controller.packageName == packageName || controller.isTrusted) super.onConnect(session, controller)
                    else MediaSession.ConnectionResult.reject()
                }
            }).build()
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
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) stopSelf()
    }
    override fun onDestroy() {
        session?.release(); session = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }
}
