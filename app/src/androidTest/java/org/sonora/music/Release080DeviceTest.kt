package org.sonora.music

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.takeScreenshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.music.i18n.AppLanguage
import org.sonora.music.i18n.tr
import org.sonora.music.playback.PlaybackService
import org.sonora.music.ui.MusicViewModel
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit opt-in live fixture is downloaded only to the test emulator, never bundled. */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class Release080DeviceTest {
    @get:org.junit.Rule val noSpeculation = SpeculationDisabledRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SonoraApplication
    private val preferences get() = app.getSharedPreferences("release080-test", 0)
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) { if (predicate()) return; Thread.sleep(100) }
        val labels = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.let(labels::add)
            node.contentDescription?.toString()?.let(labels::add)
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(instrumentation.getUiAutomation(0).rootInActiveWindow)
        fail("$label; locale=${AppLanguage.selected(app)}; UI=${labels.take(50)}")
    }
    private fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        for (i in root.childCount - 1 downTo 0) find(root.getChild(i), predicate)?.let { return it }
        return root.takeIf(predicate)
    }
    private fun node(label: String) = find(instrumentation.getUiAutomation(0).rootInActiveWindow) { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
    private fun click(label: String) {
        await("Missing control: $label") {
            var target = node(label) ?: return@await false
            while (!target.isClickable && !target.isSelected && target.parent != null) target = target.parent
            target.isEnabled && (if (target.isClickable) target.performAction(AccessibilityNodeInfo.ACTION_CLICK) else target.isSelected)
        }
        instrumentation.waitForIdleSync()
    }
    private fun reveal(label: String) {
        // Navigation and locale recreation can expose the previous accessibility tree
        // for a frame. Wait even when that tree has no scrollable container yet.
        Thread.sleep(350)
        repeat(16) {
            if (node(label) != null) return
            val scrollable = mutableListOf<AccessibilityNodeInfo>()
            fun collect(item: AccessibilityNodeInfo?) {
                if (item == null) return
                if (item.isScrollable && !item.isEditable) scrollable += item
                for (i in 0 until item.childCount) collect(item.getChild(i))
            }
            collect(instrumentation.getUiAutomation(0).rootInActiveWindow)
            for (item in scrollable) {
                if (node(label) != null) return
                // A full accessibility page can skip the bottom of a tall lazy item
                // on compact screens. Use a short gesture within observed bounds.
                val bounds = Rect().also(item::getBoundsInScreen)
                val horizontal = bounds.width() > bounds.height() * 2
                val startX = if (horizontal) bounds.left + bounds.width() * .8f else bounds.exactCenterX()
                val startY = if (horizontal) bounds.exactCenterY() else bounds.top + bounds.height() * .8f
                val distance = (if (horizontal) bounds.width() else bounds.height()) * .35f
                val started = SystemClock.uptimeMillis()
                fun touch(action: Int, fraction: Float) {
                    val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action,
                        startX - if (horizontal) distance * fraction else 0f,
                        startY - if (horizontal) 0f else distance * fraction, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    instrumentation.getUiAutomation(0).injectInputEvent(event, true)
                    event.recycle()
                }
                touch(MotionEvent.ACTION_DOWN, 0f)
                for (step in 1..12) { Thread.sleep(25); touch(MotionEvent.ACTION_MOVE, step / 12f) }
                touch(MotionEvent.ACTION_UP, 1f)
                Thread.sleep(500)
            }
            Thread.sleep(250)
        }
        assertNotNull("Missing scrollable control: $label", node(label))
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        // ATD suppresses normal drawing; warm the render pass before saving its next frame.
        takeScreenshot()
        Thread.sleep(400)
        val bitmap = takeScreenshot()
        File(app.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun connected(block: (ActivityScenario<MainActivity>, MusicViewModel, MediaController) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: MusicViewModel
            scenario.onActivity { vm = ViewModelProvider(it, MusicViewModel.Factory(app))[MusicViewModel::class.java] }
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val player = future.get(15, TimeUnit.SECONDS)
            try {
                await("Player did not connect") { vm.playback.state.value.connected }
                block(scenario, vm, player)
            } finally { main { player.pause(); player.release() } }
        }
    }
    private fun liveOnly() {
        assumeTrue("Opt in with liveDownloads=true on an emulator", InstrumentationRegistry.getArguments().getString("liveDownloads") == "true" &&
            (Build.HARDWARE in setOf("ranchu", "goldfish")))
    }
    @Test fun downloadPublicMusicThroughTheProductionService() {
        liveOnly()
        connected { _, vm, _ ->
            // A current public music search result; source availability is tested, not assumed.
            val track = runBlocking { app.graph.source.search("Kevin MacLeod Carefree").first { it.durationSeconds in 30..600 } }
            val playlist = runBlocking { app.graph.library.createPlaylistWithTracks("Offline 0.8 validation", listOf(track)) }
            preferences.edit().putString("reference", track.url).putLong("playlist", playlist).putBoolean("wifiOnly", app.graph.downloads.state.value.wifiOnly).commit()
            main { vm.downloadWifiOnly(false); vm.download(listOf(track)) }
            await("Public download did not finish", 180_000) { app.graph.downloads.state.value.items.any { it.track.url == track.url && it.ready } }
            val entry = app.graph.downloads.state.value.items.single { it.track.url == track.url }
            assertTrue(entry.bytes > 1000); assertTrue(app.graph.downloads.hasDownloaded(track.url))
            println("Downloaded public test audio: ${entry.bytes} bytes; ready for process restart / offline verification")
        }
    }
    @Test fun downloadedMusicPlaysAfterProcessRestartWithNetworkDisabled() {
        liveOnly()
        // Harness disables Wi-Fi and mobile data before starting this separate instrumentation run.
        val network = app.getSystemService(android.net.ConnectivityManager::class.java)
        assertNull("Run the offline phase with no active network", network.activeNetwork)
        val reference = preferences.getString("reference", null) ?: error("Run download phase first")
        connected { _, vm, player ->
            await("Download index did not restore") { app.graph.downloads.state.value.items.any { it.track.url == reference && it.ready } }
            val track = app.graph.downloads.state.value.items.single { it.track.url == reference }.track
            main { vm.playback.play(listOf(track), 0) }
            await("Downloaded audio did not decode offline") {
                var playing = false; main { playing = player.isPlaying && player.currentPosition > 300 }; playing
            }
            main { player.seekTo(5000) }
            await("Downloaded audio did not seek offline") { var ready = false; main { ready = player.isPlaying && player.currentPosition >= 5000 }; ready }
            main { player.pause(); assertNull(player.playerError) }
            click(track.title)
            await("Offline player status missing") { node(tr("Downloaded")) != null }
            screenshot("release-080-offline-player")
            click(tr("Close player")); click(tr("Library"))
            val tab = tr("Downloads · %1\$s", app.graph.downloads.state.value.items.count { it.ready })
            reveal(tab); click(tab)
            await("Offline screen missing") { node(tr("Play downloaded")) != null }
            screenshot("release-080-offline-downloads")
            assertNotNull(runBlocking { app.graph.library.playlist(preferences.getLong("playlist", -1)) })
        }
    }
    @Test fun changeLanguageThroughSettingsAndKeepTheLibraryAcrossRecreation() {
        val before = AppLanguage.selected(app)
        val count = runBlocking { app.graph.library.exportBackup().tracks.size }
        try {
            connected { scenario, vm, player ->
                val downloaded = app.graph.downloads.state.value.items.firstOrNull { it.ready }?.track
                if (downloaded != null) {
                    main { vm.playback.play(listOf(downloaded), 0) }
                    await("Downloaded player not ready for language check") { var ready = false; main { ready = player.isPlaying }; ready }
                }
                var queue = emptyList<String>()
                main { player.pause(); queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId } }
                scenario.onActivity { AppLanguage.select(it, "en") }
                await("English UI missing") { node("About Sonora") != null }
                for ((tag, nativeName) in listOf("es" to "Español", "ca" to "Català")) {
                    click(tr("About Sonora")); reveal(tr("App language")); click(tr("App language"))
                    var previousActivity: MainActivity? = null
                    scenario.onActivity { previousActivity = it }
                    click(nativeName)
                    await("Language did not recreate the activity in $tag") {
                        var recreated = false
                        scenario.onActivity { recreated = it !== previousActivity && it.resources.configuration.locales[0].language == tag }
                        recreated && AppLanguage.selected(app).startsWith(tag) && node(tr("Done")) != null
                    }
                    click(tr("Done")); click(tr("Home"))
                    screenshot("release-080-$tag-home")
                    click(tr("Library")); click(tr("Import music"))
                    reveal(tr("Preview playlist"))
                    await("Translated public import missing") { node(tr("Preview playlist")) != null }
                    screenshot("release-080-$tag-import")
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    await("Translated top bar missing") { node(tr("About Sonora")) != null }
                    val tab = tr("Downloads · %1\$s", app.graph.downloads.state.value.items.count { it.ready })
                    reveal(tab); click(tab)
                    await("Translated downloads missing") { node(tr("Wi-Fi only")) != null }
                    screenshot("release-080-$tag-downloads")
                    if (downloaded != null) {
                        click(downloaded.title)
                        await("Translated player download missing") { node(tr("Downloaded")) != null }
                        screenshot("release-080-$tag-player")
                        click(tr("Close player"))
                    }
                }
                scenario.recreate()
                await("Catalan did not survive recreation") { node("Quant a Sonora") != null }
                assertEquals("ca", AppLanguage.selected(app))
                main { assertEquals(queue, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }) }
                assertEquals(count, runBlocking { app.graph.library.exportBackup().tracks.size })
            }
        } finally {
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { AppLanguage.select(it, before.substringBefore(',')) } }
        }
    }
    @Test fun resumeAfterSystemPauseUnpausesTheDownloadManager() = connected { _, _, _ ->
        val downloads = app.graph.downloads
        val reference = preferences.getString("reference", "https://www.youtube.com/watch?v=s0n0ra80001")!!
        try {
            main { downloads.manager.pauseDownloads(); assertTrue(downloads.manager.downloadsPaused) }
            main { downloads.resume(reference) }
            await("Resume left the manager paused after a system timeout") {
                var resumed = false; main { resumed = !downloads.manager.downloadsPaused }; resumed
            }
        } finally { main { downloads.manager.resumeDownloads() } }
    }
    @Test fun removeFixtureKeepsPlaylistMetadata() {
        liveOnly()
        val reference = preferences.getString("reference", null) ?: return
        connected { _, _, player ->
            main { player.stop(); player.clearMediaItems(); app.graph.downloads.remove(reference) }
            await("Download was not removed") { app.graph.downloads.state.value.items.none { it.track.url == reference } }
            assertFalse(app.graph.downloads.hasDownloaded(reference))
            val id = preferences.getLong("playlist", -1)
            assertNotNull(runBlocking { app.graph.library.playlist(id) })
            runBlocking { app.graph.library.deletePlaylist(id) }
            main { app.graph.downloads.wifiOnly(preferences.getBoolean("wifiOnly", true)) }
            preferences.edit().clear().commit()
        }
    }
}
