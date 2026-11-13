package org.sonora.music.data

import org.sonora.music.i18n.tr

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Join duplicate requests, but never hold a global lock while doing network work. */
class SingleFlightCache<K, V : Any>(private val capacity: Int, private val ttlMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry<V>(val value: V, val expires: Long)
    private val cache = linkedMapOf<K, Entry<V>>()
    private val flights = mutableMapOf<K, CompletableDeferred<V>>()
    private val mutex = Mutex()
    suspend fun get(key: K, loader: suspend () -> V): V {
        while (true) {
            currentCoroutineContext().ensureActive()
            var owner = false
            val flight = mutex.withLock {
                cache[key]?.takeIf { it.expires > now() }?.let { return it.value }
                flights[key] ?: CompletableDeferred<V>().also { flights[key] = it; owner = true }
            }
            if (!owner) {
                try { return flight.await() }
                catch (e: CancellationException) { currentCoroutineContext().ensureActive(); continue }
            }
            try {
                val result = loader()
                currentCoroutineContext().ensureActive()
                val accepted = mutex.withLock {
                    if (flights[key] !== flight) false else {
                        cache.entries.removeAll { it.value.expires <= now() }
                        if (cache.size >= capacity) cache.remove(cache.keys.first())
                        cache[key] = Entry(result, now() + ttlMs)
                        flights.remove(key); flight.complete(result); true
                    }
                }
                if (accepted) return result // Invalidated in-flight results must not repopulate the cache.
            } catch (e: Exception) {
                val current = withContext(NonCancellable) { mutex.withLock {
                    if (flights[key] !== flight) false else { flights.remove(key); flight.completeExceptionally(e); true }
                } }
                currentCoroutineContext().ensureActive()
                if (current) throw e
            }
        }
    }
    suspend fun invalidate(key: K) = mutex.withLock {
        cache.remove(key)
        flights.remove(key)?.cancel(CancellationException(tr("Source invalidated")))
        Unit
    }
}
