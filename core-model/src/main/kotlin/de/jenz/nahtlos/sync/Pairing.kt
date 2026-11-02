package de.jenz.nahtlos.sync

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * User-facing pairing code. Ambiguous glyphs (0, O, 1, I, L) are left out
 * so a code read off the car screen can be typed on the phone.
 */
object PairingCodes {
    const val TTL_SECONDS = 300
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val allowed = ALPHABET.toSet()

    fun normalize(raw: String): String = raw.uppercase().filter { it in allowed }

    fun format(normalized: String): String {
        require(normalized.length == 6) { "Code muss 6 Zeichen haben" }
        return normalized.take(3) + "-" + normalized.drop(3)
    }

    fun isWellFormed(raw: String): Boolean = normalize(raw).length == 6
}

data class PairingTarget(
    val server: String,
    val code: String,
)

/** `nahtlos://pair?server=...&code=...` — the QR the car shows and the phone scans. */
object PairingLink {
    fun encode(server: String, code: String): String {
        val base = server.trim().trimEnd('/')
        val formatted = PairingCodes.format(PairingCodes.normalize(code))
        val serverParam = URLEncoder.encode(base, Charsets.UTF_8.name())
        val codeParam = URLEncoder.encode(formatted, Charsets.UTF_8.name())
        return "nahtlos://pair?server=$serverParam&code=$codeParam"
    }

    fun decode(raw: String): PairingTarget? {
        val text = raw.trim()
        if (!text.startsWith("nahtlos://pair")) return null
        val query = text.substringAfter('?', "")
        if (query.isEmpty()) return null
        val params = mutableMapOf<String, String>()
        for (part in query.split('&')) {
            val index = part.indexOf('=')
            if (index <= 0) continue
            val key = URLDecoder.decode(part.substring(0, index), Charsets.UTF_8.name())
            val value = URLDecoder.decode(part.substring(index + 1), Charsets.UTF_8.name())
            params[key] = value
        }
        val server = params["server"]?.trim()?.trimEnd('/') ?: return null
        val code = params["code"] ?: return null
        if (!server.startsWith("http://") && !server.startsWith("https://")) return null
        if (!PairingCodes.isWellFormed(code)) return null
        return PairingTarget(server, PairingCodes.format(PairingCodes.normalize(code)))
    }
}
