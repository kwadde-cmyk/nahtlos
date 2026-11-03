package de.jenz.nahtlos.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NwcProtocolTest {
    private val clientSecret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val walletSecret = "0000000000000000000000000000000000000000000000000000000000000002"

    private fun uri() = NwcUri(
        walletPubkey = Secp256k1.publicKey(walletSecret),
        relays = listOf("wss://relay.example/a", "wss://relay.example/b"),
        secret = clientSecret,
    )

    @Test
    fun requestEventCarriesTheNip44TagAndEncryptsToTheWallet() {
        val connection = uri()
        val event = NwcProtocol.request(connection, "get_info", "{}", createdAt = 1_700_000_000)
        assertEquals(listOf("p", connection.walletPubkey), event.tags[0])
        assertEquals(listOf("encryption", "nip44_v2"), event.tags[1])
        assertEquals(Secp256k1.publicKey(clientSecret), event.pubkey)
        assertFalse(event.pubkey == connection.walletPubkey)

        val openedByWallet = Nip44.decryptFrom(walletSecret, event.pubkey, event.content)
        assertTrue(openedByWallet.contains("\"method\":\"get_info\""))
        assertFailsWith<WalletException> {
            Nip44.decryptFrom(walletSecret, connection.walletPubkey, event.content)
        }
    }

    @Test
    fun nip44RoundtripWithASimulatedWallet() {
        val connection = uri()
        val request = NwcProtocol.request(connection, "get_balance", "{}", createdAt = 1_700_000_001)
        val seen = Nip44.decryptFrom(walletSecret, request.pubkey, request.content)
        assertTrue(seen.contains("get_balance"))

        val response = """{"result_type":"get_balance","result":{"balance":21000}}"""
        val cipher = Nip44.encryptTo(walletSecret, request.pubkey, response)
        assertEquals(response, NwcProtocol.decryptResponse(connection, cipher))
        assertEquals(null, NwcProtocol.failureMessage(response))
    }

    @Test
    fun infoEventWithoutEncryptionTagMeansNip04Only() {
        val missing = WalletInfo.parse("get_info pay_invoice", emptyList())
        assertEquals(listOf("nip04"), missing.encryption)
        assertFalse(missing.supportsNip44)
        val failure = assertFailsWith<WalletException> { missing.requireNip44() }
        assertTrue(failure.message!!.contains("NIP-04"))

        val only04 = WalletInfo.parse("get_info", listOf(listOf("encryption", "nip04")))
        assertFalse(only04.supportsNip44)

        val both = WalletInfo.parse(
            "pay_invoice get_balance get_info",
            listOf(listOf("encryption", "nip44_v2 nip04"), listOf("extensions", "02")),
        )
        assertTrue(both.supportsNip44)
        assertEquals(listOf("pay_invoice", "get_balance", "get_info"), both.methods)
        both.requireNip44()
    }

    @Test
    fun walletErrorBecomesAGermanSentence() {
        val plain = """{"result_type":"get_info","error":{"code":"UNAUTHORIZED","message":"no wallet connected"}}"""
        val text = NwcProtocol.failureMessage(plain)
        assertTrue(text!!.contains("nicht berechtigt"))
        assertTrue(text.contains("get_info"))
        assertTrue(text.contains("no wallet connected"))
        val decrypt = """{"result_type":"get_info","error":{"code":"OTHER","message":"failed to decrypt: error parsing encrypted message: no initialization vector"}}"""
        assertTrue(NwcProtocol.failureMessage(decrypt)!!.contains("NIP-04"))
        assertFalse(NwcProtocol.failureMessage(decrypt)!!.contains("initialization vector"))
    }
}
