package de.jenz.nahtlos.wallet

import de.jenz.nahtlos.model.ValueRecipient
import de.jenz.nahtlos.model.ValueTimeSplit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class SplitShare(
    val name: String,
    val type: String,
    val address: String,
    val sats: Long,
    val fee: Boolean,
    val customKey: Long?,
    val customValue: String?,
)

/**
 * Ganze Sats, Summe gleich dem eingegebenen Betrag.
 *
 * Gebühren-Empfänger (`fee`) bekommen zuerst `floor(Betrag * split / 100)`.
 * Übersteigt das den Betrag, wird bei den größten Anteilen satweise gekürzt.
 * Der Rest geht an die übrigen Empfänger im Verhältnis ihrer `split`-Werte:
 * `floor(Rest * split / Summe)`. Übrige Sats bekommen der Reihe nach die
 * Empfänger mit dem größten Divisionsrest (`Rest * split mod Summe`).
 * Fehlen Empfänger ohne Gebühr, gehen die übrigen Sats auf dieselbe Art an die Gebühren-Empfänger.
 * Anteile mit 0 Sats entfallen.
 *
 * Ein [ValueTimeSplit], dessen Fenster die Abspielposition enthält, bekommt
 * `remotePercentage` des Betrags. Der Rest bleibt beim Value-Block der Folge,
 * sonst des Kanals. Ohne passendes Fenster gewinnt die Folge vor dem Kanal.
 * Streaming pro Minute nutzt später denselben Plan mit [BoostAction.STREAM].
 */
object BoostSplits {
    fun plan(
        totalSats: Long,
        episode: List<ValueRecipient>,
        timeSplits: List<ValueTimeSplit>,
        channel: List<ValueRecipient>,
        positionMs: Long?,
    ): List<SplitShare> {
        if (totalSats <= 0) return emptyList()
        val base = episode.ifEmpty { channel }
        val window = positionMs?.let { position ->
            timeSplits.firstOrNull { split ->
                split.recipients.any(::payable) && position >= split.startMs && position < split.startMs + split.durationMs
            }
        }
        if (window == null) return allocate(totalSats, base)
        val remote = window.recipients.filter(::payable)
        val local = base.filter(::payable)
        if (remote.isEmpty()) return allocate(totalSats, base)
        if (local.isEmpty() || window.remotePercentage >= 100) return allocate(totalSats, remote)
        val percent = window.remotePercentage.coerceIn(0, 100)
        val remoteSats = totalSats * percent / 100
        val localSats = totalSats - remoteSats
        return allocate(remoteSats, remote) + allocate(localSats, local)
    }

    fun allocate(totalSats: Long, recipients: List<ValueRecipient>): List<SplitShare> {
        if (totalSats <= 0) return emptyList()
        val usable = recipients.filter(::payable)
        if (usable.isEmpty()) return emptyList()
        val fees = usable.filter { it.fee }
        val rest = usable.filterNot { it.fee }
        val feeSats = fees.map { totalSats * it.split!!.toLong() / 100 }.toMutableList()
        trimTo(feeSats, totalSats)
        val shares = if (rest.isEmpty()) {
            spread(feeSats, fees.map { it.split!!.toLong() }, modulus = 100, totalSats)
            feeSats
        } else {
            val weights = rest.map { it.split!!.toLong() }
            feeSats + portion(totalSats - feeSats.sum(), weights)
        }
        return (fees + rest).zip(shares).mapNotNull { (recipient, sats) ->
            if (sats <= 0L) null
            else SplitShare(
                name = recipient.name?.ifBlank { null } ?: recipient.address.orEmpty().take(10),
                type = recipient.type.orEmpty().lowercase(),
                address = recipient.address.orEmpty().trim(),
                sats = sats,
                fee = recipient.fee,
                customKey = recipient.customKey,
                customValue = recipient.customValue,
            )
        }
    }

    private fun payable(recipient: ValueRecipient): Boolean {
        val split = recipient.split ?: return false
        return split > 0 && !recipient.address.isNullOrBlank()
    }

    private fun portion(total: Long, weights: List<Long>): List<Long> {
        val sum = weights.sum()
        if (sum <= 0 || total <= 0) return List(weights.size) { 0L }
        val given = weights.map { total * it / sum }.toMutableList()
        spread(given, weights, modulus = sum, pool = total)
        return given
    }

