package org.sonora.music

import android.content.ComponentName
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.music.playback.PlaybackService
import org.sonora.music.playback.toMediaItem
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in real-network measurement: command to playing + advancing position, not acoustic latency. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class StartupBenchmarkTest {
    @Test fun measureRealYouTubeStartup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("startupBenchmark") == "true")
        val app = instrumentation.targetContext.applicationContext as SonoraApplication
        val resultsFromSource = runBlocking { app.graph.source.search("Kevin MacLeod Carefree") }
        val requestedUrl = args.getString("startupTrack")
        val track = if (requestedUrl == null) resultsFromSource.first() else resultsFromSource.first { it.url == requestedUrl }
        val previousPreparation = app.graph.preparation.enabled.value
        app.graph.preparation.setEnabled(false)
        val results = JSONArray()
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        ActivityScenario.launch(MainActivity::class.java).use {
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val player = future.get(15, TimeUnit.SECONDS)
            try {
                for (mode in listOf("cold", "resolved", "prepared")) repeat(3) { trial ->
                    main { player.pause(); player.stop(); player.clearMediaItems() }
                    main { app.graph.preparation.prioritize(null) }
                    app.graph.audioPrefixes.clear()
                    runBlocking { app.graph.streams.invalidate(track.url) }
                    var preparationMs = 0L
                    if (mode != "cold") {
                        val start = SystemClock.elapsedRealtime()
                        runBlocking {
                            if (mode == "prepared") app.graph.preparation.prepare(track.url) else app.graph.streams.resolve(track.url)
                        }
                        if (mode == "prepared") assertTrue("Audio prefix was not prepared", app.graph.audioPrefixes.hasOwner(track.url))
                        preparationMs = SystemClock.elapsedRealtime() - start
                    }
                    val start = SystemClock.elapsedRealtime()
                    main { player.setMediaItem(track.toMediaItem()); player.prepare(); player.play() }
                    var elapsed: Long? = null
                    var error: String? = null
                    while (SystemClock.elapsedRealtime() - start < 90_000) {
                        main {
                            error = player.playerError?.errorCodeName
                            if (player.isPlaying && player.currentPosition >= 50) elapsed = SystemClock.elapsedRealtime() - start
                        }
                        if (elapsed != null || error != null) break
                        Thread.sleep(10)
                    }
                    val row = JSONObject().put("mode", mode).put("trial", trial + 1).put("startupMs", elapsed ?: -1)
                        .put("preparationMs", preparationMs).put("error", error ?: JSONObject.NULL)
                    results.put(row)
                    val report = JSONObject().put("version", BuildConfig.VERSION_NAME).put("track", track.url).put("results", results)
                    File(app.getExternalFilesDir(null), "startup-benchmark.json").writeText(report.toString(2))
                    assertNotNull("Audio did not start: $error", elapsed)
                    assertNull(error)
                    if (mode == "prepared" && trial == 0) {
                        Thread.sleep(12_000) // Play past the cached prefix into the network continuation.
                        main { assertNull(player.playerError); assertTrue(player.isPlaying); assertTrue(player.currentPosition > 10_000); player.seekTo(60_000) }
                        val deadline = SystemClock.elapsedRealtime() + 15_000
                        var seeked = false
                        while (SystemClock.elapsedRealtime() < deadline && !seeked) {
                            main { seeked = player.isPlaying && player.currentPosition >= 60_000 }
                            Thread.sleep(20)
                        }
                        assertTrue("Seeking after cached startup failed", seeked)
                    }
                    Thread.sleep(300)
                }
            } finally { main { player.pause(); player.stop(); player.clearMediaItems(); player.release(); app.graph.preparation.prioritize(null); app.graph.preparation.setEnabled(previousPreparation) } }
        }
    }
}
