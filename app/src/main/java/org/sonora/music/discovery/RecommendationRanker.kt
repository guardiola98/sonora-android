package org.sonora.music.discovery

import org.sonora.music.domain.Track
import kotlin.math.ln

/** Missing dimensions are absent from the denominator; weak evidence has proportionally less weight. */
class RecommendationRanker {
    fun rank(seed: Track, seedMetadata: MusicMetadata?, candidates: List<RecommendationCandidate>,
        personal: List<PersonalTrack>, preferences: Map<String, ListeningPreference>, excluded: Set<String>,
        recent: List<Track>, personalized: Boolean = true, limit: Int = 8): List<Recommendation> {
        val library = personal.associateBy { it.track.url }
        val recentKeys = recent.takeLast(80).map { MusicText.fingerprint(it) }.toSet() + MusicText.fingerprint(seed, seedMetadata)
        val recentGroups = recent.takeLast(4).map { MusicText.uploader(it.artist) }
        val seedArtist = seedMetadata?.artistId?.takeIf { it.confidence >= .8 }
        // Merge corroborating sources before deduplication; local rows must not erase music-catalog evidence.
        val merged = candidates.groupBy { it.track.url }.values.map { rows ->
            rows.first().copy(metadata = rows.firstNotNullOfOrNull { it.metadata },
                musical = rows.any { it.musical }, related = rows.mapNotNull { it.related }.maxOrNull(),
                relatedArtist = rows.mapNotNull { it.relatedArtist }.maxOrNull(), origin = rows.map { it.origin }.distinct().joinToString("; "))
        }
        val scored = merged.mapNotNull { candidate ->
            val track = candidate.track; val pref = preferences[track.url]
            val fingerprint = MusicText.fingerprint(track, candidate.metadata)
            if (track.url in excluded || track.url == seed.url || pref?.hidden == true || fingerprint in recentKeys || MusicText.fingerprint(track) in recentKeys) return@mapNotNull null
            val musicHint = MusicText.likelyMusic(track)
            if (!candidate.musical && musicHint < .2) return@mapNotNull null
            // Public watch-page related items can be generic trending videos. Duration alone
            // is not music evidence: require catalog/recording corroboration, a strong hint,
            // prior user selection, or affinity to the seed's source credit.
            val sameCredit = MusicText.uploader(track.artist).isNotBlank() && MusicText.uploader(track.artist) == MusicText.uploader(seed.artist)
            if (!candidate.musical && (candidate.metadata?.artistId?.confidence ?: 0.0) < .85 && track.url !in library && musicHint < .8 && !sameCredit) return@mapNotNull null
            val parts = mutableListOf<ScorePart>()
            fun add(label: String, value: Double?, weight: Double, confidence: Double = 1.0) {
                if (value != null && confidence >= .4) parts += ScorePart(label, value.coerceIn(0.0, 1.0), weight * confidence)
            }
            val meta = candidate.metadata; val artist = meta?.artistId?.takeIf { it.confidence >= .8 }
            if (seedArtist != null && artist != null) add("Same credited artist", if (seedArtist.value == artist.value) 1.0 else candidate.relatedArtist, 25.0, minOf(seedArtist.confidence, artist.confidence))
            else add("Related artist · ListenBrainz", candidate.relatedArtist, 25.0, .75)
            // Uploader affinity is deliberately weak and never labeled artist identity.
            if (MusicText.uploader(track.artist).isNotEmpty()) add("Same source credit / channel", if (MusicText.uploader(seed.artist) == MusicText.uploader(track.artist)) .85 else null, 6.0, .55)
            val seedGenres = seedMetadata?.genres.orEmpty().filter { it.confidence >= .6 }
            val genres = meta?.genres.orEmpty().filter { it.confidence >= .6 }
            if (seedGenres.isNotEmpty() && genres.isNotEmpty()) add("Shared MusicBrainz genre evidence", MusicText.overlap(seedGenres.joinToString { it.value }, genres.joinToString { it.value }), 25.0, .65)
            add("Related by the source", candidate.related, 22.0, .65)
            if (personalized) {
                val saved = library[track.url]
                if (saved != null || pref != null) {
                    val preference = .45 + (if (saved?.favorite == true) .3 else 0.0) + .1 * (pref?.more ?: 0).coerceAtMost(2) + .04 * (pref?.completions ?: 0).coerceAtMost(4) - .035 * (pref?.earlySkips ?: 0).coerceAtMost(4) - .02 * (pref?.removals ?: 0).coerceAtMost(3)
                    add("Your listening preferences", preference, 18.0)
                }
                if (saved != null && saved.plays > 0) add("Played before", (ln(1.0 + saved.plays) / 5).coerceAtMost(.9), 7.0)
            }
            if (seedMetadata?.albumId != null && meta?.albumId != null) add("Same credited album", if (seedMetadata.albumId.value == meta.albumId.value) 1.0 else null, 5.0)
            val sy = seedMetadata?.year?.value?.toIntOrNull(); val cy = meta?.year?.value?.toIntOrNull()
            if (sy != null && cy != null) add("Similar release era", 1 - (kotlin.math.abs(sy - cy) / 30.0).coerceAtMost(1.0), 4.0, .8)
            if (seedMetadata?.language != null && meta?.language != null) add("Same documented language", if (seedMetadata.language.value == meta.language.value) 1.0 else 0.0, 5.0, minOf(seedMetadata.language.confidence, meta.language.confidence))
            if (seedMetadata?.scene != null && meta?.scene != null) add("Shared documented scene", if (seedMetadata.scene.value == meta.scene.value) 1.0 else 0.0, 5.0, minOf(seedMetadata.scene.confidence, meta.scene.confidence))
            add("Discovery candidate", if (track.url !in library) .85 else .35, 8.0)
            add("Music candidate", if (candidate.musical) .9 else MusicText.likelyMusic(track), 8.0)
            val score = parts.sumOf { it.value * it.weight } / parts.sumOf { it.weight }.coerceAtLeast(1.0)
            // This is a diversity bucket, not an assertion of artist identity. A partial
            // match must not let one band occupy separate verified and unverified buckets.
            val group = MusicText.uploader(meta?.artist?.takeIf { it.confidence >= .8 }?.value ?: track.artist).ifBlank { track.url }
            Recommendation(track, score, parts, group, fingerprint, candidate.origin)
        }.sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.track.url })
        val pool = scored.distinctBy { it.fingerprint }.toMutableList(); val selected = mutableListOf<Recommendation>()
        val groups = mutableMapOf<String, Int>()
        while (selected.size < limit && pool.isNotEmpty()) {
            val diversified = pool.filter { (groups[it.group] ?: 0) < 2 }
            val favorDiscovery = selected.count { it.track.url in library } >= (limit + 1) / 2 && diversified.any { it.track.url !in library }
            val eligible = if (favorDiscovery) diversified.filter { it.track.url !in library } else diversified
            if (eligible.isEmpty()) break // Do not fill a batch entirely with one artist/channel.
            val last = selected.lastOrNull()?.group ?: recentGroups.lastOrNull()
            val best = eligible.maxBy { it.score - (groups[it.group] ?: 0) * .16 - (if (it.group == last && eligible.any { other -> other.group != last }) .22 else 0.0) - (if (it.group in recentGroups) .06 else 0.0) }
            selected += best; groups[best.group] = (groups[best.group] ?: 0) + 1; pool.remove(best)
        }
        return selected
    }
}
