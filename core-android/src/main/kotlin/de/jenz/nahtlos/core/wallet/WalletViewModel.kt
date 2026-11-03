package de.jenz.nahtlos.core.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.nahtlos.wallet.SecretScrub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(private val boosts: BoostService) : ViewModel() {
    val state = boosts.state
    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val notice = _notice.asSharedFlow()
    private val _result = MutableStateFlow<PaymentView?>(null)
    val result = _result.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun connect(raw: String) = launch { tell { boosts.connect(raw) } }

    fun disconnect() = launch { tell { boosts.disconnect() } }

    fun saveLimits(sender: String, defaultSats: Long, budget: Long) {
        runCatching { boosts.saveLimits(sender, defaultSats, budget) }
            .onFailure { _notice.tryEmit(SecretScrub.message(it.message)) }
    }

    fun send(input: BoostInput) = launch {
        _result.value = null
        _error.value = null
        runCatching { boosts.send(input) }
            .onSuccess { _result.value = it }
            .onFailure { _error.value = SecretScrub.message(it.message) }
    }

    fun retry(id: String) = launch {
        runCatching { boosts.retry(id) }
            .onSuccess { _result.value = it }
            .onFailure { _notice.emit(SecretScrub.message(it.message)) }
    }

    fun clearResult() {
        _result.value = null
        _error.value = null
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _notice.emit(SecretScrub.message(error.message))
            }
        }
    }

    private suspend fun tell(block: suspend () -> String) {
        _notice.emit(runCatching { block() }.getOrElse { SecretScrub.message(it.message) })
    }
}
