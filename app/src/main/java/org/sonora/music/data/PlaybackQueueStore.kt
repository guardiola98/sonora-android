package org.sonora.music.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.sonora.music.domain.Track
import org.sonora.music.domain.isPersistentTrack

data class SavedQueue(val tracks: List<Track> = emptyList(), val index: Int = 0, val positionMs: Long = 0) {
    fun canonical(): SavedQueue {
        val accepted = tracks.mapIndexedNotNull { i, track -> if (isPersistentTrack(track.url)) i to track else null }.take(10_000)
        val selected = accepted.indexOfFirst { it.first == index }
        return SavedQueue(accepted.map { it.second }, selected.coerceAtLeast(0), if (selected >= 0) positionMs.coerceAtLeast(0) else 0)
    }
}
/** One process-wide writer preserves event order and outlives service teardown. */
class PlaybackQueueStore(private val dao: LibraryDao, scope: CoroutineScope, private val onError: () -> Unit) {
    @Volatile private var latest: SavedQueue? = null
    private val pending = Channel<SavedQueue>(Channel.CONFLATED)
    init { scope.launch {
        var previous: SavedQueue? = null
        for (pendingQueue in pending) try {
            val queue = pendingQueue.canonical()
            if (previous?.tracks != queue.tracks || dao.saveQueuePosition(queue.index, queue.positionMs) == 0) dao.saveCheckpoint(encode(queue))
            previous = queue
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { onError() }
    } }
    fun save(queue: SavedQueue) { latest = queue; pending.trySend(queue) }
    suspend fun read(): SavedQueue {
        latest?.let { return it.canonical() }
        val stored = dao.checkpoint()?.let(::decode) ?: SavedQueue()
        return latest?.canonical() ?: stored
    }
    companion object {
        fun encode(queue: SavedQueue) = PlaybackCheckpoint(queueJson = JSONArray().apply { queue.tracks.forEach { put(BackupCodec.trackJson(it)) } }.toString(), currentIndex = queue.index, positionMs = queue.positionMs)
        fun decode(row: PlaybackCheckpoint): SavedQueue {
            require(row.queueJson.length <= BackupCodec.MAX_BYTES)
            val array = JSONArray(row.queueJson)
            require(array.length() <= 10_000)
            return SavedQueue((0 until array.length()).map { BackupCodec.parseTrack(array.getJSONObject(it)) }, row.currentIndex, row.positionMs).canonical()
        }
    }
}
