package org.sonora.music

import android.app.Application
import androidx.room.Room
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import org.sonora.music.data.*
import org.sonora.music.discovery.*
import kotlinx.coroutines.*
import org.sonora.music.playback.PlaybackStatus
import org.sonora.music.playback.AudioPrefixCache
import org.sonora.music.playback.PlaybackPreparation
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.TimeUnit
import org.sonora.music.update.SonoraUpdates
import org.sonora.music.update.UpdateCheckWorker

@UnstableApi
class SonoraApplication : Application() {
    val graph by lazy { AppGraph(this) }
    override fun onCreate() {
        super.onCreate()
        graph
        if (!BuildConfig.DEBUG) UpdateCheckWorker.schedule(this)
    }
}
@UnstableApi
class AppGraph(application: Application) {
    val updates = SonoraUpdates(application)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    init { NewPipe.init(ExtractorDownloader(http), Localization("en", "GB")) }
    val source = NewPipeMusicSource()
    val podcasts = RssPodcastSource(http.newBuilder().followSslRedirects(false).dns(object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> = okhttp3.Dns.SYSTEM.lookup(hostname).also { addresses ->
            if (addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress })
                throw java.net.UnknownHostException("Only public podcast servers are supported.")
        }
    }).build())
    val audioPrefixes = AudioPrefixCache()
    val streams = StreamResolver(source, podcasts = podcasts, onInvalidated = audioPrefixes::invalidate)
    val database = Room.databaseBuilder(application, LibraryDatabase::class.java, "sonora.db").addMigrations(DatabaseMigrations.FROM_1_TO_2, DatabaseMigrations.FROM_2_TO_3, DatabaseMigrations.FROM_3_TO_4, DatabaseMigrations.FROM_4_TO_5).build()
    val library = database.library()
    val podcastLibrary = database.podcasts()
    val importer = ImportRepository(source, library)
    val playbackStatus = PlaybackStatus()
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val preparation = PlaybackPreparation(streams, audioPrefixes, persistenceScope, application.getSharedPreferences("playback", Application.MODE_PRIVATE))
    val discoverySettings = DiscoverySettings(application.getSharedPreferences("discovery", Application.MODE_PRIVATE))
    val discoveryDao = database.discovery()
    private val publicJson = PublicJsonClient(http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(7, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build(), discoveryDao)
    val metadata = MusicBrainzMetadataProvider(publicJson, discoveryDao)
    val artistSimilarity = ListenBrainzArtistSimilarityProvider(publicJson)
    val discovery = DiscoveryRepository(source, RoomLocalHistoryProvider(library, discoveryDao), discoveryDao, discoverySettings, metadata, artistSimilarity)
    val searchRepository = SearchRepository(source, library, discoveryDao, discoverySettings)
    val radioStatus = RadioStatus()
    fun feedback(track: org.sonora.music.domain.Track, kind: String, listenedMs: Long = 0) {
        if (org.sonora.music.domain.PodcastLinks.isEpisode(track.url)) return
        val time = System.currentTimeMillis()
        if (!discoverySettings.state.value.personalized && kind !in setOf("hide", "unhide", "more")) return
        persistenceScope.launch {
            try { if (time >= discoverySettings.state.value.resetAt) discoveryDao.feedback(track.url, kind, listenedMs, time) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { playbackStatus.report("Listening preferences could not be saved.") }
        }
    }
    val podcastProgress = PodcastProgressStore(podcastLibrary, persistenceScope) { playbackStatus.report("Episode progress could not be saved.") }
    val queueStore = PlaybackQueueStore(library, persistenceScope) { playbackStatus.report("Queue could not be saved. Check available storage.") }
}
