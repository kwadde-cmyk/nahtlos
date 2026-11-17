package de.jenz.nahtlos.sync

import de.jenz.nahtlos.wallet.BoostText
import kotlinx.serialization.Serializable

/** Kurztexte und der Nostr-Schalter. Kein Wallet-Geheimnis. */
@Serializable
data class BoostPrefs(
    val phrases: List<String> = emptyList(),
    val nostrComment: Boolean = false,
    val updatedAt: Long = 0,
)

object BoostPrefsSync {
    fun normalize(raw: BoostPrefs): BoostPrefs = BoostPrefs(
        phrases = BoostText.phrases(raw.phrases),
        nostrComment = raw.nostrComment,
        updatedAt = raw.updatedAt.coerceAtLeast(0),
    )

    fun reject(prefs: BoostPrefs): String? {
        if (prefs.updatedAt < 0L) return "Zeit fehlt"
        if (prefs.phrases.size > BoostText.PHRASES_MAX) return "zu viele Kurztexte"
        if (prefs.phrases.any { it.length > BoostText.PHRASE_MAX }) return "Kurztext zu lang"
        return BoostText.secret(prefs.phrases.joinToString(" "))
    }

    /** Der neuere updatedAt gewinnt. Gleichstand lässt den bisherigen Stand stehen. */
    fun prefer(current: BoostPrefs, incoming: BoostPrefs): BoostPrefs {
        if (incoming.updatedAt <= 0L) return current
        if (incoming.updatedAt < current.updatedAt) return current
        if (incoming.updatedAt == current.updatedAt) return current
        return normalize(incoming)
    }
}
