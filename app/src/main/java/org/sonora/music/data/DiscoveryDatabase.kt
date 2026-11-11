package org.sonora.music.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.sonora.music.discovery.*

@Entity(tableName = "search_history")
data class SearchHistoryEntry(@PrimaryKey val normalized: String, val query: String, val usedAt: Long, val uses: Int = 1)
@Entity(tableName = "listening_preferences")
data class ListeningPreferenceRow(@PrimaryKey val url: String, val completions: Int = 0, val earlySkips: Int = 0,
    val removals: Int = 0, val more: Int = 0, val hidden: Boolean = false, val listenedMs: Long = 0, val updatedAt: Long = 0) {
    fun domain() = ListeningPreference(url, completions, earlySkips, removals, more, hidden, listenedMs, updatedAt)
}
@Entity(tableName = "discovery_cache", indices = [Index("expiresAt")])
data class DiscoveryCacheRow(@PrimaryKey val key: String, val payload: String, val source: String, val fetchedAt: Long, val expiresAt: Long)
@Dao
abstract class DiscoveryDao {
    @Query("SELECT * FROM search_history ORDER BY usedAt DESC LIMIT 50") abstract fun searches(): Flow<List<SearchHistoryEntry>>
    @Query("SELECT * FROM search_history WHERE normalized = :key") protected abstract suspend fun search(key: String): SearchHistoryEntry?
    @Upsert protected abstract suspend fun saveSearch(entry: SearchHistoryEntry)
    @Query("DELETE FROM search_history WHERE normalized NOT IN (SELECT normalized FROM search_history ORDER BY usedAt DESC LIMIT 50)") protected abstract suspend fun trimSearches()
    @Transaction open suspend fun rememberSearch(query: String, time: Long = System.currentTimeMillis()) {
        val text = query.trim().take(200); val key = MusicText.normalize(text); if (key.isBlank()) return
        val old = search(key); saveSearch(SearchHistoryEntry(key, text, time, ((old?.uses ?: 0) + 1).coerceAtMost(100000))); trimSearches()
    }
    @Query("DELETE FROM search_history") abstract suspend fun clearSearches()
    @Query("SELECT * FROM listening_preferences") abstract fun observePreferences(): Flow<List<ListeningPreferenceRow>>
    @Query("SELECT * FROM listening_preferences") abstract suspend fun preferences(): List<ListeningPreferenceRow>
    @Query("SELECT * FROM listening_preferences WHERE url = :url") abstract suspend fun preference(url: String): ListeningPreferenceRow?
    @Upsert protected abstract suspend fun putPreference(row: ListeningPreferenceRow)
    @Transaction open suspend fun feedback(url: String, kind: String, listenedMs: Long = 0, time: Long = System.currentTimeMillis()) {
        val old = preference(url) ?: ListeningPreferenceRow(url)
        putPreference(old.copy(completions = (old.completions + if (kind == "complete") 1 else 0).coerceAtMost(100000),
            earlySkips = (old.earlySkips + if (kind == "skip") 1 else 0).coerceAtMost(100000),
            removals = (old.removals + if (kind == "remove") 1 else 0).coerceAtMost(100000),
            more = (old.more + if (kind == "more") 1 else 0).coerceAtMost(100000),
            hidden = if (kind == "hide") true else if (kind == "unhide") false else old.hidden,
            listenedMs = old.listenedMs + listenedMs.coerceIn(0, 86400000), updatedAt = time))
        trimPreferences()
    }
    @Query("DELETE FROM listening_preferences WHERE hidden = 0 AND url NOT IN (SELECT url FROM listening_preferences ORDER BY updatedAt DESC LIMIT 20000)") protected abstract suspend fun trimPreferences()
    @Query("DELETE FROM listening_preferences") abstract suspend fun resetPreferences()
    @Query("SELECT * FROM discovery_cache WHERE `key` = :key") abstract suspend fun cache(key: String): DiscoveryCacheRow?
    @Query("SELECT * FROM discovery_cache WHERE `key` LIKE 'metadata:%' AND expiresAt > :now ORDER BY fetchedAt DESC LIMIT 1000") abstract fun metadata(now: Long): Flow<List<DiscoveryCacheRow>>
    @Query("SELECT * FROM discovery_cache WHERE `key` LIKE 'metadata:%' AND expiresAt > :now ORDER BY fetchedAt DESC LIMIT 1000") abstract suspend fun metadataSnapshot(now: Long): List<DiscoveryCacheRow>
    @Upsert protected abstract suspend fun putCache(row: DiscoveryCacheRow)
    @Query("DELETE FROM discovery_cache WHERE expiresAt <= :now OR `key` NOT IN (SELECT `key` FROM discovery_cache ORDER BY fetchedAt DESC LIMIT 1000)") protected abstract suspend fun trimCache(now: Long)
    @Transaction open suspend fun saveCache(row: DiscoveryCacheRow) { require(row.payload.length <= 524288); putCache(row); trimCache(System.currentTimeMillis()) }
    @Query("DELETE FROM discovery_cache") abstract suspend fun clearCache()
}
class RoomLocalHistoryProvider(private val library: LibraryDao, private val dao: DiscoveryDao) : LocalHistoryProvider {
    override suspend fun candidates() = library.discoveryTracks().map { PersonalTrack(it.track(), it.favorite, it.playCount, it.lastPlayedAt) }
    override suspend fun preferences() = dao.preferences().associate { it.url to it.domain() }
}
