package org.sonora.music.playback

import android.content.SharedPreferences
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sonora.music.data.ExtractorDownloader
import org.sonora.music.data.StreamResolver
import org.sonora.music.domain.isPersistentTrack

/** One bounded speculative worker. Playback can join extraction or cancel unrelated preparation. */
@UnstableApi
class PlaybackPreparation(private val streams: StreamResolver, val prefixes: AudioPrefixCache,
    private val scope: CoroutineScope, private val preferences: SharedPreferences) {
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean("prepare_audio", true))
    val enabled = mutableEnabled.asStateFlow()
    private val lock = Any()
    private var job: Job? = null
    private var owner = ""
    private var version = 0
    private var current: String? = null
    private var resolving = false
    private var pending = emptySet<String>()
    @Volatile private var selected: String? = null
    private val attempted = linkedMapOf<String, Long>()
    fun setEnabled(value: Boolean) {
        mutableEnabled.value = value
        preferences.edit().putBoolean("prepare_audio", value).apply()
        if (!value) synchronized(lock) { cancelLocked(); prefixes.clear(); attempted.clear() }
    }
    fun browse(urls: List<String>) = offer(urls.take(2), "browse")
    fun next(url: String) = offer(listOf(url), "queue")
    fun cancelBrowse() = synchronized(lock) { if (owner == "browse") cancelLocked() }
    fun prioritize(url: String?) = synchronized(lock) {
        selected = url
        if (current == url && resolving && job?.isActive == true) owner = "playback"
        else cancelLocked()
    }
    private fun cancelLocked() {
        version++; job?.cancel(); job = null
        // A canceled attempt must not suppress a new screen's request for the same track.
        pending.forEach(attempted::remove); pending = emptySet()
        current = null; resolving = false
    }
    private fun offer(urls: List<String>, origin: String) = synchronized(lock) {
        if (!mutableEnabled.value) return@synchronized
        if (owner == "playback" && job?.isActive == true || origin == "browse" && owner == "queue" && job?.isActive == true) return@synchronized
        val now = android.os.SystemClock.elapsedRealtime()
        val candidates = urls.distinct().filter { it != selected && isPersistentTrack(it) && !prefixes.hasOwner(it) && now - (attempted[it] ?: -60_000) > 15_000 }
        if (candidates.isEmpty()) return@synchronized
        cancelLocked(); val request = version
        owner = origin; pending = candidates.toSet()
        candidates.forEach { attempted[it] = now }
        while (attempted.size > 50) attempted.remove(attempted.keys.first())
        job = scope.launch {
            try {
                for (url in candidates) {
                    ensureActive()
                    synchronized(lock) { if (version == request) { current = url; resolving = true } }
                    try {
                        prepare(url) { synchronized(lock) { if (version == request) resolving = false } }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Speculation never displays errors, plays audio or writes history. */ }
                    synchronized(lock) { if (version == request) pending = pending - url }
                }
            } finally { synchronized(lock) { if (version == request) { current = null; resolving = false } } }
        }
    }
    /** Shared by the speculative worker and opt-in on-device benchmarks. Never persists audio. */
    suspend fun prepare(reference: String, onResolved: () -> Unit = {}) {
        val revision = prefixes.revision()
        val stream = streams.resolve(reference)
        onResolved()
        currentCoroutineContext().ensureActive()
        if (prefixes.hasOwner(reference) || selected == reference) return
        val loaded = runInterruptible(Dispatchers.IO) {
            val source = DefaultHttpDataSource.Factory().setUserAgent(ExtractorDownloader.USER_AGENT)
                .setConnectTimeoutMs(5_000).setReadTimeoutMs(5_000).createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(stream.url.toUri()).setPosition(0).setLength(AudioPrefixCache.PREFIX_BYTES.toLong()).setHttpRequestHeaders(mapOf("Icy-MetaData" to "1")).build())
                fun header(name: String) = source.responseHeaders.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
                require(header("icy-metaint") == null) { "Interleaved live radio metadata is not preloaded." }
                val total = if (source.responseCode == 206) header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    else if (source.responseCode == 200) header("Content-Length")?.toLongOrNull() else null
                val bytes = ByteArray(minOf(AudioPrefixCache.PREFIX_BYTES.toLong(), total?.takeIf { it > 0 } ?: Long.MAX_VALUE).toInt())
                var count = 0
                while (count < bytes.size) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val read = source.read(bytes, count, bytes.size - count)
                    if (read == C.RESULT_END_OF_INPUT) break
                    count += read
                }
                bytes.copyOf(count) to total
            } finally { source.close() }
        }
        currentCoroutineContext().ensureActive()
        if (loaded.first.isNotEmpty()) prefixes.put(reference, stream.url, loaded.first, loaded.second, revision)
    }
}
