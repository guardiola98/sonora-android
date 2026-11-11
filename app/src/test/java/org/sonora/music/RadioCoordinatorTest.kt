package org.sonora.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.sonora.music.discovery.*
import org.sonora.music.domain.Track

@OptIn(ExperimentalCoroutinesApi::class)
class RadioCoordinatorTest {
    private fun track(id: Int) = Track("https://www.youtube.com/watch?v=${id.toString().padStart(11, '0')}", "Song $id", "Credit $id", "", 180)
    private fun recommendation(id: Int) = Recommendation(track(id), .8, listOf(ScorePart("Related by the source", .8, 20.0)), "$id", "$id", "Test provider")
    private class Queue(val values: MutableList<Track>) : RadioQueue {
        var playing = true; var current = 0
        override fun tracks() = values.toList()
        override fun index() = current
        override fun activePlayback() = playing
        override fun append(recommendations: List<Recommendation>) { values += recommendations.map { it.track } }
        override fun removeUpcoming(urls: Set<String>) { for (i in values.lastIndex downTo current + 1) if (values[i].url in urls) values.removeAt(i) }
    }
    @Test fun manualQueuesNeverRequestOrReceiveRecommendations() = runTest {
        var calls = 0; val queue = Queue(mutableListOf(track(1), track(2)))
        val radio = RadioCoordinator(RecommendationSource { _, _, _, _ -> calls++; DiscoveryBatch(listOf(recommendation(3))) }, queue, backgroundScope, RadioStatus(), { true })
        radio.tick(); runCurrent(); assertEquals(0, calls); assertEquals(2, queue.values.size)
    }
    @Test fun aRadioAppendsProgressivelyAndMarksAutomaticReasons() = runTest {
        val queue = Queue(mutableListOf(track(1))); val status = RadioStatus()
        val radio = RadioCoordinator(RecommendationSource { _, excluded, _, _ -> assertTrue(track(1).url in excluded); DiscoveryBatch((2..6).map(::recommendation)) }, queue, backgroundScope, status, { true })
        radio.begin(track(1)); radio.played(track(1)); runCurrent()
        assertEquals(6, queue.values.size); assertEquals(5, status.state.value.automatic.size)
        assertFalse(status.state.value.loading)
    }
    @Test fun manualEditDuringSlowNonCancellableFetchCannotInjectOldResults() = runTest {
        val queue = Queue(mutableListOf(track(1))); val status = RadioStatus()
        val radio = RadioCoordinator(RecommendationSource { _, _, _, _ -> withContext(NonCancellable) { delay(500) }; DiscoveryBatch(listOf(recommendation(2))) }, queue, backgroundScope, status, { true })
        radio.begin(track(1)); radio.tick(); runCurrent(); radio.manual(); queue.values += track(9)
        advanceTimeBy(600); runCurrent()
        assertEquals(listOf(track(1), track(9)), queue.values); assertFalse(status.state.value.active)
    }
    @Test fun disablingAutoplayCancelsInflightAndRemovesOnlyFutureAutomaticTracks() = runTest {
        var enabled = true; val queue = Queue(mutableListOf(track(1))); val status = RadioStatus()
        val radio = RadioCoordinator(RecommendationSource { _, _, _, _ -> DiscoveryBatch(listOf(recommendation(2), recommendation(3))) }, queue, backgroundScope, status, { enabled })
        radio.begin(track(1)); radio.tick(); runCurrent(); queue.values += track(9)
        enabled = false; radio.disable(); runCurrent()
        assertEquals(listOf(track(1), track(9)), queue.values)
        radio.tick(); runCurrent(); assertEquals(2, queue.values.size)
    }
    @Test fun restoringPausedRadioMakesNoNetworkRequestsUntilPlaybackStarts() = runTest {
        var calls = 0; val queue = Queue(mutableListOf(track(1))).apply { playing = false }
        val radio = RadioCoordinator(RecommendationSource { _, _, _, _ -> calls++; DiscoveryBatch(listOf(recommendation(2))) }, queue, backgroundScope, RadioStatus(), { true })
        radio.begin(track(1), recent = listOf(track(3))); radio.tick(); runCurrent(); assertEquals(0, calls)
        queue.playing = true; radio.tick(); runCurrent(); assertEquals(1, calls)
    }
    @Test fun emptyOrFailedSourcesBackOffAndNeverBusyLoop() = runTest {
        var now = 0L; var calls = 0; val queue = Queue(mutableListOf(track(1)))
        val radio = RadioCoordinator(RecommendationSource { _, _, _, _ -> calls++; throw java.io.IOException("Offline") }, queue, backgroundScope, RadioStatus(), { true }, { now })
        radio.begin(track(1)); repeat(100) { radio.tick(); runCurrent() }; assertEquals(1, calls)
        now = 60001; radio.tick(); runCurrent(); assertEquals(2, calls)
        now += 120001; radio.tick(); runCurrent(); assertEquals(3, calls)
        now += 900000; radio.tick(); runCurrent(); assertEquals(3, calls)
    }
    @Test fun radioRefillsAcrossOneHundredTransitionsWithoutRepeatingRecentTracks() = runTest {
        val queue = Queue(mutableListOf(track(1))); val status = RadioStatus()
        var now = 0L; var generated = 2; var calls = 0
        val radio = RadioCoordinator(RecommendationSource { _, excluded, recent, _ ->
            calls++; assertTrue(recent.size <= 80)
            val additions = (generated until generated + 5).map(::recommendation); generated += 5
            assertTrue(additions.none { it.track.url in excluded })
            DiscoveryBatch(additions)
        }, queue, backgroundScope, status, { true }, { now })
        radio.begin(track(1)); radio.played(track(1)); runCurrent()
        val played = mutableSetOf(track(1).url)
        repeat(100) {
            now += 180000; queue.current++
            assertTrue(queue.current < queue.values.size)
            assertTrue(played.add(queue.values[queue.current].url))
            radio.played(queue.values[queue.current]); runCurrent()
        }
        assertTrue(calls >= 20); assertEquals(80, status.state.value.recent.size)
        assertTrue(queue.values.size - queue.current - 1 in 3..7)
    }
    @Test fun seekToEndDoesNotPretendTheWholeSongWasHeard() {
        val tracker = ListeningTracker(); tracker.start(track(1), 0); tracker.tick(true, 0)
        tracker.tick(true, 2000)
        assertEquals("listen", tracker.finish(natural = true, userSkip = false, durationMs = 180000)!!.kind)
    }
    @Test fun realElapsedPlaybackCompletesAndShortUserSkipIsOnlyASoftSignal() {
        val tracker = ListeningTracker(); tracker.start(track(1), 0); tracker.tick(true, 0)
        for (time in 1000L..180000L step 1000L) tracker.tick(true, time)
        assertEquals("complete", tracker.finish(true, false, 180000)!!.kind)
        tracker.start(track(2), 200000); tracker.tick(true, 200000); tracker.tick(true, 203000)
        assertEquals("skip", tracker.finish(false, true, 180000)!!.kind)
        tracker.start(track(3), 300000); tracker.tick(false, 310000)
        assertNull(tracker.finish(false, true, 180000))
    }
}
