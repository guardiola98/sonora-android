package org.sonora.music

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.sonora.music.data.*
import org.sonora.music.domain.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PodcastTest {
    private val url = "https://example.org/podcast.xml"
    private val rss = """<?xml version="1.0"?><rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>
        <title>Stories &amp; science</title><itunes:author>Publisher</itunes:author><description>A public podcast</description>
        <itunes:image href="https://example.org/cover.jpg"/>
        <item><guid isPermaLink="false">episode-1</guid><title>First &amp; best</title><description><![CDATA[<p>Hello <b>world</b></p>]]></description>
        <pubDate>Tue, 10 Nov 2026 12:00:00 GMT</pubDate><itunes:duration>01:02:03</itunes:duration>
        <enclosure url="https://example.org/audio.mp3?signature=temporary" type="audio/mpeg" length="100"/></item>
        <item><guid>video</guid><title>Video</title><enclosure url="https://example.org/video.mp4" type="video/mp4"/></item>
        </channel></rss>""".trimIndent()
    private fun feed(text: String = rss) = PodcastFeedParser.parse(text.toByteArray(), url)
    @Test fun parsesRssMetadataAudioDatesAndDescriptionsWithoutHtml() {
        val result = feed(); val episode = result.episodes.single()
        assertEquals("Stories & science", result.show.title)
        assertEquals("Publisher", result.show.author)
        assertEquals(3723L, episode.durationSeconds)
        assertTrue(episode.publishedAt > 0)
        assertEquals("Hello world", episode.description)
        assertEquals("https://example.org/cover.jpg", episode.track(result.show).artwork)
        assertEquals(url, PodcastLinks.parse(episode.reference)!!.first)
        assertFalse(episode.reference.contains("temporary"))
    }
    @Test fun stableEpisodeIdentitySurvivesChangingSignedEnclosureUrls() {
        assertEquals(feed().episodes.single().reference, feed(rss.replace("signature=temporary", "signature=new")).episodes.single().reference)
        val duplicate = rss.replace("</channel>", rss.substringAfter("<item>").substringBefore("</item>").let { "<item>$it</item></channel>" })
        assertEquals(1, feed(duplicate).episodes.size)
    }
    @Test fun rejectsDtdExternalEntitiesAndExcessiveNesting() {
        for (xml in listOf(rss.replace("<rss ", "<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss "), "<rss><channel><title>x</title>" + "<nested>".repeat(40) + "</nested>".repeat(40) + "</channel></rss>")) {
            try { feed(xml); fail("Accepted unsafe XML") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun rejectsHtmlAndOversizeFeedsAndSkipsCleartextAudio() {
        try { feed("<html><title>Not RSS</title></html>"); fail() } catch (_: IllegalArgumentException) { }
        try { PodcastFeedParser.parse(ByteArray(PodcastFeedParser.MAX_BYTES + 1), url); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(feed(rss.replace("https://example.org/audio", "http://example.org/audio")).episodes.isEmpty())
    }
    @Test fun canonicalReferencesRejectCredentialsPrivateAddressesAndTampering() {
        for (bad in listOf("http://example.org/rss", "https://user:pass@example.org/feed", "https://127.0.0.1/rss", "https://host.local/rss", "file:///rss", "https://example.org:8443/feed")) assertNull(PodcastLinks.https(bad))
        val ref = PodcastLinks.reference(url, "guid")
        assertTrue(PodcastLinks.isEpisode(ref)); assertFalse(PodcastLinks.isEpisode(ref + "?modified=1"))
        assertFalse(PodcastLinks.isEpisode("sonora-podcast://episode/!invalid/hash"))
    }
    @Test fun durationBoundsDoNotOverflow() {
        assertEquals(83L, PodcastFeedParser.duration("01:23"))
        assertEquals(0L, PodcastFeedParser.duration("9999999999999999:00:00"))
        assertEquals(0L, PodcastFeedParser.duration("-3"))
        assertEquals(0L, PodcastFeedParser.duration("unknown"))
    }
    @Test fun podcastQueueAndBackupNeverPersistEnclosuresAndMergeProgressByDate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val restored = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val result = feed(); val track = result.episodes.single().track(result.show)
            db.podcasts().cache(result); db.podcasts().subscribe(url, true)
            db.podcasts().saveProgress(PodcastProgress(track.url, 12345, 60000, false, 100))
            db.library().toggleFavorite(track); db.library().createPlaylistWithTracks("Episodes", listOf(track))
            val encoded = BackupCodec.encode(db.library().exportBackup())
            assertFalse(encoded.contains("signature")); assertFalse(encoded.contains("audio.mp3"))
            val backup = BackupCodec.decode(encoded)
            assertEquals(1, backup.podcasts.size); assertEquals(track, backup.tracks.single().track())
            restored.library().restoreBackup(backup)
            assertTrue(restored.podcasts().subscriptions().first().single().subscribed)
            assertEquals(12345L, restored.podcasts().progressFor(track.url)!!.resumeMs)
            restored.podcasts().saveProgress(PodcastProgress(track.url, 22222, 60000, false, 200))
            restored.library().restoreBackup(backup)
            assertEquals(22222L, restored.podcasts().progressFor(track.url)!!.resumeMs)
            val queue = SavedQueue(listOf(track), 0, 12345)
            assertEquals(queue, PlaybackQueueStore.decode(PlaybackQueueStore.encode(queue)))
            assertEquals(0L, PodcastProgress(track.url, 60000, 60000, true, 300).resumeMs)
            val legacy = JSONObject(encoded).put("version", 1).apply { remove("podcasts"); remove("podcastProgress") }
            assertTrue(BackupCodec.decode(legacy.toString()).podcasts.isEmpty())
        } finally { db.close(); restored.close() }
    }
    @Test fun feedRefreshPreservesSubscriptionAndProgressButRemovesWithdrawnEpisodes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), LibraryDatabase::class.java).build()
        try {
            val first = feed(); val ref = first.episodes.single().reference
            db.podcasts().cache(first); db.podcasts().subscribe(url, true)
            db.podcasts().saveProgress(PodcastProgress(ref, 12345, 60000, false, 100))
            db.podcasts().cache(first.copy(episodes = emptyList()))
            assertTrue(db.podcasts().find(url)!!.subscribed)
            assertTrue(db.podcasts().episodes(url).isEmpty())
            assertEquals(12345L, db.podcasts().progressFor(ref)!!.resumeMs)
            db.podcasts().subscribe(url, false)
            assertTrue(db.podcasts().subscriptions().first().isEmpty())
        } finally { db.close() }
    }
    @Test fun podcastStreamsResolveThroughRssAndRetryInvalidatesBothCaches() = runBlocking {
        var calls = 0; var invalidations = 0
        val result = feed(); val reference = result.episodes.single().reference
        val podcasts = object : PodcastSource {
            override suspend fun search(query: String) = listOf(result.show)
            override suspend fun feed(url: String, refresh: Boolean) = result
            override suspend fun resolve(reference: String): AudioStream { calls++; return AudioStream("https://example.org/fresh-$calls.mp3") }
            override suspend fun invalidate(reference: String) { invalidations++ }
        }
        val music = object : MusicSource {
            override suspend fun search(query: String) = emptyList<Track>()
            override suspend fun resolve(url: String): AudioStream = error("Podcast was sent to NewPipe")
        }
        val resolver = StreamResolver(music, podcasts = podcasts)
        assertEquals(resolver.resolve(reference), resolver.resolve(reference)); assertEquals(1, calls)
        resolver.invalidate(reference); assertTrue(resolver.resolve(reference).url.contains("fresh-2")); assertEquals(1, invalidations)
    }
}
