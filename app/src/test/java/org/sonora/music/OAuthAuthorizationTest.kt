package org.sonora.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.sonora.music.accountimport.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class OAuthAuthorizationTest {
    @Test fun pkceUsesIndependentRandomStateAndS256() {
        val first = PkceRequest(); val second = PkceRequest()
        assertNotEquals(first.state, second.state); assertNotEquals(first.verifier, first.state)
        assertEquals(43, first.verifier.length)
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(first.verifier.toByteArray())), first.challenge)
        assertFalse(first.validState(null)); assertFalse(first.validState(second.state)); assertTrue(first.validState(first.state))
    }
    @Test fun googleValidatesStateAndOriginAndNeverRestoresAnOldAuthorization() = runTest {
        var browser = ""; var exchanges = 0
        val token = TemporaryAccess("test-access", 3600)
        val auth = GoogleImportAuthorization("public.apps.googleusercontent.com", "https://auth.example.com/oauth/callback") { code, verifier ->
            assertEquals("one-time-code", code); assertEquals(43, verifier.length); exchanges++; token
        }
        val pending = async { auth.authorize { browser = it } }; runCurrent()
        val url = browser.toHttpUrl(); val state = url.queryParameter("state")!!
        assertEquals("online", url.queryParameter("access_type")); assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(GoogleImportAuthorization.YOUTUBE_READONLY, url.queryParameter("scope"))
        assertFalse(auth.onRedirect("https://evil.example/oauth/callback?state=$state&code=one-time-code"))
        assertFalse(auth.onRedirect("https://auth.example.com/oauth/callback?state=wrong&code=one-time-code"))
        assertFalse(auth.onRedirect("https://auth.example.com/oauth/callback?state=$state&state=$state&code=one-time-code"))
        assertEquals(0, exchanges)
        val callback = "https://auth.example.com/oauth/callback?state=$state&code=one-time-code"
        assertTrue(auth.onRedirect(callback)); runCurrent(); assertSame(token, pending.await()); assertEquals(1, exchanges)
        assertFalse(auth.onRedirect(callback))
        val fresh = GoogleImportAuthorization("public.apps.googleusercontent.com", "https://auth.example.com/oauth/callback") { _, _ -> error("must not exchange") }
        assertFalse(fresh.onRedirect(callback)); token.close()
    }
    @Test fun cancellingGoogleClearsPendingStateAndDenialNeverReachesExchange() = runTest {
        var browser = ""
        val auth = GoogleImportAuthorization("public.apps.googleusercontent.com", "https://auth.example.com/oauth/callback") { _, _ -> error("must not exchange") }
        val waiting = launch { auth.authorize { browser = it } }; runCurrent()
        val state = browser.toHttpUrl().queryParameter("state")!!
        waiting.cancelAndJoin()
        assertFalse(auth.onRedirect("https://auth.example.com/oauth/callback?state=$state&code=late"))
        var denied: Exception? = null
        val retry = launch { try { auth.authorize { browser = it } } catch (e: ImportAccessException) { denied = e } }; runCurrent()
        val secondState = browser.toHttpUrl().queryParameter("state")!!
        assertTrue(auth.onRedirect("https://auth.example.com/oauth/callback?state=$secondState&error=access_denied"))
        retry.join(); assertNotNull(denied)
    }
    @Test fun spotifyLoopbackRejectsWrongStateBeforeAcceptingThePkceCode() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            var browser = ""; var exchanged = false; var callback: Job? = null
            val auth = SpotifyAuthorization { _, code, verifier, redirect ->
                assertEquals("authorized-code", code)
                val url = browser.toHttpUrl()
                assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())), url.queryParameter("code_challenge"))
                assertEquals(redirect, url.queryParameter("redirect_uri")); exchanged = true
                TemporaryAccess("fixture-access", 3600)
            }
            val token = withTimeout(10_000) { auth.authorize("a".repeat(32), false) { value ->
                browser = value
                callback = launch(Dispatchers.IO) {
                    val url = value.toHttpUrl(); val redirect = url.queryParameter("redirect_uri")!!
                    assertEquals("127.0.0.1", redirect.toHttpUrl().host)
                    assertFalse(url.queryParameter("scope")!!.contains("user-library-read"))
                    fun status(query: String): Int = (URL("$redirect?$query").openConnection() as HttpURLConnection).run {
                        connectTimeout = 2000; readTimeout = 2000
                        try { responseCode } finally { disconnect() }
                    }
                    assertEquals(400, status("state=wrong&code=injected"))
                    assertEquals(200, status("state=${url.queryParameter("state")}&code=authorized-code"))
                }
            } }
            callback!!.join(); assertTrue(exchanged); token.close()
        } finally { Dispatchers.resetMain() }
    }
}
