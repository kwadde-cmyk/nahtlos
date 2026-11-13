package de.jenz.nahtlos.wallet

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WalletException(message: String) : Exception(message)

object Bytes {
    private val random = SecureRandom()

    fun random(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun parseHex(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "ungerade Hex-Länge" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytes32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val stripped = if (raw.size > 32 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        require(stripped.size <= 32) { "Zahl passt nicht in 32 Byte" }
        val out = ByteArray(32)
        System.arraycopy(stripped, 0, out, 32 - stripped.size, stripped.size)
        return out
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }
}

/**
 * secp256k1 mit [BigInteger]. Reicht für NIP-44 und BIP-340-Signaturen,
 * ohne BouncyCastle ins Android-Dex zu ziehen.
 */
internal object Secp256k1 {
    val P: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val THREE = BigInteger.valueOf(3)
    private val TWO = BigInteger.valueOf(2)
    private val SEVEN = BigInteger.valueOf(7)

    class Point(val x: BigInteger, val y: BigInteger) {
        fun add(other: Point): Point {
            if (x == other.x) {
                if (y != other.y || y.signum() == 0) throw WalletException("Punktaddition ungültig")
                return dbl()
            }
            val lambda = other.y.subtract(y).multiply(other.x.subtract(x).modInverse(P)).mod(P)
            val x3 = lambda.multiply(lambda).subtract(x).subtract(other.x).mod(P)
            val y3 = lambda.multiply(x.subtract(x3)).subtract(y).mod(P)
            return Point(x3, y3)
        }

        fun dbl(): Point {
            val lambda = THREE.multiply(x).multiply(x).multiply(TWO.multiply(y).modInverse(P)).mod(P)
            val x3 = lambda.multiply(lambda).subtract(x).subtract(x).mod(P)
            val y3 = lambda.multiply(x.subtract(x3)).subtract(y).mod(P)
            return Point(x3, y3)
        }

        fun mul(scalar: BigInteger): Point {
            var result: Point? = null
            var addend = this
            var k = scalar
            while (k.signum() > 0) {
                if (k.testBit(0)) result = if (result == null) addend else result.add(addend)
                addend = addend.dbl()
                k = k.shiftRight(1)
            }
            return result ?: throw WalletException("Skalar ist 0")
        }
    }

    private val G = Point(GX, GY)

    fun scalar(hex: String): BigInteger {
        val value = BigInteger(hex.trim(), 16)
        if (value.signum() <= 0 || value >= N) throw WalletException("Schlüssel liegt außerhalb der Kurve")
        return value
    }

    fun liftX(xBytes: ByteArray, evenY: Boolean): Point {
        val x = BigInteger(1, xBytes)
        if (x < BigInteger.ZERO || x >= P) throw WalletException("Public Key ungültig")
        val y2 = x.modPow(THREE, P).add(SEVEN).mod(P)
        var y = y2.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.multiply(y).mod(P) != y2) throw WalletException("Public Key liegt nicht auf der Kurve")
        if (y.testBit(0) == evenY) y = P.subtract(y).mod(P)
        return Point(x, y)
    }

    fun decodePublic(pubHex: String): Point {
        val bytes = Bytes.parseHex(pubHex)
        return when (bytes.size) {
            32 -> liftX(bytes, evenY = true)
            33 -> {
                val prefix = bytes[0].toInt() and 0xff
                if (prefix != 2 && prefix != 3) throw WalletException("Public Key ungültig")
                liftX(bytes.copyOfRange(1, 33), evenY = prefix == 2)
            }
            else -> throw WalletException("Public Key ungültig")
        }
    }

    fun sharedX(secretHex: String, pubHex: String): ByteArray {
        val point = decodePublic(pubHex).mul(scalar(secretHex))
        return Bytes.bytes32(point.x)
    }

    fun publicKey(secretHex: String): String {
        val point = evenPoint(scalar(secretHex)).second
        return Bytes.hex(Bytes.bytes32(point.x))
    }

    fun verify(pubHex: String, message: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 64) return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = BigInteger(1, rBytes)
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        if (r.signum() <= 0 || r >= P || s.signum() <= 0 || s >= N) return false
        val px = try {
            Bytes.parseHex(pubHex)
        } catch (_: Exception) {
            return false
        }
        if (px.size != 32) return false
        val point = try {
            liftX(px, evenY = true)
        } catch (_: Exception) {
            return false
        }
        val e = BigInteger(1, tagged("BIP0340/challenge", Bytes.concat(rBytes, px, message))).mod(N)
        val left = try {
            G.mul(s)
        } catch (_: Exception) {
            return false
        }
        val combined = if (e.signum() == 0) {
            left
        } else {
            val right = try {
                point.mul(e)
            } catch (_: Exception) {
                return false
            }
            val negated = if (right.y.signum() == 0) right else Point(right.x, P.subtract(right.y).mod(P))
            try {
                left.add(negated)
            } catch (_: Exception) {
                return false
            }
        }
        if (combined.y.testBit(0)) return false
        return combined.x == r
    }

