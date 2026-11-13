package org.sonora.music.data

import org.sonora.music.i18n.tr

import org.sonora.music.domain.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Bounded RSS 2.0 parser. No WebView, external entities, DTDs or executable HTML. */
object PodcastFeedParser {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_EPISODES = 500
    private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private data class Node(val name: String, val namespace: String, val text: StringBuilder = StringBuilder())
    fun parse(bytes: ByteArray, feedUrl: String): PodcastFeed {
        require(bytes.size <= MAX_BYTES) { tr("Podcast feed exceeds 16 MiB.") }
        val canonical = requireNotNull(PodcastLinks.https(feedUrl)) { tr("Use a public HTTPS RSS feed URL.") }
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), null)
        val stack = mutableListOf<Node>()
        val show = mutableMapOf<String, String>()
        var episode: MutableMap<String, String>? = null
        val records = mutableListOf<Map<String, String>>()
        var channelSeen = false
        while (true) {
            val event = parser.nextToken()
            if (event == XmlPullParser.END_DOCUMENT) break
            require(event != XmlPullParser.DOCDECL) { tr("RSS document types are not supported.") }
            when (event) {
                XmlPullParser.START_TAG -> {
                    require(stack.size < 32) { tr("RSS is nested too deeply.") }
                    val parent = stack.lastOrNull()?.name
                    stack.add(Node(parser.name, parser.namespace.orEmpty()))
                    if (stack.size == 1) require(parser.name == "rss") { tr("This is not an RSS 2.0 podcast feed.") }
                    if (parser.name == "channel" && parent == "rss") channelSeen = true
                    if (parser.name == "item" && parent == "channel") episode = mutableMapOf()
                    val target = episode ?: show
                    if (parser.name == "image" && parser.namespace == ITUNES && parent in listOf("channel", "item"))
                        target["artwork"] = parser.getAttributeValue(null, "href").orEmpty()
                    if (parser.name == "enclosure" && parent == "item" && episode != null) {
                        val type = parser.getAttributeValue(null, "type").orEmpty().lowercase()
                        val url = PodcastLinks.https(parser.getAttributeValue(null, "url").orEmpty())
                        if (url != null && (type.startsWith("audio/") || type == "application/ogg") && target["audio"] == null) target["audio"] = url
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    val value = parser.text.orEmpty()
                    stack.forEach { if (it.text.length < 20_000) it.text.append(value.take(20_000 - it.text.length)) }
                }
                XmlPullParser.END_TAG -> {
                    val node = stack.removeAt(stack.lastIndex)
                    val parent = stack.lastOrNull()?.name
                    val target = episode ?: show
                    if (parent == "channel" || parent == "item") {
                        val key = when {
                            node.namespace == ITUNES && node.name == "author" -> "author"
                            node.namespace == ITUNES && node.name == "duration" -> "duration"
                            node.name in listOf("title", "description", "guid", "pubDate") -> node.name
                            node.name == "summary" && target["description"].isNullOrBlank() -> "description"
                            else -> null
                        }
                        key?.let { target[it] = node.text.toString().trim() }
                    }
                    if (node.name == "url" && parent == "image" && episode == null && show["artwork"].isNullOrBlank()) show["artwork"] = node.text.toString().trim()
                    if (node.name == "item" && parent == "channel") {
                        episode?.takeIf { it["audio"] != null }?.let { if (records.size < MAX_EPISODES) records.add(it) }
                        episode = null
                    }
                }
            }
        }
        require(channelSeen && !show["title"].isNullOrBlank()) { tr("RSS feed has no podcast title.") }
        val podcast = PodcastShow(canonical, plain(show["title"]).take(2000), plain(show["author"]).take(2000),
            PodcastLinks.https(show["artwork"].orEmpty()).orEmpty(), plain(show["description"]))
        val episodes = records.map { row ->
            PodcastEpisode(PodcastLinks.reference(canonical, row["guid"]?.takeIf { it.isNotBlank() } ?: row.getValue("audio")),
                plain(row["title"]).ifBlank { tr("Untitled episode") }.take(2000), plain(row["description"]),
                PodcastLinks.https(row["artwork"].orEmpty()).orEmpty(), duration(row["duration"]),
                runCatching { ZonedDateTime.parse(row["pubDate"], DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().coerceAtLeast(0) }.getOrDefault(0), row.getValue("audio"))
        }.distinctBy { it.reference }.sortedByDescending { it.publishedAt }
        return PodcastFeed(podcast, episodes)
    }
    fun duration(text: String?): Long {
        val parts = text?.trim()?.split(':') ?: return 0
        if (parts.size !in 1..3) return 0
        var result = 0L
        for (part in parts) {
            val number = part.toLongOrNull()?.takeIf { it in 0..604800 } ?: return 0
            result = result * 60 + number
            if (result > 604800) return 0
        }
        return result
    }
    private fun plain(value: String?): String = androidx.core.text.HtmlCompat.fromHtml(value.orEmpty().take(20_000), androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim().take(10_000)
}
