package org.sonora.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.sonora.music.data.NewPipeMusicSource
import org.sonora.music.discovery.*
import org.sonora.music.domain.*
import org.sonora.music.ui.SearchPresenter

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistSearchTest {
    @Test fun onlyDedicatedMusicArtistResultsBecomeArtists() {
        val item = ChannelInfoItem(0, "https://www.youtube.com/channel/UCfixture", "Live")
        val source = NewPipeMusicSource()
        val artist = source.catalog(listOf(item, item), CatalogKind.ARTISTS, true).single()
        assertEquals(CatalogKind.ARTISTS, artist.kind)
        assertEquals("YouTube Music artist", artist.credit)
        assertTrue(artist.musical)
        assertNull(artist.track)
        for ((kind, musical) in listOf(CatalogKind.CHANNELS to false, CatalogKind.SONGS to true, CatalogKind.ARTISTS to false)) {
            val channel = source.catalog(listOf(item), kind, musical).single()
            assertEquals(CatalogKind.CHANNELS, channel.kind)
            assertFalse(channel.musical)
        }
    }

    @Test fun artistNamesAreNotPenalizedAsSongVersions() {
        val artist = CatalogEntry("artist:live", "Live", "YouTube Music artist", "", CatalogKind.ARTISTS, musical = true)
        val result = SearchRanker().rank("Live", listOf(artist)).single()
        assertTrue(result.versions.isEmpty())
        assertFalse(result.reasons.any { it.contains("version") })
        assertEquals(98.0, result.score, .001)
    }

    @Test fun openingArtistCancelsItsOldPageAndSearchesSongsOnce() = runTest {
        val calls = mutableListOf<Triple<String, CatalogKind, String?>>()
        val artist = CatalogEntry("artist:obrint", "Obrint Pas", "YouTube Music artist", "", CatalogKind.ARTISTS, musical = true)
        val song = Track("song:flama", "La flama", "Obrint Pas", "", 180)
        val source = object : MusicSource {
            override suspend fun search(query: String) = emptyList<Track>()
            override suspend fun resolve(url: String) = AudioStream(url)
        }
        val data = object : SearchDataSource {
            override suspend fun local(query: String, kind: CatalogKind) = emptyList<CatalogEntry>()
            override suspend fun suggestions(query: String) = emptyList<String>()
            override suspend fun remember(query: String) {}
            override suspend fun remote(query: String, kind: CatalogKind, cursor: String?): CatalogPage {
                calls += Triple(query, kind, cursor)
                if (cursor != null) withContext(NonCancellable) { delay(500) }
                return if (kind == CatalogKind.ARTISTS) CatalogPage(listOf(artist), "artists:next")
                else CatalogPage(listOf(CatalogEntry(song.url, song.title, song.artist, "", CatalogKind.SONGS, song, true)))
            }
        }
        val presenter = SearchPresenter(source, backgroundScope, data)
        presenter.search("obrint", CatalogKind.ARTISTS); runCurrent()
        presenter.loadMore(); runCurrent()
        presenter.songsBy(artist.title); runCurrent(); advanceTimeBy(600); runCurrent()
        assertEquals(listOf(Triple("obrint", CatalogKind.ARTISTS, null), Triple("obrint", CatalogKind.ARTISTS, "artists:next"), Triple("Obrint Pas", CatalogKind.SONGS, null)), calls)
        assertEquals(CatalogKind.SONGS, presenter.state.value.kind)
        assertEquals(listOf(song), presenter.state.value.tracks)
        assertNull(presenter.state.value.next)
        assertFalse(presenter.state.value.loadingMore)
    }
}
