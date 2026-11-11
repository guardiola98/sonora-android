package org.sonora.music.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sonora.music.domain.Track

class PlaybackStatus {
    private val mutable = MutableStateFlow<String?>(null)
    val error = mutable.asStateFlow()
    fun report(message: String?) { mutable.value = message }
}
data class PlayerSnapshot(
    val connected: Boolean = false,
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f
) { val current: Track? get() = queue.getOrNull(index) }
fun Track.toMediaItem(): MediaItem = MediaItem.Builder().setMediaId(url).setUri(url)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist)
        .setArtworkUri(artwork.takeIf { it.isNotBlank() }?.toUri())
        .setExtras(Bundle().apply { putLong("durationSeconds", durationSeconds) }).build()).build()
fun MediaItem.toTrack() = Track(mediaId, mediaMetadata.title?.toString().orEmpty(),
    mediaMetadata.artist?.toString().orEmpty(), mediaMetadata.artworkUri?.toString().orEmpty(),
    mediaMetadata.extras?.getLong("durationSeconds") ?: 0)

const val MARK_PODCAST_PLAYED = "org.sonora.music.MARK_PODCAST_PLAYED"

const val PLAY_RADIO = "org.sonora.music.PLAY_RADIO"
const val RADIO_RETRY = "org.sonora.music.RADIO_RETRY"
const val RADIO_HIDE = "org.sonora.music.RADIO_HIDE"
