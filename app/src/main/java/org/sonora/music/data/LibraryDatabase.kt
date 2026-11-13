package org.sonora.music.data

import org.sonora.music.i18n.tr

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.sonora.music.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID

@Entity(tableName = "tracks")
data class LibraryTrack(
    @PrimaryKey val url: String,
    val title: String,
    val artist: String,
    val artwork: String,
    val durationSeconds: Long,
    val favorite: Boolean = false,
    val lastPlayedAt: Long? = null,
    val playCount: Int = 0
) {
    fun track() = Track(url, title, artist, artwork, durationSeconds)
}
@Entity(tableName = "playlists", indices = [Index(value = ["sourceKey"], unique = true)])
data class SavedPlaylist(@PrimaryKey(autoGenerate = true) val id: Long = 0, val sourceKey: String, val name: String, val importedAt: Long)
@Entity(tableName = "playlist_entries", primaryKeys = ["playlistId", "trackUrl"],
    foreignKeys = [ForeignKey(entity = SavedPlaylist::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = LibraryTrack::class, parentColumns = ["url"], childColumns = ["trackUrl"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["trackUrl"]), Index(value = ["playlistId", "position"])])
data class PlaylistEntry(val playlistId: Long, val trackUrl: String, val position: Int)
@Entity(tableName = "playback_checkpoint")
data class PlaybackCheckpoint(@PrimaryKey val id: Int = 1, val queueJson: String, val currentIndex: Int, val positionMs: Long)
data class PlaylistSummary(@Embedded val playlist: SavedPlaylist, val trackCount: Int)

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM tracks WHERE url LIKE 'https://www.youtube.com/%' ORDER BY favorite DESC, lastPlayedAt DESC, playCount DESC LIMIT 5000")
    abstract suspend fun discoveryTracks(): List<LibraryTrack>
    @Query("SELECT * FROM tracks ORDER BY favorite DESC, lastPlayedAt DESC LIMIT 50000")
    abstract suspend fun searchableTracks(): List<LibraryTrack>

    @Query("SELECT * FROM tracks WHERE favorite = 1 ORDER BY title COLLATE NOCASE")
    abstract fun favorites(): Flow<List<LibraryTrack>>
    @Query("SELECT * FROM tracks WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT 200")
    abstract fun history(): Flow<List<LibraryTrack>>
    @Query("SELECT * FROM tracks WHERE url = :url")
    abstract suspend fun find(url: String): LibraryTrack?
    @Upsert abstract suspend fun put(track: LibraryTrack)
    @Transaction
    open suspend fun toggleFavorite(track: Track) {
        val old = find(track.url)
        put(LibraryTrack(track.url, track.title, track.artist, track.artwork, track.durationSeconds,
            favorite = !(old?.favorite ?: false), lastPlayedAt = old?.lastPlayedAt, playCount = old?.playCount ?: 0))
    }
    @Transaction
    open suspend fun recordPlay(track: Track, time: Long) {
        val old = find(track.url)
        put(LibraryTrack(track.url, track.title, track.artist, track.artwork, track.durationSeconds,
            favorite = old?.favorite ?: false, lastPlayedAt = time, playCount = ((old?.playCount ?: 0).toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
    }
    @Query("UPDATE tracks SET lastPlayedAt = NULL, playCount = 0")
    protected abstract suspend fun resetHistory()
    @Query("DELETE FROM tracks WHERE favorite = 0 AND lastPlayedAt IS NULL AND url NOT IN (SELECT trackUrl FROM playlist_entries)")
    protected abstract suspend fun prune()
    @Transaction open suspend fun clearHistory() { resetHistory(); prune() }
    @Query("SELECT playlists.*, (SELECT COUNT(*) FROM playlist_entries WHERE playlistId = playlists.id) AS trackCount FROM playlists ORDER BY name COLLATE NOCASE")
    abstract fun playlists(): Flow<List<PlaylistSummary>>
    @Query("SELECT tracks.* FROM tracks INNER JOIN playlist_entries ON tracks.url = playlist_entries.trackUrl WHERE playlistId = :id ORDER BY position")
    abstract fun playlistTracks(id: Long): Flow<List<LibraryTrack>>
    @Query("SELECT * FROM playlists WHERE sourceKey = :key LIMIT 1")
    abstract suspend fun findPlaylist(key: String): SavedPlaylist?
    @Insert protected abstract suspend fun addPlaylist(playlist: SavedPlaylist): Long
    @Update protected abstract suspend fun updatePlaylist(playlist: SavedPlaylist)
    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position")
    protected abstract suspend fun entries(id: Long): List<PlaylistEntry>
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun addEntry(entry: PlaylistEntry): Long
    @Query("DELETE FROM playlists WHERE id = :id") protected abstract suspend fun deletePlaylistRow(id: Long)
    @Transaction open suspend fun deletePlaylist(id: Long) { deletePlaylistRow(id); prune() }

    @Query("SELECT * FROM playlists WHERE id = :id") abstract suspend fun playlist(id: Long): SavedPlaylist?
    @Query("UPDATE playlists SET name = :name WHERE id = :id") protected abstract suspend fun renameRow(id: Long, name: String)
    @Query("DELETE FROM playlist_entries WHERE playlistId = :id AND trackUrl = :url") protected abstract suspend fun removeEntry(id: Long, url: String)
    @Update protected abstract suspend fun updateEntries(entries: List<PlaylistEntry>)
    @Transaction open suspend fun createPlaylist(name: String): Long {
        return addPlaylist(SavedPlaylist(sourceKey = "local:${UUID.randomUUID()}", name = validName(name), importedAt = System.currentTimeMillis()))
    }
    @Transaction open suspend fun createPlaylistWithTracks(name: String, tracks: List<Track>): Long {
        val id = createPlaylist(name)
        addToPlaylist(id, tracks)
        return id
    }
    @Transaction open suspend fun renamePlaylist(id: Long, name: String) { require(playlist(id) != null); renameRow(id, validName(name)) }
    @Transaction open suspend fun addToPlaylist(id: Long, tracks: List<Track>) {
        val list = requireNotNull(playlist(id)) { tr("Playlist no longer exists.") }
        importCollections(listOf(ImportSelection(ImportCollection(list.sourceKey, list.name, tracks), true, false)), System.currentTimeMillis())
    }
    @Transaction open suspend fun removeFromPlaylist(id: Long, url: String) { removeEntry(id, url); prune() }
    @Transaction open suspend fun reorderPlaylist(id: Long, order: List<String>) {
        val current = entries(id)
        // Entries added by a concurrent import are kept at the end; removed entries stay removed.
        val byUrl = current.associateBy { it.trackUrl }
        val orderedUrls = order.toSet()
        val reordered = (order.distinct().mapNotNull { byUrl[it] } + current.filter { it.trackUrl !in orderedUrls })
        updateEntries(reordered.mapIndexed { index, entry -> entry.copy(position = index) })
    }
    private fun validName(name: String): String = name.trim().also { require(it.isNotEmpty() && it.length <= 180) { tr("Use a playlist name of 1–180 characters.") } }

    @Query("SELECT * FROM tracks WHERE favorite = 1 OR lastPlayedAt IS NOT NULL OR url IN (SELECT trackUrl FROM playlist_entries)")
    protected abstract suspend fun backupTracks(): List<LibraryTrack>
    @Query("SELECT * FROM playlists ORDER BY id") protected abstract suspend fun allPlaylists(): List<SavedPlaylist>
    @Transaction open suspend fun exportBackup(): LibraryBackup {
        val tracks = backupTracks().filter { isPersistentTrack(it.url) }
        val urls = tracks.map { it.url }.toSet()
        return LibraryBackup(System.currentTimeMillis(), tracks, allPlaylists().map { list ->
            BackupPlaylist(list.sourceKey, list.name, entries(list.id).map { it.trackUrl }.filter { it in urls })
        }, backupPodcasts(), backupProgress())
    }
    @Query("SELECT * FROM podcast_feeds WHERE subscribed = 1") protected abstract suspend fun backupPodcasts(): List<SavedPodcast>
    @Query("SELECT * FROM podcast_progress") protected abstract suspend fun backupProgress(): List<PodcastProgress>
    @Query("SELECT * FROM podcast_feeds WHERE feedUrl = :url") protected abstract suspend fun podcast(url: String): SavedPodcast?
    @Query("SELECT * FROM podcast_progress WHERE reference = :url") protected abstract suspend fun progress(url: String): PodcastProgress?
    @Upsert protected abstract suspend fun restorePodcast(show: SavedPodcast)
    @Upsert protected abstract suspend fun restoreProgress(progress: PodcastProgress)
    @Transaction open suspend fun restoreBackup(backup: LibraryBackup): RestoreSummary {
        var tracks = 0; var favorites = 0
        for (record in backup.tracks) {
            currentCoroutineContext().ensureActive()
            val old = find(record.url)
            if (old == null) tracks++
            if (record.favorite && old?.favorite != true) favorites++
            put(record.copy(
                title = if (YouTubeLinks.needsDetails(record.track()) && old != null) old.title else record.title,
                artwork = record.artwork.ifBlank { old?.artwork.orEmpty() },
                favorite = record.favorite || old?.favorite == true,
                lastPlayedAt = listOfNotNull(record.lastPlayedAt, old?.lastPlayedAt).maxOrNull(),
                playCount = maxOf(record.playCount, old?.playCount ?: 0)))
        }
        val byUrl = backup.tracks.associateBy { it.url }
        val result = importCollections(backup.playlists.map { list ->
            ImportSelection(ImportCollection(list.key, list.name, list.tracks.map { byUrl.getValue(it).track() }), true, false)
        }, System.currentTimeMillis())
        for (show in backup.podcasts) {
            val old = podcast(show.feedUrl)
            restorePodcast(old?.copy(subscribed = true) ?: show.copy(subscribed = true, refreshedAt = 0))
        }
        for (item in backup.progress) if ((progress(item.reference)?.updatedAt ?: -1) < item.updatedAt) restoreProgress(item)
        return RestoreSummary(tracks, favorites, result.newPlaylists, result.addedEntries)
    }
    @Query("SELECT * FROM playback_checkpoint WHERE id = 1") abstract suspend fun checkpoint(): PlaybackCheckpoint?
    @Upsert abstract suspend fun saveCheckpoint(checkpoint: PlaybackCheckpoint)
    @Query("UPDATE playback_checkpoint SET currentIndex = :index, positionMs = :position WHERE id = 1")
    abstract suspend fun saveQueuePosition(index: Int, position: Long): Int

    /** Merge only: importing likes never toggles existing hearts or touches play history. */
    @Transaction open suspend fun importCollections(selections: List<ImportSelection>, time: Long): ImportSummary {
        var newTracks = 0; var newFavorites = 0; var newPlaylists = 0; var addedEntries = 0
        for (selection in selections) {
            currentCoroutineContext().ensureActive()
            if (!selection.playlist && !selection.favorites) continue
            val collection = selection.collection
            var playlistId: Long? = null
            if (selection.playlist) {
                val existing = findPlaylist(collection.key)
                playlistId = if (existing == null) {
                    newPlaylists++; addPlaylist(SavedPlaylist(sourceKey = collection.key, name = collection.name, importedAt = time))
                } else {
                    updatePlaylist(existing.copy(importedAt = time)); existing.id
                }
            }
            val previous = playlistId?.let { entries(it) }.orEmpty()
            val present = previous.map { it.trackUrl }.toMutableSet()
            var position = (previous.maxOfOrNull { it.position } ?: -1) + 1
            for (track in collection.tracks.distinctBy { it.url }) {
                currentCoroutineContext().ensureActive()
                val old = find(track.url)
                if (old == null) newTracks++
                if (selection.favorites && old?.favorite != true) newFavorites++
                put(LibraryTrack(track.url,
                    if (YouTubeLinks.needsDetails(track) && old != null) old.title else track.title,
                    track.artist.takeUnless { it.isBlank() || it == tr("Unknown artist") } ?: old?.artist ?: track.artist,
                    track.artwork.ifBlank { old?.artwork.orEmpty() },
                    track.durationSeconds.takeIf { it > 0 } ?: old?.durationSeconds ?: 0,
                    favorite = selection.favorites || old?.favorite == true,
                    lastPlayedAt = old?.lastPlayedAt, playCount = old?.playCount ?: 0))
                if (playlistId != null && present.add(track.url)) {
                    addEntry(PlaylistEntry(playlistId, track.url, position++)); addedEntries++
                }
            }
        }
        return ImportSummary(newTracks, newFavorites, newPlaylists, addedEntries)
    }

}
@Database(entities = [LibraryTrack::class, SavedPlaylist::class, PlaylistEntry::class, PlaybackCheckpoint::class, SavedPodcast::class, SavedEpisode::class, PodcastProgress::class, SearchHistoryEntry::class, ListeningPreferenceRow::class, DiscoveryCacheRow::class], version = 5, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() { abstract fun library(): LibraryDao; abstract fun podcasts(): PodcastDao; abstract fun discovery(): DiscoveryDao }
