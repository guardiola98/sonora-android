package org.sonora.music

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.sonora.music.data.SingleFlightCache
import org.sonora.music.playback.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@UnstableApi
class PlaybackStartupTest {
    @Test fun canceledScreenPreparationCanRestartImmediatelyOnTheNextScreen() = runTest {
        var calls = 0
        val source = object : org.sonora.music.domain.MusicSource {
            override suspend fun search(query: String) = emptyList<org.sonora.music.domain.Track>()
            override suspend fun resolve(url: String): org.sonora.music.domain.AudioStream { calls++; awaitCancellation() }
        }
        val preferences = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("preparation-test", 0)
        val preparation = PlaybackPreparation(org.sonora.music.data.StreamResolver(source), AudioPrefixCache(), backgroundScope, preferences)
        preparation.setEnabled(true)
        val reference = "https://www.youtube.com/watch?v=sMcWOaJFuw0"
        preparation.browse(listOf(reference)); yield(); assertEquals(1, calls)
        preparation.cancelBrowse(); yield()
        preparation.browse(listOf(reference)); yield(); assertEquals(2, calls)
        preparation.setEnabled(false); yield()
        preparation.browse(listOf(reference)); yield(); assertEquals(2, calls)
    }
    @Test fun unrelatedResolutionNeverWaitsForASlowSpeculativeRequest() = runTest {
        val cache = SingleFlightCache<String, String>(4, 10000)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val slow = async { cache.get("slow") { started.complete(Unit); release.await(); "slow" } }
        started.await()
        assertEquals("fast", withTimeout(100) { cache.get("fast") { "fast" } })
        release.complete(Unit); assertEquals("slow", slow.await())
    }
    @Test fun cancelingSpeculationDoesNotCancelAnActivePlaybackWaiter() = runTest {
        val cache = SingleFlightCache<String, String>(4, 10000)
        val started = CompletableDeferred<Unit>()
        val speculative = launch { cache.get("same") { started.complete(Unit); awaitCancellation() } }
        started.await()
        val playback = async { cache.get("same") { "fresh playback" } }
        yield(); speculative.cancelAndJoin()
        assertEquals("fresh playback", playback.await())
    }
    @Test fun invalidatedInFlightResultCannotResurrectAnExpiredStream() = runTest {
        val cache = SingleFlightCache<String, String>(4, 10000)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val original = async { cache.get("same") { started.complete(Unit); release.await(); "stale" } }
        started.await(); cache.invalidate("same")
        assertEquals("new", cache.get("same") { "new" })
        release.complete(Unit)
        assertEquals("new", original.await())
        assertEquals("new", cache.get("same") { error("Unexpected reload") })
    }
    @Test fun memoryCacheExpiresBoundsCapacityAndRejectsLateWritesAfterInvalidation() {
        var now = 0L; val cache = AudioPrefixCache { now }
        val bytes = byteArrayOf(1, 2, 3)
        val revision = cache.revision()
        assertTrue(cache.put("one", "https://audio.test/one", bytes, 100, revision))
        bytes[0] = 9
        assertEquals(1.toByte(), cache.get("https://audio.test/one")!!.bytes[0])
        cache.invalidate("one")
        assertFalse(cache.put("one", "https://audio.test/old", bytes, 100, revision))
        repeat(5) { cache.put("$it", "https://audio.test/$it", bytes, 100, cache.revision()) }
        assertFalse(cache.hasOwner("0")); assertTrue(cache.hasOwner("4"))
        now += AudioPrefixCache.TTL_MS + 1
        assertNull(cache.get("https://audio.test/4")); assertFalse(cache.hasOwner("4"))
        cache.clear(); assertFalse(cache.hasOwner("1"))
    }
    private class Network(private val data: ByteArray) : DataSource {
        val requests = mutableListOf<DataSpec>()
        var closed = false
        private var index = 0; private var end = 0
        override fun addTransferListener(listener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long {
            requests.add(dataSpec); index = dataSpec.position.toInt()
            end = if (dataSpec.length >= 0) minOf(data.size, index + dataSpec.length.toInt()) else data.size
            return (end - index).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (index >= end) return C.RESULT_END_OF_INPUT
            val count = minOf(length, end - index); data.copyInto(buffer, offset, index, index + count); index += count; return count
        }
        override fun getUri(): Uri? = requests.lastOrNull()?.uri
        override fun close() { closed = true }
    }
    private val uri = Uri.parse("https://audio.test/track?signature=one")
    private val data = ByteArray(30) { it.toByte() }
    private fun cache(size: Int = 8, total: Long? = 30L) = AudioPrefixCache().also { it.put("track", uri.toString(), data.copyOf(size), total, it.revision()) }
    private fun readAll(source: DataSource): ByteArray {
        val result = java.io.ByteArrayOutputStream(); val bytes = ByteArray(3)
        repeat(100) {
            val count = source.read(bytes, 0, bytes.size)
            if (count == C.RESULT_END_OF_INPUT) return result.toByteArray()
            check(count > 0); result.write(bytes, 0, count)
        }
        error("Read did not terminate")
    }
    @Test fun cachedStartOpensWithoutNetworkAndContinuesAtTheExactByteOffset() {
        val network = Network(data); val source = PrefixDataSource(network, cache())
        assertEquals(30L, source.open(DataSpec(uri)))
        assertTrue(network.requests.isEmpty())
        assertArrayEquals(data, readAll(source))
        assertEquals(8L, network.requests.single().position)
        source.close(); assertTrue(network.closed)
    }
    @Test fun seeksInsidePrefixAndFiniteRangesNeverFetchUnnecessaryBytes() {
        val network = Network(data); val source = PrefixDataSource(network, cache())
        assertEquals(4L, source.open(DataSpec.Builder().setUri(uri).setPosition(3).setLength(4).build()))
        assertArrayEquals(data.copyOfRange(3, 7), readAll(source)); source.close()
        assertTrue(network.requests.isEmpty())
    }
    @Test fun media3IcyProbeHeaderCanUseAPrefixFetchedWithTheSameProbe() {
        val network = Network(data); val source = PrefixDataSource(network, cache())
        source.open(DataSpec.Builder().setUri(uri).setHttpRequestHeaders(mapOf("Icy-MetaData" to "1")).build())
        assertTrue(network.requests.isEmpty()); assertArrayEquals(data, readAll(source)); source.close()
        assertEquals(8L, network.requests.single().position)
    }
    @Test fun seekingPastPreparedBytesUsesTheRequestedNetworkRange() {
        val network = Network(data); val source = PrefixDataSource(network, cache())
        source.open(DataSpec.Builder().setUri(uri).setPosition(20).build())
        assertArrayEquals(data.copyOfRange(20, 30), readAll(source)); source.close()
        assertEquals(20L, network.requests.single().position)
    }
    @Test fun unknownContentLengthStillContinuesWithoutDuplicationOrTruncation() {
        val network = Network(data); val source = PrefixDataSource(network, cache(total = null))
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(DataSpec(uri)))
        assertArrayEquals(data, readAll(source)); source.close()
        assertEquals(8L, network.requests.single().position)
    }
    @Test fun completeSmallPrefixFinishesWithoutOpeningTheNetwork() {
        val network = Network(data); val source = PrefixDataSource(network, cache(size = 30))
        source.open(DataSpec(uri)); assertArrayEquals(data, readAll(source)); source.close()
        assertTrue(network.requests.isEmpty())
    }
    @Test fun renewedSignedUrlOrRequestHeadersNeverReuseAnotherResponse() {
        val network = Network(data); val source = PrefixDataSource(network, cache())
        source.open(DataSpec(Uri.parse("https://audio.test/track?signature=new")))
        assertArrayEquals(data, readAll(source)); source.close()
        assertEquals(0L, network.requests.single().position)
        val second = Network(data); val authenticated = PrefixDataSource(second, cache())
        authenticated.open(DataSpec.Builder().setUri(uri).setHttpRequestHeaders(mapOf("X-Variant" to "other")).build())
        assertFalse(second.requests.isEmpty()); authenticated.close()
    }
    @Test fun disablingPreparationCancelsWritesAndDropsAllBufferedAudio() {
        val cache = cache(); val revision = cache.revision(); cache.clear()
        assertNull(cache.get(uri.toString()))
        assertFalse(cache.put("track", uri.toString(), data.copyOf(8), 30, revision))
    }
}
