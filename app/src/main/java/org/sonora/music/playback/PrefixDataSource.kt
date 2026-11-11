package org.sonora.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*

/** Serve a prepared prefix immediately, then continue the same representation at the exact byte offset. */
@UnstableApi
class PrefixDataSource(private val upstream: DataSource, private val cache: AudioPrefixCache) : DataSource {
    private var spec: DataSpec? = null
    private var prefix: AudioPrefixCache.Prefix? = null
    private var consumed = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var upstreamOpen = false
    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)
    override fun open(dataSpec: DataSpec): Long {
        check(spec == null)
        spec = dataSpec; consumed = 0
        prefix = if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && dataSpec.httpRequestHeaders.all { (key, value) -> key.equals("Icy-MetaData", true) && value == "1" }) cache.get(dataSpec.uri.toString())
            ?.takeIf { dataSpec.position < it.bytes.size } else null
        if (prefix == null) {
            upstreamOpen = true
            remaining = upstream.open(dataSpec)
        } else {
            val total = prefix?.totalLength
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
                else total?.let { it - dataSpec.position } ?: C.LENGTH_UNSET.toLong()
        }
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val request = checkNotNull(spec)
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val cached = prefix
        val position = request.position + consumed
        if (cached != null && position < cached.bytes.size) {
            val count = minOf(length.toLong(), cached.bytes.size - position, if (remaining >= 0) remaining else Long.MAX_VALUE).toInt()
            cached.bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            consumed += count; if (remaining >= 0) remaining -= count
            return count
        }
        if (!upstreamOpen) {
            upstreamOpen = true
            val opened = upstream.open(request.subrange(consumed))
            if (remaining == C.LENGTH_UNSET.toLong()) remaining = opened
        }
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = upstream.read(buffer, offset, minOf(length.toLong(), if (remaining >= 0) remaining else Long.MAX_VALUE).toInt())
        if (count != C.RESULT_END_OF_INPUT) { consumed += count; if (remaining >= 0) remaining -= count }
        return count
    }
    override fun getUri(): Uri? = if (upstreamOpen) upstream.uri ?: spec?.uri else spec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = if (upstreamOpen) upstream.responseHeaders else emptyMap()
    override fun close() {
        try { if (upstreamOpen) upstream.close() }
        finally { upstreamOpen = false; spec = null; prefix = null; consumed = 0; remaining = C.LENGTH_UNSET.toLong() }
    }
}
