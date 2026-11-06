package de.jenz.nahtlos.core.wallet

import de.jenz.nahtlos.sync.BoostRecipientSync
import de.jenz.nahtlos.sync.BoostSyncItem

/**
 * Anzeige eines Boosts. Enthält weder den NWC-Link noch Preimage oder Invoice.
 */
data class BoostHistoryEntry(
    val id: String,
    val atMs: Long,
    val podcast: String,
    val episode: String,
    val totalSats: Long,
    val recipients: List<BoostRecipientLine>,
    val canRetry: Boolean,
)

data class BoostRecipientLine(
    val name: String,
    val address: String,
    val sats: Long,
    val status: String,
    val failed: Boolean,
)

fun StoredPayment.toSync(): BoostSyncItem = BoostSyncItem(
    id = id,
    atMs = atMs,
    updatedAt = if (updatedAt > 0) updatedAt else atMs,
    totalSats = totalSats,
    podcast = podcast,
    episode = episode,
    recipients = splits.map { split ->
        BoostRecipientSync(
            name = split.name.ifBlank { "Empfänger" },
            address = split.address,
            sats = split.sats,
            status = when (split.status) {
                "ok" -> "gesendet"
                "failed" -> "fehlgeschlagen"
                "unknown" -> "unklar"
                "sending" -> "unterwegs"
                else -> "vorbereitet"
            },
        )
    },
)

fun syncStatus(status: String): String = when (status) {
    "gesendet", "ok" -> "ok"
    "fehlgeschlagen", "failed" -> "failed"
    "unklar", "unknown" -> "unknown"
    "unterwegs", "sending" -> "sending"
    else -> "planned"
}

fun StoredPayment.toHistory(): BoostHistoryEntry = BoostHistoryEntry(
    id = id,
    atMs = atMs,
    podcast = podcast,
    episode = episode,
    totalSats = totalSats,
    canRetry = splits.any { it.status == "failed" },
    recipients = splits.map { split ->
        BoostRecipientLine(
            name = split.name.ifBlank { "Empfänger" },
            address = split.address,
            sats = split.sats,
            status = when (split.status) {
                "ok" -> "gesendet"
                "failed" -> "fehlgeschlagen"
                "unknown" -> "unklar"
                "sending" -> "unterwegs"
                else -> "vorbereitet"
            },
            failed = split.status == "failed" || split.status == "unknown",
        )
    },
)
