package org.sonora.music

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import org.sonora.music.data.ExtractorDownloader
import org.sonora.music.data.NewPipeMusicSource
import org.sonora.music.discovery.CatalogKind
import java.util.concurrent.TimeUnit

class LiveArtistSourceTest {
    @Test fun realArtistCatalogAndSongSearch() = runBlocking {
        assumeTrue("Enable with SONORA_LIVE_TESTS=true", System.getProperty("sonora.liveTests") == "true")
        NewPipe.init(ExtractorDownloader(OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()), Localization("en", "GB"))
        val source = NewPipeMusicSource()
        val artists = source.searchPage("Obrint Pas", CatalogKind.ARTISTS, null)
        assertTrue("Expected live artists", artists.entries.isNotEmpty())
        assertTrue(artists.entries.all { it.kind == CatalogKind.ARTISTS && it.track == null })
        val artist = artists.entries.first { it.title.equals("Obrint Pas", ignoreCase = true) }
        println("Live artist: ${artist.title}; count=${artists.entries.size}; next=${artists.next != null}")
        artists.next?.let {
            val page = source.searchPage("Obrint Pas", CatalogKind.ARTISTS, it)
            assertTrue(page.entries.all { entry -> entry.kind == CatalogKind.ARTISTS })
            println("Live artist second page count=${page.entries.size}")
        }
        val songs = source.searchPage(artist.title, CatalogKind.SONGS, null)
        assertTrue(songs.entries.any { it.track != null })
        println("Live songs for artist: ${songs.entries.size}; first=${songs.entries.first().title}")
    }
}
