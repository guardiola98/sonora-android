package org.sonora.music.discovery

import org.sonora.music.i18n.tr

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.sonora.music.data.*
import org.sonora.music.domain.Track
import org.sonora.music.domain.PodcastLinks

/** Local library is always usable. Remote candidates are bounded hints, not trusted musical labels. */
class DiscoveryRepository(private val source: MusicCatalogSource, private val history: LocalHistoryProvider,
    private val dao: DiscoveryDao, private val settings: DiscoverySettings, private val metadata: MusicMetadataProvider,
    private val similarity: ArtistSimilarityProvider, private val ranker: RecommendationRanker = RecommendationRanker()) : RecommendationSource {
    private val remoteLock = Mutex()
    private var nextRemoteAt = 0L
    private var failed = 0
    private suspend fun <T> remote(block: suspend () -> T): T = remoteLock.withLock {
        val wait = nextRemoteAt - System.currentTimeMillis()
        if (wait > 1500) throw java.io.IOException(tr("Discovery is cooling down"))
        if (wait > 0) delay(wait)
        nextRemoteAt = System.currentTimeMillis() + 1100
        try { withTimeout(12000) { block() }.also { failed = 0 } }
        catch (e: TimeoutCancellationException) { failed = (failed + 1).coerceAtMost(5); nextRemoteAt = System.currentTimeMillis() + (15000L shl failed); throw java.io.IOException(tr("Discovery timed out")) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failed = (failed + 1).coerceAtMost(5); nextRemoteAt = System.currentTimeMillis() + (15000L shl failed); throw e }
    }
    private suspend fun related(seed: Track): List<Track> {
        val key = "related:${seed.url}"; val now = System.currentTimeMillis()
        dao.cache(key)?.takeIf { it.expiresAt > now }?.let { cached -> val array = JSONArray(cached.payload); return (0 until array.length()).map { BackupCodec.parseTrack(array.getJSONObject(it)) } }
        val tracks = remote { source.related(seed) }.take(40)
        dao.saveCache(DiscoveryCacheRow(key, JSONArray().apply { tracks.forEach { put(BackupCodec.trackJson(it)) } }.toString(), tr("YouTube related items via NewPipeExtractor"), now, now + 30 * 60000))
        return tracks
    }
    suspend fun cachedMetadata(): Map<String, MusicMetadata> = dao.metadataSnapshot(System.currentTimeMillis()).mapNotNull { row ->
        if (row.payload == "null") null else runCatching { MetadataCodec.decode(row.payload) }.getOrNull()
    }.associateBy { it.reference }
    override suspend fun recommend(seed: Track, excluded: Set<String>, recent: List<Track>, limit: Int): DiscoveryBatch = withContext(Dispatchers.Default) {
        if (PodcastLinks.isEpisode(seed.url)) return@withContext DiscoveryBatch(emptyList(), tr("Music radio does not add podcast episodes."))
        val options = settings.state.value
        val preferences = history.preferences()
        val personal = (if (options.personalized) history.candidates().filter { it.favorite || (it.lastPlayedAt ?: 0) >= options.resetAt } else emptyList()).map { if (options.resetAt > 0) it.copy(plays = preferences[it.track.url]?.completions ?: 0) else it }
        val known = cachedMetadata().toMutableMap()
        val candidates = mutableListOf<RecommendationCandidate>()
        candidates += personal.filterNot { PodcastLinks.isEpisode(it.track.url) }.map { RecommendationCandidate(it.track, known[it.track.url], origin = tr("Your library")) }
        var notice: String? = null
        suspend fun <T> optional(block: suspend () -> T): T? = try { block() }
            catch (e: TimeoutCancellationException) { notice = tr("Some sources timed out. Using available candidates."); null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = tr("Some sources are unavailable. Using cached and local candidates."); null }
        optional { related(seed) }?.forEach { candidates += RecommendationCandidate(it, known[it.url], related = .75, origin = tr("Related by YouTube")) }
        if (options.enrichment) optional { withTimeout(10000) { metadata.metadata(seed) } }?.let { known[seed.url] = it }
        val artist = known[seed.url]?.artistId
        val similar = if (options.enrichment && artist != null) optional { similarity.similar(artist.value) }.orEmpty() else emptyList()
        val familiarQuery = known[seed.url]?.artist?.value ?: seed.artist.replace(Regex("(?i)\\s*-?\\s*Topic$"), "").trim()
        val queries = mutableListOf<Pair<String, SimilarArtist?>>()
        if (familiarQuery.isNotBlank()) queries += "$familiarQuery music" to null
        val rotating = if (similar.isNotEmpty()) similar[Math.floorMod(recent.size / 2, similar.size)] else null
        if (rotating != null) queries += rotating.name to rotating
        else if (options.personalized && options.interests.isNotEmpty()) queries += options.interests[Math.floorMod(recent.size, options.interests.size)] to null
        else personal.filter { MusicText.uploader(it.track.artist) != MusicText.uploader(seed.artist) && it.favorite }.firstOrNull()?.let { queries += "${it.track.artist} music" to null }
        for ((query, relatedArtist) in queries.take(2)) {
            optional { remote { source.searchPage(query, CatalogKind.SONGS) } }?.entries?.take(25)?.forEach { entry ->
                entry.track?.let { track -> candidates += RecommendationCandidate(track, known[track.url],
                    relatedArtist = if (relatedArtist != null && known[track.url]?.artistId?.value == relatedArtist.id) relatedArtist.evidence.confidence else null,
                    origin = if (relatedArtist == null) tr("Music search: %1\$s", query) else tr("Search for a ListenBrainz-related artist"), musical = entry.musical) }
            }
        }
        if (options.enrichment) {
            // At most two candidate lookups per batch. Unverified candidates stay usable without fabricated tags.
            for (candidate in candidates.filter { it.track.url !in known && it.musical }.take(2)) optional { withTimeout(7000) { metadata.metadata(candidate.track) } }?.let { known[it.reference] = it }
        }
        val enriched = candidates.map { candidate -> candidate.copy(metadata = known[candidate.track.url], relatedArtist = candidate.relatedArtist ?:
            known[candidate.track.url]?.artistId?.let { credit -> similar.firstOrNull { it.id == credit.value }?.evidence?.confidence }) }
        val ranked = ranker.rank(seed, known[seed.url], enriched, personal, preferences, excluded, recent, options.personalized, limit)
        DiscoveryBatch(ranked, notice ?: if (ranked.isEmpty()) tr("No more distinct music found. Try another seed or add favorites.") else null)
    }
}
