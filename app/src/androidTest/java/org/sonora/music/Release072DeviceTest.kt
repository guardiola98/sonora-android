package org.sonora.music

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.music.discovery.CatalogKind
import org.sonora.music.domain.PodcastLinks
import org.sonora.music.domain.Track
import org.sonora.music.playback.PlaybackService
import org.sonora.music.ui.MusicViewModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/** Runs against the signed release; generated fixtures are never bundled with Sonora. */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class Release072DeviceTest {
    @get:org.junit.Rule val noSpeculation = SpeculationDisabledRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SonoraApplication
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, timeout: Long = 20000, predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < until) { if (predicate()) return; Thread.sleep(100) }
        fail(label)
    }
    private fun position(player: MediaController, expected: Long, tolerance: Long = 0) = await("Expected position $expected (tolerance $tolerance)") {
        var actual = -1L
        main { actual = player.currentPosition }
        kotlin.math.abs(actual - expected) <= tolerance
    }
    private fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        for (i in root.childCount - 1 downTo 0) find(root.getChild(i), predicate)?.let { return it }
        return root.takeIf(predicate)
    }
    private fun node(label: String) = find(instrumentation.getUiAutomation(0).rootInActiveWindow) {
        it.text?.toString() == label || it.contentDescription?.toString() == label
    }
    private fun click(label: String) {
        await("Missing clickable control: $label") {
            var item = node(label) ?: return@await false
            while (!item.isClickable && item.parent != null) item = item.parent
            item.isEnabled && item.isClickable && item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = takeScreenshot()
        File(app.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun connected(block: (MusicViewModel, MediaController) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: MusicViewModel
            scenario.onActivity { vm = ViewModelProvider(it, MusicViewModel.Factory(app))[MusicViewModel::class.java] }
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val controller = future.get(15, TimeUnit.SECONDS)
            try {
                await("UI did not connect") { vm.playback.state.value.connected }
                main { controller.stop(); controller.clearMediaItems() }
                block(vm, controller)
            } finally { main { vm.playback.clear(); controller.release() } }
        }
    }

    @Test fun playlistShuffleCreatesCompleteManualQueueWithoutReorderingLibrary() {
        val tracks = (1..4).map { Track("https://www.youtube.com/watch?v=s0n0raS000$it", "Shuffle fixture $it", "Sonora tests", "", 120) }
        val dao = app.graph.library
        assumeTrue(runBlocking { tracks.all { dao.find(it.url) == null } })
        val id = runBlocking { dao.createPlaylistWithTracks("Shuffle test playlist", tracks) }
        try {
            connected { vm, player ->
                click("Library")
                main { vm.selectPlaylist(id) }
                await("Playlist did not load") { node("Shuffle playlist") != null }
                capture("release-072-playlist-shuffle")
                click("Shuffle playlist")
                await("Shuffle did not create a queue") { vm.playback.state.value.queue.size == tracks.size }
                main {
                    player.pause(); player.stop()
                    val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                    assertEquals(tracks.map { it.url }.toSet(), queue.toSet())
                    assertEquals(tracks.size, queue.size)
                    assertEquals(0, player.currentMediaItemIndex)
                    player.seekToNextMediaItem()
                    assertEquals(queue[1], player.currentMediaItem!!.mediaId)
                }
                assertEquals(tracks.map { it.url }, runBlocking { dao.playlistTracks(id).first().map { it.url } })
            }
        } finally { runBlocking { dao.deletePlaylist(id) } }
    }

    @Test fun podcastButtonsSeekTenSecondsAndStayBesideEpisodeControls() = connected { vm, player ->
        val samples = 16000 * 60
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort((sin(2 * Math.PI * 440 * it / 16000) * 1000).toInt().toShort()) }
        val file = File(app.cacheDir, "podcast-072-test.wav").apply { writeBytes(bytes.array()) }
        // Unique IDs prevent a prior run's persisted podcast resume position affecting this run.
        val fixtureId = java.util.UUID.randomUUID().toString()
        val feed = "https://example.com/sonora-072-test.xml"
        val reference = PodcastLinks.reference(feed, "$fixtureId-1")
        val secondReference = PodcastLinks.reference(feed, "$fixtureId-2")
        val item = MediaItem.Builder().setMediaId(reference).setUri(Uri.fromFile(file))
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Podcast controls test").setArtist("Generated PCM fixture")
                .setExtras(Bundle().apply { putLong("durationSeconds", 60) }).build()).build()
        try {
            main { player.setMediaItems(listOf(item, item.buildUpon().setMediaId(secondReference).build())); player.prepare(); player.play() }
            await("PCM did not decode") { var ready = false; main { ready = player.isPlaying && player.currentPosition > 500 }; ready }
            main { player.pause(); vm.playback.seek(25000) }
            position(player, 25000)
            await("Mini player missing") { node("Podcast controls test") != null }
            click("Podcast controls test")
            await("Podcast controls missing") { node("Forward 10 seconds") != null && node("Rewind 10 seconds") != null }
            val labels = listOf("Previous episode or restart", "Rewind 10 seconds", "Play", "Forward 10 seconds", "Next episode")
            val bounds = labels.map { label -> Rect().also { node(label)!!.getBoundsInScreen(it) } }
            assertTrue(bounds.zipWithNext().all { (left, right) -> left.centerX() < right.centerX() })
            assertTrue(bounds.all { kotlin.math.abs(it.centerY() - bounds[2].centerY()) <= 2 })
            assertNull(node("−15 sec")); assertNull(node("+30 sec"))
            main { assertEquals(10000L, player.seekBackIncrement); assertEquals(10000L, player.seekForwardIncrement) }
            click("Rewind 10 seconds")
            position(player, 15000)
            click("Forward 10 seconds")
            position(player, 25000)
            main { vm.playback.forward(); vm.playback.forward() }
            // The UI controller and observing controller communicate asynchronously via MediaSession.
            // Await the observed position instead of asserting inside the command's main-thread turn.
            position(player, 45000)
            main { vm.playback.seek(3000) }; position(player, 3000)
            click("Rewind 10 seconds")
            position(player, 0)
            main { vm.playback.seek(25000) }; position(player, 25000)
            capture("release-072-podcast-controls")
            click("Next episode")
            await("Next episode failed") { var index = -1; main { index = player.currentMediaItemIndex }; index == 1 }
            click("Previous episode or restart")
            await("Previous episode failed") { var index = -1; main { index = player.currentMediaItemIndex }; index == 0 }
            // Test the end bound on the last episode. Seeking to the end of an earlier
            // item may legitimately advance the queue, even while paused.
            main { player.seekTo(1, 55000) }
            await("Last episode position did not reach the UI controller") {
                vm.playback.state.value.index == 1 && vm.playback.state.value.positionMs == 55000L
            }
            click("Forward 10 seconds")
            position(player, 60000, tolerance = 1)
        } finally {
            main { player.stop(); player.clearMediaItems() }
            await("Fixture queue did not clear") { vm.playback.state.value.queue.isEmpty() }
            // An ordered-writer marker ensures queued progress updates finish before cleanup.
            val marker = PodcastLinks.reference(feed, "$fixtureId-flush")
            app.graph.podcastProgress.save(marker, 0, 0)
            await("Fixture progress writer did not drain") { runBlocking { app.graph.podcastLibrary.progressFor(marker) != null } }
            val db = app.graph.database.openHelper.writableDatabase
            listOf(reference, secondReference, marker).forEach { id ->
                db.execSQL("DELETE FROM podcast_progress WHERE reference = ?", arrayOf(id))
                db.execSQL("DELETE FROM tracks WHERE url = ? AND favorite = 0", arrayOf(id))
            }
            file.delete()
        }
    }

    @Test fun liveArtistSearchOpensRealSongResults() {
        assumeTrue("Opt in with liveArtists=true", InstrumentationRegistry.getArguments().getString("liveArtists") == "true")
        connected { vm, _ ->
            click("Search"); click("Artists")
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Obrint Pas") }
            val input = find(instrumentation.getUiAutomation(0).rootInActiveWindow) { it.isEditable }
            assertNotNull(input); assertTrue(input!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            click("Search music")
            await("Live artist search failed: ${vm.search.state.value.error}", 90000) {
                val state = vm.search.state.value
                !state.loading && state.error == null && state.ranked.any { it.entry.title == "Obrint Pas" && it.entry.kind == CatalogKind.ARTISTS && it.entry.credit == "YouTube Music artist" }
            }
            // The search field and history chip can have the same text. Wait for and click
            // the actual remote artist row, identified by its source credit and artist name.
            fun artistRow() = find(instrumentation.getUiAutomation(0).rootInActiveWindow) { candidate ->
                candidate.isClickable &&
                    find(candidate) { it.text?.toString() == "Obrint Pas" } != null &&
                    find(candidate) { it.text?.toString() == "Artists · YouTube Music artist" } != null
            }
            await("Remote artist row did not render") { artistRow() != null }
            capture("release-072-artist-search")
            assertTrue(artistRow()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            await("Artist did not open songs", 90000) {
                val state = vm.search.state.value
                state.kind == CatalogKind.SONGS && state.query == "Obrint Pas" && !state.loading && state.tracks.isNotEmpty()
            }
            capture("release-072-artist-songs")
        }
    }
}
