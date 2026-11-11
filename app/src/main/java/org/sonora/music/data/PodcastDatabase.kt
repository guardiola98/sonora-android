package org.sonora.music.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.sonora.music.domain.*

@Entity(tableName = "podcast_feeds")
data class SavedPodcast(@PrimaryKey val feedUrl: String, val title: String, val author: String, val artwork: String,
    val description: String, val subscribed: Boolean = false, val refreshedAt: Long = 0) {
    fun show() = PodcastShow(feedUrl, title, author, artwork, description)
}
@Entity(tableName = "podcast_episodes", indices = [Index("feedUrl")], foreignKeys = [ForeignKey(entity = SavedPodcast::class,
    parentColumns = ["feedUrl"], childColumns = ["feedUrl"], onDelete = ForeignKey.CASCADE)])
data class SavedEpisode(@PrimaryKey val reference: String, val feedUrl: String, val title: String, val description: String,
    val artwork: String, val durationSeconds: Long, val publishedAt: Long) {
    fun track(show: SavedPodcast) = Track(reference, title, show.title, artwork.ifBlank { show.artwork }, durationSeconds)
}
@Entity(tableName = "podcast_progress")
data class PodcastProgress(@PrimaryKey val reference: String, val positionMs: Long, val durationMs: Long, val completed: Boolean, val updatedAt: Long) {
    val resumeMs get() = if (completed) 0L else positionMs
}
@Dao
abstract class PodcastDao {
    @Query("SELECT * FROM podcast_feeds WHERE subscribed = 1 ORDER BY title COLLATE NOCASE") abstract fun subscriptions(): Flow<List<SavedPodcast>>
    @Query("SELECT * FROM podcast_feeds WHERE feedUrl = :url") abstract suspend fun find(url: String): SavedPodcast?
    @Query("SELECT * FROM podcast_episodes WHERE feedUrl = :url ORDER BY publishedAt DESC") abstract suspend fun episodes(url: String): List<SavedEpisode>
    @Query("SELECT * FROM podcast_progress") abstract fun progress(): Flow<List<PodcastProgress>>
    @Query("SELECT * FROM podcast_progress WHERE reference = :url") abstract suspend fun progressFor(url: String): PodcastProgress?
    @Upsert abstract suspend fun saveProgress(progress: PodcastProgress)
    @Upsert abstract suspend fun put(show: SavedPodcast)
    @Insert protected abstract suspend fun putEpisodes(episodes: List<SavedEpisode>)
    @Query("DELETE FROM podcast_episodes WHERE feedUrl = :url") protected abstract suspend fun clearEpisodes(url: String)
    @Query("UPDATE podcast_feeds SET subscribed = :subscribed WHERE feedUrl = :url") abstract suspend fun subscribe(url: String, subscribed: Boolean)
    @Transaction open suspend fun cache(feed: PodcastFeed): SavedPodcast {
        val old = find(feed.show.feedUrl)
        val show = feed.show.let { SavedPodcast(it.feedUrl, it.title, it.author, it.artwork, it.description, old?.subscribed ?: false, System.currentTimeMillis()) }
        put(show); clearEpisodes(show.feedUrl)
        putEpisodes(feed.episodes.map { SavedEpisode(it.reference, show.feedUrl, it.title, it.description, it.artwork, it.durationSeconds, it.publishedAt) })
        return show
    }
}
