package org.sonora.music.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.sonora.music.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
data class PlaylistSummary(@Embedded val playlist: SavedPlaylist, val trackCount: Int)

@Dao
abstract class LibraryDao {
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
            favorite = old?.favorite ?: false, lastPlayedAt = time, playCount = (old?.playCount ?: 0) + 1))
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
                    updatePlaylist(existing.copy(name = collection.name, importedAt = time)); existing.id
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
                    track.artist.takeUnless { it.isBlank() || it == "Unknown artist" } ?: old?.artist ?: track.artist,
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
@Database(entities = [LibraryTrack::class, SavedPlaylist::class, PlaylistEntry::class], version = 2, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() { abstract fun library(): LibraryDao }
