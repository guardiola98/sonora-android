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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.music.ui.MusicViewModel
import java.io.File

@RunWith(AndroidJUnit4::class)
@UnstableApi
class ImportDeviceTest {
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
    private fun click(text: String) {
        val automation = instrumentation.getUiAutomation(0)
        var node: AccessibilityNodeInfo? = null
        repeat(8) {
            node = find(automation.rootInActiveWindow) { it.text?.toString() == text || it.contentDescription?.toString() == text }
            if (node != null) return@repeat
            find(automation.rootInActiveWindow) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(200)
        }
        var target = node ?: throw AssertionError("Could not find $text")
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue("Could not click $text", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    @Test fun exportedListsAndLikesReachTheLibraryAndReimportWithoutDuplicates() {
        val app = instrumentation.targetContext.applicationContext as SonoraApplication
        val dao = app.graph.library
        val one = "https://www.youtube.com/watch?v=s0n0raImp01"
        val two = "https://www.youtube.com/watch?v=s0n0raImp02"
        val key = "takeout:Import smoke test"
        // Never overwrite an existing user's library or clear their database.
        assumeTrue(runBlocking { dao.find(one) == null && dao.find(two) == null && dao.findPlaylist(key) == null })
        val folder = File(app.cacheDir, "import-device-test").apply { mkdirs() }
        val playlist = File(folder, "Import smoke test.csv").apply { writeText("Video ID,Title,Artist\ns0n0raImp01,First import fixture,Sonora tests\ns0n0raImp02,Second import fixture,Sonora tests\n") }
        val likes = File(folder, "Liked Music.csv").apply { writeText("Song URL,Song Title,Artist Names\n$one,First import fixture,Sonora tests\n") }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: MusicViewModel
                scenario.onActivity { activity -> vm = ViewModelProvider(activity, MusicViewModel.Factory(app))[MusicViewModel::class.java] }
                click("Library"); click("Import music")
                scenario.onActivity { vm.importFiles(listOf(Uri.fromFile(playlist), Uri.fromFile(likes))) }
                await("Preview did not load") { vm.importer.state.value.preview != null }
                assertEquals(2, vm.importer.state.value.preview!!.trackCount)
                val screenshot = takeScreenshot()
                File(app.getExternalFilesDir(null), "import-preview.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                click("Import selected music")
                await("Import did not complete") { vm.importer.state.value.summary != null }
                assertEquals(1, vm.importer.state.value.summary!!.newPlaylists)
                assertEquals(1, vm.importer.state.value.summary!!.newFavorites)
                runBlocking {
                    val saved = dao.findPlaylist(key)!!
                    assertEquals(listOf(one, two), dao.playlistTracks(saved.id).first().map { it.url })
                    assertTrue(dao.find(one)!!.favorite)
                    assertNull(dao.find(one)!!.lastPlayedAt)
                }
                click("View library")
                await("Favorite did not reach the library UI") { find(instrumentation.getUiAutomation(0).rootInActiveWindow) { it.text?.toString() == "First import fixture" } != null }
                scenario.onActivity { vm.importer.reset(); vm.importFiles(listOf(Uri.fromFile(playlist), Uri.fromFile(likes))) }
                await("Second preview did not load") { vm.importer.state.value.preview != null }
                scenario.onActivity { vm.importer.save() }
                await("Reimport did not complete") { vm.importer.state.value.summary != null }
                assertEquals(0, vm.importer.state.value.summary!!.addedEntries)
                assertEquals(0, vm.importer.state.value.summary!!.newFavorites)
            }
        } finally {
            runBlocking {
                dao.find(one)?.takeIf { it.favorite }?.let { dao.toggleFavorite(it.track()) }
                dao.findPlaylist(key)?.let { dao.deletePlaylist(it.id) }
            }
            playlist.delete(); likes.delete(); folder.delete()
        }
    }
}
