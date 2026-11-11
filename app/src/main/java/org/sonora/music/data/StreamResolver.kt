package org.sonora.music.data

import org.sonora.music.domain.*

/** Independent requests run concurrently; identical requests share the current extraction. */
class StreamResolver(private val source: MusicSource, private val podcasts: PodcastSource? = null,
    private val onInvalidated: (String) -> Unit = {}, private val now: () -> Long = System::currentTimeMillis) {
    private val cache = SingleFlightCache<String, AudioStream>(50, 5 * 60_000, now)
    suspend fun resolve(url: String): AudioStream = cache.get(url) {
        if (PodcastLinks.isEpisode(url)) requireNotNull(podcasts).resolve(url) else source.resolve(url)
    }
    suspend fun invalidate(url: String) {
        podcasts?.invalidate(url)
        cache.invalidate(url)
        onInvalidated(url)
    }
}
