package org.sonora.music.discovery

import org.sonora.music.i18n.tr

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sonora.music.domain.Track

data class RadioSnapshot(val seed: Track? = null, val automatic: Map<String, String> = emptyMap(),
    val recent: List<Track> = emptyList(), val loading: Boolean = false, val notice: String? = null) { val active get() = seed != null }
class RadioStatus {
    private val mutable = MutableStateFlow(RadioSnapshot())
    val state = mutable.asStateFlow()
    fun publish(value: RadioSnapshot) { mutable.value = value }
}
interface RadioQueue {
    fun tracks(): List<Track>
    fun index(): Int
    fun activePlayback(): Boolean
    fun append(recommendations: List<Recommendation>)
    fun removeUpcoming(urls: Set<String>)
}
/** Main-confined session owner. Background requests may only append to the generation that requested them. */
class RadioCoordinator(private val source: RecommendationSource, private val queue: RadioQueue,
    private val scope: CoroutineScope, private val status: RadioStatus, private val enabled: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis) {
    private var job: Job? = null
    private var generation = 0
    private var retryAt = 0L
    private var emptyAttempts = 0
    var editing: Boolean = false; private set
    private fun publish(value: RadioSnapshot) = status.publish(value)
    fun replacing(block: () -> Unit) { editing = true; try { block() } finally { editing = false } }
    fun begin(seed: Track, automatic: Map<String, String> = emptyMap(), recent: List<Track> = emptyList()) {
        generation++; job?.cancel(); retryAt = 0; emptyAttempts = 0
        publish(RadioSnapshot(if (enabled()) seed else null, automatic, recent.takeLast(80)))
    }
    fun manual() {
        generation++; job?.cancel(); job = null
        publish(status.state.value.copy(seed = null, loading = false, notice = null,
            automatic = status.state.value.automatic.filterKeys { key -> queue.tracks().any { it.url == key } }))
    }
    fun disable() {
        val upcoming = status.state.value.automatic.keys
        manual(); replacing { queue.removeUpcoming(upcoming) }
        publish(status.state.value.copy(automatic = status.state.value.automatic.filterKeys { key -> queue.tracks().any { it.url == key } }))
    }
    fun played(track: Track) {
        val old = status.state.value
        if (old.seed == null) return
        val recent = if (old.recent.lastOrNull()?.url == track.url) old.recent else (old.recent + track).takeLast(80)
        publish(old.copy(recent = recent)); tick()
    }
    fun retry() { generation++; job?.cancel(); job = null; retryAt = 0; emptyAttempts = 0; tick() }
    fun tick() {
        val state = status.state.value
        if (state.seed == null || !enabled() || !queue.activePlayback() || job?.isActive == true || now() < retryAt || emptyAttempts >= 3) return
        val tracks = queue.tracks(); val index = queue.index()
        if (index !in tracks.indices || tracks.size - index - 1 >= 3) return
        val request = generation
        val anchor = tracks[index]
        publish(state.copy(loading = true, notice = null))
        job = scope.launch {
            try {
                val result = source.recommend(anchor, tracks.map { it.url }.toSet() + state.recent.map { it.url }, state.recent, 5)
                ensureActive()
                if (request != generation || !enabled() || status.state.value.seed == null) return@launch
                // Recheck the current queue: edits/feedback made while fetching cannot resurrect removed entries.
                val excluded = queue.tracks().map { it.url }.toSet() + status.state.value.recent.map { it.url }
                val additions = result.recommendations.filter { it.track.url !in excluded }.distinctBy { it.track.url }.take(5)
                if (additions.isEmpty()) { emptyAttempts++; retryAt = now() + 60000L * emptyAttempts }
                else {
                    emptyAttempts = 0; retryAt = now() + 5000
                    publish(status.state.value.copy(automatic = status.state.value.automatic + additions.associate { it.track.url to it.explanation }))
                    replacing { queue.append(additions) }
                    publish(status.state.value.copy(automatic = status.state.value.automatic.filterKeys { key -> queue.tracks().any { it.url == key } }))
                }
                publish(status.state.value.copy(loading = false, notice = result.notice))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (request == generation) { emptyAttempts++; retryAt = now() + 60000L * emptyAttempts; publish(status.state.value.copy(loading = false, notice = tr("Radio is unavailable. Your existing queue is unchanged."))) } }
        }
    }
    fun close() { generation++; job?.cancel() }
}

/** Elapsed playing time, not seek position, determines completion and soft skip signals. */
class ListeningTracker {
    data class Observation(val track: Track, val kind: String, val listenedMs: Long)
    private var track: Track? = null
    private var total = 0L
    private var pending = 0L
    private var last = 0L
    private var playing = false
    fun start(value: Track?, now: Long) { track = value; total = 0; pending = 0; last = now; playing = false }
    fun tick(isPlaying: Boolean, now: Long): Observation? {
        if (playing) { val delta = (now - last).coerceIn(0, 5000); total += delta; pending += delta }
        last = now; playing = isPlaying
        val current = track ?: return null
        return if (pending >= 10000) Observation(current, "listen", pending).also { pending = 0 } else null
    }
    fun finish(natural: Boolean, userSkip: Boolean, durationMs: Long): Observation? {
        val current = track ?: return null
        val complete = natural && durationMs > 0 && total >= durationMs * .8
        val skip = userSkip && total in 1000..30000 && (durationMs <= 0 || total < durationMs * .2)
        val observation = Observation(current, if (complete) "complete" else if (skip) "skip" else "listen", pending)
        track = null; pending = 0; total = 0; playing = false
        return observation.takeIf { it.listenedMs > 0 || it.kind != "listen" }
    }
}
