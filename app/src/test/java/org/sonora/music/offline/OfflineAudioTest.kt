package org.sonora.music.offline

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.offline.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.sonora.music.domain.PodcastLinks
import org.sonora.music.playback.AudioPrefixCache
import org.sonora.music.playback.PlaybackPreparation
import org.sonora.music.data.StreamResolver
import org.sonora.music.domain.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@UnstableApi
class OfflineAudioTest {
    private val music = "https://www.youtube.com/watch?v=sMcWOaJFuw0"
    private val podcast = PodcastLinks.reference("https://example.org/feed.xml", "offline-episode")
    private fun request(reference: String) = DownloadRequest.Builder(reference, Uri.parse(reference)).setCustomCacheKey(reference).build()
    private fun read(source: DataSource, reference: String, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            source.open(DataSpec.Builder().setUri(reference).setPosition(position).setLength(length).build())
            val buffer = ByteArray(4096)
            while (true) { val count = source.read(buffer, 0, buffer.size); if (count == C.RESULT_END_OF_INPUT) break; out.write(buffer, 0, count) }
        } finally { source.close() }
        return out.toByteArray()
    }
    private fun fixture(block: (SimpleCache, StandaloneDatabaseProvider) -> Unit) {
        val folder = Files.createTempDirectory("sonora-offline").toFile()
        val provider = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        val cache = SimpleCache(folder, NoOpCacheEvictor(), provider)
        try { block(cache, provider) } finally { cache.release(); provider.close(); folder.deleteRecursively() }
    }
    @Test fun musicAndPodcastsDownloadFullyAndSeekOfflineWithoutContactingASource() = fixture { cache, _ ->
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        for (reference in listOf(music, podcast)) {
            var resolutions = 0
            val downloader = OfflineAudioDownloader(request(reference), cache, DataSource.Factory { ByteArrayDataSource(bytes) }) {
                resolutions++; "https://audio.example.org/temporary?token=must-not-be-persisted"
            }
            downloader.download(null)
            assertEquals(1, resolutions)
            assertTrue(completeAudio(cache, reference))
            assertEquals(reference, ContentMetadata.getRedirectedUri(cache.getContentMetadata(reference))?.toString() ?: reference)
            assertFalse(cache.keys.any { it.contains("token") })
            val offline = OfflinePlaybackDataSource(cache, { true }, DataSource.Factory { error("Offline playback must not create a network source") })
            assertArrayEquals(bytes, read(offline, reference))
            assertArrayEquals(bytes.copyOfRange(12345, 22345), read(offline, reference, 12345, 10000))
            assertEquals(1, resolutions)
        }
    }
    @Test fun incompleteAudioIsNeverAdvertisedAsCompleteAndANewTaskDoesNotMixFormats() = fixture { cache, _ ->
        val bytes = ByteArray(100_000) { 7 }
        val failing = DataSource.Factory {
            object : DataSource {
                val delegate = ByteArrayDataSource(bytes)
                var delivered = 0
                override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)
                override fun open(spec: DataSpec) = delegate.open(spec)
                override fun getUri() = delegate.uri
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (delivered >= 4096) throw IOException("Connection lost")
                    return delegate.read(buffer, offset, minOf(length, 4096)).also { delivered += it }
                }
                override fun close() = delegate.close()
            }
        }
        try { OfflineAudioDownloader(request(music), cache, failing) { "https://audio.example.org/old" }.download(null); fail("Expected interruption") }
        catch (_: IOException) { }
        assertFalse(completeAudio(cache, music))
        val different = ByteArray(80_000) { 9 }
        OfflineAudioDownloader(request(music), cache, DataSource.Factory { ByteArrayDataSource(different) }) { "https://audio.example.org/new" }.download(null)
        assertArrayEquals(different, read(OfflinePlaybackDataSource(cache, { true }, DataSource.Factory { error("No network") }), music))
    }
    @Test fun completedDownloadAndMetadataSurviveCacheAndIndexReopening() {
        val folder = Files.createTempDirectory("sonora-offline-restart").toFile()
        val context = RuntimeEnvironment.getApplication()
        val provider = StandaloneDatabaseProvider(context)
        val index = DefaultDownloadIndex(provider, "restart_test")
        var cache = SimpleCache(folder, NoOpCacheEvictor(), provider)
        val bytes = ByteArray(50_000) { (it % 77).toByte() }
        try {
            OfflineAudioDownloader(request(podcast), cache, DataSource.Factory { ByteArrayDataSource(bytes) }) { "https://audio.example.org/episode" }.download(null)
            index.putDownload(Download(request(podcast), Download.STATE_COMPLETED, 1, 2, bytes.size.toLong(), 0, 0))
            cache.release()
            cache = SimpleCache(folder, NoOpCacheEvictor(), provider)
            val reopened = DefaultDownloadIndex(provider, "restart_test")
            assertEquals(Download.STATE_COMPLETED, reopened.getDownload(podcast)?.state)
            assertTrue(completeAudio(cache, podcast))
            assertArrayEquals(bytes, read(OfflinePlaybackDataSource(cache, { reopened.getDownload(it)?.state == Download.STATE_COMPLETED }, DataSource.Factory { error("No network after restart") }), podcast))
            OfflineAudioDownloader(request(podcast), cache, DataSource.Factory { error("Removal must not resolve or fetch") }) { error("Removal must not resolve") }.remove()
            assertFalse(completeAudio(cache, podcast))
            assertEquals(0, cache.cacheSpace)
        } finally { cache.release(); provider.close(); folder.deleteRecursively() }
    }
    @Test fun expiredStreamsAreResolvedAgainWhileSignedUrlsRemainOutOfTheCache() = fixture { cache, _ ->
        var resolutions = 0
        val bytes = ByteArray(8000) { 3 }
        val factory = DataSource.Factory {
            if (resolutions > 1) ByteArrayDataSource(bytes) else object : DataSource {
                override fun addTransferListener(listener: TransferListener) { }
                override fun open(spec: DataSpec): Long = throw HttpDataSource.InvalidResponseCodeException(403, "Expired", null, emptyMap(), spec, byteArrayOf())
                override fun getUri(): Uri? = null
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = error("not opened")
                override fun close() { }
            }
        }
        val downloader = OfflineAudioDownloader(request(music), cache, factory) { resolutions++; "https://audio.example.org/file?token=$resolutions" }
        try { downloader.download(null); fail("Expected expiry") } catch (_: IOException) { }
        downloader.download(null)
        assertEquals(2, resolutions)
        assertTrue(completeAudio(cache, music))
    }
    @Test fun cancelledDownloadCannotResolveAndCompletedItemsAreNotSpeculativelyFetched() = fixture { cache, _ ->
        val downloader = OfflineAudioDownloader(request(music), cache, DataSource.Factory { error("No download after cancellation") }) { error("No resolution after cancellation") }
        downloader.cancel()
        try { downloader.download(null); fail("Expected cancellation") } catch (_: InterruptedException) { }
        val source = object : MusicSource {
            override suspend fun search(query: String) = emptyList<Track>()
            override suspend fun resolve(url: String): AudioStream = error("No extraction of downloaded audio")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("offline-preparation", 0)
        val preparation = PlaybackPreparation(StreamResolver(source), AudioPrefixCache(), scope, prefs) { true }
        try { runBlocking { preparation.prepare(music) } } finally { scope.cancel() }
    }
}
