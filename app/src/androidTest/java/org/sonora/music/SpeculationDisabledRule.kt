package org.sonora.music

import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Keep fixture tests deterministic: only tests explicitly exercising preparation enable it. */
@UnstableApi
class SpeculationDisabledRule : ExternalResource() {
    private var previous = true
    private val graph get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SonoraApplication).graph
    override fun before() { previous = graph.preparation.enabled.value; graph.preparation.setEnabled(false) }
    override fun after() { graph.preparation.setEnabled(previous) }
}
