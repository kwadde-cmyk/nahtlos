package de.jenz.nahtlos.sync

import de.jenz.nahtlos.wallet.BoostText
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
data class BoostRecipientSync(
    val name: String,
    val address: String,
    val sats: Long,
    val status: String,
)

@Serializable
data class BoostSyncItem(
    val id: String,
    val atMs: Long,
    val updatedAt: Long,
    val totalSats: Long,
    val podcast: String,
    val episode: String,
    val recipients: List<BoostRecipientSync>,
    val message: String = "",
    val senderName: String = "",
)

@Serializable
data class BoostBatchRequest(val items: List<BoostSyncItem>)

object BoostSync {
    const val OUTCOME_OK = "Boost gesendet"
    const val OUTCOME_FAIL = "Boost nicht gesendet"

    fun outcome(anyFailed: Boolean): String = if (anyFailed) OUTCOME_FAIL else OUTCOME_OK

    fun acceptTap(alreadySending: Boolean): Boolean = !alreadySending

    fun formatWhen(epochMs: Long, zone: ZoneId): String {
        val formatter = DateTimeFormatter.ofPattern("d. MMM yyyy, HH:mm", Locale.GERMAN)
        return Instant.ofEpochMilli(epochMs).atZone(zone).format(formatter)
    }

    fun rejectReason(item: BoostSyncItem): String? {
        if (item.id.isBlank() || item.id.length > 80) return "id fehlt"
        if (item.atMs <= 0L || item.updatedAt <= 0L) return "Zeit fehlt"
        if (item.totalSats < 0 || item.recipients.any { it.sats < 0 }) return "Betrag fehlt"
        if (item.recipients.size > 40) return "zu viele Empfänger"
        if (item.message.length > BoostText.MESSAGE_MAX) return "Nachricht zu lang"
        if (item.senderName.length > BoostText.SENDER_MAX) return "Name zu lang"
        val blob = listOf(item.podcast, item.episode, item.id, item.message, item.senderName) +
            item.recipients.flatMap { listOf(it.name, it.address, it.status) }
        val joined = blob.joinToString(" ")
        BoostText.secret(joined)?.let { return it }
        if (item.recipients.any { it.address.length > 200 || it.name.length > 80 }) return "Empfänger zu lang"
        return null
    }

    /**
     * Der neuere Stand gewinnt. Eine leere Nachricht aus einem älteren Client
     * löscht keine schon gespeicherte Nachricht.
     */
    fun retainMessage(current: BoostSyncItem?, incoming: BoostSyncItem): BoostSyncItem {
        val chosen = prefer(current, incoming)
        if (current == null) return chosen
        return chosen.copy(
            message = chosen.message.ifBlank { current.message },
            senderName = chosen.senderName.ifBlank { current.senderName },
        )
    }

    /** Gleiche ID: der neuere updatedAt gewinnt. Der Verlierer bleibt unangetastet. */
    fun prefer(current: BoostSyncItem?, incoming: BoostSyncItem): BoostSyncItem {
        if (current == null) return incoming
        return if (incoming.updatedAt >= current.updatedAt) incoming else current
    }
}
