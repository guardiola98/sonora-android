package org.sonora.music

import android.content.ComponentName
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.music.playback.PlaybackService
import org.sonora.music.ui.MusicViewModel
import java.io.File
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AutomaticPreparationDeviceTest {
    @Test fun visibleSearchResultIsPreparedWithoutPlaybackOrHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("startupBenchmark") == "true")
        val app = instrumentation.targetContext.applicationContext as SonoraApplication
        val previous = app.graph.preparation.enabled.value
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(label: String, timeout: Long = 30_000, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(20) }
            fail(label)
        }
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            for (index in node.childCount - 1 downTo 0) find(node.getChild(index), label)?.let { return it }
            return node.takeIf { it.text?.toString() == label || it.contentDescription?.toString() == label }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: MusicViewModel
            scenario.onActivity { vm = ViewModelProvider(it, MusicViewModel.Factory(app))[MusicViewModel::class.java] }
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val player = future.get(15, TimeUnit.SECONDS)
            try {
                main { app.graph.preparation.setEnabled(true); player.pause(); player.stop(); player.clearMediaItems(); app.graph.preparation.prioritize(null); app.graph.audioPrefixes.clear() }
                await("Search navigation not exposed") { find(instrumentation.getUiAutomation(0).rootInActiveWindow, "Search") != null }
                var node = find(instrumentation.getUiAutomation(0).rootInActiveWindow, "Search")!!
                while (!node.isClickable && node.parent != null) node = node.parent
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                main { vm.search.search("Kevin MacLeod Carefree") }
                await("Search failed", 60_000) { vm.search.state.value.tracks.isNotEmpty() }
                val track = vm.search.state.value.tracks.first()
                val before = runBlocking { app.graph.library.find(track.url)?.playCount ?: 0 }
                await("Visible search track was not prepared", 60_000) { app.graph.audioPrefixes.hasOwner(track.url) }
                main { assertFalse(player.isPlaying); assertFalse(player.playWhenReady) }
                assertEquals(before, runBlocking { app.graph.library.find(track.url)?.playCount ?: 0 })
                val start = SystemClock.elapsedRealtime()
                main { vm.playback.play(listOf(track), 0) }
                var elapsed = -1L
                await("Prepared track did not start", 15_000) {
                    main { if (player.currentMediaItem?.mediaId == track.url && player.isPlaying && player.currentPosition >= 50) elapsed = SystemClock.elapsedRealtime() - start }
                    elapsed >= 0
                }
                File(app.getExternalFilesDir(null), "automatic-preparation.json").writeText(JSONObject().put("track", track.url).put("startupMs", elapsed).toString(2))
                assertTrue("Prepared startup exceeded 1.5 seconds: $elapsed ms", elapsed < 1500)
                main { player.pause(); app.graph.preparation.setEnabled(false) }
                assertFalse(app.graph.audioPrefixes.hasOwner(track.url))
            } finally { main { player.pause(); player.stop(); player.clearMediaItems(); player.release(); app.graph.preparation.prioritize(null); app.graph.preparation.setEnabled(previous) } }
        }
    }
}
