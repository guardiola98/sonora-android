package org.sonora.music.playback

/** Small, expiring RAM-only prefixes. Exact resolved URLs prevent mixing formats or signed streams. */
class AudioPrefixCache(private val now: () -> Long = System::currentTimeMillis) {
    data class Prefix(val owner: String, val url: String, val bytes: ByteArray, val totalLength: Long?, val expires: Long)
    private val entries = java.util.LinkedHashMap<String, Prefix>(8, 0.75f, true)
    private var revision = 0L
    @Synchronized fun revision() = revision
    @Synchronized fun get(url: String): Prefix? {
        val item = entries[url] ?: return null
        if (item.expires <= now()) { entries.remove(url); return null }
        return item
    }
    @Synchronized fun hasOwner(owner: String) = entries.values.any { it.owner == owner && it.expires > now() }
    @Synchronized fun put(owner: String, url: String, bytes: ByteArray, totalLength: Long?, expectedRevision: Long): Boolean {
        require(bytes.isNotEmpty() && bytes.size <= PREFIX_BYTES)
        require(totalLength == null || totalLength >= bytes.size)
        if (revision != expectedRevision) return false
        entries.entries.removeAll { it.value.expires <= now() || it.value.owner == owner }
        while (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
        entries[url] = Prefix(owner, url, bytes.copyOf(), totalLength, now() + TTL_MS)
        return true
    }
    @Synchronized fun invalidate(owner: String) { revision++; entries.entries.removeAll { it.value.owner == owner } }
    @Synchronized fun clear() { revision++; entries.clear() }
    companion object {
        const val PREFIX_BYTES = 192 * 1024
        const val MAX_ENTRIES = 4
        const val TTL_MS = 5 * 60_000L
    }
}
