package org.sonora.music
import org.junit.Assert.assertEquals
import org.junit.Test
import org.sonora.music.domain.formatDuration
class DurationTest {
    @Test fun formatsUnknownMinutesAndHours() {
        assertEquals("0:00", formatDuration(-1))
        assertEquals("3:07", formatDuration(187))
        assertEquals("1:02:03", formatDuration(3723))
    }
}
