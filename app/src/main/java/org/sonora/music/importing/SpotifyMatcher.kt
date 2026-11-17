package org.sonora.music.importing

import org.sonora.music.discovery.MusicText
import org.sonora.music.domain.Track
import org.sonora.music.domain.YouTubeLinks
import kotlin.math.abs

/** Rank the available YouTube versions; missing duration or ambiguity never requires review. */
object SpotifyMatcher {
    data class Result(val chosen: Track?, val candidates: List<Track>)
    private fun title(song: RemoteSong, track: Track): String {
        var value = MusicText.cleanTitle(track.title)
        song.artists.forEach { artist ->
            val prefix = MusicText.normalize(artist) + " "
            if (value.startsWith(prefix)) value = value.removePrefix(prefix)
        }
        return value
    }
    fun match(song: RemoteSong, tracks: List<Track>): Result {
        if (song.unavailable != null || song.title.isBlank()) return Result(null, emptyList())
        val wantedTitle = MusicText.cleanTitle(song.title)
        val ranked = tracks.distinctBy(Track::url)
            .filter { YouTubeLinks.track(it.url) == it.url && it.title.isNotBlank() }
            .sortedByDescending { track ->
                val title = title(song, track)
                val credit = MusicText.uploader(track.artist).removeSuffix("vevo").trim()
                val artist = song.artists.maxOfOrNull { MusicText.overlap(it, credit) } ?: 0.0
                val duration = if (song.durationSeconds > 0 && track.durationSeconds > 0)
                    (1.0 - abs(track.durationSeconds - song.durationSeconds).toDouble() / song.durationSeconds).coerceAtLeast(0.0) else 0.0
                (if (title == wantedTitle) 10.0 else MusicText.overlap(wantedTitle, title) * 8) +
                    artist * 5 + (if (MusicText.variants(song.title) == MusicText.variants(track.title)) 2 else 0) + duration * 1.5
            }
        // Stable ties preserve provider order. An approximate result is still imported.
        return Result(ranked.firstOrNull(), ranked.take(5))
    }
}

data class ReviewedSong(val source: RemoteSong, val chosen: Track? = source.playable)
data class ReviewedPlaylist(val source: RemotePlaylist, val songs: List<ReviewedSong>)
