package org.sonora.music.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sonora.music.data.StreamResolver
import org.sonora.music.domain.Track

@UnstableApi
class PlaybackConnection(context: Context, private val scope: CoroutineScope,
    private val status: PlaybackStatus, private val streams: StreamResolver, private val podcasts: org.sonora.music.data.PodcastDao, private val preparation: PlaybackPreparation) {
    private val mutable = MutableStateFlow(PlayerSnapshot())
    val state = mutable.asStateFlow()
    private var controller: MediaController? = null
    private var released = false
    private var playRequest = 0
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val disconnectedMessage = "Player disconnected. Reopen Sonora to reconnect."
    private val connectionFailedMessage = "Could not connect to the player. Reopen Sonora to retry."
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                if (released) return
                this@PlaybackConnection.controller = null
                mutable.value = PlayerSnapshot()
                status.report(disconnectedMessage)
            }
        }).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { publish() }
    }
    init {
        future.addListener({
            if (released) return@addListener
            try {
                controller = future.get().also { it.addListener(listener) }
                if (status.error.value in listOf(disconnectedMessage, connectionFailedMessage)) status.report(null)
                publish()
            } catch (_: Exception) { status.report(connectionFailedMessage) }
        }, ContextCompat.getMainExecutor(context))
        scope.launch { while (isActive) { publish(); delay(500) } }
    }
    private fun publish() {
        val c = controller ?: return
        mutable.value = PlayerSnapshot(true, (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toTrack() },
            c.currentMediaItemIndex, c.playWhenReady, c.playbackState == Player.STATE_BUFFERING,
            c.currentPosition.coerceAtLeast(0), c.duration.takeIf { it > 0 } ?: ((c.currentMediaItem?.toTrack()?.durationSeconds ?: 0) * 1000), c.playbackParameters.speed)
    }
    fun play(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        preparation.prioritize(tracks[index].url)
        val request = ++playRequest
        scope.launch {
            val position = if (org.sonora.music.domain.PodcastLinks.isEpisode(tracks[index].url)) try { podcasts.progressFor(tracks[index].url)?.resumeMs ?: 0 }
                catch (e: CancellationException) { throw e } catch (_: Exception) { 0L } else 0L
            if (request != playRequest || released) return@launch
            status.report(null)
            controller?.apply { setMediaItems(tracks.map { it.toMediaItem() }, index, position); prepare(); play() }
        }
    }
    fun enqueue(track: Track) { controller?.apply { addMediaItem(track.toMediaItem()); if (playbackState == Player.STATE_IDLE) prepare() } }
    fun toggle() { controller?.let { if (it.isPlaying || it.playWhenReady && it.playbackState == Player.STATE_BUFFERING) it.pause() else retry() } }
    fun retry() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        scope.launch {
            if (c.playerError != null) streams.invalidate(id)
            if (c.currentMediaItem?.mediaId == id) { status.report(null); if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(); c.prepare(); c.play() }
        }
    }
    fun seek(position: Long) { controller?.seekTo(position.coerceAtLeast(0)) }
    fun markPodcastPlayed(reference: String, durationMs: Long, completed: Boolean, done: (Boolean) -> Unit) {
        val c = controller ?: run { done(false); return }
        val result = c.sendCustomCommand(SessionCommand(MARK_PODCAST_PLAYED, Bundle.EMPTY), Bundle().apply {
            putString("reference", reference); putLong("durationMs", durationMs); putBoolean("completed", completed)
        })
        result.addListener({ done(runCatching { result.get().resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false)) }, mainExecutor)
    }
    fun speed(value: Float) { controller?.setPlaybackSpeed(value.coerceIn(0.5f, 2f)) }
    fun next() { controller?.let { c -> if (c.nextMediaItemIndex != androidx.media3.common.C.INDEX_UNSET) preparation.prioritize(c.getMediaItemAt(c.nextMediaItemIndex).mediaId); c.seekToNextMediaItem() } }
    fun previous() { controller?.seekToPrevious() }
    fun jump(index: Int) { controller?.apply { if (index in 0 until mediaItemCount) { seekTo(index, 0); prepare(); play() } } }
    fun remove(index: Int) { controller?.apply { if (index in 0 until mediaItemCount) removeMediaItem(index) } }
    fun moveUp(index: Int) { controller?.apply { if (index in 1 until mediaItemCount) moveMediaItem(index, index - 1) } }
    fun clear() { playRequest++; preparation.prioritize(null); controller?.apply { stop(); clearMediaItems() }; status.report(null) }
    fun release() {
        released = true // Intentional Activity/ViewModel teardown is not a playback error.
        controller?.removeListener(listener)
        controller = null
        MediaController.releaseFuture(future)
    }
}
