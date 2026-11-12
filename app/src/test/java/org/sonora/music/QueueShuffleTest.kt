package org.sonora.music

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.sonora.music.playback.shuffleUpcoming

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@UnstableApi
class QueueShuffleTest {
    private val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Application>()).build()
    private fun queue() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("file:///fixture-$id.wav").build()
    @After fun close() { player.release() }

    @Test fun shufflesOnlyPendingItemsWithoutSeekingOrChangingPlaybackState() {
        val original = (0..7).map { item("$it") }
        val observedOrders = mutableSetOf<List<MediaItem>>()
        for (seed in 0..9) {
            player.setMediaItems(original, 2, 12_345)
            player.playWhenReady = seed % 2 == 0
            player.shuffleUpcoming(Random(seed))
            val shuffled = queue()
            assertEquals(original.take(3), shuffled.take(3))
            assertEquals(original.toSet(), shuffled.toSet())
            assertEquals(original.size, shuffled.size)
            assertEquals(2, player.currentMediaItemIndex)
            assertEquals(12_345L, player.currentPosition)
            assertEquals(seed % 2 == 0, player.playWhenReady)
            assertEquals(Player.STATE_IDLE, player.playbackState)
            observedOrders.add(shuffled)
        }
        assertTrue("Shuffling must actually vary the upcoming order", observedOrders.size > 1)
    }

    @Test fun preservesDuplicatesAndDoesNothingWhenFewerThanTwoItemsArePending() {
        player.shuffleUpcoming(Random(0))
        assertEquals(0, player.mediaItemCount)
        val duplicate = item("duplicate")
        val original = listOf(item("current"), duplicate, item("other"), duplicate)
        player.setMediaItems(original, 0, 7_000)
        player.shuffleUpcoming(Random(4))
        assertEquals(original.groupingBy { it }.eachCount(), queue().groupingBy { it }.eachCount())
        for (index in 2..3) {
            player.setMediaItems(original, index, 7_000)
            player.shuffleUpcoming(Random(0))
            assertEquals(original, queue())
            assertEquals(7_000L, player.currentPosition)
            assertFalse(player.playWhenReady)
        }
    }
}