    /** Gibt den noch fehlenden Rest an die größten Divisionsreste, bei Gleichstand an die frühere Position. */
    private fun spread(given: MutableList<Long>, weights: List<Long>, modulus: Long, pool: Long) {
        val missing = pool - given.sum()
        if (missing <= 0 || given.isEmpty() || modulus <= 0) return
        val ranked = weights.indices.sortedWith(
            compareByDescending<Int> { index -> (pool * weights[index]) % modulus }.thenBy { it },
        )
        var left = missing
        var cursor = 0
        while (left > 0) {
            given[ranked[cursor % ranked.size]]++
            left--
            cursor++
        }
    }

    private fun trimTo(values: MutableList<Long>, cap: Long) {
        while (values.sum() > cap) {
            values[values.indices.maxBy { values[it] }]--
        }
    }
}

enum class BoostAction { BOOST, STREAM }

data class BoostMeta(
    val action: BoostAction = BoostAction.BOOST,
    val podcast: String,
    val episode: String?,
    val feedUrl: String?,
    val feedGuid: String?,
    val episodeGuid: String?,
    val positionSec: Long?,
    val totalMsat: Long,
    val message: String?,
    val senderName: String?,
    val appVersion: String = "0.1.0",
)

data class TlvRecord(val type: Long, val valueHex: String)

object Boostagram {
    const val TLV = 7629169L
    const val APP = "Nahtlos"
    private val json = Json { explicitNulls = false; encodeDefaults = false }

    @Serializable
    private data class Body(
        val action: String,
        val app_name: String,
        val app_version: String,
        val value_msat: Long,
        val value_msat_total: Long,
        val url: String? = null,
        val podcast: String? = null,
        val episode: String? = null,
        val feedID: String? = null,
        val guid: String? = null,
        val episode_guid: String? = null,
        val ts: Long? = null,
        val message: String? = null,
        val sender_name: String? = null,
        val name: String? = null,
    )

    fun json(share: SplitShare, meta: BoostMeta): String = json.encodeToString(
        Body(
            action = if (meta.action == BoostAction.STREAM) "stream" else "boost",
            app_name = APP,
            app_version = meta.appVersion,
            value_msat = share.sats * 1000,
            value_msat_total = meta.totalMsat,
            url = meta.feedUrl?.ifBlank { null },
            podcast = meta.podcast.ifBlank { null },
            episode = meta.episode?.ifBlank { null },
            feedID = meta.feedGuid?.ifBlank { null },
            guid = meta.feedGuid?.ifBlank { null },
            episode_guid = meta.episodeGuid?.ifBlank { null },
            ts = meta.positionSec,
            message = meta.message?.ifBlank { null },
            sender_name = meta.senderName?.ifBlank { null },
            name = share.name.ifBlank { null },
        ),
    )

    fun records(share: SplitShare, meta: BoostMeta): List<TlvRecord> {
        val records = mutableListOf(TlvRecord(TLV, Bytes.hex(json(share, meta).toByteArray(Charsets.UTF_8))))
        val key = share.customKey
        val custom = share.customValue
        if (key != null && !custom.isNullOrBlank()) {
            records += TlvRecord(key, customHex(custom))
        }
        return records
    }

    private fun customHex(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.matches(Regex("[0-9a-fA-F]+")) && trimmed.length % 2 == 0) trimmed.lowercase()
        else Bytes.hex(trimmed.toByteArray(Charsets.UTF_8))
    }
}

