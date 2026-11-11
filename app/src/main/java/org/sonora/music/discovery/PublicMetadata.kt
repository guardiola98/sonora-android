package org.sonora.music.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.sonora.music.BuildConfig
import org.sonora.music.data.DiscoveryCacheRow
import org.sonora.music.data.DiscoveryDao
import org.sonora.music.domain.Track
import java.io.IOException

/** Per-provider pacing, bounded reads and backoff. Never retries in a loop or sends account/history data. */
class PublicJsonClient(private val http: OkHttpClient, private val dao: DiscoveryDao, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private val next = mutableMapOf<String, Long>()
    private val failures = mutableMapOf<String, Int>()
    suspend fun get(url: String, key: String, ttl: Long, provider: String): String {
        dao.cache(key)?.takeIf { it.expiresAt > now() }?.let { return it.payload }
        return lock.withLock {
            dao.cache(key)?.takeIf { it.expiresAt > now() }?.let { return@withLock it.payload }
            val wait = (next[provider] ?: 0) - now()
            if (wait > 1500) throw IOException("$provider is cooling down. Cached/local results remain available.")
            if (wait > 0) delay(wait)
            next[provider] = now() + 1100
            try {
                val result = runInterruptible(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(url).header("User-Agent", "Sonora/${BuildConfig.VERSION_NAME} (https://github.com/guardiola98/sonora-android)").header("Accept", "application/json").build()).execute().use { response ->
                        val remaining = response.header("X-RateLimit-Remaining")?.toIntOrNull()
                        val reset = response.header("X-RateLimit-Reset-In")?.toLongOrNull()
                        if (remaining == 0 && reset != null) next[provider] = now() + reset.coerceIn(1, 3600) * 1000
                        if (!response.isSuccessful) {
                            val header = response.header("Retry-After")
                            val retry = header?.toLongOrNull() ?: header?.let { runCatching { (java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now()) / 1000 }.getOrNull() }
                            if (retry != null) next[provider] = now() + retry.coerceIn(1, 86400) * 1000
                            throw IOException("$provider returned HTTP ${response.code}")
                        }
                        val body = response.body ?: throw IOException("Empty metadata response")
                        require(body.contentLength() <= 524288) { "Metadata response too large" }
                        body.byteStream().use { input -> val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 524288); output.write(buffer, 0, count) }
                            output.toString("UTF-8") }
                    }
                }
                failures[provider] = 0
                dao.saveCache(DiscoveryCacheRow(key, result, provider, now(), now() + ttl))
                result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val count = ((failures[provider] ?: 0) + 1).coerceAtMost(6); failures[provider] = count
                next[provider] = maxOf(next[provider] ?: 0, now() + (15000L shl count))
                throw e
            }
        }
    }
}
object MetadataCodec {
    fun encode(metadata: MusicMetadata): String = JSONObject().put("reference", metadata.reference).apply {
        fun evidence(value: Evidence?) = value?.let { JSONObject().put("value", it.value).put("source", it.source).put("confidence", it.confidence).put("fetchedAt", it.fetchedAt).put("license", it.license).put("scope", it.scope) }
        put("artist", evidence(metadata.artist)); put("artistId", evidence(metadata.artistId)); put("recordingId", evidence(metadata.recordingId)); put("album", evidence(metadata.album)); put("albumId", evidence(metadata.albumId)); put("year", evidence(metadata.year)); put("language", evidence(metadata.language)); put("scene", evidence(metadata.scene))
        put("genres", JSONArray().apply { metadata.genres.forEach { put(evidence(it)) } })
    }.toString()
    fun decode(json: String): MusicMetadata {
        val root = JSONObject(json)
        fun evidence(item: JSONObject?): Evidence? = item?.let { Evidence(it.getString("value"), it.getString("source"), it.getDouble("confidence").coerceIn(0.0, 1.0), it.getLong("fetchedAt"), it.optString("license", "CC0"), it.optString("scope", "recording")) }
        val genres = root.optJSONArray("genres") ?: JSONArray()
        return MusicMetadata(root.getString("reference"), evidence(root.optJSONObject("artist")), evidence(root.optJSONObject("artistId")), evidence(root.optJSONObject("recordingId")), evidence(root.optJSONObject("album")), evidence(root.optJSONObject("albumId")), evidence(root.optJSONObject("year")), (0 until genres.length()).mapNotNull { evidence(genres.optJSONObject(it)) }, evidence(root.optJSONObject("language")), evidence(root.optJSONObject("scene")))
    }
}
class MusicBrainzMetadataProvider(private val client: PublicJsonClient, private val dao: DiscoveryDao) : MusicMetadataProvider {
    override suspend fun metadata(track: Track): MusicMetadata? {
        val key = "metadata:${track.url}"; val now = System.currentTimeMillis()
        dao.cache(key)?.takeIf { it.expiresAt > now }?.let { return if (it.payload == "null") null else MetadataCodec.decode(it.payload) }
        // A title split / Topic channel is only a search hypothesis. It is not stored as an artist fact.
        val split = track.title.split(Regex("\\s+[-–—]\\s+"), limit = 2)
        val hint = if (split.size == 2) split[0] else track.artist.replace(Regex("(?i)\\s*-?\\s*Topic$"), "")
        val title = MusicText.cleanTitle(if (split.size == 2) split[1] else track.title)
        if (hint.isBlank() || title.length < 2 || MusicText.variants(track.title).isNotEmpty()) return null
        fun quote(value: String) = value.replace(Regex("[^\\p{L}\\p{N} ]"), " ").take(140)
        val query = "recording:\"${quote(title)}\" AND artist:\"${quote(hint)}\""
        val url = "https://musicbrainz.org/ws/2/recording".toHttpUrl().newBuilder().addQueryParameter("query", query).addQueryParameter("fmt", "json").addQueryParameter("limit", "5").build()
        val raw = client.get(url.toString(), "mb-search:$query", 7 * 86400000L, "MusicBrainz")
        var match = match(track, hint, title, JSONObject(raw), now)
        if (match != null) {
            val entity = match.recordingId?.value?.let { "recording/$it" } ?: "artist/${match.artistId!!.value}"
            try {
                val details = JSONObject(client.get("https://musicbrainz.org/ws/2/$entity?inc=genres&fmt=json", "mb-genres:$entity", 30 * 86400000L, "MusicBrainz"))
                val genres = details.optJSONArray("genres") ?: JSONArray()
                match = match.copy(genres = (0 until genres.length()).map { genres.getJSONObject(it) }.filter { it.optInt("count") >= 1 }.take(8).map {
                    Evidence(it.getString("name"), "https://musicbrainz.org/$entity/tags", (.55 + it.optInt("count") * .05).coerceAtMost(.8), now, "CC-BY-NC-SA-3.0", if (entity.startsWith("artist/")) "artist" else "recording") })
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Core credits remain usable if genre lookup fails. */ }
        }
        dao.saveCache(DiscoveryCacheRow(key, match?.let(MetadataCodec::encode) ?: "null", "MusicBrainz", now, now + if (match == null) 86400000 else 30 * 86400000L))
        return match
    }
    companion object {
        fun match(track: Track, hint: String, title: String, root: JSONObject, now: Long): MusicMetadata? {
            val rows = root.optJSONArray("recordings") ?: return null
            val matches = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { item ->
                val credits = item.optJSONArray("artist-credit") ?: JSONArray()
                val credited = (0 until credits.length()).mapNotNull { credits.optJSONObject(it)?.optJSONObject("artist")?.optString("name") }
                item.optInt("score") >= 95 && MusicText.cleanTitle(item.optString("title")) == MusicText.cleanTitle(title) && credited.size == 1 && MusicText.normalize(credited.single()) == MusicText.normalize(hint) &&
                    (track.durationSeconds <= 0 || item.optLong("length") <= 0 || kotlin.math.abs(item.optLong("length") / 1000 - track.durationSeconds) <= 12)
            }
            if (matches.isEmpty()) return null
            val identities = matches.map { it.getJSONArray("artist-credit").getJSONObject(0).getJSONObject("artist") }.distinctBy { it.optString("id") }
            if (identities.size != 1) return null
            // Several recordings can agree on artist identity while album/recording remain unknown.
            val unique = matches.distinctBy { it.optString("id") }
            if (unique.size != 1) {
                val credit = identities.single(); val artistId = credit.getString("id")
                return MusicMetadata(track.url, Evidence(credit.getString("name"), "https://musicbrainz.org/artist/$artistId", .9, now, scope = "artist"),
                    Evidence(artistId, "https://musicbrainz.org/artist/$artistId", .9, now, scope = "artist"))
            }
            val item = unique.single()
            val id = item.getString("id"); val artist = item.getJSONArray("artist-credit").getJSONObject(0).getJSONObject("artist")
            fun fact(value: String, path: String = "recording/$id") = Evidence(value, "https://musicbrainz.org/$path", .95, now)
            val releases = item.optJSONArray("releases") ?: JSONArray()
            val albums = (0 until releases.length()).mapNotNull { releases.getJSONObject(it).optJSONObject("release-group") }
                .filter { it.optString("primary-type") == "Album" && it.optJSONArray("secondary-types")?.length().let { count -> count == null || count == 0 } }.distinctBy { it.optString("id") }
            val album = albums.singleOrNull()
            val tags = item.optJSONArray("genres") ?: JSONArray()
            val genres = (0 until tags.length()).map { tags.getJSONObject(it) }.filter { it.optInt("count") >= 2 }.take(8).map {
                Evidence(it.getString("name"), "https://musicbrainz.org/recording/$id/tags", .65, now, "CC-BY-NC-SA-3.0") }
            return MusicMetadata(track.url, fact(artist.getString("name"), "artist/${artist.getString("id")}"), fact(artist.getString("id")), fact(id),
                album?.optString("title")?.takeIf { it.isNotBlank() }?.let { fact(it, "release-group/${album.getString("id")}") },
                album?.getString("id")?.let { fact(it) }, item.optString("first-release-date").take(4).toIntOrNull()?.let { fact(it.toString()) }, genres)
        }
    }
}
class ListenBrainzArtistSimilarityProvider(private val client: PublicJsonClient) : ArtistSimilarityProvider {
    override suspend fun similar(artistId: String): List<SimilarArtist> {
        require(Regex("[a-fA-F0-9-]{36}").matches(artistId))
        val url = "https://labs.api.listenbrainz.org/similar-artists/json".toHttpUrl().newBuilder().addQueryParameter("artist_mbids", artistId).addQueryParameter("algorithm", "session_based_days_9000_session_300_contribution_5_threshold_15_limit_50_skip_30").build()
        val json = client.get(url.toString(), "lb-similar:$artistId", 7 * 86400000L, "ListenBrainz")
        return parse(json, System.currentTimeMillis()).take(8)
    }
    companion object {
        fun parse(json: String, now: Long): List<SimilarArtist> {
            val rows = JSONArray(json)
            return (0 until rows.length()).mapNotNull { index ->
                val row = rows.getJSONObject(index)
                val id = row.optString("artist_mbid").ifBlank { row.optString("similar_artist_mbid") }
                val name = row.optString("name").ifBlank { row.optString("artist_name") }.ifBlank { row.optString("similar_artist_name") }
                if (!Regex("[a-fA-F0-9-]{36}").matches(id) || name.isBlank()) null else SimilarArtist(id, name,
                    Evidence(name, "https://labs.api.listenbrainz.org/similar-artists", .7, now, "CC0"))
            }.distinctBy { it.id }
        }
    }
}
