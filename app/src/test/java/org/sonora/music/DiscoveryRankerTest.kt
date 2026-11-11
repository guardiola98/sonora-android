package org.sonora.music

import org.junit.Assert.*
import org.junit.Test
import org.sonora.music.discovery.*
import org.sonora.music.domain.Track

class DiscoveryRankerTest {
    private fun track(id: Int, credit: String = "Channel $id", title: String = "Song $id") = Track("https://www.youtube.com/watch?v=${id.toString().padStart(11, '0')}", title, credit, "", 180)
    private fun metadata(track: Track, artist: String, genre: String? = null) = MusicMetadata(track.url, Evidence(artist, "test", .95, 1), Evidence(artist, "test", .95, 1),
        genres = listOfNotNull(genre?.let { Evidence(it, "test", .8, 1) }))
    @Test fun normalizationHandlesAccentsCaseAndPunctuationWithoutInventingIdentity() {
        assertEquals("obrint pas", MusicText.normalize("  ÓBRINT—PÀS "))
        assertEquals(MusicText.uploader("Band - Topic"), MusicText.uploader("BAND"))
        assertNotEquals(MusicText.fingerprint(track(1, "Fan channel")), MusicText.fingerprint(track(1, "Artist")))
    }
    @Test fun officialDuplicatesCollapseButDifferentCoversStayDistinct() {
        val first = track(1, "Band", "Song Official Audio")
        val entries = listOf(first, first.copy(url = track(2).url, title = "Song Official Video"), track(3, "Another performer", "Song cover"))
            .map { CatalogEntry(it.url, it.title, it.artist, "", CatalogKind.SONGS, it, true) }
        assertEquals(2, SearchRanker().rank("Song", entries).size)
        assertEquals(3, SearchRanker().rank("Song", entries, allVersions = true).size)
        assertTrue(SearchRanker().rank("Song cover", entries).first().versions.contains("cover"))
    }
    @Test fun musicCatalogAndExactQueriesOutrankNonMusicalWordMatches() {
        val song = track(1, "Band", "Song")
        val interview = track(2, "Band", "Song interview reaction news")
        val entries = listOf(interview, song).map { CatalogEntry(it.url, it.title, it.artist, "", CatalogKind.SONGS, it, it == song) }
        val results = SearchRanker().rank("Song", entries)
        assertEquals(song.url, results.first().entry.url)
        assertTrue(results.first().reasons.contains("Music catalog"))
    }
    @Test fun missingGenreDoesNotActAsAnExplicitGenreMismatch() {
        val seed = track(0); val candidate = track(1)
        val ranker = RecommendationRanker(); val sm = metadata(seed, "artist", "rock")
        fun score(meta: MusicMetadata) = ranker.rank(seed, sm, listOf(RecommendationCandidate(candidate, meta, musical = true)), emptyList(), emptyMap(), emptySet(), emptyList()).single()
        val absent = score(metadata(candidate, "artist"))
        val mismatch = score(metadata(candidate, "artist", "jazz"))
        assertTrue(absent.score > mismatch.score)
        assertFalse(absent.reasons.any { it.label.contains("genre") })
    }
    @Test fun channelEqualityNeverBecomesVerifiedArtistIdentity() {
        val seed = track(0, "Aggregator"); val candidate = track(1, "Aggregator")
        val result = RecommendationRanker().rank(seed, null, listOf(RecommendationCandidate(candidate)), emptyList(), emptyMap(), emptySet(), emptyList()).single()
        assertFalse(result.reasons.any { it.label == "Same credited artist" })
        assertTrue(result.reasons.any { it.label == "Same source credit / channel" && it.weight < 4 })
    }
    @Test fun diversityCapsEachArtistAndAvoidsAdjacentRepeatsWhenAlternativesExist() {
        val seed = track(0, "Seed")
        val candidates = (1..24).map { val t = track(it, "Channel ${it % 4}"); RecommendationCandidate(t, metadata(t, "Artist ${it % 4}"), related = .8, musical = true) }
        val results = RecommendationRanker().rank(seed, null, candidates, emptyList(), emptyMap(), emptySet(), emptyList(), limit = 8)
        assertEquals(8, results.size)
        assertTrue(results.groupingBy { it.group }.eachCount().values.all { it <= 2 })
        assertTrue(results.zipWithNext().all { it.first.group != it.second.group })
    }
    @Test fun partialArtistMatchesDoNotCreateExtraDiversitySlots() {
        val seed = track(0)
        val candidates = (1..8).map {
            val t = track(it, "Band - Topic")
            RecommendationCandidate(t, if (it % 2 == 0) metadata(t, "Band") else null, musical = true)
        }
        val result = RecommendationRanker().rank(seed, null, candidates, emptyList(), emptyMap(), emptySet(), emptyList())
        assertEquals(2, result.size)
        assertEquals(1, result.map { it.group }.distinct().size)
    }
    @Test fun songTitleLiveForeverIsNotAConcertAndVersionToggleUnfoldsAlternates() {
        assertTrue(MusicText.variants("Live Forever").isEmpty())
        val tracks = listOf(track(1, "Band", "Song"), track(2, "Band", "Song (Live at Wembley)"))
        val entries = tracks.map { CatalogEntry(it.url, it.title, it.artist, "", CatalogKind.SONGS, it, true) }
        assertEquals(1, SearchRanker().rank("Song", entries).size)
        assertEquals(2, SearchRanker().rank("Song", entries, allVersions = true).size)
    }
    @Test fun recentlyQueuedAndExplicitlyHiddenMusicNeverReturns() {
        val seed = track(0); val candidates = (1..4).map { RecommendationCandidate(track(it), musical = true) }
        val result = RecommendationRanker().rank(seed, null, candidates, emptyList(), mapOf(track(2).url to ListeningPreference(track(2).url, hidden = true)), setOf(track(1).url), listOf(track(3)))
        assertEquals(listOf(track(4).url), result.map { it.track.url })
    }
    @Test fun earlySkipIsSoftAndDisablingPersonalizationRemovesBehaviorWeights() {
        val seed = track(0); val candidate = track(1)
        val personal = listOf(PersonalTrack(candidate, true, 5, 1))
        val preferences = mapOf(candidate.url to ListeningPreference(candidate.url, earlySkips = 1))
        val ranker = RecommendationRanker()
        val enabled = ranker.rank(seed, null, listOf(RecommendationCandidate(candidate, related = .7)), personal, preferences, emptySet(), emptyList()).single()
        assertTrue(enabled.reasons.first { it.label == "Your listening preferences" }.value > .65)
        val disabled = ranker.rank(seed, null, listOf(RecommendationCandidate(candidate, related = .7)), personal, preferences, emptySet(), emptyList(), personalized = false).single()
        assertFalse(disabled.reasons.any { it.label == "Your listening preferences" || it.label == "Played before" })
    }
    @Test fun unrelatedWatchPageVideosNeedPositiveMusicEvidence() {
        val seed = track(0, "Obrint Pas", "La Flama")
        val sketch = track(1, "Saturday Night Live", "Kavanaugh Hegseth Patel Bar Cold Open - SNL")
        val unknown = track(2, "Unrelated channel", "A day in the city")
        val song = track(3, "Another band", "New song (Official Audio)")
        val values = listOf(sketch, unknown, song).map { RecommendationCandidate(it, related = .9) }
        val ranked = RecommendationRanker().rank(seed, null, values, emptyList(), emptyMap(), emptySet(), emptyList())
        assertEquals(listOf(song.url), ranked.map { it.track.url })
    }
    @Test fun duplicateCandidateSourcesKeepCatalogAndRelatedEvidence() {
        val seed = track(0); val t = track(1)
        val candidates = listOf(RecommendationCandidate(t), RecommendationCandidate(t, related = .75), RecommendationCandidate(t, musical = true))
        val result = RecommendationRanker().rank(seed, null, candidates, emptyList(), emptyMap(), emptySet(), emptyList()).single()
        assertTrue(result.reasons.any { it.label == "Related by the source" })
        assertEquals(.9, result.reasons.first { it.label == "Music candidate" }.value, .001)
    }
    @Test fun tenThousandCandidatesStayBoundedAndDeterministic() {
        val entries = (1..10000).map { val t = track(it, "Band ${it % 100}", "Song $it official audio"); CatalogEntry(t.url, t.title, t.artist, "", CatalogKind.SONGS, t, true) }
        val start = System.nanoTime()
        val ranked = SearchRanker().rank("Song 8234", entries)
        assertEquals(track(8234).url, ranked.first().entry.url)
        assertTrue("Ranking took over 10 seconds", System.nanoTime() - start < 10000000000L)
        assertEquals(10000, ranked.size)
    }
}
