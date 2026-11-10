package org.sonora.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.sonora.music.data.StreamResolver
import org.sonora.music.domain.*

class StreamResolverTest {
    private class Source : MusicSource {
        var calls = 0
        var fail = false
        override suspend fun search(query: String) = emptyList<Track>()
        override suspend fun resolve(url: String): AudioStream {
            calls++; delay(20)
            if (fail) throw MusicSourceException(MusicSourceException.Kind.NETWORK)
            return AudioStream("https://audio.example/$calls")
        }
    }
    @Test fun concurrentLoadsShareResolutionAndExpiredUrlsAreRefreshed() = runTest {
        val source = Source()
        var time = 1000L
        val resolver = StreamResolver(source) { time }
        val results = (1..5).map { async { resolver.resolve("track") } }.awaitAll()
        assertEquals(1, source.calls)
        assertEquals(1, results.distinct().size)
        time += 300_001
        assertNotEquals(results.first(), resolver.resolve("track"))
        assertEquals(2, source.calls)
    }
    @Test fun serverRejectionCanInvalidateAnOtherwiseFreshUrl() = runTest {
        val source = Source(); val resolver = StreamResolver(source)
        val old = resolver.resolve("track")
        resolver.invalidate("track")
        assertNotEquals(old, resolver.resolve("track"))
    }
    @Test fun failedResolutionIsNotCached() = runTest {
        val source = Source(); val resolver = StreamResolver(source)
        source.fail = true
        try { resolver.resolve("track"); fail("Expected network failure") } catch (_: MusicSourceException) { }
        source.fail = false
        assertTrue(resolver.resolve("track").url.isNotBlank())
        assertEquals(2, source.calls)
    }
    @Test fun distinctTracksNeverReuseEachOthersStreams() = runTest {
        val source = Source(); val resolver = StreamResolver(source)
        assertNotEquals(resolver.resolve("one"), resolver.resolve("two"))
    }
}
