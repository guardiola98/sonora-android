package org.sonora.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.sonora.music.importing.*
import org.sonora.music.domain.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import org.sonora.music.data.ExtractorDownloader
import org.sonora.music.data.NewPipeMusicSource
import java.util.concurrent.TimeUnit

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], application = android.app.Application::class)
class LiveImportTest {
    @Test(timeout = 90_000) fun publicSpotifySongsMatchAndImportWithoutAReviewStep() = runBlocking {
        assumeTrue("Opt in with SONORA_LIVE_IMPORT=true", System.getProperty("sonora.liveImport") == "true")
        val client = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
        NewPipe.init(ExtractorDownloader(client), Localization("en", "GB"))
        val playlist = PublicSpotifyPlaylist().read("https://open.spotify.com/playlist/37i9dQZEVXbMDoHDwVN2tF")
        val sample = playlist.copy(songs = playlist.songs.filter { it.source.unavailable == null }.take(3))
        var saved: List<ImportSelection>? = null
        val presenter = SpotifyImportPresenter(this, NewPipeMusicSource()) { saved = it; ImportSummary(newPlaylists = 1) }
        val start = System.nanoTime()
        presenter.loadSpotifyPublicPlaylist { sample }
        val finished = withTimeout(65_000) { presenter.state.first { !it.busy && (it.summary != null || it.error != null) } }
        assertNull(finished.error)
        assertEquals(3, finished.matched)
        assertTrue(saved!!.single().collection.tracks.all { YouTubeLinks.track(it.url) == it.url })
        println("Automatic public Spotify sample: ${finished.matched}/${sample.songs.size} matched and handed to save in ${(System.nanoTime() - start) / 1_000_000} ms; no review step")
    }

    @Test(timeout = 180_000) fun publicPlaylistLoadsBeyondItsFirstPage() = runBlocking {
        assumeTrue("Opt in with SONORA_LIVE_IMPORT=true", System.getProperty("sonora.liveImport") == "true")
        val client = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
        NewPipe.init(ExtractorDownloader(client), Localization("en", "GB"))
        val progress = mutableListOf<Int>()
        // Public playlist also used by upstream to check pagination; no account data.
        val result = NewPipeMusicSource().playlist("https://music.youtube.com/playlist?list=PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj") { progress += it }
        assertTrue("Expected more than the first page", result.tracks.size > 100)
        assertTrue(progress.size > 1)
        assertEquals(result.tracks.size, result.tracks.distinctBy { it.url }.size)
        assertTrue(result.tracks.all { it.title.isNotBlank() && it.url.startsWith("https://www.youtube.com/watch?v=") })
        println("Imported public playlist: ${result.name}; ${result.tracks.size} tracks across ${progress.size} pages")
    }
}
