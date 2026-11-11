package org.sonora.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.sonora.music.domain.*
import java.io.IOException
import java.util.Locale

class RssPodcastSource(private val http: OkHttpClient) : PodcastSource {
    private val cache = SingleFlightCache<String, PodcastFeed>(8, 5 * 60_000)
    private var searchTime = 0L
    override suspend fun search(query: String): List<PodcastShow> = runInterruptible(Dispatchers.IO) {
        val term = query.trim().also { require(it.length in 2..200) { "Enter 2–200 characters to search podcasts." } }
        synchronized(this) {
            val now = android.os.SystemClock.elapsedRealtime()
            require(searchTime == 0L || now - searchTime >= 3_000) { "Please wait a moment before searching again." }
            searchTime = now
        }
        val country = Locale.getDefault().country.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder().addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast").addQueryParameter("term", term).addQueryParameter("country", country)
            .addQueryParameter("limit", "40").build().toString()
        val results = JSONObject(String(download(url, 2 * 1024 * 1024), Charsets.UTF_8)).getJSONArray("results")
        (0 until minOf(results.length(), 40)).mapNotNull { index ->
            val row = results.getJSONObject(index)
            val feed = PodcastLinks.https(row.optString("feedUrl")) ?: return@mapNotNull null
            // Discovery metadata only; full artwork and audio come from the publisher's RSS.
            PodcastShow(feed, row.optString("collectionName").take(2000), row.optString("artistName").take(2000))
        }.distinctBy { it.feedUrl }
    }
    override suspend fun feed(url: String, refresh: Boolean): PodcastFeed {
        val canonical = requireNotNull(PodcastLinks.https(url)) { "Use a public HTTPS RSS feed URL." }
        if (refresh) cache.invalidate(canonical)
        return cache.get(canonical) {
            runInterruptible(Dispatchers.IO) { PodcastFeedParser.parse(download(canonical, PodcastFeedParser.MAX_BYTES), canonical) }
        }
    }
    override suspend fun resolve(reference: String): AudioStream {
        val (url, _) = requireNotNull(PodcastLinks.parse(reference)) { "Invalid podcast episode." }
        val episode = feed(url).episodes.firstOrNull { it.reference == reference }
            ?: throw IOException("This episode is no longer in the publisher's RSS feed.")
        return AudioStream(episode.audioUrl)
    }
    override suspend fun invalidate(reference: String) { PodcastLinks.parse(reference)?.let { cache.invalidate(it.first) } }
    private fun download(url: String, limit: Int): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", "Sonora/${org.sonora.music.BuildConfig.VERSION_NAME} (Android; public RSS podcast client)").build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(when (response.code) {
                401, 403 -> "This podcast requires access that Sonora cannot provide."
                404, 410 -> "This podcast feed is no longer available."
                429 -> "The podcast provider is limiting requests. Try again later."
                else -> "Podcast provider returned HTTP ${response.code}. Try again later."
            })
            val body = response.body ?: throw IOException("Podcast provider returned an empty response.")
            require(body.contentLength() <= limit) { "Podcast response is too large." }
            body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { "Podcast response is too large." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }
}
