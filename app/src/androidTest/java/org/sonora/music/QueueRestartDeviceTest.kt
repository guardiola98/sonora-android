package org.sonora.music

import android.content.ComponentName
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.music.data.PlaybackQueueStore
import org.sonora.music.domain.Track
import org.sonora.music.playback.PlaybackService
import org.sonora.music.playback.toMediaItem
import java.util.concurrent.TimeUnit

/** Run seed, force-stop the process, then verify in a separate instrumentation invocation. */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class QueueRestartDeviceTest {
    @get:org.junit.Rule val noSpeculation = SpeculationDisabledRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as SonoraApplication
    private val podcastUrl = org.sonora.music.domain.PodcastLinks.reference("https://example.org/sonora-test.xml", "queue-podcast-fixture")
    private val url = "https://www.youtube.com/watch?v=s0n0raQ0001"
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail(label)
    }
    private fun session(block: (MediaController) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use {
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            instrumentation.runOnMainSync { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val controller = future.get(15, TimeUnit.SECONDS)
            try { block(controller) } finally { instrumentation.runOnMainSync { controller.release() } }
        }
    }
    @Test fun seedQueueBeforeProcessDeath() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("queueStage") == "seed")
        session { controller ->
            val tracks = listOf(Track(url, "Queue persistence fixture", "Sonora tests", "", 120), Track(podcastUrl, "Podcast queue fixture", "Sonora tests", "", 180))
            runBlocking {
                app.graph.podcastLibrary.put(org.sonora.music.data.SavedPodcast("https://example.org/sonora-test.xml", "Podcast restart fixture", "Sonora tests", "", "", true, 1))
                app.graph.podcastLibrary.saveProgress(org.sonora.music.data.PodcastProgress(podcastUrl, 12_345, 180_000, false, 100))
            }
            instrumentation.runOnMainSync { controller.setMediaItems(tracks.map { it.toMediaItem() }, 1, 12_345); controller.pause() }
            await("Queue was not committed") { runBlocking { app.graph.library.checkpoint()?.let { val q = PlaybackQueueStore.decode(it); q.tracks.size == 2 && q.index == 1 && q.positionMs == 12_345L } == true } }
        }
    }
    @Test fun restoreQueueAfterProcessDeathWithoutAutoplayOrHistory() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("queueStage") == "verify")
        session { controller ->
            await("Queue was not restored") { var ready = false; instrumentation.runOnMainSync { ready = controller.mediaItemCount == 2 }; ready }
            instrumentation.runOnMainSync {
                assertEquals(1, controller.currentMediaItemIndex)
                assertEquals(podcastUrl, controller.currentMediaItem?.mediaId)
                assertEquals(12_345L, controller.currentPosition)
                assertFalse(controller.playWhenReady); assertFalse(controller.isPlaying)
            }
            runBlocking {
                assertNull(app.graph.library.find(url)); assertNull(app.graph.library.find(podcastUrl))
                assertTrue(app.graph.podcastLibrary.find("https://example.org/sonora-test.xml")!!.subscribed)
                assertEquals(12_345L, app.graph.podcastLibrary.progressFor(podcastUrl)!!.resumeMs)
                app.graph.podcastLibrary.subscribe("https://example.org/sonora-test.xml", false)
            }
            instrumentation.runOnMainSync { controller.clearMediaItems() }
            await("Cleared queue not persisted") { runBlocking { app.graph.library.checkpoint()?.let { PlaybackQueueStore.decode(it).tracks.isEmpty() } == true } }
        }
    }
}
