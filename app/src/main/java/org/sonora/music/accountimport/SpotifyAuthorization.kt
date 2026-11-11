package org.sonora.music.accountimport

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal class PkceRequest {
    private fun random() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    val verifier = random()
    val state = random()
    val challenge: String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun validState(received: String?) = received != null && MessageDigest.isEqual(state.toByteArray(), received.toByteArray())
}

/** Spotify permits explicit loopback redirects. This listener exists on this phone only, during PKCE. */
class SpotifyAuthorization(private val exchange: suspend (String, String, String, String) -> TemporaryAccess) {
    constructor(http: AccountHttp) : this(http::spotifyToken)
    suspend fun authorize(clientId: String, likes: Boolean, openBrowser: (String) -> Unit): TemporaryAccess {
        var issued: TemporaryAccess? = null
        try { return withContext(Dispatchers.IO) {
        if (!clientId.matches(Regex("[A-Za-z0-9]{20,100}"))) throw ImportAccessException("Spotify is not configured in this build. See the OAuth setup guide; file and link imports still work.")
        val proof = PkceRequest()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 250
            val redirect = "http://127.0.0.1:${server.localPort}/sonora/callback"
            val authorize = "https://accounts.spotify.com/authorize".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId).addQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", redirect).addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("code_challenge", proof.challenge).addQueryParameter("state", proof.state)
                .addQueryParameter("show_dialog", "true")
                .addQueryParameter("scope", "playlist-read-private playlist-read-collaborative" + if (likes) " user-library-read" else "").build()
            withContext(Dispatchers.Main) { openBrowser(authorize.toString()) }
            val code = withTimeout(180_000) {
                var accepted: String? = null
                while (accepted == null) {
                    ensureActive()
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    socket.use {
                        it.soTimeout = 250
                        val line = try {
                            val bytes = ArrayList<Byte>(); val input = it.getInputStream()
                            val deadline = System.nanoTime() + 2_000_000_000L
                            while (bytes.size < 8192 && System.nanoTime() < deadline) { ensureActive(); val b = input.read(); if (b < 0 || b == 10) break; bytes.add(b.toByte()) }
                            bytes.toByteArray().toString(Charsets.US_ASCII).trim()
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
                        val parts = line.split(' ')
                        val target = parts.getOrNull(1)?.takeIf { path -> path.startsWith("/sonora/callback?") }
                        val url = target?.let { path -> runCatching { ("http://127.0.0.1" + path).toHttpUrl() }.getOrNull() }
                        val valid = parts.firstOrNull() == "GET" && url?.encodedPath == "/sonora/callback" &&
                            url.queryParameterValues("state").size == 1 && proof.validState(url.queryParameter("state"))
                        val denied = valid && url?.queryParameter("error") != null
                        if (valid && !denied && url?.queryParameterValues("code")?.size == 1) accepted = url.queryParameter("code")?.takeIf { value -> value.isNotBlank() }
                        val text = if (accepted != null) "Authorization received. Return to Sonora using the back button to select your playlists."
                            else if (denied) "Authorization cancelled. Return to Sonora." else "Invalid authorization response. Return to Sonora and try again."
                        val body = "<!doctype html><html><meta name=viewport content='width=device-width,initial-scale=1'><title>Sonora</title><h1>Sonora</h1><p>$text</p></html>".toByteArray()
                        runCatching { it.getOutputStream().apply {
                            write("HTTP/1.1 ${if (valid) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray()); write(body); flush()
                        } }
                        if (denied) throw ImportAccessException("Spotify authorization was cancelled. No account was connected.")
                    }
                }
                accepted
            }
            exchange(clientId, code, proof.verifier, redirect).also { issued = it }
        }
    }
        } finally { if (!currentCoroutineContext().isActive) issued?.close() }
    }
}
