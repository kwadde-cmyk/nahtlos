package de.jenz.nahtlos.feed

import de.jenz.nahtlos.model.Chapter
import de.jenz.nahtlos.xml.XmlParsers
import de.jenz.nahtlos.model.FeedParseException
import de.jenz.nahtlos.model.ParsedEpisode
import de.jenz.nahtlos.model.ParsedFeed
import de.jenz.nahtlos.model.Person
import de.jenz.nahtlos.model.TranscriptLink
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * RSS 2.0 plus the Podcasting 2.0 fields the MVP needs:
 * channel `podcast:guid`, item GUID, `podcast:person`, `podcast:transcript`,
 * remote `podcast:chapters`, and inline Podlove Simple Chapters (`psc:chapter`).
 */
class RssFeedParser {
    fun parse(xml: String): ParsedFeed {
        val document = try {
            val factory = XmlParsers.documentBuilderFactory(namespaceAware = true)
            val source = InputSource(StringReader(sanitize(xml)))
            source.encoding = "UTF-8"
            factory.newDocumentBuilder().parse(source)
        } catch (e: FeedParseException) {
            throw e
        } catch (e: Exception) {
            throw FeedParseException("Feed-XML konnte nicht gelesen werden: ${e.message}", e)
        }
        val channel = document.documentElement
            ?.takeIf { it.localName == "rss" }
            ?.children()
            ?.firstOrNull { it.localName == "channel" }
            ?: document.documentElement?.takeIf { it.localName == "channel" }
            ?: throw FeedParseException("Kein RSS-Channel im Feed")

        val title = channel.childText("title").ifBlank {
            throw FeedParseException("Der Feed hat keinen Titel")
        }
        val episodes = channel.children()
            .filter { it.localName == "item" }
            .mapNotNull { parseItem(it, channel.imageUrl()) }

        return ParsedFeed(
            title = title,
            description = plainText(
                channel.childText("encoded").ifBlank {
                    channel.childText("description").ifBlank { channel.childText("summary") }
                },
            ),
            link = channel.childText("link").ifBlank { null },
            imageUrl = channel.imageUrl(),
            podcastGuid = channel.childText("guid").ifBlank { null },
            author = channel.childText("author").ifBlank { null },
            persons = channel.children().filter { it.localName == "person" }.mapNotNull { it.toPerson() },
            episodes = episodes,
        )
    }

    private fun parseItem(item: Element, channelImage: String?): ParsedEpisode? {
        val enclosure = item.children().firstOrNull { element ->
            element.localName == "enclosure" && !element.attr("url").isNullOrBlank()
        } ?: return null
        val enclosureUrl = enclosure.attr("url") ?: return null
        val guid = item.children()
            .firstOrNull { it.localName == "guid" }
            ?.textContent
            ?.trim()
            ?.ifEmpty { null }
        val inlineChapters = item.children()
            .filter { it.localName == "chapters" || it.localName == "chapter" }
            .flatMap { chapters ->
                if (chapters.localName == "chapter") listOf(chapters) else chapters.children()
            }
            .filter { it.localName == "chapter" }
            .mapNotNull { it.toChapter() }
        val remoteChapters = item.children().firstOrNull { element ->
            element.localName == "chapters" && !element.attr("url").isNullOrBlank()
        }
        return ParsedEpisode(
            title = item.childText("title").ifBlank { "Ohne Titel" },
            description = plainText(
                item.childText("encoded").ifBlank {
                    item.childText("description").ifBlank { item.childText("summary") }
                },
            ),
            guid = guid,
            enclosureUrl = enclosureUrl,
            mimeType = enclosure.attr("type"),
            durationMs = parseDuration(item.childText("duration")),
            publishedAtEpochMs = parseDate(item.childText("pubDate")),
            imageUrl = item.imageUrl() ?: channelImage,
            chapters = inlineChapters,
            chaptersUrl = remoteChapters?.attr("url"),
            chaptersType = remoteChapters?.attr("type"),
            transcripts = item.children()
                .filter { it.localName == "transcript" && !it.attr("url").isNullOrBlank() }
                .map { element ->
                    TranscriptLink(
                        url = element.attr("url").orEmpty(),
                        type = element.attr("type"),
                        language = element.attr("language"),
                    )
                },
            persons = item.children().filter { it.localName == "person" }.mapNotNull { it.toPerson() },
        )
    }

    private fun Element.toPerson(): Person? {
        val name = textContent?.trim().orEmpty()
        if (name.isEmpty()) return null
        return Person(
            name = name,
            role = attr("role"),
            href = attr("href"),
            img = attr("img"),
            group = attr("group"),
        )
    }

    private fun Element.toChapter(): Chapter? {
        val startRaw = attr("start") ?: attr("startTime") ?: return null
        val startMs = parseClock(startRaw) ?: return null
        return Chapter(
            startMs = startMs,
            title = attr("title")?.takeIf { it.isNotBlank() } ?: "Kapitel",
            url = attr("href") ?: attr("url"),
            imageUrl = attr("image") ?: attr("img"),
        )
    }

    private fun Element.imageUrl(): String? {
        val itunes = children().firstOrNull { it.localName == "image" && !it.attr("href").isNullOrBlank() }
        if (itunes != null) return itunes.attr("href")
        val nested = children().firstOrNull { it.localName == "image" }
            ?.children()
            ?.firstOrNull { it.localName == "url" }
            ?.textContent
            ?.trim()
        return nested?.ifEmpty { null }
    }

    private fun Element.childText(localName: String): String =
        children().firstOrNull { it.localName == localName }?.textContent?.trim().orEmpty()

    private fun Element.children(): List<Element> {
        val list = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) list += node as Element
        }
        return list
    }

    private fun Element.attr(name: String): String? {
        if (hasAttribute(name)) return getAttribute(name).trim().ifEmpty { null }
        val attributes = attributes ?: return null
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            if (attribute.localName == name || attribute.nodeName.endsWith(":$name")) {
                return attribute.nodeValue?.trim()?.ifEmpty { null }
            }
        }
        return null
    }

    companion object {
        private val bareAmp = Regex("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9A-Fa-f]+);)")
        private val namedEntity = Regex("&([A-Za-z][A-Za-z0-9]+);")
        private val htmlEntities = mapOf(
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
            "ldquo" to "“",
            "rdquo" to "”",
            "lsquo" to "‘",
            "rsquo" to "’",
            "euro" to "€",
            "copy" to "©",
        )
        private val dateFormats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss zzz",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ssX",
        ).map { DateTimeFormatter.ofPattern(it, Locale.US) }

        fun sanitize(xml: String): String {
            val withoutBom = xml.removePrefix("\uFEFF")
            val entities = namedEntity.replace(withoutBom) { match ->
                val name = match.groupValues[1]
                when (name) {
                    "amp", "lt", "gt", "quot", "apos" -> match.value
                    else -> htmlEntities[name] ?: match.value
                }
            }
            return bareAmp.replace(entities, "&amp;")
                .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
        }

        fun plainText(raw: String): String {
            if (raw.isBlank()) return ""
            return raw
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?i)</p>"), "\n\n")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("[\\t\\u00A0 ]+"), " ")
                .replace(Regex(" *\\n *"), "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
                .take(10_000)
        }

        fun parseDuration(raw: String): Long? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            return parseClock(text)
        }

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
                    // try the next pattern
                }
            }
            return null
        }
    }
}
