package org.sonora.music.discovery

import kotlinx.coroutines.*
import org.sonora.music.data.*

interface SearchDataSource {
    suspend fun local(query: String, kind: CatalogKind): List<CatalogEntry>
    suspend fun remote(query: String, kind: CatalogKind, cursor: String?): CatalogPage
    suspend fun suggestions(query: String): List<String>
    suspend fun remember(query: String)
}
class SearchRepository(private val source: MusicCatalogSource, private val library: LibraryDao, private val dao: DiscoveryDao,
    private val settings: DiscoverySettings) : SearchDataSource {
    private val cache = SingleFlightCache<String, CatalogPage>(32, 5 * 60000)
    private val suggestCache = SingleFlightCache<String, List<String>>(32, 10 * 60000)
    override suspend fun local(query: String, kind: CatalogKind): List<CatalogEntry> {
        val words = MusicText.tokens(query)
        if (words.isEmpty()) return emptyList()
        val rows = library.searchableTracks()
        if (kind == CatalogKind.ARTISTS || kind == CatalogKind.ALBUMS) {
            val urls = rows.map { it.url }.toSet()
            return dao.metadataSnapshot(System.currentTimeMillis()).mapNotNull { row -> runCatching { MetadataCodec.decode(row.payload) }.getOrNull() }
                .filter { it.reference in urls }.mapNotNull { meta ->
                    val name = (if (kind == CatalogKind.ARTISTS) meta.artist else meta.album)?.takeIf { it.confidence >= .85 } ?: return@mapNotNull null
                    val id = if (kind == CatalogKind.ARTISTS) meta.artistId else meta.albumId
                    if (id == null || !words.all { it in MusicText.tokens(name.value + " " + meta.artist?.value.orEmpty()) }) return@mapNotNull null
                    CatalogEntry("https://musicbrainz.org/${if (kind == CatalogKind.ARTISTS) "artist" else "release-group"}/${id.value}", name.value,
                        "MusicBrainz · ${meta.artist?.value.orEmpty()}", rows.firstOrNull { it.url == meta.reference }?.artwork.orEmpty(), kind, musical = true)
                }.distinctBy { it.url }.take(50)
        }
        return withContext(Dispatchers.Default) {
            when (kind) {
                CatalogKind.SONGS -> rows.asSequence().filter { row -> words.all { it in MusicText.tokens(row.title + " " + row.artist) } }.take(150)
                    .map { CatalogEntry(it.url, it.title, it.artist, it.artwork, CatalogKind.SONGS, it.track()) }.toList()
                else -> emptyList()
            }
        }
    }
    override suspend fun remote(query: String, kind: CatalogKind, cursor: String?): CatalogPage =
        if (cursor == null) cache.get("${kind.name}:${MusicText.normalize(query)}") { source.searchPage(query, kind) } else source.searchPage(query, kind, cursor)
    override suspend fun suggestions(query: String): List<String> = if (!settings.state.value.onlineSuggestions || query.trim().length < 2) emptyList()
        else suggestCache.get(MusicText.normalize(query)) { withTimeout(6000) { source.suggestions(query) } }
    override suspend fun remember(query: String) = dao.rememberSearch(query)
}
