package org.sonora.music

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.takeScreenshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Captures real Compose screens, including on ATD images with drawing disabled. */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class UiSmokeTest {
    @Test fun bottomNavigationRendersAllThreeScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use {
            fun capture(name: String) {
                instrumentation.waitForIdleSync()
                val bitmap = takeScreenshot().copy(Bitmap.Config.ARGB_8888, false)
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                val colors = (0 until bitmap.width step 20).flatMap { x -> (0 until bitmap.height step 20).map { y -> bitmap.getPixel(x, y) } }.toSet()
                assertTrue("Screenshot is blank", colors.size > 5)
                val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
                file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
            }
            // Compose exposes virtual accessibility nodes; traverse them instead of
            // relying on the optional provider findAccessibilityNodeInfosByText API.
            fun findNode(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
                if (root == null) return null
                for (i in root.childCount - 1 downTo 0) {
                    findNode(root.getChild(i), text)?.let { return it }
                }
                return root.takeIf { it.text?.toString() == text || it.contentDescription?.toString() == text }
            }
            fun navigate(label: String, expected: String) {
                // Screenshot capture may reconnect UiAutomation without accessibility.
                val automation = instrumentation.getUiAutomation(0)
                val findDeadline = System.currentTimeMillis() + 10_000
                var found: AccessibilityNodeInfo? = null
                while (found == null && System.currentTimeMillis() < findDeadline) {
                    found = findNode(automation.rootInActiveWindow, label)
                    if (found == null) Thread.sleep(100)
                }
                var node = found ?: throw AssertionError("Navigation label $label was not exposed")
                while (!node.isClickable && node.parent != null) node = node.parent
                assertTrue("Could not open $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline) {
                    if (findNode(automation.rootInActiveWindow, expected) != null) return
                    Thread.sleep(100)
                }
                throw AssertionError("$expected screen did not appear")
            }
            capture("home")
            navigate("Search", "Follow your curiosity")
            capture("search")
            navigate("Library", "Your library")
            capture("library")
        }
    }
}
