package org.sonora.music

import android.app.Application
import androidx.room.Room
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import org.sonora.music.data.*
import org.sonora.music.domain.MusicSource
import org.sonora.music.playback.PlaybackStatus
import java.util.concurrent.TimeUnit

class SonoraApplication : Application() {
    val graph by lazy { AppGraph(this) }
    override fun onCreate() { super.onCreate(); graph }
}
class AppGraph(application: Application) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    init { NewPipe.init(ExtractorDownloader(http), Localization("en", "GB")) }
    val source: MusicSource = NewPipeMusicSource()
    val streams = StreamResolver(source)
    val database = Room.databaseBuilder(application, LibraryDatabase::class.java, "sonora.db").build()
    val library = database.library()
    val playbackStatus = PlaybackStatus()
}
