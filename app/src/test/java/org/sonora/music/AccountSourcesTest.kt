package org.sonora.music

import android.app.Application
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.sonora.music.accountimport.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AccountSourcesTest {
    private val token = TemporaryAccess("fixture-not-a-real-token", 3600)
    private class JsonFixture(private val response: (HttpUrl) -> String) : AccountJsonClient {
        val urls = mutableListOf<HttpUrl>()
        override suspend fun get(url: HttpUrl, access: TemporaryAccess): JSONObject { urls += url; return JSONObject(response(url)) }
    }
    @Test fun spotifyUsesNewItemsEndpointAndHandlesOlderTrackEnvelope() = runTest {
        val json = JsonFixture { url ->
            if (url.queryParameter("offset") == "0") """{"items":[{"item":{"id":"one","type":"track","name":"First","duration_ms":180000,"artists":[{"name":"Artist"}]}}],"next":"https://api.spotify.com/v1/playlists/abcdefghijklmnop/items?offset=1&limit=50"}"""
            else """{"items":[{"track":{"id":"two","type":"track","name":"Second","duration_ms":200000,"artists":[{"name":"Artist"}]}},{"item":null}],"next":null}"""
        }
        val provider = SpotifyPlaylistSource(json)
        val songs = collectAccountPages(100) { provider.songs(token, RemotePlaylist("abcdefghijklmnop", "List", 3, ""), it) }
        assertEquals(listOf("First", "Second", "Unavailable Spotify entry"), songs.map { it.title })
        assertEquals(180L, songs.first().durationSeconds); assertNotNull(songs.last().unavailable)
        assertTrue(json.urls.all { it.encodedPath.endsWith("/items") })
    }
    @Test fun providerCannotRedirectBearerCredentialsViaNextPage() = runTest {
        val json = JsonFixture { """{"items":[],"next":"https://attacker.example/steal?offset=50"}""" }
        try { SpotifyPlaylistSource(json).playlists(token, null); fail() } catch (_: ImportAccessException) { }
        assertEquals(1, json.urls.size)
    }
    @Test fun youtubePreservesPositionAndDoesNotTreatPrivateVideosAsPlayable() = runTest {
        val json = JsonFixture { url -> if (url.encodedPath.endsWith("playlistItems")) """{"items":[{"snippet":{"position":1,"title":"Private video"},"contentDetails":{"videoId":"lmnopqrstuv"}},{"snippet":{"position":0,"title":"Public"},"contentDetails":{"videoId":"abcdefghijk"}}]}"""
            else """{"items":[{"id":"abcdefghijk","snippet":{"title":"Public","channelTitle":"Uploader"},"contentDetails":{"duration":"PT3M2S"},"status":{"privacyStatus":"public"}},{"id":"lmnopqrstuv","snippet":{"title":"Private","channelTitle":"Private uploader"},"contentDetails":{"duration":"PT2M"},"status":{"privacyStatus":"private"}}]}""" }
        val songs = YouTubePlaylistSource(json).songs(token, RemotePlaylist("PLabcdefghijk", "YT", 2, ""), null).items
        assertEquals("Public", songs.first().title); assertEquals(182L, songs.first().durationSeconds)
        assertEquals("Uploader", songs.first().playable!!.artist)
        assertNull(songs.last().playable); assertNotNull(songs.last().unavailable)
        assertTrue(json.urls.none { it.queryParameter("access_token") != null })
    }
    @Test fun youtubeOwnedPlaylistsAndLikesUseDistinctFiltersAndPageTokens() = runTest {
        val json = JsonFixture { """{"items":[],"nextPageToken":"PAGE2"}""" }
        val provider = YouTubePlaylistSource(json)
        assertEquals("PAGE2", provider.playlists(token, null).next)
        provider.playlists(token, "PAGE2")
        provider.songs(token, RemotePlaylist("likes", "Likes", null, "", true), null)
        assertEquals("true", json.urls[0].queryParameter("mine")); assertEquals("PAGE2", json.urls[1].queryParameter("pageToken"))
        assertEquals("like", json.urls[2].queryParameter("myRating")); assertNull(json.urls[2].queryParameter("mine"))
    }
}
