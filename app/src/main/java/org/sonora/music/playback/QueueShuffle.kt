package org.sonora.music.playback

import androidx.media3.common.Player
import kotlin.random.Random

/** Reorder only pending items, retaining the current item, position and play/pause state. */
internal fun Player.shuffleUpcoming(random: Random = Random.Default) {
    if (currentMediaItemIndex !in 0 until mediaItemCount) return
    val first = currentMediaItemIndex + 1
    if (mediaItemCount - first < 2) return
    val pending = (first until mediaItemCount).map(::getMediaItemAt)
    // One timeline update also keeps large imported queues responsive.
    replaceMediaItems(first, mediaItemCount, pending.shuffled(random))
}
