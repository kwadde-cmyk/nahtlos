package de.jenz.nahtlos.automotive

import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.core.sync.SyncClient
import de.jenz.nahtlos.core.sync.SyncRepository
import de.jenz.nahtlos.model.PairPollResponse
import de.jenz.nahtlos.sync.PairingLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CarPairUi(
    val phase: String = "idle",
    val code: String = "",
    val link: String = "",
    val message: String = "",
)

@HiltViewModel
class CarPairViewModel @Inject constructor(
    private val api: SyncClient,
    private val settings: SettingsStore,
    private val sync: SyncRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(CarPairUi())
    val ui: StateFlow<CarPairUi> = _ui.asStateFlow()
    private var job: Job? = null

    fun start(serverUrl: String) {
        job?.cancel()
        job = viewModelScope.launch {
            _ui.value = CarPairUi(phase = "waiting", message = "Code wird geholt …")
            try {
                val started = api.pairStart(serverUrl)
                val link = PairingLink.encode(serverUrl, started.code)
                _ui.value = CarPairUi(
                    phase = "waiting",
                    code = started.code,
                    link = link,
                    message = "Handy auf den Code halten oder den Code dort eintippen.",
                )
                val deadline = System.currentTimeMillis() + started.expiresIn * 1000L + 70_000L
                while (System.currentTimeMillis() < deadline) {
                    delay(2_000)
                    val poll = api.pairPoll(serverUrl, started.pairingId)
                    when (poll.status) {
                        PairPollResponse.APPROVED -> {
                            val token = poll.token
                            if (token.isNullOrBlank()) {
                                _ui.value = CarPairUi(phase = "error", message = "Der Server hat keinen Token geliefert.")
                                return@launch
                            }
                            val current = settings.read()
                            settings.save(
                                serverUrl = serverUrl,
                                token = token,
                                bufferAheadMinutes = current.bufferAheadMinutes,
                                downloadOnUnmeteredOnly = current.downloadOnUnmeteredOnly,
                                downloadLimitMb = current.downloadLimitMb,
                                deletePlayedAfterHours = current.deletePlayedAfterHours,
                            )
                            sync.pullAndMerge(force = true)
                            _ui.value = CarPairUi(phase = "done", code = started.code, message = "Gekoppelt. Die Position wird abgeglichen.")
                            return@launch
                        }
                        PairPollResponse.EXPIRED, PairPollResponse.CONSUMED -> {
                            _ui.value = CarPairUi(phase = "error", message = "Der Code ist abgelaufen. Neuen Code holen.")
                            return@launch
                        }
                    }
                }
                _ui.value = CarPairUi(phase = "error", message = "Zeit abgelaufen. Neuen Code holen.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _ui.value = CarPairUi(phase = "error", message = error.message ?: "Koppeln fehlgeschlagen")
            }
        }
    }

    fun reset() {
        job?.cancel()
        _ui.value = CarPairUi()
    }
}

@Composable
fun CarPairing(
    serverUrl: String,
    configured: Boolean,
    viewModel: CarPairViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text("Mit Handy verbinden", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Nur im Parkzustand. Das Handy scannt den Code und gibt dieses Auto frei. Die Server-Adresse steckt im QR-Code, hier muss sie nicht abgetippt werden.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        if (serverUrl.isBlank()) {
            Text("Noch keine Server-Adresse. Unten eintragen oder NAHTLOS_DEFAULT_SERVER beim Bauen setzen.")
            return@Column
        }
        if (configured && ui.phase == "idle") {
            Text("Dieses Auto ist bereits gekoppelt.", modifier = Modifier.padding(bottom = 8.dp))
        }
        if (ui.phase == "idle") {
            Button(
                onClick = { viewModel.start(serverUrl) },
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text(if (configured) "QR-Code erneut anzeigen" else "QR-Code anzeigen") }
        } else {
            if (ui.link.isNotBlank()) {
                val image = remember(ui.link) { pairingQr(ui.link) }
                Image(
                    bitmap = image,
                    contentDescription = "QR-Code zum Koppeln",
                    modifier = Modifier
                        .size(280.dp)
                        .align(Alignment.CenterHorizontally)
                        .background(androidx.compose.ui.graphics.Color.White)
                        .padding(12.dp),
                )
            }
            if (ui.code.isNotBlank()) {
                Text(
                    ui.code,
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
            Text(ui.message, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.reset() },
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("Abbrechen") }
        }
    }
}

internal fun pairingQr(contents: String, size: Int = 640): androidx.compose.ui.graphics.ImageBitmap {
    val matrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    return bitmap.asImageBitmap()
}
