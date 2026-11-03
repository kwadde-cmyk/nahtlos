package de.jenz.nahtlos.core.wallet

import de.jenz.nahtlos.core.AppLog
import de.jenz.nahtlos.core.db.extras
import de.jenz.nahtlos.core.db.valueRecipients
import de.jenz.nahtlos.core.feed.FeedRepository
import de.jenz.nahtlos.model.ValueRecipient
import de.jenz.nahtlos.model.ValueTimeSplit
import de.jenz.nahtlos.wallet.BoostAction
import de.jenz.nahtlos.wallet.BoostMeta
import de.jenz.nahtlos.wallet.BoostSplits
import de.jenz.nahtlos.wallet.Boostagram
import de.jenz.nahtlos.wallet.Bytes
import de.jenz.nahtlos.wallet.LnurlPay
import de.jenz.nahtlos.wallet.NwcRequests
import de.jenz.nahtlos.wallet.SecretScrub
import de.jenz.nahtlos.wallet.SplitShare
import de.jenz.nahtlos.wallet.WalletException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class BoostInput(
    val podcastTitle: String,
    val episodeTitle: String?,
    val feedUrl: String?,
    val feedGuid: String?,
    val episodeGuid: String?,
    val positionMs: Long?,
    val episodeRecipients: List<ValueRecipient>,
    val timeSplits: List<ValueTimeSplit>,
    val channelRecipients: List<ValueRecipient>,
    val amountSats: Long,
    val message: String,
    val senderName: String,
)

