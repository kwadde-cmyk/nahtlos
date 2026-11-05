package de.jenz.nahtlos.automotive

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.core.sync.SyncClient
import de.jenz.nahtlos.core.wallet.BoostService
import de.jenz.nahtlos.model.WalletPollResponse
import de.jenz.nahtlos.wallet.SecretScrub
import de.jenz.nahtlos.wallet.WalletRelay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import de.jenz.nahtlos.wallet.Offer
import javax.inject.Inject

data class WalletReceiveUi(
    val phase: String = "idle",
    val link: String = "",
    val fingerprint: String = "",
    val senderName: String = "",
    val message: String = "",
)

@HiltViewModel
class WalletReceiveViewModel @Inject constructor(
    private val api: SyncClient,
    private val settings: SettingsStore,
    private val boosts: BoostService,
) : ViewModel() {
    private val _ui = MutableStateFlow(WalletReceiveUi())
    val ui: StateFlow<WalletReceiveUi> = _ui.asStateFlow()
    private var offer: Offer? = null
    private var secret: String? = null
    private var job: Job? = null

    fun start() {
        val current = settings.read()
        if (!current.configured) {
            _ui.value = WalletReceiveUi(
                phase = "error",
                message = "Zuerst das Auto koppeln. Ohne Geräte-Token nimmt der Server keine Sendung an.",
            )
            return
        }
        job?.cancel()
        wipe()
        val created = WalletRelay.newOffer(current.deviceId)
        offer = created
        _ui.value = WalletReceiveUi(
            phase = "waiting",
            link = created.qr(),
            fingerprint = created.fingerprint,
            message = "Am Handy denselben Code prüfen. Der Server sieht den Schlüssel nicht.",
        )
        job = viewModelScope.launch {
            try {
                val opened = api.openWalletRelay(current, created.sessionId)
                if (opened.sessionId != created.sessionId) {
                    fail("Der Server hat eine andere Sitzung geantwortet. Nichts gespeichert.")
                    return@launch
                }
                val deadline = System.currentTimeMillis() + WalletRelay.TTL_SECONDS * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(2_000)
                    val poll = api.pollWalletRelay(settings.read(), created.sessionId)
                    when (poll.status) {
                        WalletPollResponse.READY -> {
                            val cipher = poll.ciphertext
                            val openedSecret = if (cipher.isNullOrBlank()) null else {
                                runCatching { created.open(cipher) }.getOrNull()
                            }
                            if (openedSecret == null) {
                                fail("Die Sendung lässt sich nicht öffnen. Nichts gespeichert.")
                                return@launch
                            }
                            secret = openedSecret
                            _ui.value = WalletReceiveUi(
                                phase = "arrived",
                                fingerprint = created.fingerprint,
                                senderName = poll.senderName.orEmpty(),
                                message = "Verbindung empfangen. Übernehmen speichert sie verschlüsselt nur auf diesem Auto und teilt Guthaben und Tagesbudget dieser Wallet.",
                            )
                            return@launch
                        }
                        WalletPollResponse.EXPIRED, WalletPollResponse.GONE -> {
                            fail("Zeit abgelaufen. Neu starten.")
                            return@launch
                        }
                    }
                }
                fail("Zeit abgelaufen. Neu starten.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(SecretScrub.message(error.message) ?: "Abholen fehlgeschlagen")
            }
        }
    }

    fun confirm(driving: Boolean) {
        if (driving) {
            abandon("Das Auto steht nicht. Nichts gespeichert.")
            return
        }
        val uri = secret ?: return
        viewModelScope.launch {
            val message = runCatching { boosts.connect(uri) }.fold(
                { it },
                { SecretScrub.message(it.message) ?: "Verbinden fehlgeschlagen" },
            )
            wipe()
            _ui.value = WalletReceiveUi(phase = "done", message = message)
        }
    }

    fun abandon(message: String = "") {
        job?.cancel()
        wipe()
        _ui.value = if (message.isBlank()) WalletReceiveUi() else WalletReceiveUi(phase = "error", message = message)
    }

    private fun fail(message: String) {
        wipe()
        _ui.value = WalletReceiveUi(phase = "error", message = message)
    }

    private fun wipe() {
        secret = null
        offer?.wipe()
        offer = null
    }
}

@Composable
fun WalletReceive(driving: Boolean, viewModel: WalletReceiveViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        onDispose { viewModel.abandon() }
    }
    Column(Modifier.fillMaxWidth()) {
        Text("Wallet vom Handy", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Dieselbe Verbindung teilt Guthaben und Tagesbudget mit dem Handy. Fürs Auto kann auf dem Handy eine zweite Alby-Verbindung erzeugt und hierher geschickt werden, ohne sie abzutippen.",
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        when (ui.phase) {
            "waiting" -> {
                if (ui.link.isNotBlank()) {
                    Image(
                        bitmap = pairingQr(ui.link),
                        contentDescription = "QR-Code für die Wallet-Übergabe, ohne den Wallet-Link",
                        modifier = Modifier
                            .size(280.dp)
                            .align(Alignment.CenterHorizontally)
                            .background(Color.White)
                            .padding(12.dp),
                    )
                }
                Text(
                    ui.fingerprint,
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Text(ui.message, modifier = Modifier.padding(top = 8.dp))
            }
            "arrived" -> {
                Text(ui.message)
                if (ui.senderName.isNotBlank()) Text("Von ${ui.senderName}.", modifier = Modifier.padding(top = 8.dp))
                Button(
                    onClick = { viewModel.confirm(driving) },
                    enabled = !driving,
                    modifier = Modifier.fillMaxWidth().height(76.dp).padding(top = 16.dp),
                ) { Text("Übernehmen") }
                OutlinedButton(
                    onClick = { viewModel.abandon("Verworfen. Nichts gespeichert.") },
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp),
                ) { Text("Verwerfen") }
            }
            "done", "error" -> Text(ui.message)
            else -> {
                Button(
                    onClick = { viewModel.start() },
                    modifier = Modifier.fillMaxWidth().height(76.dp),
                ) { Text("Code anzeigen") }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
