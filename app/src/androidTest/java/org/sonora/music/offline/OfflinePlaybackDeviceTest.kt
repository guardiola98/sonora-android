package org.sonora.music.offline

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.music.domain.PodcastLinks
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

/** Generated PCM only: no test audio or catalog is shipped in the app. Network fallback fails the test. */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class OfflinePlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            var success = false
            main { success = predicate() }
            if (success) return
            Thread.sleep(100)
        }
        fail(label)
    }
    private fun pcm(): ByteArray {
        val rate = 16_000; val count = rate * 24
        return ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray())
            putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(count * 2)
            repeat(count) { putShort((sin(2 * Math.PI * 330 * it / rate) * 1000).toInt().toShort()) }
        }.array()
    }
    @Test fun downloadedMusicAndPodcastDecodeSeekAndRestoreWithoutNetwork() {
        val folder = File(context.cacheDir, "offline-device-${System.nanoTime()}").apply { mkdirs() }
        val provider = StandaloneDatabaseProvider(context)
        val index = DefaultDownloadIndex(provider, "offline_device_test")
        var cache = SimpleCache(folder, NoOpCacheEvictor(), provider)
        var player: ExoPlayer? = null
        val networkCalls = AtomicInteger()
        val music = "https://www.youtube.com/watch?v=s0n0raDL001"
        val podcast = PodcastLinks.reference("https://example.org/sonora-test.xml", "offline-device-fixture")
        try {
            val audio = pcm()
            for (reference in listOf(music, podcast)) {
                val request = DownloadRequest.Builder(reference, Uri.parse(reference)).setCustomCacheKey(reference).build()
                OfflineAudioDownloader(request, cache, DataSource.Factory { ByteArrayDataSource(audio) }) { "https://example.org/fixture.wav?temporary=not-persisted" }.download(null)
                assertTrue(completeAudio(cache, reference))
                index.putDownload(Download(request, Download.STATE_COMPLETED, 1, 2, audio.size.toLong(), 0, 0))
            }
            // Reopen the actual cache and index as a process restart would, before creating the player.
            cache.release()
            cache = SimpleCache(folder, NoOpCacheEvictor(), provider)
            val reopened = DefaultDownloadIndex(provider, "offline_device_test")
            val factory = DataSource.Factory { OfflinePlaybackDataSource(cache,
                { reopened.getDownload(it)?.state == Download.STATE_COMPLETED },
                DataSource.Factory { networkCalls.incrementAndGet(); error("Offline audio requested the network") }) }
            main {
                player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
                    setMediaItems(listOf(music, podcast).map { MediaItem.Builder().setMediaId(it).setUri(it).build() })
                    prepare(); play()
                }
            }
            await("Downloaded song did not decode") { player!!.isPlaying && player!!.currentPosition > 500 }
            main { player!!.seekTo(10_000) }
            await("Downloaded song did not seek") { player!!.currentPosition >= 10_000 && player!!.isPlaying }
            main { player!!.seekToNextMediaItem() }
            await("Downloaded podcast did not decode") { player!!.currentMediaItemIndex == 1 && player!!.isPlaying && player!!.currentPosition > 500 }
            main { player!!.setPlaybackSpeed(1.5f); player!!.seekTo(12_000) }
            await("Downloaded podcast did not seek") { player!!.currentPosition >= 12_000 && player!!.isPlaying }
            main { assertEquals(1.5f, player!!.playbackParameters.speed); assertNull(player!!.playerError); player!!.pause() }
            assertEquals(0, networkCalls.get())
            main { player!!.release(); player = null }
            OfflineAudioDownloader(DownloadRequest.Builder(music, Uri.parse(music)).build(), cache, DataSource.Factory { error("No fetch during removal") }) { error("No resolve during removal") }.remove()
            assertFalse(completeAudio(cache, music))
            assertTrue(completeAudio(cache, podcast))
        } finally {
            main { player?.release() }
            index.removeDownload(music); index.removeDownload(podcast)
            cache.release(); provider.close(); folder.deleteRecursively()
        }
    }
}
