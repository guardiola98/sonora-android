package org.sonora.music.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val FROM_4_TO_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS search_history (normalized TEXT NOT NULL PRIMARY KEY, query TEXT NOT NULL, usedAt INTEGER NOT NULL, uses INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS listening_preferences (url TEXT NOT NULL PRIMARY KEY, completions INTEGER NOT NULL, earlySkips INTEGER NOT NULL, removals INTEGER NOT NULL, more INTEGER NOT NULL, hidden INTEGER NOT NULL, listenedMs INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS discovery_cache (`key` TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL, source TEXT NOT NULL, fetchedAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_discovery_cache_expiresAt ON discovery_cache(expiresAt)")
        }
    }

    val FROM_3_TO_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS podcast_feeds (feedUrl TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL, artwork TEXT NOT NULL, description TEXT NOT NULL, subscribed INTEGER NOT NULL, refreshedAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS podcast_episodes (reference TEXT NOT NULL PRIMARY KEY, feedUrl TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, artwork TEXT NOT NULL, durationSeconds INTEGER NOT NULL, publishedAt INTEGER NOT NULL, FOREIGN KEY(feedUrl) REFERENCES podcast_feeds(feedUrl) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_podcast_episodes_feedUrl ON podcast_episodes(feedUrl)")
            db.execSQL("CREATE TABLE IF NOT EXISTS podcast_progress (reference TEXT NOT NULL PRIMARY KEY, positionMs INTEGER NOT NULL, durationMs INTEGER NOT NULL, completed INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        }
    }
    val FROM_2_TO_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS playback_checkpoint (id INTEGER NOT NULL PRIMARY KEY, queueJson TEXT NOT NULL, currentIndex INTEGER NOT NULL, positionMs INTEGER NOT NULL)")
        }
    }
    val FROM_1_TO_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS playlists (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceKey TEXT NOT NULL, name TEXT NOT NULL, importedAt INTEGER NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_playlists_sourceKey ON playlists (sourceKey)")
            db.execSQL("CREATE TABLE IF NOT EXISTS playlist_entries (playlistId INTEGER NOT NULL, trackUrl TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlistId, trackUrl), FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(trackUrl) REFERENCES tracks(url) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_entries_trackUrl ON playlist_entries (trackUrl)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_entries_playlistId_position ON playlist_entries (playlistId, position)")
        }
    }
}
