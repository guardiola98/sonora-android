package org.sonora.music

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.takeScreenshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.music.data.PlaybackQueueStore
import org.sonora.music.playback.PlaybackService
import org.sonora.music.ui.MusicViewModel

@RunWith(AndroidJUnit4::class)
@UnstableApi
class PlayerShuffleDeviceTest {
    @get:Rule val noSpeculation = SpeculationDisabledRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SonoraApplication
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) { if (predicate()) return; Thread.sleep(100) }
        fail(message)
    }
    private fun node(label: String): AccessibilityNodeInfo? {
        fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (root == null) return null
            for (index in root.childCount - 1 downTo 0) find(root.getChild(index))?.let { return it }
            return root.takeIf { it.text?.toString() == label || it.contentDescription?.toString() == label }
        }
        return find(instrumentation.getUiAutomation(0).rootInActiveWindow)
    }
    private fun click(label: String) {
        await("Missing enabled control: $label") {
            var item = node(label) ?: return@await false
            while (!item.isClickable && item.parent != null) item = item.parent
            item.isEnabled && item.isClickable && item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(400) // Let the sheet animation render before capturing its frame.
        val screenshot = takeScreenshot()
        File(app.getExternalFilesDir(null), "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun playerAndQueueShuffleKeepCurrentAudioAndPersistActualOrder() {
        // Silent generated PCM and canonical IDs allow persistence without network requests.
        val samples = 16_000 * 60
        val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        val file = File(app.cacheDir, "shuffle-test.wav").apply { writeBytes(wav.array()) }
        val items = (0..7).map { index ->
            MediaItem.Builder().setMediaId("https://www.youtube.com/watch?v=s0n0raQ000$index").setUri(Uri.fromFile(file))
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Queue shuffle fixture $index").setArtist("Sonora test audio").build()).build()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: MusicViewModel
            scenario.onActivity { vm = ViewModelProvider(it, MusicViewModel.Factory(app))[MusicViewModel::class.java] }
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val player = future.get(15, TimeUnit.SECONDS)
            fun ids(): List<String> { var result = emptyList<String>(); main { result = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId } }; return result }
            try {
                await("UI did not connect") { vm.playback.state.value.connected }
                main { player.setMediaItems(items, 1, 8_000); player.prepare(); player.play() }
                await("PCM did not start") { var playing = false; main { playing = player.isPlaying && player.currentPosition >= 8_000 }; playing }
                main { player.pause(); player.seekTo(8_000) }
                await("Position did not settle") { vm.playback.state.value.positionMs == 8_000L && !vm.playback.state.value.playing }
                click("Queue shuffle fixture 1")
                await("Shuffle button missing") { node("Shuffle up next") != null }
                val shuffleBounds = Rect().also { node("Shuffle up next")!!.getBoundsInScreen(it) }
                val playBounds = Rect().also { node("Play")!!.getBoundsInScreen(it) }
                assertTrue(shuffleBounds.width() > 0 && shuffleBounds.height() > 0)
                assertTrue("Play must remain visible on compact screens", playBounds.width() > 0 && playBounds.height() > 0)
                val original = ids()
                // The same permutation is legal. Repeated taps exercise a real changed queue.
                for (attempt in 0..4) { click("Shuffle up next"); if (ids() != original) break }
                val shuffled = ids()
                assertNotEquals(original, shuffled)
                assertEquals(original.take(2), shuffled.take(2))
                assertEquals(original.toSet(), shuffled.toSet())
                assertEquals(original.size, shuffled.size)
                main { assertEquals(1, player.currentMediaItemIndex); assertEquals(8_000L, player.currentPosition); assertFalse(player.playWhenReady) }
                await("Shuffled order was not persisted") {
                    runBlocking { app.graph.library.checkpoint()?.let(PlaybackQueueStore::decode)?.let { it.tracks.map { track -> track.url } == shuffled && it.index == 1 && it.positionMs == 8_000L } == true }
                }
                capture("release-073-player-shuffle")
                click("Play")
                await("Playback did not resume") { vm.playback.state.value.playing && vm.playback.state.value.positionMs > 8_000 }
                click("Shuffle up next")
                main { assertTrue(player.isPlaying); assertEquals(items[1].mediaId, player.currentMediaItem!!.mediaId); assertTrue(player.currentPosition >= 8_000) }
                click("Open queue")
                await("Up next sheet did not open") { node("Up next")?.isVisibleToUser == true }
                click("Shuffle up next")
                capture("release-073-queue-shuffle")
                main { player.pause() }
                val next = ids()[2]
                main { vm.playback.next() }
                await("Next did not follow the displayed queue") { vm.playback.state.value.current?.url == next }
                main { player.seekTo(items.lastIndex - 1, 5_000) }
                await("Last pending item did not reach the UI controller") {
                    vm.playback.state.value.index == items.lastIndex - 1 && !vm.playback.state.value.canShuffleUpcoming
                }
                capture("release-073-queue-shuffle-disabled")
                await("Shuffle should be disabled with one pending item") {
                    // Compose may expose the text as a child of the disabled button.
                    !vm.playback.state.value.canShuffleUpcoming &&
                        generateSequence(node("Shuffle up next")) { it.parent }.any { !it.isEnabled }
                }
            } finally {
                main { vm.playback.clear(); player.release() }
                file.delete()
            }
        }
    }
}
