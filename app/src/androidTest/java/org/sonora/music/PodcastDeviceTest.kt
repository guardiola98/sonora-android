package org.sonora.music

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.takeScreenshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.music.data.*
import org.sonora.music.domain.PodcastLinks
import org.sonora.music.ui.MusicViewModel
import java.io.File

@UnstableApi
@RunWith(AndroidJUnit4::class)
class PodcastDeviceTest {
    @get:org.junit.Rule val noSpeculation = SpeculationDisabledRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, timeoutMs: Long = 30_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var success = false; main { success = predicate() }
            if (success) return
            Thread.sleep(100)
        }
        fail(message)
    }
    private fun node(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        for (i in root.childCount - 1 downTo 0) node(root.getChild(i), text)?.let { return it }
        return root.takeIf { it.text?.toString() == text || it.contentDescription?.toString() == text }
    }
    private fun click(text: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var found = node(instrumentation.getUiAutomation(0).rootInActiveWindow, text)
            if (found != null) {
                while (!found!!.isClickable && found.parent != null) found = found.parent
                assertTrue("Could not click $text", found.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return
            }
            Thread.sleep(100)
        }
        fail("Missing UI action: $text")
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val image = takeScreenshot().copy(Bitmap.Config.ARGB_8888, false)
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun liveSearchSubscriptionAudioProgressAndBackgroundPlayback() {
        assumeTrue("Live podcast check is opt-in", InstrumentationRegistry.getArguments().getString("livePodcasts") == "true")
        val graph = (context.applicationContext as SonoraApplication).graph
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: MusicViewModel
            scenario.onActivity { vm = ViewModelProvider(it, MusicViewModel.Factory(it.application as SonoraApplication))[MusicViewModel::class.java] }
            await("Player not connected") { vm.playback.state.value.connected }
            click("Podcasts")
            main { vm.podcasts.search("Science Friday") }
            await("Live podcast search failed: ${vm.podcasts.state.value.searchError}", 60_000) { !vm.podcasts.state.value.searching }
            val result = vm.podcasts.state.value.results.firstOrNull { it.title == "Science Friday" } ?: error("Podcast not found: ${vm.podcasts.state.value}")
            main { vm.podcasts.open(result.feedUrl) }
            await("RSS did not load", 60_000) { !vm.podcasts.state.value.loading }
            assertNull(vm.podcasts.state.value.error)
            val show = vm.podcasts.state.value.show!!
            assertTrue("Publisher artwork is missing", show.artwork.isNotBlank())
            val episode = vm.podcasts.state.value.episodes.first { it.durationSeconds > 120 }
            if (!show.subscribed) click("Subscribe")
            await("Subscription not saved") { vm.podcasts.state.value.show?.subscribed == true }
            assertTrue(runBlocking { graph.podcastLibrary.find(result.feedUrl) }!!.subscribed)
            screenshot("podcast-episodes")
            main { vm.podcasts.play(episode) }
            await("Publisher RSS audio did not decode: ${graph.playbackStatus.error.value}", 90_000) {
                val player = vm.playback.state.value
                player.current?.url == episode.reference && player.playing && !player.buffering && player.positionMs > 2500
            }
            await("Media notification missing") { context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.notification.category == Notification.CATEGORY_TRANSPORT } }
            main { vm.playback.seek(30_000); vm.playback.speed(1.5f) }
            await("Podcast seek or playback speed failed") { vm.playback.state.value.positionMs >= 30_000 && vm.playback.state.value.speed == 1.5f }
            scenario.moveToState(Lifecycle.State.CREATED)
            val backgroundStart = vm.playback.state.value.positionMs
            await("Podcast stopped in background") { vm.playback.state.value.positionMs > backgroundStart + 1500 && vm.playback.state.value.playing }
            main { vm.playback.toggle() }
            await("Pause did not persist progress") {
                runBlocking { graph.podcastLibrary.progressFor(episode.reference) }?.positionMs?.let { it >= 30_000 } == true
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            main { vm.playback.clear() }
            await("Queue did not clear") { vm.playback.state.value.current == null }
            main { vm.podcasts.play(episode) }
            await("Episode did not resume from saved position", 60_000) { vm.playback.state.value.positionMs >= 30_000 && vm.playback.state.value.playing && !vm.playback.state.value.buffering }
            main { vm.playback.toggle() }
            val snapshot = runBlocking { graph.library.exportBackup() }
            assertTrue(snapshot.podcasts.any { it.feedUrl == result.feedUrl })
            assertTrue(snapshot.progress.any { it.reference == episode.reference && it.positionMs >= 30_000 })
            val queue = runBlocking { graph.queueStore.read() }
            assertTrue(PodcastLinks.isEpisode(queue.tracks.single().url))
            assertEquals(queue, PlaybackQueueStore.decode(PlaybackQueueStore.encode(queue)))
            click("Subscribed · Unsubscribe")
            await("Unsubscribe failed") { vm.podcasts.state.value.show?.subscribed == false }
            click("Subscribe")
            main { vm.podcasts.markPlayed(episode) }
            await("Played state not saved") { vm.podcasts.progress.value[episode.reference]?.completed == true }
            main { vm.podcasts.markPlayed(episode) }
            await("Unplayed state not saved") { vm.podcasts.progress.value[episode.reference]?.let { !it.completed && it.positionMs == 0L } == true }
            Thread.sleep(2500) // A late periodic/checkpoint write must not undo the explicit state.
            assertEquals(0L, runBlocking { graph.podcastLibrary.progressFor(episode.reference) }!!.resumeMs)
            assertFalse(runBlocking { graph.podcastLibrary.progressFor(episode.reference) }!!.completed)
            screenshot("podcast-episodes")
            main { vm.playback.clear() }
        }
    }
}