@Singleton
class BoostService @Inject constructor(
    private val store: WalletStore,
    private val nwc: NwcClient,
    private val http: OkHttpClient,
    private val feeds: FeedRepository,
) {
    private val busy = AtomicBoolean(false)
    private val _state = MutableStateFlow(WalletUi())
    val state: StateFlow<WalletUi> = _state.asStateFlow()
    private val lnurl = http.newBuilder().callTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = try {
            healStuck()
            val uri = store.readUri()
            WalletUi(
                connected = uri != null,
                alias = store.alias(),
                balanceLabel = store.balanceLabel(),
                methods = store.methods(),
                senderName = store.senderName(),
                defaultSats = store.defaultSats(),
                dailyBudget = store.dailyBudget(),
                spentToday = store.spentToday(),
                history = store.history().map { it.toView() },
            )
        } catch (error: Exception) {
            WalletUi(storageError = "Die sichere Ablage ist nicht verfügbar. Der Wallet-Link wurde nicht gespeichert.")
        }
    }

    suspend fun connect(raw: String): String = guard {
        val uri = NwcUriSafe.parse(raw)
        val probe = withContext(Dispatchers.IO) { nwc.probe(uri) }
        store.saveUri(raw.trim())
        val balance = probe.balanceMsat?.let { formatSats(it) } ?: "Guthaben unbekannt"
        store.saveProbe(probe.alias, balance, probe.methods)
        val missing = listOf("pay_keysend", "pay_invoice").filter { it !in probe.methods && probe.methods.isNotEmpty() }
        val info = if (probe.infoEvent) "" else " Kein Info-Event (kind 13194), Methoden kommen aus get_info."
        val gap = if (missing.isEmpty()) "" else " Es fehlt: ${missing.joinToString()}."
        "Verbunden mit ${probe.alias}. $balance.$info$gap"
    }

    suspend fun disconnect() = guard {
        store.disconnect()
        "Wallet getrennt. Der Link ist vom Gerät gelöscht."
    }

    fun saveLimits(sender: String, defaultSats: Long, budget: Long) {
        store.saveLimits(sender, defaultSats, budget)
        refresh()
    }

    fun preview(input: BoostInput): List<SplitShare> = BoostSplits.plan(
        input.amountSats,
        input.episodeRecipients,
        input.timeSplits,
        input.channelRecipients,
        input.positionMs,
    )

    suspend fun send(input: BoostInput): PaymentView = guard {
        if (store.readUri() == null) throw WalletException("Kein Wallet verbunden.")
        val amount = input.amountSats
        if (amount < 1) throw WalletException("Der Betrag muss mindestens 1 sat sein.")
        val shares = preview(input)
        if (shares.isEmpty()) throw WalletException("Keine zahlbaren Empfänger in diesem Value-Block.")
        if (shares.sumOf { it.sats } != amount) {
            throw WalletException("Die Aufteilung ergibt ${shares.sumOf { it.sats }} statt $amount sats.")
        }
        val spent = store.spentToday()
        val budget = store.dailyBudget()
        if (spent + amount > budget) {
            throw WalletException("Tagesbudget erreicht: $spent von $budget sats. Es wurde nichts gesendet.")
        }
        if (input.senderName.isNotBlank()) store.saveLimits(input.senderName, store.defaultSats(), budget)
        val record = StoredPayment(
            id = UUID.randomUUID().toString(),
            atMs = System.currentTimeMillis(),
            podcast = input.podcastTitle,
            episode = input.episodeTitle.orEmpty(),
            feedUrl = input.feedUrl,
            feedGuid = input.feedGuid,
            episodeGuid = input.episodeGuid,
            positionSec = input.positionMs?.div(1000),
            message = input.message.trim(),
            senderName = input.senderName.ifBlank { store.senderName() },
            totalSats = amount,
            splits = shares.map { share ->
                StoredSplit(
                    name = share.name,
                    type = share.type,
                    address = share.address,
                    sats = share.sats,
                    preimage = Bytes.hex(Bytes.random(32)),
                    fee = share.fee,
                    customKey = share.customKey,
                    customValue = share.customValue,
                )
            },
        )
        store.upsert(record)
        pay(record)
    }

    suspend fun retry(id: String): PaymentView = guard {
        val record = store.history().firstOrNull { it.id == id }
            ?: throw WalletException("Diese Zahlung ist nicht mehr im Verlauf.")
        val need = record.splits.filter { it.status == "failed" }.sumOf { it.sats }
        if (store.spentToday() + need > store.dailyBudget()) {
            throw WalletException("Tagesbudget reicht für den Rest nicht. Es wurde nichts erneut gesendet.")
        }
        pay(record)
    }

    suspend fun quickBoost(episodeKey: String, positionMs: Long): PaymentView = guard {
        val episode = feeds.getEpisode(episodeKey) ?: throw WalletException("Keine Folge in der Wiedergabe.")
        val podcast = feeds.getPodcast(episode.podcastId) ?: throw WalletException("Der Podcast fehlt lokal.")
        val extra = episode.extras()
        sendInside(
            BoostInput(
                podcastTitle = podcast.title,
                episodeTitle = episode.title,
                feedUrl = podcast.feedUrl,
                feedGuid = podcast.podcastGuid,
                episodeGuid = episode.guid,
                positionMs = positionMs,
                episodeRecipients = extra.value,
                timeSplits = extra.valueTimeSplits,
                channelRecipients = podcast.valueRecipients(),
                amountSats = store.defaultSats(),
                message = "",
                senderName = store.senderName(),
            ),
        )
    }

    private fun healStuck() {
        store.history().forEach { payment ->
            if (payment.splits.none { it.sent && it.status == "sending" }) return@forEach
            store.upsert(
                payment.copy(
                    splits = payment.splits.map { split ->
                        if (split.sent && split.status == "sending") {
                            split.copy(status = "unknown", error = "Unterbrochen, nicht erneut gesendet.")
                        } else {
                            split
                        }
                    },
                ),
            )
        }
    }

    private suspend fun <T> guard(block: suspend () -> T): T {
        if (!busy.compareAndSet(false, true)) throw WalletException("Eine Zahlung läuft schon.")
        _state.value = _state.value.copy(busy = true)
        return try {
            block()
        } finally {
            busy.set(false)
            refresh()
        }
    }

    private suspend fun sendInside(input: BoostInput): PaymentView {
        if (store.readUri() == null) throw WalletException("Kein Wallet verbunden.")
        val amount = input.amountSats
        val shares = preview(input)
        if (shares.isEmpty()) throw WalletException("Keine zahlbaren Empfänger.")
        if (shares.sumOf { it.sats } != amount) {
            throw WalletException("Die Aufteilung ergibt ${shares.sumOf { it.sats }} statt $amount sats.")
        }
        val spent = store.spentToday()
        if (spent + amount > store.dailyBudget()) {
            throw WalletException("Tagesbudget erreicht: $spent von ${store.dailyBudget()} sats.")
        }
        val record = StoredPayment(
            id = UUID.randomUUID().toString(),
            atMs = System.currentTimeMillis(),
            podcast = input.podcastTitle,
            episode = input.episodeTitle.orEmpty(),
            feedUrl = input.feedUrl,
            feedGuid = input.feedGuid,
            episodeGuid = input.episodeGuid,
            positionSec = input.positionMs?.div(1000),
            message = "",
            senderName = input.senderName,
            totalSats = amount,
            splits = shares.map { share ->
                StoredSplit(
                    name = share.name,
                    type = share.type,
                    address = share.address,
                    sats = share.sats,
                    preimage = Bytes.hex(Bytes.random(32)),
                    fee = share.fee,
                    customKey = share.customKey,
                    customValue = share.customValue,
                )
            },
        )
        store.upsert(record)
        return pay(record)
    }

    private suspend fun pay(start: StoredPayment): PaymentView {
        val uri = store.readUri() ?: throw WalletException("Kein Wallet verbunden.")
        var current = start
        for (index in current.splits.indices) {
            val split = current.splits[index]
            if (split.status == "ok" || split.status == "unknown") continue
            if (split.sent && split.status == "sending") {
                current = update(current, index) {
                    it.copy(status = "unknown", error = "Unterbrochen, nicht erneut gesendet.")
                }
                continue
            }
            if (split.status != "failed" && split.status != "planned" && split.status != "sending") continue
            current = payOne(uri, current, index)
        }
        val failed = current.splits.count { it.status == "failed" || it.status == "unknown" }
        AppLog.record(
            "Wallet",
            "Boost ${current.totalSats} sats, ${current.splits.size} Empfänger, $failed ohne Erfolg.",
        )
        return current.toView()
    }

    private suspend fun payOne(uri: de.jenz.nahtlos.wallet.NwcUri, payment: StoredPayment, index: Int): StoredPayment {
        val split = payment.splits[index]
        val methods = store.methods()
        val needed = if (split.type == "lnaddress") "pay_invoice" else "pay_keysend"
        if (split.type != "node" && split.type != "lnaddress") {
            return update(payment, index) { it.copy(status = "failed", error = "Typ ${split.type.ifBlank { "?" }} wird nicht unterstützt") }
        }
        if (methods.isNotEmpty() && needed !in methods) {
            return update(payment, index) { it.copy(status = "failed", error = "Das Wallet kann $needed nicht") }
        }
        var current = payment
        var bolt11 = split.bolt11
        if (split.type == "lnaddress" && bolt11.isNullOrBlank()) {
            try {
                bolt11 = withContext(Dispatchers.IO) {
                    LnurlPay.invoice(split.address, split.sats * 1000, payment.message.takeIf { it.isNotBlank() }, ::fetchLnurl)
                }
                current = update(current, index) { it.copy(bolt11 = bolt11) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return update(current, index) {
                    it.copy(status = "failed", sent = false, error = SecretScrub.message(error.message, uri.secret))
                }
            }
        }
        current = update(current, index) { it.copy(sent = true, status = "sending", error = null) }
        return try {
            val result = withContext(Dispatchers.IO) {
                if (split.type == "lnaddress") {
                    nwc.call(uri, "pay_invoice", NwcRequests.payInvoice(bolt11!!), NwcClient.PAY_TIMEOUT_MS)
                } else {
                    val share = SplitShare(split.name, split.type, split.address, split.sats, split.fee, split.customKey, split.customValue)
                    val meta = BoostMeta(
                        action = BoostAction.BOOST,
                        podcast = payment.podcast,
                        episode = payment.episode.ifBlank { null },
                        feedUrl = payment.feedUrl,
                        feedGuid = payment.feedGuid,
                        episodeGuid = payment.episodeGuid,
                        positionSec = payment.positionSec,
                        totalMsat = payment.totalSats * 1000,
                        message = payment.message.ifBlank { null },
                        senderName = payment.senderName.ifBlank { null },
                    )
                    nwc.call(
                        uri,
                        "pay_keysend",
                        NwcRequests.payKeysend(split.sats * 1000, split.address, split.preimage, Boostagram.records(share, meta)),
                        NwcClient.PAY_TIMEOUT_MS,
                    )
                }
            }
            val fees = result.longish("fees_paid")
            update(current, index) { it.copy(status = "ok", error = null, feesMsat = fees) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RelayUnreachable) {
            update(current, index) { it.copy(sent = false, status = "failed", error = "Relay nicht erreichbar") }
        } catch (error: Exception) {
            val text = SecretScrub.message(error.message, uri.secret)
            val uncertain = text.contains("nicht rechtzeitig") || text.contains("abgebrochen")
            update(current, index) {
                if (uncertain) it.copy(status = "unknown", error = text) else it.copy(status = "failed", error = text)
            }
        }
    }

    private fun update(payment: StoredPayment, index: Int, block: (StoredSplit) -> StoredSplit): StoredPayment {
        val splits = payment.splits.toMutableList()
        splits[index] = block(splits[index])
        val next = payment.copy(splits = splits)
        store.upsert(next)
        return next
    }

    private fun fetchLnurl(url: String): String {
        val response = lnurl.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute()
        response.use {
            if (!it.isSuccessful) throw WalletException("LNURL HTTP ${it.code}")
            return it.body?.string().orEmpty()
        }
    }

    private fun JsonObject.longish(name: String): Long? {
        val primitive = this[name]?.jsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
    }
}

private object NwcUriSafe {
    fun parse(raw: String) = de.jenz.nahtlos.wallet.NwcUri.parse(raw)
}

fun formatSats(msat: Long): String {
    val whole = msat / 1000
    val rest = msat % 1000
    return if (rest == 0L) "$whole sats" else "$whole,${"%03d".format(rest)} sats"
}
