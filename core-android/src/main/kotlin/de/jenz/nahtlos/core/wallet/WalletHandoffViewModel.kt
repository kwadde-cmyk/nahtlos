package de.jenz.nahtlos.core.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.core.sync.SyncClient
import de.jenz.nahtlos.wallet.SecretScrub
import de.jenz.nahtlos.wallet.WalletException
import de.jenz.nahtlos.wallet.WalletRelay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WalletHandoffViewModel @Inject constructor(
    private val api: SyncClient,
    private val settings: SettingsStore,
    private val store: WalletStore,
) : ViewModel() {
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    fun send(qr: String, otherUri: String, confirmed: Boolean) {
        viewModelScope.launch {
            val offer = WalletRelay.parseOffer(qr)
            if (offer == null) {
                _message.value = "Das ist kein Code vom Auto. Dort „Wallet vom Handy holen“ öffnen und diesen QR scannen."
                return@launch
            }
            val current = settings.read()
            if (!current.configured) {
                _message.value = "Zuerst Server und Token koppeln. Die Wallet geht über denselben Server, verschlüsselt."
                return@launch
            }
            val uri = otherUri.trim().ifBlank { store.readRaw().orEmpty() }
            val cipher = try {
                WalletRelay.seal(offer, uri, if (confirmed) offer.fingerprint else "")
            } catch (error: WalletException) {
                _message.value = error.message ?: "Senden abgebrochen"
                return@launch
            }
            _message.value = try {
                api.putWalletCipher(current, offer.sessionId, cipher)
                "Gesendet. Im Auto übernehmen, solange es steht."
            } catch (error: Exception) {
                SecretScrub.message(error.message) ?: "Senden fehlgeschlagen"
            }
        }
    }
}
