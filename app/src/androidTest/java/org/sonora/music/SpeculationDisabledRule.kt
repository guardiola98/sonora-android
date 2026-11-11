package org.sonora.music

import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Keep fixture tests deterministic: only tests explicitly exercising preparation enable it. */
@UnstableApi
class SpeculationDisabledRule : ExternalResource() {
    private var previous = true
    private var discovery: org.sonora.music.discovery.DiscoveryOptions? = null
    private val graph get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SonoraApplication).graph
    override fun before() { previous = graph.preparation.enabled.value; graph.preparation.setEnabled(false); discovery = graph.discoverySettings.state.value; graph.discoverySettings.update(discovery!!.copy(autoplay = false, personalized = false, enrichment = false, onlineSuggestions = false)) }
    override fun after() { graph.preparation.setEnabled(previous); discovery?.let(graph.discoverySettings::update) }
}