object LnurlPay {
    fun invoice(address: String, amountMsat: Long, comment: String?, fetch: (String) -> String): String {
        val parts = address.trim().split("@")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw WalletException("LN-Adresse erwartet name@domain, bekommen: ${address.take(40)}")
        }
        val user = URLEncoder.encode(parts[0], StandardCharsets.UTF_8)
        val host = parts[1].trim()
        val meta = parse(fetch("https://$host/.well-known/lnurlp/$user"))
        meta.failure?.let { throw WalletException(it) }
        val callback = meta.callback ?: throw WalletException("LNURL ohne callback")
        val min = meta.minSendable ?: 1000
        val max = meta.maxSendable ?: Long.MAX_VALUE
        if (amountMsat < min || amountMsat > max) {
            throw WalletException("Betrag liegt außerhalb von ${min / 1000}–${max / 1000} sats")
        }
        val allowed = meta.commentAllowed ?: 0
        val joiner = if (callback.contains("?")) "&" else "?"
        val commentPart = if (allowed > 0 && !comment.isNullOrBlank()) {
            "&comment=" + URLEncoder.encode(comment.take(allowed), StandardCharsets.UTF_8)
        } else {
            ""
        }
        val invoice = parse(fetch("$callback${joiner}amount=$amountMsat$commentPart"))
        invoice.failure?.let { throw WalletException(it) }
        return invoice.pr ?: throw WalletException("LNURL hat keine Invoice geliefert")
    }

    /**
     * NIP-57: die LNURL-Antwort muss Zaps erlauben. Der signierte Zap-Request
     * geht als `nostr` an den Callback, die Invoice bleibt beim Aufrufer.
     */
    fun zapInvoice(address: String, amountMsat: Long, zapRequestJson: String, fetch: (String) -> String): String {
        val parts = address.trim().split("@")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw WalletException("LN-Adresse erwartet name@domain.")
        }
        val user = URLEncoder.encode(parts[0], StandardCharsets.UTF_8)
        val host = parts[1].trim()
        val meta = parse(fetch("https://$host/.well-known/lnurlp/$user"))
        meta.failure?.let { throw WalletException(it) }
        if (meta.allowsNostr != true || meta.nostrPubkey.isNullOrBlank()) {
            throw WalletException("Diese Adresse nimmt keine Zaps an.")
        }
        val callback = meta.callback ?: throw WalletException("LNURL ohne callback")
        val min = meta.minSendable ?: 1000
        val max = meta.maxSendable ?: Long.MAX_VALUE
        if (amountMsat < min || amountMsat > max) {
            throw WalletException("Betrag liegt außerhalb von ${min / 1000}–${max / 1000} sats")
        }
        val joiner = if (callback.contains("?")) "&" else "?"
        val nostr = URLEncoder.encode(zapRequestJson, StandardCharsets.UTF_8)
        val invoice = parse(fetch("$callback${joiner}amount=$amountMsat&nostr=$nostr"))
        invoice.failure?.let { throw WalletException(it) }
        return invoice.pr ?: throw WalletException("LNURL hat keine Invoice geliefert")
    }

    fun nostrPubkey(address: String, fetch: (String) -> String): String {
        val parts = address.trim().split("@")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw WalletException("LN-Adresse erwartet name@domain.")
        }
        val user = URLEncoder.encode(parts[0], StandardCharsets.UTF_8)
        val meta = parse(fetch("https://${parts[1].trim()}/.well-known/lnurlp/$user"))
        meta.failure?.let { throw WalletException(it) }
        if (meta.allowsNostr != true || meta.nostrPubkey.isNullOrBlank()) {
            throw WalletException("Diese Adresse nimmt keine Zaps an.")
        }
        return meta.nostrPubkey.lowercase()
    }

    fun allowsZap(body: String): Boolean {
        val meta = parse(body)
        return meta.allowsNostr == true && !meta.nostrPubkey.isNullOrBlank()
    }

    private fun parse(body: String): Reply = try {
        json.decodeFromString(body)
    } catch (error: Exception) {
        throw WalletException("LNURL-Antwort ist kein JSON")
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Reply(
        val callback: String? = null,
        val minSendable: Long? = null,
        val maxSendable: Long? = null,
        val commentAllowed: Int? = null,
        val allowsNostr: Boolean? = null,
        val nostrPubkey: String? = null,
        val pr: String? = null,
        val reason: String? = null,
        val status: String? = null,
    ) {
        val failure: String? get() = if (status.equals("ERROR", ignoreCase = true)) reason ?: "LNURL-Fehler" else null
    }
}

object NwcRequests {
    const val REQUEST = 23194
    const val RESPONSE = 23195
    const val INFO = 13194

    fun envelope(method: String, paramsJson: String): String =
        """{"method":${NostrEvent.jsonString(method)},"params":$paramsJson}"""

    fun payKeysend(amountMsat: Long, pubkey: String, preimageHex: String, records: List<TlvRecord>): String {
        val tlvs = records.joinToString(",") { record ->
            """{"type":${record.type},"value":${NostrEvent.jsonString(record.valueHex)}}"""
        }
        return """{"amount":$amountMsat,"pubkey":${NostrEvent.jsonString(pubkey)},"preimage":${NostrEvent.jsonString(preimageHex)},"tlv_records":[$tlvs]}"""
    }

    fun payInvoice(bolt11: String): String = """{"invoice":${NostrEvent.jsonString(bolt11)}}"""

    fun emptyParams(): String = "{}"
}
