package org.sonora.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.sonora.music.domain.*
import org.sonora.music.ui.SearchPresenter

@OptIn(ExperimentalCoroutinesApi::class)
class SearchPresenterTest {
    @Test fun latestQueryWinsAndBlankInputClearsResults() = runTest {
        val source = object : MusicSource {
            override suspend fun search(query: String): List<Track> {
                delay(if (query == "old") 1000 else 10)
                return listOf(Track(query, query, "Artist", "", 120))
            }
            override suspend fun resolve(url: String) = AudioStream(url)
        }
        val presenter = SearchPresenter(source, backgroundScope)
        presenter.search("old"); runCurrent()
        presenter.search(" new "); advanceTimeBy(11); runCurrent()
        assertEquals("new", presenter.state.value.tracks.single().title)
        advanceTimeBy(2000); runCurrent()
        assertEquals("new", presenter.state.value.query)
        presenter.search("  ")
        assertFalse(presenter.state.value.searched)
        assertTrue(presenter.state.value.tracks.isEmpty())
    }
    @Test fun networkFailureIsVisibleAndRetryReplacesTheError() = runTest {
        var fail = true
        val source = object : MusicSource {
            override suspend fun search(query: String): List<Track> {
                if (fail) throw MusicSourceException(MusicSourceException.Kind.NETWORK)
                return emptyList()
            }
            override suspend fun resolve(url: String) = AudioStream(url)
        }
        val presenter = SearchPresenter(source, backgroundScope)
        presenter.search("song"); runCurrent()
        assertNotNull(presenter.state.value.error)
        assertFalse(presenter.state.value.loading)
        fail = false
        presenter.search("song"); runCurrent()
        assertNull(presenter.state.value.error)
        assertTrue(presenter.state.value.searched)
    }
}
