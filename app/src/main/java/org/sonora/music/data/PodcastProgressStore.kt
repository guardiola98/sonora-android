package org.sonora.music.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.sonora.music.domain.PodcastLinks

/** One ordered writer outlives the service, including final pause/transition events. */
class PodcastProgressStore(private val dao: PodcastDao, scope: CoroutineScope, onError: () -> Unit) {
    private val pending = Channel<PodcastProgress>(Channel.UNLIMITED)
    init { scope.launch {
        for (progress in pending) try { dao.saveProgress(progress) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { onError() }
    } }
    fun save(reference: String, positionMs: Long, durationMs: Long, completed: Boolean = false) {
        if (!PodcastLinks.isEpisode(reference)) return
        pending.trySend(PodcastProgress(reference, positionMs.coerceIn(0, 604800000), durationMs.coerceIn(0, 604800000), completed, System.currentTimeMillis()))
    }
}