    fun sign(secretHex: String, message: ByteArray, aux: ByteArray): ByteArray {
        require(aux.size == 32)
        val (d, point) = evenPoint(scalar(secretHex))
        val px = Bytes.bytes32(point.x)
        val t = tagged("BIP0340/aux", aux)
        val dBytes = Bytes.bytes32(d)
        val rand = ByteArray(32) { index -> (dBytes[index].toInt() xor t[index].toInt()).toByte() }
        var k = BigInteger(1, tagged("BIP0340/nonce", Bytes.concat(rand, px, message))).mod(N)
        if (k.signum() == 0) throw WalletException("Signatur-Nonce ungültig")
        val rPoint = G.mul(k)
        if (rPoint.y.testBit(0)) k = N.subtract(k)
        val rx = Bytes.bytes32(rPoint.x)
        val challenge = BigInteger(1, tagged("BIP0340/challenge", Bytes.concat(rx, px, message))).mod(N)
        val s = k.add(challenge.multiply(d)).mod(N)
        return Bytes.concat(rx, Bytes.bytes32(s))
    }

    private fun evenPoint(secret: BigInteger): Pair<BigInteger, Point> {
        val point = G.mul(secret)
        return if (point.y.testBit(0)) {
            N.subtract(secret) to Point(point.x, P.subtract(point.y).mod(P))
        } else {
            secret to point
        }
    }

    private fun tagged(tag: String, message: ByteArray): ByteArray {
        val tagHash = Bytes.sha256(tag.toByteArray(Charsets.UTF_8))
        return Bytes.sha256(Bytes.concat(tagHash, tagHash, message))
    }
}

/** ChaCha20 nach RFC 8439, Zähler startet bei 0 (NIP-44). */
internal object ChaCha20 {
    fun xor(key: ByteArray, nonce: ByteArray, input: ByteArray, counter: Int = 0): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val out = ByteArray(input.size)
        var offset = 0
        var blockCounter = counter
        while (offset < input.size) {
            val block = block(key, nonce, blockCounter++)
            val length = minOf(64, input.size - offset)
            for (index in 0 until length) {
                out[offset + index] = (input[offset + index].toInt() xor (block[index].toInt() and 0xff)).toByte()
            }
            offset += length
        }
        return out
    }

    private fun block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (index in 0 until 8) state[4 + index] = little(key, index * 4)
        state[12] = counter
        state[13] = little(nonce, 0)
        state[14] = little(nonce, 4)
        state[15] = little(nonce, 8)
        val working = state.copyOf()
        repeat(10) {
            quarter(working, 0, 4, 8, 12)
            quarter(working, 1, 5, 9, 13)
            quarter(working, 2, 6, 10, 14)
            quarter(working, 3, 7, 11, 15)
            quarter(working, 0, 5, 10, 15)
            quarter(working, 1, 6, 11, 12)
            quarter(working, 2, 7, 8, 13)
            quarter(working, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (index in 0 until 16) store(out, index * 4, working[index] + state[index])
        return out
    }

    private fun quarter(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
        state[a] += state[b]
        state[d] = rotl(state[d] xor state[a], 16)
        state[c] += state[d]
        state[b] = rotl(state[b] xor state[c], 12)
        state[a] += state[b]
        state[d] = rotl(state[d] xor state[a], 8)
        state[c] += state[d]
        state[b] = rotl(state[b] xor state[c], 7)
    }

    private fun rotl(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))

    private fun little(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun store(out: ByteArray, offset: Int, value: Int) {
        out[offset] = value.toByte()
        out[offset + 1] = (value ushr 8).toByte()
        out[offset + 2] = (value ushr 16).toByte()
        out[offset + 3] = (value ushr 24).toByte()
    }
}

/**
 * NIP-44 Version 2. NIP-04 wird nicht implementiert.
 * Gesprächsschlüssel, Padding und ChaCha20 folgen den offiziellen Testvektoren.
 */
object Nip44 {
    private const val VERSION: Byte = 2
    private val SALT = "nip44-v2".toByteArray(Charsets.UTF_8)

    fun conversationKey(secretHex: String, remotePublicHex: String): ByteArray =
        hkdfExtract(SALT, Secp256k1.sharedX(secretHex, remotePublicHex))

