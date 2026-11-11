package org.sonora.music

import android.graphics.Bitmap
import android.net.Uri
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
import org.sonora.music.accountimport.SpotifyExportParser
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
        val bitmap = takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun spotifyFileCanBeSelectedReviewedOfflineAndReimportedWithoutAnAccount() {
        val app = instrumentation.targetContext.applicationContext as SonoraApplication
        val json = """{"playlists":[{"name":"Spotify export device fixture","items":[{"track":{"trackName":"Fixture pending review","artistName":"Sonora tests"}}]}]}"""
        val source = SpotifyExportParser().read("Playlist1.json", json.byteInputStream()).single().source
        val key = "spotify:${source.id}"
        assumeTrue(runBlocking { app.graph.library.findPlaylist(key) == null })
        val file = File(app.cacheDir, "Playlist1.json").apply { writeText(json) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: MusicViewModel
                scenario.onActivity { vm = ViewModelProvider(it, MusicViewModel.Factory(app))[MusicViewModel::class.java] }
                click("Library"); click("Import music")
                repeat(2) { attempt ->
                    scenario.onActivity { vm.importSpotifyFiles(listOf(Uri.fromFile(file))) }
                    await("File playlist selection did not load") { vm.accountImporter.state.value.lists.size == 1 }
                    assertTrue(vm.accountImporter.state.value.disconnected)
                    click("Select ${source.name}")
                    click("Search selected songs online")
                    assertFalse(vm.accountImporter.state.value.searchOnline)
                    click("Review selected lists")
                    await("Offline pending review missing") { !vm.accountImporter.state.value.busy && vm.accountImporter.state.value.pending == 1 }
                    assertTrue(vm.accountImporter.state.value.review.single().songs.single().candidates.isEmpty())
                    if (attempt == 0) {
                        Thread.sleep(400)
                        capture("spotify-export-review.png")
                    }
                    click("Leave pending entries out")
                    click("Continue to local import preview")
                    await("Local preview missing") { vm.importer.state.value.preview != null }
                    assertEquals(0, vm.importer.state.value.preview!!.trackCount)
                    click("Import selected music")
                    await("Local import did not complete") { vm.importer.state.value.summary != null }
                    assertEquals(if (attempt == 0) 1 else 0, vm.importer.state.value.summary!!.newPlaylists)
                    assertEquals(0, vm.importer.state.value.summary!!.newTracks)
                    if (attempt == 0) click("Import more")
                }
            }
        } finally {
            runBlocking { app.graph.library.findPlaylist(key)?.let { app.graph.library.deletePlaylist(it.id) } }
            file.delete()
        }
    }
}
