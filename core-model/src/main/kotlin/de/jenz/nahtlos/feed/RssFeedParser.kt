package de.jenz.nahtlos.feed

import de.jenz.nahtlos.model.Chapter
import de.jenz.nahtlos.model.FeedParseException
import de.jenz.nahtlos.model.ParsedEpisode
import de.jenz.nahtlos.model.ParsedFeed
import de.jenz.nahtlos.model.Person
import de.jenz.nahtlos.model.TranscriptLink
import com.ctc.wstx.stax.WstxInputFactory
import java.io.Reader
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * Pull parser (StAX). Android's implementation is streaming, so a multi-megabyte
 * feed is not turned into a DOM. At most [maxEpisodes] newest episodes are kept.
 */
class RssFeedParser {
    fun parse(xml: String, maxEpisodes: Int = Int.MAX_VALUE): ParsedFeed =
        parse(StringReader(xml), maxEpisodes)

    fun parse(reader: Reader, maxEpisodes: Int = Int.MAX_VALUE): ParsedFeed {
        // Woodstox, not the platform factory: Android has no usable javax.xml.stream,
        // and its DocumentBuilder rejects the secure-processing features.
        val factory = WstxInputFactory()
        runCatching { factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false) }
        runCatching { factory.setProperty(XMLInputFactory.SUPPORT_DTD, false) }
        runCatching { factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true) }
        val xml = factory.createXMLStreamReader(AmpersandFixReader(reader))
        return try {
            readFeed(xml, maxEpisodes)
        } catch (e: FeedParseException) {
            throw e
        } catch (e: Exception) {
            throw FeedParseException("Feed-XML konnte nicht gelesen werden: ${e.message}", e)
        } finally {
            runCatching { xml.close() }
        }
    }

    private fun readFeed(xml: XMLStreamReader, maxEpisodes: Int): ParsedFeed {
        val cursor = Cursor(xml)
        var title = ""
        var description = ""
        var encoded = ""
        var summary = ""
        var link: String? = null
        var image: String? = null
        var guid: String? = null
        var author: String? = null
        val persons = mutableListOf<Person>()
        val episodes = ArrayList<ParsedEpisode>()
        var seenChannel = false
        while (xml.hasNext()) {
            if (xml.next() != XMLStreamConstants.START_ELEMENT) continue
            when (cursor.local()) {
                "channel" -> seenChannel = true
                "title" -> if (seenChannel && title.isEmpty()) title = cursor.text()
                "encoded" -> if (seenChannel) encoded = cursor.text()
                "description" -> if (seenChannel && description.isEmpty()) description = cursor.text()
                "summary" -> if (seenChannel && summary.isEmpty()) summary = cursor.text()
                "link" -> if (seenChannel && link == null) link = cursor.text().ifBlank { null }
                "author" -> if (seenChannel && author == null) author = cursor.text().ifBlank { null }
                "guid" -> if (seenChannel && guid == null) guid = cursor.text().ifBlank { null }
                "person" -> if (seenChannel) cursor.person()?.let { persons += it }
                "image" -> if (seenChannel && image == null) image = cursor.image()
                "item" -> if (seenChannel) {
                    cursor.item(image)?.let { episode ->
                        episodes += episode
                        if (maxEpisodes != Int.MAX_VALUE && episodes.size > maxEpisodes * 2) {
                            trim(episodes, maxEpisodes)
                        }
                    }
                } else {
                    cursor.skip()
                }
                else -> if (seenChannel) cursor.skip()
            }
        }
        if (!seenChannel) throw FeedParseException("Kein RSS-Channel im Feed")
        if (title.isBlank()) throw FeedParseException("Der Feed hat keinen Titel")
        trim(episodes, maxEpisodes)
        return ParsedFeed(
            title = title,
            description = plainText(encoded.ifBlank { description.ifBlank { summary } }),
            link = link,
            imageUrl = image,
            podcastGuid = guid,
            author = author,
            persons = persons,
            episodes = episodes,
        )
    }

    private fun trim(episodes: MutableList<ParsedEpisode>, maxEpisodes: Int) {
        if (maxEpisodes == Int.MAX_VALUE || episodes.size <= maxEpisodes) return
        val kept = episodes
            .sortedByDescending { it.publishedAtEpochMs ?: Long.MIN_VALUE }
            .take(maxEpisodes)
        episodes.clear()
        episodes.addAll(kept)
    }

    private class Cursor(private val xml: XMLStreamReader) {
        fun local(): String = xml.localName

        fun item(channelImage: String?): ParsedEpisode? {
            var title = ""
            var description = ""
            var encoded = ""
            var summary = ""
            var guid: String? = null
            var enclosureUrl: String? = null
            var mimeType: String? = null
            var duration: String? = null
            var pubDate: String? = null
            var image: String? = null
            var chaptersUrl: String? = null
            var chaptersType: String? = null
            val chapters = mutableListOf<Chapter>()
            val transcripts = mutableListOf<TranscriptLink>()
            val persons = mutableListOf<Person>()
            var depth = 1
            while (depth > 0 && xml.hasNext()) {
                when (xml.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        if (depth != 1) {
                            depth++
                            continue
                        }
                        when (local()) {
                            "title" -> title = text()
                            "encoded" -> encoded = text()
                            "description" -> description = text()
                            "summary" -> summary = text()
                            "guid" -> guid = text().ifBlank { null }
                            "duration" -> duration = text()
                            "pubDate" -> pubDate = text()
                            "enclosure" -> {
                                enclosureUrl = attr("url")
                                mimeType = attr("type")
                                skip()
                            }
                            "transcript" -> {
                                val url = attr("url")
                                if (!url.isNullOrBlank()) {
                                    transcripts += TranscriptLink(url, attr("type"), attr("language"))
                                }
                                skip()
                            }
                            "person" -> person()?.let { persons += it }
                            "image" -> image = image()
                            "chapters" -> readChapters(chapters) { url, type ->
                                if (chaptersUrl == null && url != null) {
                                    chaptersUrl = url
                                    chaptersType = type
                                }
                            }
                            "chapter" -> chapter()?.let { chapters += it }
                            else -> skip()
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> depth--
                }
            }
            val url = enclosureUrl ?: return null
            return ParsedEpisode(
                title = title.ifBlank { "Ohne Titel" },
                description = plainText(encoded.ifBlank { description.ifBlank { summary } }),
                guid = guid,
                enclosureUrl = url,
                mimeType = mimeType,
                durationMs = parseDuration(duration.orEmpty()),
                publishedAtEpochMs = parseDate(pubDate.orEmpty()),
                imageUrl = image ?: channelImage,
                chapters = chapters,
                chaptersUrl = chaptersUrl,
                chaptersType = chaptersType,
                transcripts = transcripts,
                persons = persons,
            )
        }

        private fun readChapters(into: MutableList<Chapter>, onUrl: (String?, String?) -> Unit) {
            onUrl(attr("url"), attr("type"))
            var depth = 1
            while (depth > 0 && xml.hasNext()) {
                when (xml.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        if (depth == 1 && local() == "chapter") {
                            chapter()?.let { into += it }
                        } else {
                            depth++
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> depth--
                }
            }
        }

        fun person(): Person? {
            val role = attr("role")
            val href = attr("href")
            val img = attr("img")
            val group = attr("group")
            val name = text()
            if (name.isEmpty()) return null
            return Person(name = name, role = role, href = href, img = img, group = group)
        }

        fun image(): String? {
            val href = attr("href")
            if (href != null) {
                skip()
                return href
            }
            var url: String? = null
            var depth = 1
            while (depth > 0 && xml.hasNext()) {
                when (xml.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        if (depth == 1 && local() == "url") url = text() else depth++
                    }
                    XMLStreamConstants.END_ELEMENT -> depth--
                }
            }
            return url?.ifBlank { null }
        }

        fun chapter(): Chapter? {
            val start = attr("start") ?: attr("startTime") ?: run {
                skip()
                return null
            }
            val startMs = parseClock(start) ?: run {
                skip()
                return null
            }
            val chapter = Chapter(
                startMs = startMs,
                title = attr("title")?.takeIf { it.isNotBlank() } ?: "Kapitel",
                url = attr("href") ?: attr("url"),
                imageUrl = attr("image") ?: attr("img"),
            )
            skip()
            return chapter
        }

        fun text(): String {
            val sb = StringBuilder()
            var depth = 1
            while (depth > 0 && xml.hasNext()) {
                when (xml.next()) {
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                        if (depth == 1 && sb.length < MAX_TEXT_CHARS) {
                            val room = MAX_TEXT_CHARS - sb.length
                            val chars = xml.textCharacters
                            val start = xml.textStart
                            val length = minOf(xml.textLength, room)
                            sb.append(chars, start, length)
                        }
                    XMLStreamConstants.START_ELEMENT -> depth++
                    XMLStreamConstants.END_ELEMENT -> depth--
                }
            }
            return sb.toString().trim()
        }

        fun skip() {
            var depth = 1
            while (depth > 0 && xml.hasNext()) {
                when (xml.next()) {
                    XMLStreamConstants.START_ELEMENT -> depth++
                    XMLStreamConstants.END_ELEMENT -> depth--
                }
            }
        }

        fun attr(name: String): String? {
            for (i in 0 until xml.attributeCount) {
                val local = xml.getAttributeLocalName(i)
                if (local == name) return xml.getAttributeValue(i)?.trim()?.ifEmpty { null }
            }
            return null
        }
    }

    companion object {
        /**
         * Newest episodes kept from one refresh. Older episodes already stored are not deleted.
         * A "since date" playlist can only include episodes that were stored, so a cutoff older
         * than the oldest stored episode of a long-running show will miss the rest. See the README.
         */
        const val MAX_EPISODES_PER_REFRESH = 200
        private const val MAX_TEXT_CHARS = 8_000

        private val BREAKS = Regex("(?i)<br\\s*/?>")
        private val PARAGRAPHS = Regex("(?i)</p>")
        private val TAGS = Regex("<[^>]+>")
        private val SPACES = Regex("[\\t\\u00A0 ]+")
        private val SPACE_BEFORE_BREAK = Regex(" *\\n *")
        private val BLANK_LINES = Regex("\\n{3,}")

        fun plainText(raw: String): String {
            if (raw.isBlank()) return ""
            return raw
                .replace(BREAKS, "\n")
                .replace(PARAGRAPHS, "\n\n")
                .replace(TAGS, " ")
                .replace(SPACES, " ")
                .replace(SPACE_BEFORE_BREAK, "\n")
                .replace(BLANK_LINES, "\n\n")
                .trim()
                .take(4_000)
        }

        fun parseDuration(raw: String): Long? = parseClock(raw.trim())

        fun parseClock(raw: String): Long? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val parts = text.split(":")
            return try {
                val seconds = when (parts.size) {
                    1 -> parts[0].toDouble()
                    2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
                    3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
                    else -> return null
                }
                (seconds * 1000.0).toLong().coerceAtLeast(0)
            } catch (_: NumberFormatException) {
                null
            }
        }

        fun parseDate(raw: String): Long? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            for (format in dateFormats) {
                try {
                    return ZonedDateTime.parse(text, format).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    // next pattern
                }
            }
            return null
        }

        private val dateFormats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss zzz",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ssX",
        ).map { DateTimeFormatter.ofPattern(it, Locale.US) }
    }
}

