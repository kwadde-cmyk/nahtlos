package de.jenz.nahtlos.feed

import de.jenz.nahtlos.model.AlternateSource
import de.jenz.nahtlos.model.Chapter
import de.jenz.nahtlos.model.FeedParseException
import de.jenz.nahtlos.model.FundingLink
import de.jenz.nahtlos.model.ParsedEpisode
import de.jenz.nahtlos.model.ParsedFeed
import de.jenz.nahtlos.model.Person
import de.jenz.nahtlos.model.PodLicense
import de.jenz.nahtlos.model.PodLocation
import de.jenz.nahtlos.model.PodTxt
import de.jenz.nahtlos.model.RemoteItem
import de.jenz.nahtlos.model.Soundbite
import de.jenz.nahtlos.model.Trailer
import de.jenz.nahtlos.model.TranscriptLink
import de.jenz.nahtlos.model.ValueRecipient
import de.jenz.nahtlos.model.ValueTimeSplit
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Streaming pull parser. Android uses the platform [XmlPullParser]; JVM tests use kxml2.
 * Nothing here references javax.xml.stream. The parser object itself does no I/O until [parse].
 *
 * [publishedAfterEpochMs] drops older items. When the feed is newest-first, parsing of
 * further items stops after the first run of older dates. [maxEpisodes] is an optional cap.
 */
