package de.jenz.nahtlos.core.wallet

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.wallet.NwcUri
import de.jenz.nahtlos.wallet.WalletException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class StoredSplit(
    val name: String,
    val type: String,
    val address: String,
    val sats: Long,
    val preimage: String,
    val fee: Boolean = false,
    val customKey: Long? = null,
    val customValue: String? = null,
    val bolt11: String? = null,
    val sent: Boolean = false,
    val status: String = "planned",
    val error: String? = null,
    val feesMsat: Long? = null,
)

@Serializable
data class StoredPayment(
    val id: String,
    val atMs: Long,
    val podcast: String,
    val episode: String,
    val feedUrl: String? = null,
    val feedGuid: String? = null,
    val episodeGuid: String? = null,
    val positionSec: Long? = null,
    val message: String = "",
    val senderName: String = "",
    val totalSats: Long,
    val splits: List<StoredSplit>,
)

data class PaymentLine(val text: String, val error: Boolean = false)

data class PaymentView(
    val id: String,
    val atMs: Long,
    val podcast: String,
    val episode: String,
    val totalSats: Long,
    val lines: List<PaymentLine>,
    val routingMsat: Long,
    val canRetry: Boolean,
)

data class WalletUi(
    val connected: Boolean = false,
    val alias: String = "",
    val balanceLabel: String = "",
    val methods: List<String> = emptyList(),
    val senderName: String = "Nahtlos",
    val defaultSats: Long = 210,
    val dailyBudget: Long = 5_000,
    val spentToday: Long = 0,
    val history: List<PaymentView> = emptyList(),
    val busy: Boolean = false,
    val storageError: String? = null,
)

/**
 * NWC-Link und Zahlungsverlauf liegen nur in EncryptedSharedPreferences.
 * Nichts davon geht an den Sync-Server, in Logcat oder in die Diagnose.
 */
@Singleton
class WalletStore @Inject constructor(@ApplicationContext context: Context) {
    private val app = context.applicationContext
    private val lock = Any()
    private val json = NahtlosJson.json

    private val prefs: SharedPreferences by lazy { openEncrypted() }

    fun readUri(): NwcUri? = synchronized(lock) {
        val raw = prefs.getString(KEY_URI, null) ?: return null
        runCatching { NwcUri.parse(raw) }.getOrNull()
    }

    fun saveUri(raw: String) {
        NwcUri.parse(raw)
        synchronized(lock) { prefs.edit().putString(KEY_URI, raw.trim()).apply() }
    }

    fun disconnect() {
        synchronized(lock) {
            prefs.edit().remove(KEY_URI).remove(KEY_ALIAS).remove(KEY_METHODS).remove(KEY_BALANCE).apply()
        }
    }

    fun alias(): String = synchronized(lock) { prefs.getString(KEY_ALIAS, "").orEmpty() }

    fun balanceLabel(): String = synchronized(lock) { prefs.getString(KEY_BALANCE, "").orEmpty() }

    fun methods(): List<String> = synchronized(lock) {
        prefs.getString(KEY_METHODS, "").orEmpty().split(',').filter { it.isNotBlank() }
    }

    fun saveProbe(alias: String, balanceLabel: String, methods: List<String>) {
        synchronized(lock) {
            prefs.edit()
                .putString(KEY_ALIAS, alias.take(80))
                .putString(KEY_BALANCE, balanceLabel.take(40))
                .putString(KEY_METHODS, methods.joinToString(","))
                .apply()
        }
    }

    fun senderName(): String = synchronized(lock) { prefs.getString(KEY_SENDER, "Nahtlos").orEmpty().ifBlank { "Nahtlos" } }

    fun defaultSats(): Long = synchronized(lock) { prefs.getLong(KEY_DEFAULT, 210) }

    fun dailyBudget(): Long = synchronized(lock) { prefs.getLong(KEY_BUDGET, 5_000) }

    fun saveLimits(sender: String, defaultSats: Long, budget: Long) {
        synchronized(lock) {
            prefs.edit()
                .putString(KEY_SENDER, sender.trim().take(40).ifBlank { "Nahtlos" })
                .putLong(KEY_DEFAULT, defaultSats.coerceIn(1, 1_000_000))
                .putLong(KEY_BUDGET, budget.coerceIn(0, 10_000_000))
                .apply()
        }
    }

    fun history(): List<StoredPayment> = synchronized(lock) { decodeHistory() }

    fun upsert(payment: StoredPayment) {
        synchronized(lock) {
            val next = listOf(payment) + decodeHistory().filterNot { it.id == payment.id }
            prefs.edit().putString(KEY_HISTORY, json.encodeToString(next.take(40))).apply()
        }
    }

    fun spentToday(nowMs: Long = System.currentTimeMillis()): Long {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (nowMs < start) return 0
        return history().filter { it.atMs >= start }.flatMap { it.splits }
            .filter { it.status == "ok" || it.status == "unknown" || (it.status == "sending" && it.sent) }
            .sumOf { it.sats }
    }

    private fun decodeHistory(): List<StoredPayment> = try {
        val raw = prefs.getString(KEY_HISTORY, null).orEmpty()
        if (raw.isBlank()) emptyList() else json.decodeFromString(raw)
    } catch (error: Throwable) {
        emptyList()
    }

    private fun openEncrypted(): SharedPreferences = try {
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app,
            "nahtlos.wallet",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (error: Exception) {
        throw WalletException("Die sichere Ablage ist nicht verfügbar. Der Wallet-Link wurde nicht gespeichert.")
    }

    private companion object {
        const val KEY_URI = "uri"
        const val KEY_ALIAS = "alias"
        const val KEY_METHODS = "methods"
        const val KEY_BALANCE = "balance"
        const val KEY_SENDER = "sender"
        const val KEY_DEFAULT = "default_sats"
        const val KEY_BUDGET = "budget_sats"
        const val KEY_HISTORY = "history"
    }
}

fun StoredPayment.toView(): PaymentView {
    val routing = splits.sumOf { it.feesMsat ?: 0L }
    val lines = splits.map { split ->
        val state = when (split.status) {
            "ok" -> "gesendet"
            "failed" -> "fehlgeschlagen"
            "unknown" -> "unklar, nicht erneut gesendet"
            "sending" -> "unterwegs"
            else -> "vorbereitet"
        }
        val fee = if (split.fee) " · Gebühr" else ""
        val detail = split.error?.let { " · $it" }.orEmpty()
        PaymentLine("${split.name} · ${split.sats} sats · $state$fee$detail", error = split.status == "failed" || split.status == "unknown")
    }
    return PaymentView(
        id = id,
        atMs = atMs,
        podcast = podcast,
        episode = episode,
        totalSats = totalSats,
        lines = lines,
        routingMsat = routing,
        canRetry = splits.any { it.status == "failed" },
    )
}
