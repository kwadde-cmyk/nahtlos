package de.jenz.nahtlos.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kind-13194-Info des Wallets. Fehlt das Tag `encryption`, gilt nach NIP-47 nur NIP-04.
 */
data class WalletInfo(
    val methods: List<String>,
    val encryption: List<String>,
) {
    val supportsNip44: Boolean get() = encryption.any { it == NwcProtocol.NIP44 }

    fun requireNip44() {
        if (supportsNip44) return
        throw WalletException(NwcProtocol.nip04Only())
    }

    companion object {
        fun parse(content: String, tags: List<List<String>>): WalletInfo {
            val methods = content.split(Regex("\\s+"))
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() && it != "notifications" }
            val encryptionTag = tags.firstOrNull { it.firstOrNull()?.equals("encryption", ignoreCase = true) == true }
            val encryption = encryptionTag
                ?.drop(1)
                ?.flatMap { it.split(Regex("\\s+")) }
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty()
                .ifEmpty { listOf("nip04") }
            return WalletInfo(methods, encryption)
        }
    }
}

object NwcProtocol {
    const val NIP44 = "nip44_v2"

    /**
     * Request (kind 23194). Verschlüsselt mit dem Secret aus dem Link an den Wallet-Pubkey,
     * nicht an den eigenen Schlüssel. Das Tag `encryption` sagt dem Wallet, NIP-44 zu nehmen.
     */
    fun request(
        uri: NwcUri,
        method: String,
        paramsJson: String,
        createdAt: Long,
        nonce: ByteArray = Bytes.random(32),
        aux: ByteArray = Bytes.random(32),
    ): SignedEvent {
        val plain = NwcRequests.envelope(method, paramsJson)
        val content = Nip44.encryptTo(uri.secret, uri.walletPubkey, plain, nonce)
        return NostrEvent.signed(
            secretHex = uri.secret,
            kind = NwcRequests.REQUEST,
            tags = listOf(
                listOf("p", uri.walletPubkey),
                listOf("encryption", NIP44),
            ),
            content = content,
            createdAt = createdAt,
            aux = aux,
        )
    }

    /** Antwort des Wallets öffnen: Secret aus dem Link, Gegenstelle ist der Wallet-Pubkey. */
    fun decryptResponse(uri: NwcUri, payload: String): String =
        Nip44.decryptFrom(uri.secret, uri.walletPubkey, payload)

    /**
     * @return deutscher Text, wenn die Antwort ein Fehlerobjekt ist, sonst null.
     */
    fun failureMessage(plain: String): String? {
        val obj = runCatching { Json.parseToJsonElement(plain).jsonObject }.getOrNull() ?: return null
        val error = obj["error"] ?: return null
        if (error is JsonNull) return null
        val type = obj["result_type"]?.jsonPrimitive?.contentOrNull
        val asObject = runCatching { error.jsonObject }.getOrNull()
        val code = asObject?.get("code")?.jsonPrimitive?.contentOrNull
        val message = asObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: error.jsonPrimitive.contentOrNull
        return german(type, code, message)
    }

    fun german(resultType: String?, code: String?, message: String?): String {
        val raw = message?.trim().orEmpty()
        val lower = raw.lowercase()
        if ("initialization vector" in lower || "failed to decrypt" in lower) {
            return "Das Wallet hat den Request als NIP-04 gelesen und konnte ihn nicht öffnen. Nahtlos sendet NIP-44 und setzt dafür das Tag encryption nip44_v2."
        }
        val summary = when (code?.uppercase()) {
            "RATE_LIMITED" -> "Zu viele Anfragen. Kurz warten und erneut versuchen."
            "NOT_IMPLEMENTED" -> "Das Wallet kann diese Aktion nicht."
            "INSUFFICIENT_BALANCE" -> "Das Guthaben reicht nicht."
            "QUOTA_EXCEEDED" -> "Das Ausgabenlimit des Wallets ist erreicht."
            "RESTRICTED" -> "Diese Verbindung darf das nicht."
            "UNAUTHORIZED" -> "Diese Verbindung ist nicht berechtigt."
            "INTERNAL" -> "Interner Fehler im Wallet."
            "UNSUPPORTED_ENCRYPTION" -> "Das Wallet akzeptiert NIP-44 nicht."
            "PAYMENT_FAILED" -> "Die Zahlung ist fehlgeschlagen."
            "NOT_FOUND" -> "Nicht gefunden."
            "OTHER" -> "Das Wallet meldet einen Fehler."
            null, "" -> "Das Wallet lehnt ab."
            else -> "Das Wallet lehnt ab (${code.uppercase()})."
        }
        val action = resultType?.takeIf { it.isNotBlank() }?.let { "$summary ($it)" } ?: summary
        val detail = raw.takeIf { it.isNotBlank() && it.length <= 180 && "exception" !in lower }
        return if (detail != null && !action.contains(detail)) "$action $detail" else action
    }

    fun nip04Only(): String =
        "Dieses Wallet spricht nur NIP-04. Nahtlos braucht NIP-44 (nip44_v2), zum Beispiel Alby Hub."

    fun missingInfo(): String =
        "Kein Info-Event (kind 13194) vom Wallet auf den Relays aus dem Link. Ohne dieses Event gilt nur NIP-04, das Nahtlos nicht spricht."
}
