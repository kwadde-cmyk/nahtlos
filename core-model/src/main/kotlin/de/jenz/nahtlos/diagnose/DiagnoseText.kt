package de.jenz.nahtlos.diagnose

import kotlinx.serialization.Serializable

@Serializable
data class DiagnoseUpload(val updatedAt: Long, val body: String)

@Serializable
data class DiagnoseNote(
    val deviceId: String,
    val updatedAt: Long,
    val body: String,
)

object DiagnoseText {
    const val MAX_CHARS = 20_000

    private val nwc = Regex("nostr\\+walletconnect://\\S+", RegexOption.IGNORE_CASE)
    private val invoice = Regex("\\blnbc[0-9a-z]+", RegexOption.IGNORE_CASE)
    private val bearer = Regex("(?i)(authorization:\\s*bearer\\s+)\\S+")
    private val tokenParam = Regex("(?i)((?:^|[?&\\s])(?:token|secret|access_token)=)[^\\s&]+")
    private val preimage = Regex("(?i)(preimage[=:]\\s*)[0-9a-f]{16,}")

    fun scrub(raw: String): String = raw
        .replace(nwc, "[nwc]")
        .replace(invoice, "[invoice]")
        .replace(bearer, "$1[token]")
        .replace(tokenParam, "$1[token]")
        .replace(preimage, "$1[entfernt]")
        .take(MAX_CHARS)

    /** Host und Pfad, ohne Schema, Nutzerinfo und Query. Für die Diagnose bei Source error. */
    fun publicLocation(url: String): String {
        val clean = scrub(url).substringBefore('#').substringBefore('?').trim()
        val rest = when {
            clean.startsWith("https://") -> clean.removePrefix("https://")
            clean.startsWith("http://") -> clean.removePrefix("http://")
            else -> return clean.take(180).ifBlank { "unbekannt" }
        }
        val authority = rest.substringBefore('/')
        val named = if ('@' in authority) authority.substringAfterLast('@') else authority
        val host = named.substringBefore(':')
        val path = rest.substringAfter('/', "")
        val location = if (path.isEmpty()) host else "$host/$path"
        return location.trimEnd('/').take(180).ifBlank { "unbekannt" }
    }

    fun reject(body: String): String? {
        if (body.length > MAX_CHARS) return "zu lang"
        val folded = body.lowercase()
        if ("nostr+walletconnect://" in folded) return "Wallet-Link"
        if (Regex("\\blnbc[0-9a-z]{8,}").containsMatchIn(folded)) return "Invoice"
        return null
    }
}
