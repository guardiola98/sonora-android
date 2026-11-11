package org.sonora.music

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import org.sonora.music.ui.MusicViewModel
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import org.sonora.music.playback.PlaybackService
import org.sonora.music.playback.toMediaItem
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
@UnstableApi
class PlaybackDeviceTest {
    @get:org.junit.Rule val noSpeculation = SpeculationDisabledRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, timeoutMs: Long = 20_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var success = false
            main { success = predicate() }
            if (success) return
            Thread.sleep(100)
        }
        fail(message)
    }
    private fun connected(block: (ActivityScenario<MainActivity>, MediaController) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
            val controller = future.get(15, TimeUnit.SECONDS)
            try { block(scenario, controller) }
            finally { main { controller.stop(); controller.clearMediaItems(); controller.release() } }
        }
    }
    @Test fun closingAndReopeningActivityDoesNotReportAPlayerFailure() {
        val app = context.applicationContext as SonoraApplication
        repeat(2) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: MusicViewModel
                scenario.onActivity { vm = ViewModelProvider(it, MusicViewModel.Factory(app))[MusicViewModel::class.java] }
                await("UI controller did not connect") { vm.playback.state.value.connected }
                main { assertNull(app.graph.playbackStatus.error.value) }
            }
            instrumentation.waitForIdleSync()
            main { assertNull("Intentional release must not report disconnection", app.graph.playbackStatus.error.value) }
        }
    }
    @Test fun realDecoderSessionSeekQueueAndBackgroundPlayback() = connected { scenario, controller ->
        // Locally generated PCM fixture, never bundled with or shown by the production app.
        val sampleRate = 16_000
        val samples = sampleRate * 20
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort((sin(2 * Math.PI * 440 * it / sampleRate) * 1000).toInt().toShort()) }
        val file = java.io.File(context.cacheDir, "playback-test.wav").apply { writeBytes(bytes.array()) }
        val first = MediaItem.Builder().setMediaId("fixture-one").setUri(Uri.fromFile(file))
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Playback test").setArtist("Sonora test fixture").build()).build()
        main { controller.setMediaItems(listOf(first, first.buildUpon().setMediaId("fixture-two").build())); controller.prepare(); controller.play() }
        await("PCM decoder did not start") { controller.isPlaying && controller.currentPosition > 500 }
        await("Media notification was not published") {
            context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.notification.category == Notification.CATEGORY_TRANSPORT }
        }
        main { controller.seekTo(5_000) }
        await("Seek did not take effect") { controller.currentPosition >= 5_000 }
        main { controller.seekToNextMediaItem() }
        await("Queue did not advance") { controller.currentMediaItemIndex == 1 && controller.isPlaying }
        main { controller.pause() }
        await("Pause failed") { !controller.isPlaying }
        main { controller.seekToPreviousMediaItem(); controller.play() }
        await("Previous failed") { controller.currentMediaItemIndex == 0 && controller.isPlaying }
        scenario.moveToState(Lifecycle.State.CREATED)
        var position = 0L
        main { position = controller.currentPosition }
        await("Playback stopped with activity in background") { controller.isPlaying && controller.currentPosition > position + 500 }
        file.delete()
    }
    @Test fun realYouTubePlaybackWhenExplicitlyEnabled() {
        assumeTrue("Live test is opt-in", InstrumentationRegistry.getArguments().getString("liveYouTube") == "true")
        val app = context.applicationContext as SonoraApplication
        val tracks = runBlocking { app.graph.source.search("Kevin MacLeod Carefree") }
        assertTrue(tracks.isNotEmpty())
        connected { scenario, controller ->
            main { controller.setMediaItem(tracks.first().toMediaItem()); controller.prepare(); controller.play() }
            await("Extracted YouTube audio did not play; inspect logcat/source availability", 90_000) {
                controller.isPlaying && controller.currentPosition > 2_000
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            var position = 0L
            main { position = controller.currentPosition }
            await("YouTube playback stopped in background") { controller.isPlaying && controller.currentPosition > position + 1_000 }
        }
    }
}
