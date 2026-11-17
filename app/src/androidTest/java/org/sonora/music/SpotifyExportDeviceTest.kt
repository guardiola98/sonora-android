package org.sonora.music

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
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
import org.sonora.music.importing.SpotifyExportParser
import org.sonora.music.domain.Track
import org.sonora.music.ui.MusicViewModel
import java.io.File

@RunWith(AndroidJUnit4::class)
@UnstableApi
class SpotifyExportDeviceTest {
    @get:org.junit.Rule val noSpeculation = SpeculationDisabledRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        for (i in root.childCount - 1 downTo 0) find(root.getChild(i), predicate)?.let { return it }
        return root.takeIf(predicate)
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) { if (predicate()) return; Thread.sleep(100) }
        fail(label)
    }
    private fun click(label: String) {
        await("Could not click $label") {
            val automation = instrumentation.getUiAutomation(0)
            val found = find(automation.rootInActiveWindow) { it.text?.toString() == label || it.contentDescription?.toString() == label }
            if (found == null) { find(automation.rootInActiveWindow) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); return@await false }
            var node: AccessibilityNodeInfo = found
            while (!node.isClickable && node.parent != null) node = node.parent
            node.isEnabled && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }
    private fun capture(name: String) {
        takeScreenshot(); Thread.sleep(400)
        val bitmap = takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun spotifyImportsSaveWithoutSelectionOrReviewAndReimportWithoutDuplicates() {
        val app = instrumentation.targetContext.applicationContext as SonoraApplication
        val json = """{"playlists":[{"name":"Spotify automatic device fixture","items":[{"track":{"trackName":"Fixture automatic song","artistName":"Sonora tests"}},{"episode":{"episodeName":"Unavailable episode"}}]}]}"""
        val parsed = SpotifyExportParser().read("Playlist1.json", json.byteInputStream()).single()
        val track = Track("https://www.youtube.com/watch?v=auto082test", "Fixture automatic song", "Sonora tests", "", 180)
        val resolved = parsed.copy(songs = parsed.songs.mapIndexed { index, row -> if (index == 0) row.copy(chosen = track) else row })
        val key = "spotify:${parsed.source.id}"
        assumeTrue(runBlocking { app.graph.library.findPlaylist(key) == null && app.graph.library.find(track.url) == null })
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: MusicViewModel
                scenario.onActivity { vm = ViewModelProvider(it, MusicViewModel.Factory(app))[MusicViewModel::class.java] }
                click("Library"); click("Import music")
                repeat(2) { attempt ->
                    scenario.onActivity {
                        if (attempt == 0) vm.spotifyImporter.loadSpotifyPublicPlaylist { resolved }
                        else vm.spotifyImporter.loadSpotifyExport { listOf(resolved) }
                    }
                    await("Automatic import did not finish") { vm.spotifyImporter.state.value.summary != null }
                    val state = vm.spotifyImporter.state.value
                    assertEquals(2, state.entries); assertEquals(1, state.matched); assertEquals(1, state.skipped)
                    assertEquals(if (attempt == 0) 1 else 0, state.summary!!.newPlaylists)
                    assertEquals(if (attempt == 0) 1 else 0, state.summary!!.addedEntries)
                    assertNull(vm.importer.state.value.preview)
                    await("Completion missing from screen") {
                        find(instrumentation.getUiAutomation(0).rootInActiveWindow) { it.text?.toString() == "Your music is ready" } != null
                    }
                    assertNull(find(instrumentation.getUiAutomation(0).rootInActiveWindow) { it.text?.toString() in setOf("Select playlists", "Review playable versions", "Import selected music") })
                    if (attempt == 0) { capture("spotify-automatic-complete.png"); click("Import more") }
                }
            }
        } finally {
            runBlocking { app.graph.library.findPlaylist(key)?.let { app.graph.library.deletePlaylist(it.id) } }
        }
    }
}
