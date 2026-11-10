package org.sonora.music.playback

import android.content.ComponentName
import android.content.Context
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
    private val status: PlaybackStatus, private val streams: StreamResolver) {
    private val mutable = MutableStateFlow(PlayerSnapshot())
    val state = mutable.asStateFlow()
    private var controller: MediaController? = null
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                this@PlaybackConnection.controller = null
                mutable.value = PlayerSnapshot()
                status.report("Player disconnected. Reopen Sonora to reconnect.")
            }
        }).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { publish() }
    }
    init {
        future.addListener({
            try { controller = future.get().also { it.addListener(listener) }; publish() }
            catch (_: Exception) { status.report("Could not connect to the player. Reopen Sonora to retry.") }
        }, ContextCompat.getMainExecutor(context))
        scope.launch { while (isActive) { publish(); delay(500) } }
    }
    private fun publish() {
        val c = controller ?: return
        mutable.value = PlayerSnapshot(true, (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toTrack() },
            c.currentMediaItemIndex, c.playWhenReady, c.playbackState == Player.STATE_BUFFERING,
            c.currentPosition.coerceAtLeast(0), c.duration.takeIf { it > 0 } ?: 0)
    }
    fun play(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        status.report(null)
        controller?.apply { setMediaItems(tracks.map { it.toMediaItem() }, index, 0); prepare(); play() }
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
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }
    fun jump(index: Int) { controller?.apply { if (index in 0 until mediaItemCount) { seekTo(index, 0); prepare(); play() } } }
    fun remove(index: Int) { controller?.apply { if (index in 0 until mediaItemCount) removeMediaItem(index) } }
    fun moveUp(index: Int) { controller?.apply { if (index in 1 until mediaItemCount) moveMediaItem(index, index - 1) } }
    fun clear() { controller?.apply { stop(); clearMediaItems() }; status.report(null) }
    fun release() { controller?.removeListener(listener); MediaController.releaseFuture(future) }
}
