package org.sonora.music.accountimport

import org.sonora.music.i18n.tr

import org.sonora.music.discovery.MusicText
import org.sonora.music.domain.Track
import org.sonora.music.domain.YouTubeLinks
import kotlin.math.abs

/** Metadata agreement is not a verified recording identity. Ambiguity always requires a person. */
object SpotifyMatcher {
    data class Result(val chosen: Track?, val candidates: List<Track>, val reason: String)
    private fun normalizedTitle(value: String): String = MusicText.normalize(value
        .replace(Regex("\\((?i:official (?:audio|video|music video)|lyrics?|audio|visualizer)\\)|\\[(?i:official (?:audio|video|music video)|lyrics?|audio|visualizer)]"), " "))
    private fun title(song: RemoteSong, track: Track): String {
        var value = normalizedTitle(track.title)
        song.artists.forEach { artist ->
            val prefix = MusicText.normalize(artist) + " "
            if (value.startsWith(prefix) && value.removePrefix(prefix) == normalizedTitle(song.title)) value = value.removePrefix(prefix)
        }
        return value
    }
    private fun artistAgrees(song: RemoteSong, track: Track): Boolean {
        val credit = MusicText.normalize(track.artist).removeSuffix(" topic").removeSuffix("vevo").trim()
        return song.artists.firstOrNull()?.let { MusicText.normalize(it) == credit } == true
    }
    fun match(song: RemoteSong, tracks: List<Track>): Result {
        val all = tracks.distinctBy { it.url }.filter { YouTubeLinks.track(it.url) == it.url }
            .sortedByDescending { MusicText.overlap(song.title, it.title) * 2 + if (artistAgrees(song, it)) 1 else 0 }
        val candidates = all.take(5)
        val matches = all.filter { track ->
            song.unavailable == null && song.title.isNotBlank() && title(song, track) == normalizedTitle(song.title) && artistAgrees(song, track) &&
                MusicText.variants(song.title) == MusicText.variants(track.title) && song.durationSeconds > 0 && track.durationSeconds > 0 &&
                abs(track.durationSeconds - song.durationSeconds) <= (song.durationSeconds * .015).coerceIn(2.0, 4.0)
        }
        return when (matches.size) {
            1 -> Result(matches.single(), (listOf(matches.single()) + candidates).distinctBy { it.url }.take(5), tr("Title, source credit, version and duration agree; check before importing."))
            0 -> Result(null, candidates, tr("Pending review: no sufficiently close title, artist and duration match."))
            else -> Result(null, candidates, tr("Pending review: several versions fit. Choose the recording yourself."))
        }
    }
}

data class ReviewedSong(val source: RemoteSong, val candidates: List<Track> = emptyList(), val chosen: Track? = source.playable,
    val reason: String = source.unavailable ?: tr("YouTube video reference"))
data class ReviewedPlaylist(val source: RemotePlaylist, val songs: List<ReviewedSong>)
