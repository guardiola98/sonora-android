package org.sonora.music

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.sonora.music.accountimport.*
import org.sonora.music.discovery.*
import org.sonora.music.domain.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PublicSpotifyPlaylistTest {
    private val id = "1234567890123456789012"
    private val url = "https://open.spotify.com/playlist/$id"
    private val track = Track("https://www.youtube.com/watch?v=abcdefghijk", "La flama", "Obrint Pas - Topic", "", 180)
    private fun html(type: String = "playlist", identity: String = id, rows: String = """
        {"uri":"spotify:track:abcdefghijklmnopqrstuv","title":"La flama","subtitle":"Obrint Pas","duration":180999,"audioPreview":{"url":"https://example.org/secret-preview"}},
        {"uri":"spotify:track:bcdefghijklmnopqrstuvw","title":"Second","subtitle":"Tyler, The Creator, Another Artist","duration":234000},
        {"uri":"spotify:episode:cdefghijklmnopqrstuvwx","title":"Unavailable","duration":100000}
    """) = "<script type=\"application/json\" id=\"__NEXT_DATA__\">" +
        """{"props":{"pageProps":{"state":{"data":{"entity":{"type":"$type","id":"$identity","name":"My ordered playlist","trackList":[$rows]}}}}}}""" + "</script>"
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected explicit rejection") } catch (_: ImportAccessException) { } }

    @Test fun canonicalLinksAcceptLocalesAndRejectSpoofedOrPrivateDestinations() {
        listOf(url, "$url?si=public", "spotify:playlist:$id", "https://open.spotify.com/intl-es/playlist/$id", "https://open.spotify.com/embed/playlist/$id").forEach {
            assertEquals(it, id, PublicSpotifyPlaylist.playlistId(it))
        }
        listOf("http://open.spotify.com/playlist/$id", "https://open.spotify.com.evil.example/playlist/$id", "https://name@open.spotify.com/playlist/$id",
            "https://open.spotify.com:444/playlist/$id", "https://open.spotify.com/album/$id", "https://open.spotify.com/collection/tracks", "$url/extra", "spotify:playlist:bad").forEach {
            assertFalse(it, PublicSpotifyPlaylist.accepts(it))
        }
        assertTrue(PublicSpotifyPlaylist.accepts("https://spotify.link/short123"))
        assertFalse(PublicSpotifyPlaylist.accepts("https://spotify.link/one/two"))
        assertFalse(PublicSpotifyPlaylist.accepts("https://spotify.link.evil.example/short123"))
    }
    @Test fun publicMetadataPreservesOrderDurationsArtistCommasAndUnavailableRows() {
        val result = PublicSpotifyPlaylist.parse(html(), id)
        assertEquals("My ordered playlist", result.source.name)
        assertEquals(3, result.source.count)
        assertEquals(listOf("La flama", "Second", "Unavailable"), result.songs.map { it.source.title })
        assertEquals(180L, result.songs[0].source.durationSeconds)
        assertEquals(listOf("Tyler, The Creator", "Another Artist"), result.songs[1].source.artists)
        assertNotNull(result.songs[2].source.unavailable)
        assertTrue(result.songs.all { it.chosen == null && it.source.playable == null })
        assertFalse(result.toString().contains("secret-preview"))
        assertEquals(url, result.source.link)
    }
    @Test fun malformedChangedOrWrongPlaylistPagesFailClearlyAndEmptyListsStayEmpty() {
        rejected { PublicSpotifyPlaylist.parse("<html>Login required</html>", id) }
        rejected { PublicSpotifyPlaylist.parse(html(type = "album"), id) }
        rejected { PublicSpotifyPlaylist.parse(html(identity = "abcdefghijklmnopqrstuv"), id) }
        rejected { PublicSpotifyPlaylist.parse("x".repeat(2_000_001), id) }
        assertTrue(PublicSpotifyPlaylist.parse(html(rows = ""), id).songs.isEmpty())
    }
    @Test fun shortLinkOnlyFollowsValidatedSpotifyRedirectsAndSendsNoCredentials() = runBlocking {
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            val request = chain.request(); seen += request.url.toString()
            assertNull(request.header("Authorization")); assertNull(request.header("Cookie"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Fixture")
                .code(if (request.url.host == "spotify.link") 302 else 200)
                .apply { if (request.url.host == "spotify.link") header("Location", url) }
                .body(html().toResponseBody()).build()
        }.build()
        assertEquals(3, PublicSpotifyPlaylist(client).read("https://spotify.link/short123").songs.size)
        assertEquals(listOf("https://spotify.link/short123", "https://open.spotify.com/embed/playlist/$id"), seen)
        seen.clear()
        val unsafeClient = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            seen += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Fixture").code(302)
                .header("Location", "https://evil.example/private").body("".toResponseBody()).build()
        }.build()
        try { PublicSpotifyPlaylist(unsafeClient).read("https://spotify.link/short123"); fail() } catch (_: ImportAccessException) { }
        assertEquals(listOf("https://spotify.link/short123"), seen)
    }
    @Test fun publicImportRequiresSelectionAndReviewAndNeverAuthorizesAnAccount() = runTest {
        var searches = 0; var output: ImportPreview? = null
        val catalog = object : MusicCatalogSource {
            override suspend fun searchPage(query: String, kind: CatalogKind, cursor: String?): CatalogPage {
                searches++; return CatalogPage(listOf(CatalogEntry(track.url, track.title, track.artist, "", CatalogKind.SONGS, track, true)))
            }
            override suspend fun suggestions(query: String) = emptyList<String>()
            override suspend fun related(track: Track) = emptyList<Track>()
            override suspend fun collection(url: String, cursor: String?) = error("unused")
        }
        val lookup = object : LibraryImportSource {
            override suspend fun track(url: String) = track
            override suspend fun playlist(url: String, progress: (Int) -> Unit) = error("unused")
        }
        val presenter = AccountImportPresenter(backgroundScope, catalog, lookup, { error("Public import must not authorize") }, { output = it })
        presenter.loadSpotifyPublicPlaylist { PublicSpotifyPlaylist.parse(html(), id) }; runCurrent()
        assertTrue(presenter.state.value.publicLink); assertTrue(presenter.state.value.disconnected)
        assertEquals(0, searches); assertNull(output)
        presenter.select(id); presenter.readSelected(); advanceTimeBy(1500); runCurrent()
        assertEquals(2, searches); assertEquals(2, presenter.state.value.pending)
        presenter.confirm(); assertNull(output)
        presenter.skipPending(true); presenter.confirm()
        assertEquals("spotify:$id", output!!.collections.single().key)
        assertEquals(listOf(track), output!!.collections.single().tracks)
        assertEquals(2, output!!.collections.single().skipped)
        presenter.cancel(); assertEquals(AccountImportState(), presenter.state.value)
        var cancelled = false
        presenter.loadSpotifyPublicPlaylist { try { awaitCancellation() } finally { cancelled = true } }; runCurrent()
        presenter.cancel(); runCurrent(); assertTrue(cancelled); assertTrue(presenter.state.value.lists.isEmpty())
    }
    @Test(timeout = 60_000) fun livePublicEmbedNeedsNoAccount() = runBlocking {
        assumeTrue("Opt in with SONORA_LIVE_IMPORT=true", System.getProperty("sonora.liveImport") == "true")
        val result = PublicSpotifyPlaylist().read("https://open.spotify.com/playlist/37i9dQZEVXbMDoHDwVN2tF")
        assertTrue(result.songs.isNotEmpty())
        assertTrue(result.songs.any { it.source.durationSeconds > 0 && it.source.artists.isNotEmpty() })
        println("Public Spotify metadata: ${result.songs.size} entries; no credentials or audio")
    }
}