class RssFeedParser(
    private val pullParsers: () -> XmlPullParser = { defaultPullParser() },
) {
    fun parse(
        xml: String,
        maxEpisodes: Int = Int.MAX_VALUE,
        publishedAfterEpochMs: Long? = null,
    ): ParsedFeed = parse(StringReader(xml), maxEpisodes, publishedAfterEpochMs)

    fun parse(
        reader: Reader,
        maxEpisodes: Int = Int.MAX_VALUE,
        publishedAfterEpochMs: Long? = null,
    ): ParsedFeed {
        val xml = pullParsers()
        runCatching { xml.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
        runCatching { xml.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        xml.setInput(AmpersandFixReader(reader))
        return try {
            readFeed(xml, maxEpisodes, publishedAfterEpochMs)
        } catch (e: FeedParseException) {
            throw e
        } catch (e: Exception) {
            throw FeedParseException("Feed-XML konnte nicht gelesen werden: ${e.message}", e)
        }
    }

    private fun readFeed(xml: XmlPullParser, maxEpisodes: Int, publishedAfterEpochMs: Long?): ParsedFeed {
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
        val funding = mutableListOf<FundingLink>()
        val value = mutableListOf<ValueRecipient>()
        var valueMethod: String? = null
        var valueSuggested: String? = null
        var location: PodLocation? = null
        var license: PodLicense? = null
        val txt = mutableListOf<PodTxt>()
        val trailers = mutableListOf<Trailer>()
        val podroll = mutableListOf<RemoteItem>()
        var locked = false
        val episodes = ArrayList<ParsedEpisode>()
        var seenFeed = false
        var seenNewer = false
        var stopItems = false
        var event = xml.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (cursor.local()) {
                    "channel", "feed" -> seenFeed = true
                    "title" -> if (seenFeed && title.isEmpty()) title = cursor.text()
                    "encoded" -> if (seenFeed) encoded = cursor.text()
                    "description", "subtitle" -> if (seenFeed && description.isEmpty()) description = cursor.text()
                    "summary" -> if (seenFeed && summary.isEmpty()) summary = cursor.text()
                    "link" -> if (seenFeed && link == null) link = cursor.link()
                    "author" -> if (seenFeed && author == null) author = cursor.text().ifBlank { null }
                    "guid" -> if (seenFeed && guid == null) guid = cursor.text().ifBlank { null }
                    "person" -> if (seenFeed) cursor.person()?.let { persons += it }
                    "image", "icon" -> if (seenFeed && image == null) image = cursor.image()
                    "funding" -> if (seenFeed) cursor.funding()?.let { funding += it }
                    "locked" -> if (seenFeed) locked = cursor.text().equals("yes", ignoreCase = true)
                    "value" -> if (seenFeed && value.isEmpty()) {
                        val block = cursor.valueBlock()
                        value += block.recipients
                        valueMethod = block.method
                        valueSuggested = block.suggested
                    }
                    "location" -> if (seenFeed && location == null) location = cursor.location()
                    "license" -> if (seenFeed && license == null) license = cursor.license()
                    "txt" -> if (seenFeed) cursor.txtTag()?.let { txt += it }
                    "trailer" -> if (seenFeed) cursor.trailer()?.let { trailers += it }
                    "podroll" -> if (seenFeed && podroll.isEmpty()) podroll += cursor.podroll()
                    "item", "entry", "liveItem" -> if (!seenFeed) {
                        cursor.skip()
                    } else if (stopItems) {
                        cursor.skip()
                    } else {
                        cursor.item(image, live = cursor.local() == "liveItem")?.let { episode ->
                            val published = episode.publishedAtEpochMs
                            val tooOld = publishedAfterEpochMs != null && published != null && published < publishedAfterEpochMs
                            if (tooOld) {
                                if (seenNewer) stopItems = true
                            } else {
                                if (published != null) seenNewer = true
                                episodes += episode
                                if (maxEpisodes != Int.MAX_VALUE && episodes.size > maxEpisodes * 2) {
                                    trim(episodes, maxEpisodes)
                                }
                            }
                        }
                    }
                    else -> if (seenFeed) cursor.skip()
                }
            }
            event = if (xml.eventType == XmlPullParser.END_DOCUMENT) XmlPullParser.END_DOCUMENT else xml.next()
        }
        if (!seenFeed) throw FeedParseException("Kein RSS-Channel im Feed")
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
            funding = funding,
            locked = locked,
            value = value,
            valueMethod = valueMethod,
            valueSuggested = valueSuggested,
            location = location,
            license = license,
            txt = txt,
            trailers = trailers,
            podroll = podroll,
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

    private class Cursor(private val xml: XmlPullParser) {
        fun local(): String = xml.name ?: ""

        fun item(channelImage: String?, live: Boolean = false): ParsedEpisode? {
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
            val soundbites = mutableListOf<Soundbite>()
            val value = mutableListOf<ValueRecipient>()
            val timeSplits = mutableListOf<ValueTimeSplit>()
            var season: Int? = null
            var episodeNumber: String? = null
            var bestBitrate = -1
            val alternates = mutableListOf<AlternateSource>()
            var location: PodLocation? = null
            var license: PodLicense? = null
            val txt = mutableListOf<PodTxt>()
            var valueMethod: String? = null
            var valueSuggested: String? = null
            val liveStatus = attr("status")
            var depth = 1
            while (depth > 0) {
                when (nextOrEnd()) {
                    XmlPullParser.START_TAG -> {
                        if (depth != 1) {
                            depth++
                            continue
                        }
                        when (local()) {
                            "title" -> title = text()
                            "encoded" -> encoded = text()
                            "description", "subtitle" -> description = text()
                            "summary" -> summary = text()
                            "guid", "id" -> guid = text().ifBlank { null }
                            "duration" -> duration = text()
                            "pubDate", "published" -> pubDate = text()
                            "updated" -> if (pubDate == null) pubDate = text()
                            "enclosure" -> {
                                val bitrate = attr("bitrate")?.toIntOrNull() ?: 0
                                if (bitrate >= bestBitrate || enclosureUrl == null) {
                                    enclosureUrl = attr("url")
                                    mimeType = attr("type")
                                    bestBitrate = bitrate
                                }
                                skip()
                            }
                            "alternateEnclosure" -> {
                                val choice = alternateEnclosure()
                                if (choice != null) {
                                    alternates += AlternateSource(choice.url, choice.type, choice.bitrate)
                                    if (choice.bitrate >= bestBitrate || enclosureUrl == null) {
                                        enclosureUrl = choice.url
                                        mimeType = choice.type
                                        bestBitrate = choice.bitrate
                                    }
                                }
                            }
                            "soundbite" -> soundbite()?.let { soundbites += it }
                            "season" -> season = text().toIntOrNull()
                            "episode" -> episodeNumber = text().ifBlank { null }
                            "value" -> {
                                val block = valueBlock()
                                value += block.recipients
                                valueMethod = block.method
                                valueSuggested = block.suggested
                            }
                            "valueTimeSplit" -> valueTimeSplit()?.let { timeSplits += it }
                            "location" -> if (location == null) location = location()
                            "license" -> if (license == null) license = license()
                            "txt" -> txtTag()?.let { txt += it }
                            "link" -> {
                                val href = attr("href")
                                if (attr("rel") == "enclosure" && !href.isNullOrBlank()) {
                                    enclosureUrl = href
                                    mimeType = attr("type") ?: mimeType
                                }
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
                            "image", "icon" -> image = image()
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
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            val url = enclosureUrl ?: return null
            val rawHtml = encoded.ifBlank { description }.ifBlank { summary }
            return ParsedEpisode(
                title = title.ifBlank { "Ohne Titel" },
                description = plainText(rawHtml),
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
                descriptionHtml = rawHtml.take(8_000),
                season = season,
                episodeNumber = episodeNumber,
                live = live || liveStatus.equals("live", ignoreCase = true),
                liveStatus = liveStatus,
                soundbites = soundbites,
                value = value,
                valueMethod = valueMethod,
                valueSuggested = valueSuggested,
                valueTimeSplits = timeSplits,
                alternates = if (enclosureUrl == null) alternates else {
                    if (alternates.any { it.url == enclosureUrl }) alternates
                    else listOf(AlternateSource(enclosureUrl, mimeType, bestBitrate.coerceAtLeast(0))) + alternates
                },
                location = location,
                license = license,
                txt = txt,
            )
        }

        fun funding(): FundingLink? {
            val url = attr("url") ?: run {
                skip()
                return null
            }
            val title = text().ifBlank { "Unterstützen" }
            return FundingLink(url, title)
        }

        data class ValueBlock(val method: String?, val suggested: String?, val recipients: List<ValueRecipient>)

        fun valueBlock(): ValueBlock = ValueBlock(attr("method"), attr("suggested"), valueRecipients())

        fun podroll(): List<RemoteItem> {
            val found = mutableListOf<RemoteItem>()
            var depth = 1
            while (depth > 0) {
                when (nextOrEnd()) {
                    XmlPullParser.START_TAG -> {
                        if (depth == 1 && local() == "remoteItem") found += remoteItem() else depth++
                    }
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.END_DOCUMENT -> return found
                }
            }
            return found
        }

        fun remoteItem(): RemoteItem {
            val item = RemoteItem(
                feedGuid = attr("feedGuid"),
                feedUrl = attr("feedUrl"),
                itemGuid = attr("itemGuid"),
                title = attr("title"),
                medium = attr("medium"),
            )
            skip()
            return item
        }

        fun trailer(): Trailer? {
            val url = attr("url") ?: run {
                skip()
                return null
            }
            val season = attr("season")
            val title = text().ifBlank { "Trailer" }
            return Trailer(url, title, season)
        }

        fun location(): PodLocation? {
            val geo = attr("geo")
            val osm = attr("osm")
            val name = text()
            if (name.isBlank() && geo == null && osm == null) return null
            return PodLocation(name, geo, osm)
        }

        fun license(): PodLicense? {
            val url = attr("url").orEmpty()
            val name = text()
            if (url.isBlank() && name.isBlank()) return null
            return PodLicense(url, name)
        }

        fun txtTag(): PodTxt? {
            val purpose = attr("purpose")
            val value = text()
            if (value.isBlank()) return null
            return PodTxt(purpose, value)
        }

        fun valueTimeSplit(): ValueTimeSplit? {
            val start = attr("startTime")?.toDoubleOrNull() ?: run {
                skip()
                return null
            }
            val duration = attr("duration")?.toDoubleOrNull() ?: 0.0
            val percent = attr("remotePercentage")?.toIntOrNull() ?: 100
            return ValueTimeSplit(
                startMs = (start * 1000).toLong(),
                durationMs = (duration * 1000).toLong(),
                remotePercentage = percent.coerceIn(0, 100),
                recipients = valueRecipients(),
            )
        }

        fun valueRecipients(): List<ValueRecipient> {
            val found = mutableListOf<ValueRecipient>()
            var depth = 1
            while (depth > 0) {
                when (nextOrEnd()) {
                    XmlPullParser.START_TAG -> {
                        if (depth == 1 && local() == "valueRecipient") {
                            val recipient = ValueRecipient(
                                name = attr("name"),
                                type = attr("type"),
                                address = attr("address"),
                                split = attr("split")?.toIntOrNull(),
                                fee = attr("fee").equals("true", ignoreCase = true),
                                customKey = attr("customKey")?.toLongOrNull(),
                                customValue = attr("customValue"),
                            )
                            if (!recipient.address.isNullOrBlank() || !recipient.name.isNullOrBlank()) found += recipient
                            skip()
                        } else {
                            depth++
                        }
                    }
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.END_DOCUMENT -> return found
                }
            }
            return found
        }

        private fun soundbite(): Soundbite? {
            val start = attr("startTime")?.toDoubleOrNull() ?: run {
                skip()
                return null
            }
            val duration = attr("duration")?.toDoubleOrNull()
            val title = text().ifBlank { "Soundbite" }
            return Soundbite((start * 1000).toLong(), duration?.let { (it * 1000).toLong() }, title)
        }

        private data class EnclosureChoice(val url: String, val type: String?, val bitrate: Int)

        private fun alternateEnclosure(): EnclosureChoice? {
            val type = attr("type")
            val bitrate = attr("bitrate")?.toIntOrNull() ?: 0
            var uri: String? = null
            var depth = 1
            while (depth > 0) {
                when (nextOrEnd()) {
                    XmlPullParser.START_TAG -> {
                        if (depth == 1 && local() == "source") {
                            uri = uri ?: attr("uri")
                            skip()
                        } else {
                            depth++
                        }
                    }
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            val url = uri ?: return null
            return EnclosureChoice(url, type, bitrate)
        }

        fun link(): String? {
            val href = attr("href")
            return if (!href.isNullOrBlank()) {
                skip()
                href
            } else {
                text().ifBlank { null }
            }
        }

        private fun readChapters(into: MutableList<Chapter>, onUrl: (String?, String?) -> Unit) {
            onUrl(attr("url"), attr("type"))
            var depth = 1
            while (depth > 0) {
                when (nextOrEnd()) {
                    XmlPullParser.START_TAG -> {
                        if (depth == 1 && local() == "chapter") {
                            chapter()?.let { into += it }
                        } else {
                            depth++
                        }
                    }
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.END_DOCUMENT -> return
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
            while (depth > 0) {
                when (nextOrEnd()) {
                    XmlPullParser.START_TAG -> {
                        if (depth == 1 && local() == "url") url = text() else depth++
                    }
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.END_DOCUMENT -> return url?.ifBlank { null }
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
            while (depth > 0) {
                when (nextOrEnd()) {
                    XmlPullParser.TEXT -> if (depth == 1 && sb.length < MAX_TEXT_CHARS) {
                        val chunk = xml.text.orEmpty()
                        val room = MAX_TEXT_CHARS - sb.length
                        if (chunk.length <= room) sb.append(chunk) else sb.append(chunk, 0, room)
                    }
                    XmlPullParser.START_TAG -> depth++
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
                }
            }
            return sb.toString().trim()
        }

        fun skip() {
            var depth = 1
            while (depth > 0) {
                when (nextOrEnd()) {
                    XmlPullParser.START_TAG -> depth++
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.END_DOCUMENT -> return
                }
            }
        }

        fun attr(name: String): String? {
            for (i in 0 until xml.attributeCount) {
                val local = xml.getAttributeName(i)
                if (local == name) return xml.getAttributeValue(i)?.trim()?.ifEmpty { null }
            }
            return null
        }

        private fun nextOrEnd(): Int =
            if (xml.eventType == XmlPullParser.END_DOCUMENT) XmlPullParser.END_DOCUMENT else xml.next()
    }

    companion object {
        /** Optional safety cap. The app limits by [EpisodeHorizon] instead. */
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

private fun defaultPullParser(): XmlPullParser = try {
    val factory = XmlPullParserFactory.newInstance()
    factory.isNamespaceAware = true
    factory.newPullParser()
} catch (_: Exception) {
    Class.forName("org.kxml2.io.KXmlParser").getDeclaredConstructor().newInstance() as XmlPullParser
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
