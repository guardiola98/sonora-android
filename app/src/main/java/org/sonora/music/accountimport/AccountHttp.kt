package org.sonora.music.accountimport

import org.sonora.music.i18n.tr

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface AccountJsonClient {
    suspend fun get(url: HttpUrl, access: TemporaryAccess): JSONObject
}
/** Dedicated client: no disk cache, cookies, logging, redirects, authenticator or automatic retries. */
class AccountHttp : AccountJsonClient {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    override suspend fun get(url: HttpUrl, access: TemporaryAccess): JSONObject {
        require(url.isHttps && url.host in setOf("api.spotify.com", "www.googleapis.com"))
        return request(Request.Builder().url(url).header("Authorization", access.header()).header("Cache-Control", "no-store").build())
    }
    suspend fun spotifyToken(clientId: String, code: String, verifier: String, redirect: String): TemporaryAccess {
        val body = FormBody.Builder().add("client_id", clientId).add("grant_type", "authorization_code")
            .add("code", code).add("code_verifier", verifier).add("redirect_uri", redirect).build()
        val json = request(Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build())
        val token = json.optString("access_token").takeIf { it.isNotBlank() && it != "null" }
            ?: throw ImportAccessException(tr("Spotify did not return an access token. Connect again."))
        if (!json.optString("token_type").equals("Bearer", true)) throw ImportAccessException(tr("Unsupported authorization response."))
        // refresh_token, if returned, is deliberately ignored. No token response is cached or logged.
        return TemporaryAccess(token, json.optLong("expires_in", 3600))
    }
    suspend fun googleToken(endpoint: String, code: String, verifier: String): TemporaryAccess {
        val body = FormBody.Builder().add("code", code).add("code_verifier", verifier).build()
        val json = request(Request.Builder().url(endpoint).post(body).header("Cache-Control", "no-store").build())
        val token = json.optString("access_token").takeIf { it.isNotBlank() && it != "null" }
            ?: throw ImportAccessException(tr("The Google broker did not return an access token."))
        if (!json.optString("token_type").equals("Bearer", true) ||
            GoogleImportAuthorization.YOUTUBE_READONLY !in json.optString("scope").split(' '))
            throw ImportAccessException(tr("YouTube read permission was not granted."))
        return TemporaryAccess(token, json.optLong("expires_in", 3500))
    }
    private suspend fun request(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(ImportAccessException(tr("Cannot reach the provider. Check your connection and authorize again.")))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val json = response.use {
                        if (!it.isSuccessful) throw ImportAccessException(when (it.code) {
                            401 -> tr("Authorization expired or was withdrawn. Connect again.")
                            403 -> tr("Access denied. Check the app's test-user allowlist, API activation and quota. Spotify only exposes owned/collaborative playlist contents.")
                            429 -> tr("The provider's request quota was reached. Wait before trying another import.")
                            404 -> tr("This playlist is no longer available to this account.")
                            else -> tr("The provider could not complete the import. Try again later.")
                        })
                        val source = it.body?.source() ?: throw IOException()
                        if (source.request(2_000_001)) throw IOException()
                        val bytes = source.readByteArray()
                        JSONObject(bytes.toString(Charsets.UTF_8))
                    }
                    if (continuation.isActive) continuation.resume(json)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e as? ImportAccessException
                        ?: ImportAccessException(tr("The provider returned an unreadable response. Nothing was imported.")))
                }
            }
        })
    }
}
