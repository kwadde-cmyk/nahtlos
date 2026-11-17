package de.jenz.nahtlos.core.nostr

import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.core.wallet.StoredPayment
import de.jenz.nahtlos.nostr.PodcastNostr
import de.jenz.nahtlos.wallet.BoostText
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in: ein erfolgreicher Boost mit Nachricht wird zusätzlich als Kind 1111
 * über die bestehende Signatur (Amber oder die Warteschlange) veröffentlicht.
 * Ohne Schalter passiert nichts. Wallet-Daten gehen nicht in den Text.
 */
@Singleton
class BoostNostrComments @Inject constructor(
    private val nostr: NostrRepository,
    private val settings: SettingsStore,
) {
    suspend fun maybe(payment: StoredPayment) {
        if (!settings.boostPrefs().nostrComment) return
        val text = BoostText.message(payment.message)
        val guid = payment.episodeGuid?.trim().orEmpty()
        if (text.isBlank() || guid.isBlank()) return
        if (BoostText.secret(text) != null) return
        if (!nostr.identity().loggedIn) return
        val page = payment.feedUrl?.takeIf { it.startsWith("http") }
        nostr.publish(
            PodcastNostr.topLevelComment(
                episodeGuid = guid,
                podcastGuid = payment.feedGuid,
                showTitle = payment.podcast,
                episodeTitle = payment.episode,
                page = page,
                text = text,
                createdAt = System.currentTimeMillis() / 1000,
            ),
        )
    }
}
