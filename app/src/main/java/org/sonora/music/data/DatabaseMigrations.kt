package org.sonora.music.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
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
