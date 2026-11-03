package de.jenz.nahtlos.wallet

import de.jenz.nahtlos.model.ValueRecipient
import de.jenz.nahtlos.model.ValueTimeSplit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class BoostPaymentTest {
    @Test
    fun feesComeOffTheTopAndRemaindersAreWholeSats() {
        val shares = BoostSplits.allocate(
            100,
            listOf(
                ValueRecipient(name = "Gebühr", type = "node", address = "03aa", split = 10, fee = true),
                ValueRecipient(name = "Ada", type = "node", address = "03bb", split = 60),
                ValueRecipient(name = "Bob", type = "node", address = "03cc", split = 40),
            ),
        )
        assertEquals(listOf(10L, 54L, 36L), shares.map { it.sats })
        assertEquals(100L, shares.sumOf { it.sats })
        assertTrue(shares.first().fee)

        val five = BoostSplits.allocate(
            5,
            listOf(
                ValueRecipient("A", "node", "01", 1),
                ValueRecipient("B", "node", "02", 1),
                ValueRecipient("C", "node", "03", 1),
            ),
        )
        assertEquals(listOf(2L, 2L, 1L), five.map { it.sats })
        assertEquals(5L, five.sumOf { it.sats })
    }

    @Test
    fun episodeBeatsChannelAndTimeSplitTakesItsPercentage() {
        val episode = listOf(ValueRecipient("Folge", "node", "aa", 100))
        val channel = listOf(ValueRecipient("Kanal", "node", "bb", 100))
        assertEquals("Folge", BoostSplits.plan(21, episode, emptyList(), channel, 0).single().name)
        assertEquals("Kanal", BoostSplits.plan(21, emptyList(), emptyList(), channel, null).single().name)

        val window = ValueTimeSplit(
            startMs = 60_000,
            durationMs = 30_000,
            remotePercentage = 80,
            recipients = listOf(ValueRecipient("Gast", "node", "cc", 100)),
        )
        val during = BoostSplits.plan(100, episode, listOf(window), channel, 70_000)
        assertEquals(listOf("Gast" to 80L, "Folge" to 20L), during.map { it.name to it.sats })
        val outside = BoostSplits.plan(100, episode, listOf(window), channel, 10_000)
        assertEquals("Folge", outside.single().name)
        val podcastLevel = BoostSplits.plan(100, episode, listOf(window), channel, positionMs = null)
        assertEquals("Folge", podcastLevel.single().name)
    }

    @Test
    fun boostagramJsonAndTlvCarryThePodcastFields() {
        val share = SplitShare("Ada", "node", "03abc", 21, fee = false, customKey = 696969, customValue = "ff00")
        val meta = BoostMeta(
            action = BoostAction.BOOST,
            podcast = "Kabine",
            episode = "Folge 1",
            feedUrl = "https://example.com/feed.xml",
            feedGuid = "guid-feed",
            episodeGuid = "guid-ep",
            positionSec = 12,
            totalMsat = 210_000,
            message = "Danke",
            senderName = "Jenz",
        )
        val body = Boostagram.json(share, meta)
        val parsed = Json.parseToJsonElement(body).jsonObject
        assertEquals("boost", parsed.getValue("action").jsonPrimitive.content)
        assertEquals("Nahtlos", parsed.getValue("app_name").jsonPrimitive.content)
        assertEquals(21_000L, parsed.getValue("value_msat").jsonPrimitive.long)
        assertEquals(210_000L, parsed.getValue("value_msat_total").jsonPrimitive.long)
        assertEquals("guid-feed", parsed.getValue("feedID").jsonPrimitive.content)
        assertEquals("guid-ep", parsed.getValue("episode_guid").jsonPrimitive.content)
        assertEquals(12L, parsed.getValue("ts").jsonPrimitive.long)
        assertEquals("Danke", parsed.getValue("message").jsonPrimitive.content)
        val records = Boostagram.records(share, meta)
        assertEquals(7629169L, records[0].type)
        assertEquals(body, String(Bytes.parseHex(records[0].valueHex), Charsets.UTF_8))
        assertEquals(696969L, records[1].type)
        assertEquals("ff00", records[1].valueHex)
        val stream = Boostagram.json(share, meta.copy(action = BoostAction.STREAM, message = null))
        assertTrue(stream.contains("\"action\":\"stream\""))
        assertTrue(!stream.contains("message"))
    }

    @Test
    fun lnurlResolvesAgainstAFakeServerAndRespectsComments() {
        val pages = mutableMapOf(
            "https://walletofsatoshi.com/.well-known/lnurlp/ada" to
                """{"callback":"https://pay.example/lnurl","minSendable":1000,"maxSendable":1000000,"commentAllowed":10,"tag":"payRequest"}""",
            "https://pay.example/lnurl?amount=21000&comment=Hallo+Welt" to """{"pr":"lnbc210"}""",
            "https://pay.example/lnurl?amount=21000" to """{"pr":"lnbc-nocomment"}""",
        )
        val fetch: (String) -> String = { url -> pages[url] ?: error("unerwartet $url") }
        assertEquals("lnbc210", LnurlPay.invoice("ada@walletofsatoshi.com", 21_000, "Hallo Welt das ist zu lang", fetch))
        pages["https://quiet.example/.well-known/lnurlp/bob"] =
            """{"callback":"https://pay.example/lnurl","minSendable":1000,"maxSendable":1000000,"commentAllowed":0}"""
        assertEquals("lnbc-nocomment", LnurlPay.invoice("bob@quiet.example", 21_000, "Hallo", fetch))
        assertFails { LnurlPay.invoice("keine-adresse", 21_000, null, fetch) }
        val request = NwcRequests.payKeysend(21_000, "03ab", "aa", listOf(TlvRecord(7629169, "abcd")))
        assertTrue(request.contains("\"amount\":21000"))
        assertTrue(request.contains("\"type\":7629169"))
    }
}
