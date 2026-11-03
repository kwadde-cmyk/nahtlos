package de.jenz.nahtlos.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletCryptoTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val vectors = json.parseToJsonElement(
        javaClass.getResource("/nip44.vectors.json")!!.readText(),
    ).jsonObject.getValue("v2").jsonObject

    @Test
    fun conversationKeysMatchTheOfficialVectors() {
        val rows = vectors.getValue("valid").jsonObject.getValue("get_conversation_key").jsonArray
        assertTrue(rows.size >= 30)
        rows.forEach { row ->
            val item = row.jsonObject
            val key = Nip44.conversationKey(item.string("sec1"), item.string("pub2"))
            assertEquals(item.string("conversation_key"), Bytes.hex(key))
        }
    }

    @Test
    fun messageKeysMatchTheOfficialVectors() {
        val block = vectors.getValue("valid").jsonObject.getValue("get_message_keys").jsonObject
        val conversation = Bytes.parseHex(block.string("conversation_key"))
        block.getValue("keys").jsonArray.forEach { row ->
            val item = row.jsonObject
            val (chacha, nonce, hmac) = Nip44.messageKeys(conversation, Bytes.parseHex(item.string("nonce")))
            assertEquals(item.string("chacha_key"), Bytes.hex(chacha))
            assertEquals(item.string("chacha_nonce"), Bytes.hex(nonce))
            assertEquals(item.string("hmac_key"), Bytes.hex(hmac))
        }
    }

    @Test
    fun paddedLengthsMatchTheOfficialVectors() {
        vectors.getValue("valid").jsonObject.getValue("calc_padded_len").jsonArray.forEach { row ->
            val pair = row.jsonArray
            assertEquals(pair[1].jsonPrimitive.int, Nip44.paddedLength(pair[0].jsonPrimitive.int))
        }
    }

    @Test
    fun encryptAndDecryptMatchTheOfficialVectors() {
        val rows = vectors.getValue("valid").jsonObject.getValue("encrypt_decrypt").jsonArray
        val first = rows.first().jsonObject
        assertEquals("a", first.string("plaintext"))
        assertEquals(
            "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb",
            first.string("payload"),
        )
        rows.forEach { row ->
            val item = row.jsonObject
            val key = Bytes.parseHex(item.string("conversation_key"))
            val nonce = Bytes.parseHex(item.string("nonce"))
            val payload = Nip44.encrypt(key, item.string("plaintext"), nonce)
            assertEquals(item.string("payload").trimEnd('='), payload.trimEnd('='))
            assertEquals(item.string("plaintext"), Nip44.decrypt(key, item.string("payload")))
            assertEquals(
                item.string("plaintext"),
                Nip44.decryptFrom(item.string("sec1"), Secp256k1.publicKey(item.string("sec2")), payload),
            )
        }
    }

    @Test
    fun rejectsNip04AndInvalidKeys() {
        val nip04 = assertFails {
            Nip44.decrypt(ByteArray(32), "a?b")
        }
        assertTrue(nip04.message!!.contains("NIP-04"))
        val invalid = vectors.getValue("invalid").jsonObject.getValue("get_conversation_key").jsonArray
        invalid.forEach { row ->
            val item = row.jsonObject
            assertFails { Nip44.conversationKey(item.string("sec1"), item.string("pub2")) }
        }
    }

    @Test
    fun bip340SignsTheFirstOfficialVector() {
        val secret = "0000000000000000000000000000000000000000000000000000000000000003"
        assertEquals(
            "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
            Secp256k1.publicKey(secret),
        )
        val signature = Secp256k1.sign(secret, ByteArray(32), ByteArray(32))
        assertEquals(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
            Bytes.hex(signature),
        )
    }

    @Test
    fun connectionStringKeepsTheSecretOutOfToString() {
        val uri = NwcUri.parse(
            "nostr+walletconnect://ab".padEnd(0, ' ').let {
                "nostr+walletconnect://" + "ab".repeat(32) +
                    "?relay=wss%3A%2F%2Frelay.example%2Fnostr&secret=" + "cd".repeat(32) +
                    "&relay=wss%3A%2F%2Fother.example%2F"
            },
        )
        assertEquals("ab".repeat(32), uri.walletPubkey)
        assertEquals(listOf("wss://relay.example/nostr", "wss://other.example/"), uri.relays)
        assertEquals("cd".repeat(32), uri.secret)
        assertFalse(uri.toString().contains(uri.secret))
        assertFails { NwcUri.parse("https://example") }
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.contentOrNull.orEmpty()
}
