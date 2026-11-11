package org.sonora.music.discovery

import java.text.Normalizer
import java.util.Locale
import org.sonora.music.domain.Track

object MusicText {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace('&', ' ')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    private val decoration = Regex("\\b(official|music|video|audio|lyrics?|visuali[sz]er|hd|hq|4k|1080p|oficial|videoclip)\\b")
    private val versions = linkedMapOf("live" to Regex("\\b(live (at|in|from|session)|en directo|en viu|ao vivo|concert recording)\\b|\\blive$"),
        "cover" to Regex("\\b(cover by|karaoke)\\b|\\bcovers?$"), "remix" to Regex("\\b(remix|remixed|mix|edit)\\b"),
        "acoustic" to Regex("\\b(acoustic|acustic|acustico|unplugged)\\b"),
        "alternate" to Regex("\\b(sped up|slowed|instrumental|remaster|remastered|demo)\\b"))
    fun variants(title: String): Set<String> { val normalized = normalize(title); return versions.filterValues { it.containsMatchIn(normalized) }.keys }
    fun familyFingerprint(track: Track): String {
        val title = track.title.replace(Regex("\\([^)]*\\)|\\[[^]]*]")) { match -> if (variants(match.value).isNotEmpty()) "" else match.value }
        return fingerprint(track.copy(title = title))
    }
    fun cleanTitle(title: String): String = normalize(title).replace(decoration, " ").replace(Regex("\\s+"), " ").trim()
    fun uploader(value: String): String = normalize(value).removeSuffix(" topic").removeSuffix(" vevo").trim()
    /** Conservative: keep variants and distinct uploaders separate unless there is recording identity evidence. */
    fun fingerprint(track: Track, metadata: MusicMetadata? = null): String = metadata?.recordingId?.takeIf { it.confidence >= .85 }
        ?.let { "recording:${it.value}:${variants(track.title).sorted()}" }
        ?: "${uploader(track.artist)}|${cleanTitle(track.title)}|${variants(track.title).sorted()}"
    fun tokens(value: String) = normalize(value).split(' ').filter { it.length > 1 }.toSet()
    fun overlap(a: String, b: String): Double {
        val left = tokens(a); val right = tokens(b)
        return if (left.isEmpty() || right.isEmpty()) 0.0 else left.intersect(right).size.toDouble() / left.union(right).size
    }
    fun likelyMusic(track: Track): Double {
        val title = normalize(track.title)
        if (Regex("\\b(interview|entrevista|reaction|reaccion|tutorial|review|news|noticies|noticias|gameplay|podcast|trailer|cold open|comedy|sketch|talk show|monologue)\\b").containsMatchIn(title)) return .1
        if (Regex("\\b(official|audio|lyrics|music|oficial|videoclip)\\b").containsMatchIn(title) || normalize(track.artist).endsWith(" topic")) return .85
        return if (track.durationSeconds in 90..900) .6 else .3
    }
}

class SearchRanker {
    data class Ranked(val entry: CatalogEntry, val score: Double, val reasons: List<String>, val versions: Set<String>)
    fun rank(query: String, entries: List<CatalogEntry>, local: Set<String> = emptySet(), allVersions: Boolean = false): List<Ranked> {
        val wantedVersions = MusicText.variants(query)
        val q = MusicText.normalize(query); val words = MusicText.tokens(query)
        val ranked = entries.distinctBy { it.url }.map { entry ->
            val title = MusicText.normalize(entry.title)
            val haystack = MusicText.tokens(entry.title + " " + entry.credit)
            val coverage = if (words.isEmpty()) 0.0 else words.intersect(haystack).size.toDouble() / words.size
            val variants = MusicText.variants(entry.title)
            val reasons = mutableListOf<String>()
            var score = coverage * 55
            if (title == q) { score += 25; reasons += "Exact title" }
            else if (title.contains(q)) { score += 12; reasons += "Title match" }
            if (entry.musical) { score += 18; reasons += "Music catalog" }
            else entry.track?.let { score += MusicText.likelyMusic(it) * 10 }
            if (entry.url in local) { score += 8; reasons += "In your library" }
            if (wantedVersions.isNotEmpty() && variants.containsAll(wantedVersions)) { score += 18; reasons += "Requested version" }
            else if (variants.isNotEmpty()) { score -= 6; reasons += "${variants.joinToString()} version" }
            if (coverage > .5) reasons += "Query terms"
            Ranked(entry, score, reasons, variants)
        }.sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.entry.url })
        if (allVersions) return ranked
        val seen = mutableSetOf<String>()
        return ranked.filter { result ->
            val track = result.entry.track
            track == null || seen.add(MusicText.familyFingerprint(track))
        }
    }
}
