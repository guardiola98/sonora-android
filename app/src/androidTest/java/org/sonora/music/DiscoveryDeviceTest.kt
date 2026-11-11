package org.sonora.music

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.takeScreenshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.music.discovery.*
import org.sonora.music.playback.PlaybackService
import org.sonora.music.ui.MusicViewModel
import java.io.File
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class DiscoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, timeout: Long = 60000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(100) }
        fail(label)
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        for (i in node.childCount - 1 downTo 0) find(node.getChild(i), label)?.let { return it }
        return node.takeIf { it.text?.toString() == label || it.contentDescription?.toString() == label }
    }
    private fun click(label: String) {
        await("Missing UI control: $label", 10000) { find(instrumentation.getUiAutomation(0).rootInActiveWindow, label) != null }
        var node = find(instrumentation.getUiAutomation(0).rootInActiveWindow, label)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); instrumentation.waitForIdleSync()
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(350)
        val bitmap = takeScreenshot().copy(Bitmap.Config.ARGB_8888, false)
        val colors = (0 until bitmap.width step 30).flatMap { x -> (0 until bitmap.height step 30).map { y -> bitmap.getPixel(x, y) } }.toSet()
        assertTrue("Blank capture", colors.size > 8)
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun realMusicSearchRadioBackgroundAndAutoplayPolicy() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveDiscovery") == "true")
        val app = instrumentation.targetContext.applicationContext as SonoraApplication
        val previous = app.graph.discoverySettings.state.value
        val prepared = app.graph.preparation.enabled.value
        app.graph.preparation.setEnabled(false)
        app.graph.discoverySettings.update(previous.copy(autoplay = true, personalized = false, enrichment = false))
        val report = JSONObject()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: MusicViewModel
            scenario.onActivity { vm = ViewModelProvider(it, MusicViewModel.Factory(app))[MusicViewModel::class.java] }
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val player = future.get(15, TimeUnit.SECONDS)
            try {
                main { player.pause(); player.stop(); player.clearMediaItems() }
                click("Search")
                main { vm.search.search("Obrint Pas La flama") }
                await("Real song search failed") { !vm.search.state.value.loading && vm.search.state.value.tracks.isNotEmpty() }
                main {} // Publish the final ranked remote state before taking the test snapshot.
                val songs = vm.search.state.value.tracks
                val seed = songs.firstOrNull { MusicText.normalize(it.title).contains("la flama") && MusicText.variants(it.title).isEmpty() } ?: songs.first()
                report.put("seed", seed.url).put("seedTitle", seed.title).put("sourceCredit", seed.artist).put("searchResults", songs.size).put("searchNotice", vm.search.state.value.notice)
                if (vm.search.state.value.next != null) {
                    val original = songs.map(MusicText::familyFingerprint).toSet()
                    main { vm.search.loadMore() }
                    await("Next page did not finish") { !vm.search.state.value.loadingMore }
                    main {} // Wait until the main-thread page publication, including ranking, has completed.
                    assertNull(vm.search.state.value.error)
                    assertTrue("Paging lost a song family", vm.search.state.value.tracks.map(MusicText::familyFingerprint).containsAll(original))
                    assertEquals(vm.search.state.value.tracks.size, vm.search.state.value.tracks.distinctBy { it.url }.size)
                    report.put("pagedResults", vm.search.state.value.tracks.size)
                }
                capture("discovery-search")
                main { vm.playback.playSingle(seed) }
                await("Radio seed did not decode real audio") { var ready = false; main { ready = player.isPlaying && player.currentMediaItem?.mediaId == seed.url && player.currentPosition > 300 }; ready }
                await("Radio did not append real candidates", 90000) { app.graph.radioStatus.state.value.automatic.size >= 2 }
                report.put("unenrichedAutomaticCount", app.graph.radioStatus.state.value.automatic.size)
                main {
                    app.graph.discoverySettings.update(app.graph.discoverySettings.state.value.copy(enrichment = true))
                    vm.playback.retryRadio()
                }
                await("Enriched radio did not diversify across source credits", 90000) {
                    var diverse = false
                    main { diverse = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaMetadata.artist.toString() }.map(MusicText::uploader).distinct().size >= 2 }
                    diverse && !app.graph.radioStatus.state.value.loading
                }
                val radio = app.graph.radioStatus.state.value
                val automaticTracks = mutableListOf<org.sonora.music.domain.Track>()
                main { for (i in 0 until player.mediaItemCount) {
                    val item = player.getMediaItemAt(i)
                    if (item.mediaId in radio.automatic) automaticTracks += org.sonora.music.domain.Track(item.mediaId, item.mediaMetadata.title.toString(), item.mediaMetadata.artist.toString(), "", 180)
                } }
                assertTrue("Spoken/comedy content entered music radio", automaticTracks.none { MusicText.likelyMusic(it) < .2 })
                report.put("automaticTracks", org.json.JSONArray().apply { automaticTracks.forEach { put(JSONObject().put("url", it.url).put("title", it.title).put("sourceCredit", it.artist)) } })
                report.put("automaticCount", radio.automatic.size).put("firstExplanation", radio.automatic.values.first())
                main { assertTrue(player.mediaItemCount >= 3); assertEquals(seed.url, player.currentMediaItem?.mediaId) }
                assertTrue(radio.automatic.keys.none { it == seed.url })
                assertTrue(radio.automatic.values.all { it.isNotBlank() })
                // No queued recommendation is counted as listened before actual playback.
                val upcoming = radio.automatic.keys.first()
                val before = runBlocking { app.graph.library.find(upcoming)?.playCount ?: 0 }
                main { vm.playback.next() }
                await("Next recommended song failed to play") { var ready = false; main { ready = player.isPlaying && player.currentMediaItem?.mediaId != seed.url && player.currentPosition > 300 }; ready }
                var next = ""; main { next = player.currentMediaItem!!.mediaId }
                report.put("playedRecommendation", next)
                if (next == upcoming) await("Recommended track was not recorded") { runBlocking { (app.graph.library.find(next)?.playCount ?: 0) > before } }
                var position = 0L; main { position = player.currentPosition }
                scenario.moveToState(Lifecycle.State.CREATED); Thread.sleep(2500)
                main { assertTrue(player.isPlaying); assertTrue(player.currentPosition > position + 1000) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                main { vm.discoveryOptions(app.graph.discoverySettings.state.value.copy(personalized = true)) }
                click("Home")
                await("Home discovery did not complete", 90000) { vm.homeDiscovery.state.value.recommendations.isNotEmpty() }
                report.put("homeRecommendations", vm.homeDiscovery.state.value.recommendations.size)
                capture("discovery-home")
                click("Continue listening"); click("Open queue"); capture("discovery-radio")
                main { app.graph.discoverySettings.update(app.graph.discoverySettings.state.value.copy(autoplay = false)) }
                await("Autoplay did not stop adding tracks") { !app.graph.radioStatus.state.value.active }
                main { assertEquals(player.mediaItemCount - 1, player.currentMediaItemIndex); assertTrue(player.isPlaying)
                    if (player.duration > 2000) player.seekTo(player.duration - 800) }
                await("Playback did not end normally with autoplay off", 30000) { var ended = false; main { ended = player.playbackState == Player.STATE_ENDED }; ended }
                main { app.graph.discoverySettings.update(app.graph.discoverySettings.state.value.copy(autoplay = true)); vm.playback.play(songs.take(2), 0) }
                Thread.sleep(3500)
                main { assertEquals(2, player.mediaItemCount) }; assertFalse(app.graph.radioStatus.state.value.active)
                main { vm.playback.hideFromRadio(songs.first()) }
                Thread.sleep(300)
                main { assertEquals("Recommendation feedback must not edit a manual queue", 2, player.mediaItemCount) }
                report.put("manualQueuePreserved", true).put("autoplayOffEnded", true).put("backgroundPlayback", true)
                val meta = runBlocking { app.graph.metadata.metadata(seed) }
                report.put("matchedArtist", meta?.artist?.value).put("recordingMatched", meta?.recordingId != null).put("genreEvidenceCount", meta?.genres?.size ?: 0)
                assertNotNull("Expected a MusicBrainz artist match for the real seed", meta?.artistId)
                val similar = runBlocking { app.graph.artistSimilarity.similar(meta!!.artistId!!.value) }
                report.put("similarArtists", similar.size)
                for (kind in listOf(CatalogKind.ALBUMS, CatalogKind.PLAYLISTS, CatalogKind.CHANNELS)) {
                    try {
                        val page = runBlocking { app.graph.source.searchPage("Obrint Pas", kind) }
                        report.put(kind.name, page.entries.size)
                        if (kind != CatalogKind.CHANNELS && page.entries.isNotEmpty()) {
                            val tracks = runBlocking { app.graph.source.collection(page.entries.first().url) }
                            report.put("${kind.name}_loadedTracks", tracks.entries.size)
                        }
                    } catch (e: Exception) { report.put(kind.name, "Unavailable: ${e.javaClass.simpleName}") }
                }
            } finally {
                File(app.getExternalFilesDir(null), "discovery-device-report.json").writeText(report.toString(2))
                main { player.pause(); player.stop(); player.clearMediaItems(); player.release(); app.graph.discoverySettings.update(previous); app.graph.preparation.setEnabled(prepared) }
            }
        }
    }
}