    fun encrypt(conversationKey: ByteArray, plaintext: String, nonce: ByteArray): String {
        val utf8 = plaintext.toByteArray(Charsets.UTF_8)
        if (utf8.isEmpty() || utf8.size > 65535) throw WalletException("Text für NIP-44 hat eine ungültige Länge")
        if (nonce.size != 32) throw WalletException("NIP-44-Nonce muss 32 Byte haben")
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val padded = pad(utf8)
        val ciphertext = ChaCha20.xor(chachaKey, chachaNonce, padded, counter = 0)
        val mac = Bytes.hmacSha256(hmacKey, Bytes.concat(nonce, ciphertext))
        val payload = Bytes.concat(byteArrayOf(VERSION), nonce, ciphertext, mac)
        return java.util.Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(conversationKey: ByteArray, payload: String): String {
        val raw = decodeBase64(payload)
        if (raw.isEmpty() || raw[0] != VERSION) throw nip04()
        if (raw.size < 99) throw WalletException("NIP-44-Payload ist zu kurz")
        val nonce = raw.copyOfRange(1, 33)
        val mac = raw.copyOfRange(raw.size - 32, raw.size)
        val ciphertext = raw.copyOfRange(33, raw.size - 32)
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val expected = Bytes.hmacSha256(hmacKey, Bytes.concat(nonce, ciphertext))
        if (!MessageDigest.isEqual(mac, expected)) throw WalletException("NIP-44-Prüfsumme stimmt nicht")
        val padded = ChaCha20.xor(chachaKey, chachaNonce, ciphertext, counter = 0)
        return String(unpad(padded), Charsets.UTF_8)
    }

    fun encryptTo(secretHex: String, remotePublicHex: String, plaintext: String, nonce: ByteArray = Bytes.random(32)): String =
        encrypt(conversationKey(secretHex, remotePublicHex), plaintext, nonce)

    fun decryptFrom(secretHex: String, remotePublicHex: String, payload: String): String =
        decrypt(conversationKey(secretHex, remotePublicHex), payload)

    internal fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val expanded = hkdfExpand(conversationKey, nonce, 76)
        return Triple(
            expanded.copyOfRange(0, 32),
            expanded.copyOfRange(32, 44),
            expanded.copyOfRange(44, 76),
        )
    }

    internal fun paddedLength(length: Int): Int {
        if (length < 1) throw WalletException("Padding-Länge ungültig")
        if (length <= 32) return 32
        var nextPower = 1
        while (nextPower < length) nextPower = nextPower shl 1
        val chunk = maxOf(32, nextPower / 8)
        return chunk * ((length - 1) / chunk + 1)
    }

    private fun pad(plaintext: ByteArray): ByteArray {
        val data = paddedLength(plaintext.size)
        val out = ByteArray(2 + data)
        out[0] = ((plaintext.size ushr 8) and 0xff).toByte()
        out[1] = (plaintext.size and 0xff).toByte()
        System.arraycopy(plaintext, 0, out, 2, plaintext.size)
        return out
    }

    private fun unpad(padded: ByteArray): ByteArray {
        if (padded.size < 2) throw WalletException("NIP-44-Padding ungültig")
        val first = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        val prefix: Int
        val length: Int
        if (first == 0) {
            if (padded.size < 6) throw WalletException("NIP-44-Padding ungültig")
            length = ((padded[2].toInt() and 0xff) shl 24) or
                ((padded[3].toInt() and 0xff) shl 16) or
                ((padded[4].toInt() and 0xff) shl 8) or
                (padded[5].toInt() and 0xff)
            if (length < 65536) throw WalletException("NIP-44-Padding ungültig")
            prefix = 6
        } else {
            length = first
            prefix = 2
        }
        if (length < 1 || prefix + paddedLength(length) != padded.size) {
            throw WalletException("NIP-44-Padding ungültig")
        }
        return padded.copyOfRange(prefix, prefix + length)
    }

    private fun hkdfExtract(salt: ByteArray, input: ByteArray): ByteArray = Bytes.hmacSha256(salt, input)

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = Bytes.hmacSha256(prk, Bytes.concat(previous, info, byteArrayOf(counter.toByte())))
            val take = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, take)
            offset += take
            counter++
        }
        return out
    }

    private fun decodeBase64(value: String): ByteArray {
        val clean = value.trim().replace("\\s".toRegex(), "")
        if (clean.contains('?')) throw nip04()
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            0 -> clean
            else -> throw WalletException("NIP-44-Payload ist kein Base64")
        }
        return try {
            java.util.Base64.getDecoder().decode(padded)
        } catch (error: IllegalArgumentException) {
            throw WalletException("NIP-44-Payload ist kein Base64")
        }
    }

    private fun nip04(): WalletException =
        WalletException("Dieses Wallet spricht nur NIP-04. Nahtlos braucht NIP-44, zum Beispiel Alby Hub.")
}
