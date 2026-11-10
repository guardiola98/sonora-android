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

/** Opt-in network probe, not evidence of on-device audio playback. */
class LiveSourceTest {
    @Test fun realSearchExtractionAndAudioBytes() = runBlocking {
        assumeTrue("Enable with SONORA_LIVE_TESTS=true", System.getProperty("sonora.liveTests") == "true")
        val client = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
        NewPipe.init(ExtractorDownloader(client), Localization("en", "GB"))
        val source = NewPipeMusicSource()
        val tracks = source.search("Kevin MacLeod Carefree")
        assertTrue("Expected real search results", tracks.isNotEmpty())
        println("Live search returned ${tracks.size} results; first title: ${tracks.first().title}")
        val stream = source.resolve(tracks.first().url)
        val request = okhttp3.Request.Builder().url(stream.url).header("User-Agent", ExtractorDownloader.USER_AGENT)
            .header("Range", "bytes=0-4095").build()
        client.newCall(request).execute().use { response ->
            assertTrue("Audio HTTP status ${response.code}", response.isSuccessful)
            val bytes = response.body!!.source().readByteArray(1024)
            assertEquals(1024, bytes.size)
            println("Read 1024 audio bytes from an extracted stream. This is not a playback test.")
        }
    }
}
