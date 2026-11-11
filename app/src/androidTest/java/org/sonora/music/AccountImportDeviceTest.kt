package org.sonora.music

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.takeScreenshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.music.accountimport.ImportProvider
import org.sonora.music.ui.MusicViewModel
import java.io.File

@RunWith(AndroidJUnit4::class)
@UnstableApi
class AccountImportDeviceTest {
    @get:org.junit.Rule val noSpeculation = SpeculationDisabledRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun find(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (root == null) return null
        for (i in 0 until root.childCount) find(root.getChild(i), label)?.let { return it }
        return root.takeIf { it.text?.toString() == label || it.contentDescription?.toString() == label }
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) { if (predicate()) return; Thread.sleep(100) }
        fail(label)
    }
    private fun click(label: String) {
        await("Could not click $label") {
            var node = find(instrumentation.getUiAutomation(0).rootInActiveWindow, label) ?: return@await false
            while (!node.isClickable && node.parent != null) node = node.parent
            node.isEnabled && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }
    private fun capture(name: String) {
        val bitmap = takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun importEntryPointsExplainConfigurationAndLeavingTheFlowCancelsAuthorization() {
        assumeTrue(BuildConfig.SPOTIFY_CLIENT_ID.isBlank() && !BuildConfig.YOUTUBE_OAUTH_ENABLED)
        val app = instrumentation.targetContext.applicationContext as SonoraApplication
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: MusicViewModel
            scenario.onActivity { vm = ViewModelProvider(it, MusicViewModel.Factory(app))[MusicViewModel::class.java] }
            click("Library"); click("Import music")
            await("Account import buttons missing") { find(instrumentation.getUiAutomation(0).rootInActiveWindow, "Import from Spotify") != null }
            capture("account-import.png")
            click("Import from Spotify"); click("Use file or link import")
            click("Import from YouTube / YouTube Music")
            await("Google availability notice missing") {
                find(instrumentation.getUiAutomation(0).rootInActiveWindow, "Experimental: YouTube API rules require data refresh/deletion and restrict background/audio-only clients. A permanent disconnected import needs use-case approval; registration alone is not enough. This integration is disabled by default. Use Takeout or public links meanwhile.") != null
            }
            Thread.sleep(400) // Allow the dialog's entry animation to settle for the actual screenshot.
            capture("account-import-availability.png")
            click("Use file or link import")
            var cancelled = false
            scenario.onActivity { vm.accountImporter.connect(ImportProvider.SPOTIFY, false) { try { awaitCancellation() } finally { cancelled = true } } }
            await("Waiting authorization UI missing") { vm.accountImporter.state.value.authorizing }
            click("Home")
            await("Leaving import did not cancel authorization") { cancelled && vm.accountImporter.state.value.provider == null }
            assertTrue(vm.accountImporter.state.value.disconnected)
        }
    }
}
