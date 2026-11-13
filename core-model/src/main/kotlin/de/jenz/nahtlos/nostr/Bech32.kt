package de.jenz.nahtlos.nostr

/** Bech32 (BIP-173), genug für den `lnurl`-Tag eines Zap-Requests. */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATORS = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    fun encode(hrp: String, data: ByteArray): String {
        val values = convertBits(data, 8, 5, pad = true)
        val combined = values + checksum(hrp, values)
        return hrp + "1" + combined.joinToString("") { CHARSET[it].toString() }
    }

    fun valid(bech: String): Boolean {
        if (bech.length < 8 || bech.length > 1024) return false
        val lower = bech.lowercase()
        if (lower.any { it.code < 33 || it.code > 126 }) return false
        val pos = lower.lastIndexOf('1')
        if (pos < 1 || pos + 7 > lower.length) return false
        val hrp = lower.substring(0, pos)
        val data = lower.substring(pos + 1).map { CHARSET.indexOf(it) }
        if (data.any { it < 0 }) return false
        return polymod(expand(hrp) + data) == 1
    }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): List<Int> {
        var acc = 0
        var bits = 0
        val max = (1 shl to) - 1
        val out = mutableListOf<Int>()
        for (value in data) {
            acc = (acc shl from) or (value.toInt() and 0xff)
            bits += from
            while (bits >= to) {
                bits -= to
                out += (acc shr bits) and max
            }
        }
        if (pad && bits > 0) out += (acc shl (to - bits)) and max
        return out
    }

    private fun expand(hrp: String): List<Int> =
        hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 }

    private fun polymod(values: List<Int>): Int {
        var chk = 1
        for (value in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor value
            for (index in GENERATORS.indices) {
                if (((top ushr index) and 1) != 0) chk = chk xor GENERATORS[index]
            }
        }
        return chk
    }

    private fun checksum(hrp: String, data: List<Int>): List<Int> {
        val mod = polymod(expand(hrp) + data + List(6) { 0 }) xor 1
        return List(6) { index -> (mod ushr (5 * (5 - index))) and 31 }
    }
}
