package org.sonora.music.accountimport

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Browser PKCE + verified HTTPS App Link. The optional broker alone knows the web client secret. */
class GoogleImportAuthorization(private val clientId: String, private val redirect: String,
    private val exchange: suspend (String, String) -> TemporaryAccess) {
    private class Attempt(val proof: PkceRequest, val result: CompletableDeferred<String>)
    private var pending: Attempt? = null
    suspend fun authorize(openBrowser: (String) -> Unit): TemporaryAccess {
        if (clientId.isBlank() || redirect.isBlank()) throw ImportAccessException("YouTube authorization needs a registered Google OAuth web client, a verified HTTPS callback and the optional token broker. Takeout and public link imports still work.")
        val target = redirect.toHttpUrl()
        require(target.isHttps && target.encodedPath == "/oauth/callback")
        val attempt = Attempt(PkceRequest(), CompletableDeferred()); pending = attempt
        try {
            val authorize = "https://accounts.google.com/o/oauth2/v2/auth".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId).addQueryParameter("redirect_uri", redirect)
                .addQueryParameter("response_type", "code").addQueryParameter("scope", YOUTUBE_READONLY)
                .addQueryParameter("access_type", "online").addQueryParameter("include_granted_scopes", "false")
                .addQueryParameter("prompt", "consent select_account").addQueryParameter("state", attempt.proof.state)
                .addQueryParameter("code_challenge_method", "S256").addQueryParameter("code_challenge", attempt.proof.challenge).build()
            openBrowser(authorize.toString())
            val code = withTimeout(180_000) { attempt.result.await() }
            currentCoroutineContext().ensureActive()
            return exchange(code, attempt.proof.verifier)
        } finally {
            if (pending === attempt) pending = null
            attempt.result.cancel()
        }
    }
    /** Never restores an attempt from an Intent. A callback after process death is simply discarded. */
    fun onRedirect(value: String): Boolean {
        val attempt = pending ?: return false
        val url = value.toHttpUrlOrNull() ?: return false
        val expected = redirect.toHttpUrlOrNull() ?: return false
        if (url.scheme != expected.scheme || url.host != expected.host || url.port != expected.port || url.encodedPath != expected.encodedPath ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.queryParameterValues("state").size != 1 ||
            !attempt.proof.validState(url.queryParameter("state"))) return false
        if (url.queryParameter("error") != null) attempt.result.completeExceptionally(ImportAccessException("Google authorization was cancelled or denied. No connection was kept."))
        else {
            val code = url.queryParameter("code")
            if (url.queryParameterValues("code").size != 1 || code.isNullOrBlank() || code.length > 4096)
                attempt.result.completeExceptionally(ImportAccessException("Google returned an invalid authorization response."))
            else attempt.result.complete(code)
        }
        return true
    }
    companion object { const val YOUTUBE_READONLY = "https://www.googleapis.com/auth/youtube.readonly" }
}
