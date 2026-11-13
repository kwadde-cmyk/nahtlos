package de.jenz.nahtlos.core.nostr

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.jenz.nahtlos.core.AppLog
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.core.sync.SyncClient
import de.jenz.nahtlos.model.NostrSignStatus
import de.jenz.nahtlos.nostr.NostrDraft
import de.jenz.nahtlos.nostr.NostrException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Das Handy signiert die Entwürfe vom Auto. Nur der öffentliche Schlüssel geht an den Server.
 * Im Hintergrund nur, wenn Amber die Art schon dauerhaft erlaubt hat.
 */
@Singleton
class NostrPhoneSigner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val identity: IdentityStore,
    private val api: SyncClient,
    private val nostr: NostrRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                runCatching { pass(interactive = false) }
                delay(12_000)
            }
        }
        val request = PeriodicWorkRequestBuilder<NostrSignWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        scope.launch { runCatching { pass(interactive = false) } }
    }

    suspend fun pass(interactive: Boolean) = gate.withLock {
        val current = identity.read()
        val server = settings.read()
        if (!current.amber || !server.configured) return@withLock
        val signer = AmberSignerHolder.signer ?: return@withLock
        runCatching { api.putNostrIdentity(server, current.userPubkey, current.name, current.picture) }
        val pending = runCatching { api.listNostrPending(server) }.getOrElse { error ->
            AppLog.record("Nostr", "Warteschlange nicht lesbar: ${error.nostrMessage()}")
            return@withLock
        }
        if (pending.isEmpty()) return@withLock
        AppLog.record("Nostr", "Warteschlange: ${pending.size} offen.")
        for (item in pending) {
            if (item.status != NostrSignStatus.PENDING) continue
            val draft = NostrDraft(item.kind, item.content, item.tags, item.createdAt)
            val signed = try {
                signer.sign(current.signerPackage!!, current.userPubkey, draft, interactive)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val text = error.nostrMessage()
                if (text.contains("abgelehnt")) {
                    runCatching { api.failNostrSign(server, item.id, "Amber hat abgelehnt.") }
                }
                AppLog.record("Nostr", "Signieren ausstehend: $text")
                continue
            }
            if (draft.kind != 9734) {
                val published = runCatching { nostr.publishEvent(signed) }.isSuccess
                if (!published) {
                    AppLog.record("Nostr", "Kind ${draft.kind} noch nicht auf einem Relay.")
                    continue
                }
            }
            runCatching { api.putNostrSigned(server, item.id, signed.toJson()) }
                .onFailure { AppLog.record("Nostr", "Signatur nicht abgelegt: ${it.nostrMessage()}") }
        }
    }

    companion object {
        const val UNIQUE = "nahtlos-nostr-sign"
    }
}

class NostrSignWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val signer = EntryPointAccessors.fromApplication(applicationContext, NostrSignerEntryPoint::class.java).signer()
        return try {
            signer.pass(interactive = false)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: NostrException) {
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount >= 4) Result.success() else Result.retry()
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NostrSignerEntryPoint {
    fun signer(): NostrPhoneSigner
}
