package de.jenz.nahtlos.core.playback

import de.jenz.nahtlos.diagnose.DiagnoseText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Kurze Meldung für den Player, ohne Wallet-Daten. Bleibt stehen, bis die nächste Wiedergabe sie ablöst. */
object PlaybackNotice {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text.asStateFlow()

    fun show(message: String) {
        _text.value = DiagnoseText.scrub(message).take(180)
    }

    fun clear() {
        _text.value = null
    }
}
