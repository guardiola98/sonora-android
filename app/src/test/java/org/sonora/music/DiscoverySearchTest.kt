package org.sonora.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.sonora.music.discovery.*
import org.sonora.music.domain.*
import org.sonora.music.ui.SearchPresenter

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverySearchTest {
    private val source = object : MusicSource {
        override suspend fun search(query: String) = emptyList<Track>()
        override suspend fun resolve(url: String) = AudioStream(url)
    }
    private fun entry(id: String) = CatalogEntry(id, "Song $id", "Credit", "", CatalogKind.SONGS, Track(id, "Song $id", "Credit", "", 180), true)
    private open inner class Repository : SearchDataSource {
        val calls = mutableListOf<String>(); val remembered = mutableListOf<String>()
        override suspend fun local(query: String, kind: CatalogKind) = listOf(entry("local"))
        override suspend fun remote(query: String, kind: CatalogKind, cursor: String?): CatalogPage { calls += query; return CatalogPage(listOf(entry(query)), if (cursor == null) "page2" else null) }
        override suspend fun suggestions(query: String) = listOf("$query suggestion")
        override suspend fun remember(query: String) { remembered += query }
    }
    @Test fun typingIsDebouncedAndOnlyExplicitSearchesEnterHistory() = runTest {
        val data = Repository(); val presenter = SearchPresenter(source, backgroundScope, data)
        presenter.edit("so"); advanceTimeBy(200); presenter.edit("song"); advanceTimeBy(449); runCurrent()
        assertTrue(data.calls.isEmpty()); advanceTimeBy(2); runCurrent()
        assertEquals(listOf("song"), data.calls); assertTrue(data.remembered.isEmpty())
        presenter.search("song"); runCurrent(); assertEquals(listOf("song"), data.remembered)
    }
    @Test fun localResultsRemainWhenRemoteSourceFails() = runTest {
        val data = object : Repository() { override suspend fun remote(query: String, kind: CatalogKind, cursor: String?): CatalogPage = throw java.io.IOException("Offline") }
        val presenter = SearchPresenter(source, backgroundScope, data); presenter.search("song"); runCurrent()
        assertEquals("local", presenter.state.value.tracks.single().url); assertNotNull(presenter.state.value.error)
    }
    @Test fun paginationDeduplicatesAndOldResponsesCannotReplaceANewQuery() = runTest {
        val data = object : Repository() {
            override suspend fun remote(query: String, kind: CatalogKind, cursor: String?): CatalogPage {
                if (cursor != null) withContext(NonCancellable) { delay(500) }
                return CatalogPage(listOf(entry(query), entry("local")), if (cursor == null) "page2" else null)
            }
        }
        val presenter = SearchPresenter(source, backgroundScope, data)
        presenter.search("first"); runCurrent(); assertEquals(2, presenter.state.value.tracks.size)
        presenter.loadMore(); runCurrent(); presenter.search("second"); runCurrent()
        advanceTimeBy(600); runCurrent()
        assertEquals("second", presenter.state.value.query); assertFalse(presenter.state.value.tracks.any { it.url == "first" })
    }
    @Test fun clearingQueryCancelsPaginationAndItsLoadingIndicator() = runTest {
        val data = object : Repository() {
            override suspend fun remote(query: String, kind: CatalogKind, cursor: String?): CatalogPage {
                if (cursor != null) withContext(NonCancellable) { delay(500) }
                return CatalogPage(listOf(entry(query)), "page2")
            }
        }
        val presenter = SearchPresenter(source, backgroundScope, data)
        presenter.search("song"); runCurrent(); presenter.loadMore(); runCurrent()
        assertTrue(presenter.state.value.loadingMore)
        presenter.edit(""); advanceTimeBy(600); runCurrent()
        assertTrue(presenter.state.value.tracks.isEmpty()); assertFalse(presenter.state.value.loadingMore)
        assertFalse(presenter.state.value.loading); assertNull(presenter.state.value.next)
    }
    @Test fun failedPageCanBeRetriedWithoutDroppingExistingResults() = runTest {
        var fail = true
        val data = object : Repository() {
            override suspend fun remote(query: String, kind: CatalogKind, cursor: String?): CatalogPage {
                if (cursor != null && fail) throw java.io.IOException("Offline")
                return CatalogPage(listOf(entry(if (cursor == null) "first" else "next")), if (cursor == null) "page2" else null)
            }
        }
        val presenter = SearchPresenter(source, backgroundScope, data); presenter.search("song"); runCurrent()
        presenter.loadMore(); runCurrent(); assertEquals(2, presenter.state.value.tracks.size); assertEquals("page2", presenter.state.value.next)
        fail = false; presenter.loadMore(); runCurrent(); assertEquals(3, presenter.state.value.tracks.size); assertNull(presenter.state.value.next)
    }
}
