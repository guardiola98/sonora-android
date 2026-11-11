package org.sonora.music.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.sonora.music.domain.Track
import org.sonora.music.domain.isPersistentTrack

data class SavedQueue(val tracks: List<Track> = emptyList(), val index: Int = 0, val positionMs: Long = 0, val radioSeed: Track? = null, val automatic: Map<String, String> = emptyMap(), val radioRecent: List<Track> = emptyList()) {
    fun canonical(): SavedQueue {
        val accepted = tracks.mapIndexedNotNull { i, track -> if (isPersistentTrack(track.url)) i to track else null }.take(10_000)
        val selected = accepted.indexOfFirst { it.first == index }
        return SavedQueue(accepted.map { it.second }, selected.coerceAtLeast(0), if (selected >= 0) positionMs.coerceAtLeast(0) else 0, radioSeed?.takeIf { isPersistentTrack(it.url) }, automatic.filterKeys { key -> accepted.any { it.second.url == key } }, radioRecent.filter { isPersistentTrack(it.url) }.takeLast(80))
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
            if (previous?.tracks != queue.tracks || previous.radioSeed != queue.radioSeed || previous.automatic != queue.automatic || previous.radioRecent != queue.radioRecent || dao.saveQueuePosition(queue.index, queue.positionMs) == 0) dao.saveCheckpoint(encode(queue))
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
        fun encode(queue: SavedQueue) = PlaybackCheckpoint(queueJson = JSONObject().put("version", 2)
            .put("tracks", JSONArray().apply { queue.tracks.forEach { put(BackupCodec.trackJson(it)) } })
            .put("radioSeed", queue.radioSeed?.let(BackupCodec::trackJson))
            .put("automatic", JSONObject(queue.automatic))
            .put("radioRecent", JSONArray().apply { queue.radioRecent.takeLast(80).forEach { put(BackupCodec.trackJson(it)) } }).toString(),
            currentIndex = queue.index, positionMs = queue.positionMs)
        fun decode(row: PlaybackCheckpoint): SavedQueue {
            require(row.queueJson.length <= BackupCodec.MAX_BYTES)
            val root = if (row.queueJson.trimStart().startsWith("{")) JSONObject(row.queueJson).also { require(it.getInt("version") == 2) } else null
            val array = root?.getJSONArray("tracks") ?: JSONArray(row.queueJson)
            require(array.length() <= 10_000)
            val automatic = root?.optJSONObject("automatic")
            val recent = root?.optJSONArray("radioRecent") ?: JSONArray()
            require(recent.length() <= 80)
            return SavedQueue((0 until array.length()).map { BackupCodec.parseTrack(array.getJSONObject(it)) }, row.currentIndex, row.positionMs,
                root?.optJSONObject("radioSeed")?.let(BackupCodec::parseTrack),
                automatic?.keys()?.asSequence()?.take(10000)?.associateWith { automatic.getString(it).take(300) }.orEmpty(),
                (0 until recent.length()).map { BackupCodec.parseTrack(recent.getJSONObject(it)) }).canonical()
        }
    }
}
