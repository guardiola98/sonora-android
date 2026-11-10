package org.sonora.music.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.sonora.music.domain.AudioStream
import org.sonora.music.domain.MusicSource

/** Short in-memory cache. A queued item is resolved when opened, not when enqueued. */
class StreamResolver(private val source: MusicSource, private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val stream: AudioStream, val expires: Long)
    private val cache = mutableMapOf<String, Entry>()
    private val mutex = Mutex()
    suspend fun resolve(url: String): AudioStream = mutex.withLock {
        cache[url]?.takeIf { it.expires > now() }?.let { return@withLock it.stream }
        val stream = source.resolve(url)
        cache.entries.removeAll { it.value.expires <= now() }
        if (cache.size >= 50) cache.remove(cache.keys.first())
        cache[url] = Entry(stream, now() + 5 * 60 * 1000)
        stream
    }
    suspend fun invalidate(url: String) = mutex.withLock { cache.remove(url); Unit }
}
