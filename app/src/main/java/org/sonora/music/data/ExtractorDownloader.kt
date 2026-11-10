package org.sonora.music.data

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** HTTP transport adapter; extraction remains entirely inside the external library. */
class ExtractorDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
            ?: if (request.httpMethod() in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
        val builder = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body)
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        return client.newCall(builder.build()).execute().use {
            if (it.code == 429) throw ReCaptchaException("Request rate limited", request.url())
            Response(it.code, it.message, it.headers.toMultimap(), it.body?.string(), it.request.url.toString())
        }
    }
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