/**
 * Rewrites bare ampersands and a few HTML entities while reading, so a multi-megabyte
 * feed is never copied into a second string before parsing.
 */
internal class AmpersandFixReader(private val source: Reader) : Reader() {
    private val pending = ArrayDeque<Char>()

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        var count = 0
        while (count < len) {
            val next = pull() ?: return if (count == 0) -1 else count
            cbuf[off + count] = next
            count++
        }
        return count
    }

    override fun close() {
        source.close()
    }

    private fun pull(): Char? {
        if (pending.isNotEmpty()) return pending.removeFirst()
        val value = source.read()
        if (value < 0) return null
        val char = value.toChar()
        if (char == '\uFEFF') return pull()
        if (char.code in 0x00..0x08 || char.code == 0x0B || char.code == 0x0C || char.code in 0x0E..0x1F) {
            return pull()
        }
        if (char != '&') return char
        val name = StringBuilder()
        while (name.length < 16) {
            val next = source.read()
            if (next < 0) {
                queue("&")
                queue(name.toString())
                return pending.removeFirst()
            }
            val n = next.toChar()
            if (n == ';') {
                val token = name.toString()
                when {
                    token == "amp" || token == "lt" || token == "gt" || token == "quot" || token == "apos" ->
                        queue("&$token;")
                    token.startsWith("#") && token.length > 1 -> queue("&#${token.drop(1)};")
                    else -> queue(HTML_ENTITIES[token] ?: "&amp;$token;")
                }
                return pending.removeFirst()
            }
            if (n != '#' && !n.isLetterOrDigit()) {
                queue("&amp;")
                queue(name.toString())
                queue(n.toString())
                return pending.removeFirst()
            }
            name.append(n)
        }
        queue("&amp;")
        queue(name.toString())
        return pending.removeFirst()
    }

    private fun queue(text: String) {
        text.forEach { pending.addLast(it) }
    }

    companion object {
        private val HTML_ENTITIES = mapOf(
            "nbsp" to " ",
            "auml" to "ä",
            "ouml" to "ö",
            "uuml" to "ü",
            "Auml" to "Ä",
            "Ouml" to "Ö",
            "Uuml" to "Ü",
            "szlig" to "ß",
            "mdash" to "—",
            "ndash" to "–",
            "hellip" to "…",
            "laquo" to "«",
            "raquo" to "»",
            "euro" to "€",
        )
    }
}
