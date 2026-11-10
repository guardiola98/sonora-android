package org.sonora.music.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.sonora.music.domain.Track

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
    @Query("DELETE FROM tracks WHERE favorite = 0 AND lastPlayedAt IS NULL")
    protected abstract suspend fun prune()
    @Transaction open suspend fun clearHistory() { resetHistory(); prune() }
}
@Database(entities = [LibraryTrack::class], version = 1, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() { abstract fun library(): LibraryDao }
