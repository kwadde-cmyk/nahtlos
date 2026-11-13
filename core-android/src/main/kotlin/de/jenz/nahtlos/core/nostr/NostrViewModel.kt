package de.jenz.nahtlos.core.nostr

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jenz.nahtlos.core.wallet.BoostService
import de.jenz.nahtlos.nostr.CommentAuthor
import de.jenz.nahtlos.nostr.EpisodeComment
import de.jenz.nahtlos.nostr.PodcastNostr
import de.jenz.nahtlos.nostr.ZapSafety
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NostrUi(
    val loggedIn: Boolean = false,
    val name: String = "",
    val picture: String? = null,
    val pubkey: String = "",
    val amberInstalled: Boolean = false,
    val relays: String = "",
    val busy: Boolean = false,
    val notice: String = "",
    val carUri: String = "",
    val comments: List<EpisodeComment> = emptyList(),
    val authors: Map<String, CommentAuthor> = emptyMap(),
    val episodeLikes: Int = 0,
    val episodeZaps: Long = 0,
    val zap: ZapSafety = ZapSafety(),
    val loadedGuid: String = "",
) {
    override fun toString(): String = "NostrUi(loggedIn=$loggedIn, name=$name)"
}

@HiltViewModel
class NostrViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val nostr: NostrRepository,
    private val boosts: BoostService,
) : ViewModel() {
    private val amber = AmberSigner(context.applicationContext).also { AmberSignerHolder.signer = it }
    private val _state = MutableStateFlow(NostrUi())
    val state: StateFlow<NostrUi> = _state.asStateFlow()
    private var carJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        val id = nostr.identity()
        _state.value = _state.value.copy(
            loggedIn = id.loggedIn,
            name = id.name.ifBlank { id.userPubkey.take(8) },
            picture = id.picture,
            pubkey = id.userPubkey,
            amberInstalled = amber.installed(),
            relays = nostr.relayText(),
        )
    }

    fun login() = work {
        nostr.loginAmber(amber)
        "Angemeldet."
    }

    fun logout() {
        carJob?.cancel()
        nostr.logout()
        _state.value = NostrUi(amberInstalled = amber.installed(), relays = nostr.relayText())
    }

    fun saveRelays(text: String) {
        nostr.saveRelays(text)
        refresh()
    }

    fun approveCar(raw: String) = work {
        nostr.openCarCode(amber, raw)
        "Amber öffnet die Anmeldung fürs Auto."
    }

    fun startCarOffer() {
        carJob?.cancel()
        carJob = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = "Warte auf Amber am Handy.", carUri = "")
            try {
                nostr.connectCar { uri ->
                    _state.value = _state.value.copy(carUri = uri, notice = "QR am Handy scannen und in Amber erlauben.")
                }
                refresh()
                _state.value = _state.value.copy(busy = false, carUri = "", notice = "Dieses Auto spricht jetzt mit demselben Konto.")
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(busy = false, carUri = "", notice = error.nostrMessage())
            }
        }
    }

    fun cancelCarOffer() {
        carJob?.cancel()
        _state.value = _state.value.copy(busy = false, carUri = "", notice = "")
    }

    fun load(episodeGuid: String) {
        if (episodeGuid.isBlank()) return
        viewModelScope.launch {
            val loaded = try {
                nostr.load(episodeGuid)
            } catch (error: Exception) {
                _state.value = _state.value.copy(notice = error.nostrMessage())
                return@launch
            }
            _state.value = _state.value.copy(
                comments = loaded.thread.comments,
                authors = loaded.authors,
                episodeLikes = loaded.thread.episodeLikes,
                episodeZaps = loaded.thread.episodeZaps,
                loadedGuid = episodeGuid,
            )
        }
    }

    fun comment(
        episodeGuid: String,
        podcastGuid: String?,
        showTitle: String?,
        episodeTitle: String?,
        page: String?,
        text: String,
        parent: EpisodeComment? = null,
    ) = work {
        val draft = if (parent == null) {
            PodcastNostr.topLevelComment(episodeGuid, podcastGuid, showTitle, episodeTitle, page, text, now())
        } else {
            PodcastNostr.reply(
                episodeGuid, podcastGuid, page, parent.id, parent.pubkey, parent.kind, null, text, now(),
            )
        }
        nostr.publish(draft)
        load(episodeGuid)
        "Kommentar gesendet."
    }

    fun likeComment(episodeGuid: String, comment: EpisodeComment) = work {
        if (_state.value.pubkey in comment.likes) return@work "Schon geliked."
        nostr.publish(PodcastNostr.likeComment(comment.id, comment.pubkey, comment.kind, "", now()))
        load(episodeGuid)
        "Like gesendet."
    }

    fun likeEpisode(episodeGuid: String, podcastGuid: String?) = work {
        nostr.publish(PodcastNostr.likeEpisode(episodeGuid, podcastGuid, now()))
        load(episodeGuid)
        "Like gesendet."
    }

    fun zap(
        target: String,
        address: String,
        sats: Long,
        podcast: String,
        episode: String,
        episodeGuid: String?,
        podcastGuid: String?,
        eventId: String? = null,
        eventKind: Int? = null,
        eventAuthor: String? = null,
    ) {
        val (next, send) = _state.value.zap.tap(target)
        if (!send) {
            _state.value = _state.value.copy(zap = next, notice = "Schon gezappt. Nochmal tippen sendet erneut.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = "")
            try {
                val message = nostr.zap(
                    address, sats, "", podcast, episode, episodeGuid, podcastGuid, eventId, eventKind, eventAuthor,
                )
                _state.value = _state.value.copy(busy = false, notice = message, zap = ZapSafety().sent(target))
                episodeGuid?.let { load(it) }
            } catch (error: Exception) {
                _state.value = _state.value.copy(busy = false, notice = error.nostrMessage())
            }
        }
    }

    fun defaultSats(): Long = boosts.state.value.defaultSats

    fun clearNotice() {
        _state.value = _state.value.copy(notice = "")
    }

    private fun work(block: suspend () -> String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = "")
            try {
                val message = block()
                refresh()
                _state.value = _state.value.copy(busy = false, notice = message)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(busy = false, notice = error.nostrMessage())
            }
        }
    }

    private fun now(): Long = System.currentTimeMillis() / 1000
}
