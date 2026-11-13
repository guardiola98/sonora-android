package org.sonora.music.discovery

import org.sonora.music.i18n.tr

import org.sonora.music.domain.Track

/** No extractor, Android player or UI types cross these interfaces. */
enum class CatalogKind(private val sourceLabel: String) { SONGS("Songs"), ARTISTS("Artists"), ALBUMS("Albums"), PLAYLISTS("Playlists"), CHANNELS("Channels"); val label: String get() = tr(sourceLabel) }
data class CatalogEntry(val url: String, val title: String, val credit: String, val artwork: String,
    val kind: CatalogKind, val track: Track? = null, val musical: Boolean = false)
data class CatalogPage(val entries: List<CatalogEntry>, val next: String? = null, val notice: String? = null)
interface MusicCatalogSource {
    suspend fun searchPage(query: String, kind: CatalogKind, cursor: String? = null): CatalogPage
    suspend fun suggestions(query: String): List<String>
    suspend fun related(track: Track): List<Track>
    suspend fun collection(url: String, cursor: String? = null): CatalogPage
}
/** Confidence describes the mapping to this recording, not an assertion of objective musical truth. */
data class Evidence(val value: String, val source: String, val confidence: Double, val fetchedAt: Long, val license: String = "CC0", val scope: String = "recording")
data class MusicMetadata(val reference: String, val artist: Evidence? = null, val artistId: Evidence? = null,
    val recordingId: Evidence? = null, val album: Evidence? = null, val albumId: Evidence? = null,
    val year: Evidence? = null, val genres: List<Evidence> = emptyList(), val language: Evidence? = null, val scene: Evidence? = null)
data class SimilarArtist(val id: String, val name: String, val evidence: Evidence)
interface MusicMetadataProvider { suspend fun metadata(track: Track): MusicMetadata? }
interface ArtistSimilarityProvider { suspend fun similar(artistId: String): List<SimilarArtist> }
data class ListeningPreference(val url: String, val completions: Int = 0, val earlySkips: Int = 0,
    val removals: Int = 0, val more: Int = 0, val hidden: Boolean = false, val listenedMs: Long = 0, val updatedAt: Long = 0)
data class PersonalTrack(val track: Track, val favorite: Boolean, val plays: Int, val lastPlayedAt: Long?)
interface LocalHistoryProvider {
    suspend fun candidates(): List<PersonalTrack>
    suspend fun preferences(): Map<String, ListeningPreference>
}
data class RecommendationCandidate(val track: Track, val metadata: MusicMetadata? = null,
    val related: Double? = null, val relatedArtist: Double? = null, val origin: String = tr("Local library"), val musical: Boolean = false)
data class ScorePart(val label: String, val value: Double, val weight: Double)
data class Recommendation(val track: Track, val score: Double, val reasons: List<ScorePart>, val group: String,
    val fingerprint: String, val origin: String) {
    val explanation: String get() = reasons.filter { it.value > .55 }.sortedByDescending { it.value * it.weight }
        .take(2).joinToString(" · ") { it.label }.ifBlank { origin }
}
data class DiscoveryBatch(val recommendations: List<Recommendation>, val notice: String? = null)
fun interface RecommendationSource {
    suspend fun recommend(seed: Track, excluded: Set<String>, recent: List<Track>, limit: Int): DiscoveryBatch
}
