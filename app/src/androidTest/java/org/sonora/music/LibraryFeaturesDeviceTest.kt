package org.sonora.music

import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
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
import org.sonora.music.data.*
import org.sonora.music.domain.Track
import org.sonora.music.ui.MusicViewModel
import java.io.File

@RunWith(AndroidJUnit4::class)
@UnstableApi
class LibraryFeaturesDeviceTest {
    @get:org.junit.Rule val noSpeculation = SpeculationDisabledRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun find(root: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        for (i in root.childCount - 1 downTo 0) find(root.getChild(i), test)?.let { return it }
        return root.takeIf(test)
    }
    private fun root() = instrumentation.getUiAutomation(0).rootInActiveWindow
    private fun node(text: String) = find(root()) { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail(label)
    }
    private fun click(text: String) {
        // Text-field edits and navigation can replace a semantics node on the next frame.
        // Retry only rejected actions; never repeat a click that Android accepted.
        await("Could not click $text") {
            var item = node(text) ?: return@await false
            while (!item.isClickable && item.parent != null) item = item.parent
            item.isEnabled && item.isClickable && item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }
    private fun enter(text: String) {
        await("Missing name input") { find(root()) { it.isEditable } != null }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        assertTrue(find(root()) { it.isEditable }!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        instrumentation.waitForIdleSync()
    }
    private fun drag(from: String, to: String) {
        val start = Rect(); val end = Rect()
        node(from)!!.getBoundsInScreen(start); node(to)!!.getBoundsInScreen(end)
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) { val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, start.centerX().toFloat(), y, 0); instrumentation.sendPointerSync(e); e.recycle() }
        event(MotionEvent.ACTION_DOWN, start.centerY().toFloat()); Thread.sleep(700)
        repeat(16) { step -> event(MotionEvent.ACTION_MOVE, start.centerY() + (end.centerY() - start.centerY()) * (step + 1) / 16f); Thread.sleep(30) }
        event(MotionEvent.ACTION_UP, end.centerY().toFloat())
        instrumentation.waitForIdleSync()
    }
    @Test fun editReorderExportPreviewAndMergeLibraryThroughTheUi() {
        val app = instrumentation.targetContext.applicationContext as SonoraApplication
        val dao = app.graph.library
        val one = Track("https://www.youtube.com/watch?v=s0n0raL0001", "Library first fixture", "Sonora tests", "", 120)
        val two = one.copy(url = "https://www.youtube.com/watch?v=s0n0raL0002", title = "Library second fixture")
        val third = one.copy(url = "https://www.youtube.com/watch?v=s0n0raL0003", title = "Restored fixture")
        val name = "Device playlist fixture"
        val restoredKey = "test:backup-device"
        assumeTrue(runBlocking { listOf(one, two, third).all { dao.find(it.url) == null } && dao.findPlaylist(restoredKey) == null })
        var playlistId: Long? = null
        val backupFile = File(app.cacheDir, "library-device-backup.json")
        val restoreFile = File(app.cacheDir, "library-device-restore.json")
        try {
            runBlocking { dao.toggleFavorite(one); dao.toggleFavorite(two) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: MusicViewModel
                scenario.onActivity { vm = ViewModelProvider(it, MusicViewModel.Factory(app))[MusicViewModel::class.java] }
                click("Library")
                val listCount = runBlocking { dao.playlists().first().size }
                click("Playlists · $listCount"); click("New playlist"); enter(name); click("Save playlist")
                await("Playlist not created") { runBlocking { dao.playlists().first().any { it.playlist.name == name } } }
                playlistId = runBlocking { dao.playlists().first().first { it.playlist.name == name }.playlist.id }
                click("Back to playlists")
                val favoriteCount = runBlocking { dao.favorites().first().size }
                click("Favorites · $favoriteCount")
                click("Options for ${one.title}"); click("Add to playlist"); click(name)
                await("Song not added") { runBlocking { dao.playlistTracks(playlistId!!).first().size == 1 } }
                // Second fixture is inserted through the same ViewModel action; drag and menus are driven through UI.
                scenario.onActivity { vm.addToPlaylist(playlistId!!, two) {} }
                await("Second song not added") { runBlocking { dao.playlistTracks(playlistId!!).first().size == 2 } }
                click("Playlists · ${listCount + 1}"); click(name)
                await("Drag handles missing") { node("Drag ${one.title}") != null && node("Drag ${two.title}") != null }
                drag("Drag ${one.title}", "Drag ${two.title}")
                await("Drag order not persisted") { runBlocking { dao.playlistTracks(playlistId!!).first().first().url == two.url } }
                click("Rename playlist"); enter("Renamed device playlist"); click("Save playlist")
                await("Rename failed") { runBlocking { dao.playlist(playlistId!!)!!.name == "Renamed device playlist" } }
                val bitmap = takeScreenshot()
                File(app.getExternalFilesDir(null), "playlist-editor.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                click("Options for ${two.title}"); click("Remove from playlist")
                await("Remove failed") { runBlocking { dao.playlistTracks(playlistId!!).first().size == 1 } }
                assertTrue(runBlocking { dao.find(two.url)!!.favorite })
                click("Back to playlists"); click("Backup and restore")
                scenario.onActivity { vm.exportBackup(Uri.fromFile(backupFile)) }
                await("Export failed") { !vm.backup.value.busy && vm.backup.value.message != null }
                val exported = backupFile.inputStream().use(BackupCodec::read)
                assertTrue(exported.tracks.any { it.url == one.url && it.favorite })
                val importedRecord = LibraryTrack(third.url, third.title, third.artist, "", 120, true, 42, 1)
                restoreFile.writeText(BackupCodec.encode(LibraryBackup(42, listOf(importedRecord), listOf(BackupPlaylist(restoredKey, "Restored device playlist", listOf(third.url))))))
                scenario.onActivity { vm.previewBackup(Uri.fromFile(restoreFile)) }
                await("Preview failed") { vm.backup.value.preview != null }
                assertNull(runBlocking { dao.find(third.url) })
                // Preview confirmation may be below the fold on a small screen.
                repeat(6) { if (node("Merge backup into library") == null) { find(root()) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(150) } }
                click("Merge backup into library")
                await("Restore failed") { !vm.backup.value.busy && vm.backup.value.message != null }
                assertEquals(42L, runBlocking { dao.find(third.url)!!.lastPlayedAt })
                assertTrue(runBlocking { dao.find(one.url)!!.favorite })
            }
        } finally {
            runBlocking {
                listOf(one, two, third).forEach { track -> dao.find(track.url)?.let { dao.put(it.copy(favorite = false, lastPlayedAt = null, playCount = 0)) } }
                playlistId?.let { dao.deletePlaylist(it) }
                dao.findPlaylist(restoredKey)?.let { dao.deletePlaylist(it.id) }
            }
            backupFile.delete(); restoreFile.delete()
        }
    }
}
