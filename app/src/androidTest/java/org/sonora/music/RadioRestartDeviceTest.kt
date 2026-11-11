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
import org.sonora.music.data.SavedQueue
import org.sonora.music.domain.Track
import org.sonora.music.playback.PlaybackService
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class RadioRestartDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val seed = Track("https://www.youtube.com/watch?v=radiotest01", "Radio restart fixture", "Sonora tests", "", 180)
    private val next = seed.copy(url = "https://www.youtube.com/watch?v=radiotest02", title = "Automatic fixture")
    @Test fun seedPausedRadioCheckpoint() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("radioStage") == "seed")
        val app = instrumentation.targetContext.applicationContext as SonoraApplication
        app.graph.preparation.setEnabled(false)
        app.graph.discoverySettings.update(app.graph.discoverySettings.state.value.copy(autoplay = true, personalized = false))
        app.graph.queueStore.save(SavedQueue(listOf(seed, next), 1, 12345, seed, mapOf(next.url to "Related fixture"), listOf(seed, next)))
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline && runBlocking { app.graph.library.checkpoint()?.positionMs } != 12345L) Thread.sleep(100)
        assertEquals(12345L, runBlocking { app.graph.library.checkpoint()!!.positionMs })
    }
    @Test fun verifyPausedRadioAfterProcessDeathAndReinstall() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("radioStage") == "verify")
        val app = instrumentation.targetContext.applicationContext as SonoraApplication
        ActivityScenario.launch(MainActivity::class.java).use {
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            instrumentation.runOnMainSync { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val player = future.get(15, TimeUnit.SECONDS)
            try {
                val deadline = System.currentTimeMillis() + 10000
                while (System.currentTimeMillis() < deadline && app.graph.radioStatus.state.value.seed == null) Thread.sleep(100)
                instrumentation.runOnMainSync {
                    assertEquals(2, player.mediaItemCount); assertEquals(1, player.currentMediaItemIndex); assertEquals(12345L, player.currentPosition)
                    assertFalse(player.playWhenReady); assertFalse(player.isPlaying)
                }
                assertEquals(seed, app.graph.radioStatus.state.value.seed)
                assertEquals("Related fixture", app.graph.radioStatus.state.value.automatic[next.url])
                assertFalse(app.graph.radioStatus.state.value.loading)
                assertNull(runBlocking { app.graph.library.find(seed.url) })
            } finally { instrumentation.runOnMainSync { player.stop(); player.clearMediaItems(); player.release() } }
        }
    }
}
