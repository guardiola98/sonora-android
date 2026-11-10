package org.sonora.music.domain

/** Stable metadata only. Expiring playback URLs never enter the database or UI. */
data class Track(val url: String, val title: String, val artist: String, val artwork: String, val durationSeconds: Long)
data class AudioStream(val url: String)
interface MusicSource {
    suspend fun search(query: String): List<Track>
    suspend fun resolve(url: String): AudioStream
}
class MusicSourceException(val kind: Kind, cause: Throwable? = null) : java.io.IOException(kind.message, cause) {
    enum class Kind(val message: String) {
        NETWORK("Cannot connect. Check your connection and try again."),
        UNAVAILABLE("This track is unavailable or requires access that Sonora cannot provide."),
        RESTRICTED("The source requires verification or has limited requests. Try again later."),
        EXTRACTION("The source could not be read. Try another track or update Sonora.")
    }
}
fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
