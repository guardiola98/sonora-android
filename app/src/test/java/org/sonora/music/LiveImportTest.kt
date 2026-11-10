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
import java.util.concurrent.TimeUnit

class LiveImportTest {
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
