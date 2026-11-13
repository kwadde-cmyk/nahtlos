package de.jenz.nahtlos.nostr

import de.jenz.nahtlos.wallet.SignedEvent

/**
 * Warteschlange auf dem Sync-Server. Nur öffentliche Entwürfe und fertige Events.
 * Kein nsec, kein Sitzungsgeheimnis, kein Wallet-Link.
 */
object NostrQueue {
    const val TTL_MS = 90_000L
    const val WAITING = "Warte aufs Handy. Amber signiert, sobald das Telefon wach ist."
    const val TIMEOUT = "Das Handy hat nicht signiert. Es wurde nichts veröffentlicht und nichts gezahlt."
    val KINDS = setOf(1, 7, 1111, 9734)

    fun reject(text: String): String? {
        if (text.length > 16_000) return "zu lang"
        val folded = text.lowercase()
        if ("nsec1" in folded) return "Privater Schlüssel"
        if ("bunker://" in folded) return "Bunker"
        if ("nostrconnect://" in folded) return "Nostr-Verbindung"
        if ("nostr+walletconnect://" in folded) return "Wallet-Link"
        if (Regex("\\blnbc[0-9a-z]{8,}").containsMatchIn(folded)) return "Invoice"
        return null
    }

    fun checkDraft(id: String, kind: Int, content: String, createdAt: Long): String? {
        if (!ID.matches(id)) return "Kennung ungültig"
        if (kind !in KINDS) return "Diese Art wird nicht signiert"
        if (content.length > 8_000) return "zu lang"
        if (kind == 1111 && content.isBlank()) return "Kommentar ist leer"
        if (createdAt <= 0L) return "Zeit fehlt"
        return reject(content)
    }

    fun sameRequest(kind: Int, content: String, createdAt: Long, tags: List<List<String>>, event: SignedEvent): Boolean {
        if (event.kind != kind || event.content != content || event.createdAt != createdAt) return false
        return tags.all { tag -> event.tags.any { it == tag } }
    }

    private val ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
}
