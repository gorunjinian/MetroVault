package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.domain.service.bitcoin.SignMessageRequest
import com.gorunjinian.metrovault.domain.service.bitcoin.WalletMessageSigner
import com.gorunjinian.vaultovich.Bip322
import com.gorunjinian.vaultovich.Block
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.MessageSigning
import com.gorunjinian.vaultovich.MnemonicCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text message-signing requests scanned from a coordinator: the Specter `signmessage` string and
 * the Coinkite JSON form. Covers parsing, strict path handling, and that the address is derived
 * for the chain named by the path's coin type — a testnet path used to yield a mainnet address
 * that no testnet wallet could sign for.
 */
class SignMessageRequestTest {
    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val master = DeterministicWallet.generate(MnemonicCode.toSeed(mnemonic.split(" "), ""))
    private val mainnet = Block.LivenetGenesisBlock.hash
    private val testnet = Block.Testnet4GenesisBlock.hash

    private fun parsed(text: String): SignMessageRequest {
        val request = SignMessageRequest.parse(text)
        assertNotNull("expected a request from: $text", request)
        return request!!
    }

    // ---- signmessage string ----

    @Test
    fun parsesSignMessageString() {
        val request = parsed("signmessage m/84h/1h/0h/0/5 ascii:hello")
        assertEquals("m/84h/1h/0h/0/5", request.displayPath)
        assertEquals("hello", request.message)
        assertEquals(MessageSigning.AddressType.P2WPKH, request.addressType)
    }

    @Test
    fun signMessageKeepsSpacesAndNewlinesInTheMessage() {
        assertEquals("hello big world\nline two", parsed("signmessage m/84h/0h/0h/0/0 ascii:hello big world\nline two").message)
    }

    @Test
    fun signMessageAcceptsApostrophePathsPrefixVariantsAndHex() {
        assertEquals("m/84h/0h/0h/0/0", parsed("SIGNMESSAGE m/84'/0'/0'/0/0 ASCII:hi").displayPath)
        assertEquals("hi", parsed("signmessage m/84H/0H/0H/0/0 utf8:hi").message)
        assertEquals("hello", parsed("signmessage m/84h/0h/0h/0/0 hex:68656c6c6f").message)
        assertEquals("no prefix", parsed("signmessage m/84h/0h/0h/0/0 no prefix").message)
    }

    @Test
    fun signMessageInfersAddressTypeFromPurpose() {
        assertEquals(MessageSigning.AddressType.P2PKH, parsed("signmessage m/44h/0h/0h/0/0 ascii:x").addressType)
        assertEquals(MessageSigning.AddressType.P2SH_P2WPKH, parsed("signmessage m/49h/0h/0h/0/0 ascii:x").addressType)
        assertEquals(MessageSigning.AddressType.P2TR, parsed("signmessage m/86h/0h/0h/0/0 ascii:x").addressType)
    }

    // ---- Coinkite JSON ----

    @Test
    fun parsesCoinkiteJsonIncludingTrailingSpaceInSubpath() {
        // Verbatim from the bug report, trailing space and all.
        val request = parsed("""{"msg": "hello", "subpath": "m/84h/1h/0h/0/5 ", "addr_fmt": "p2wpkh"}""")
        assertEquals("m/84h/1h/0h/0/5", request.displayPath)
        assertEquals("hello", request.message)
        assertEquals(MessageSigning.AddressType.P2WPKH, request.addressType)
    }

    @Test
    fun coinkiteAddrFmtOverridesThePurpose() {
        val request = parsed("""{"msg":"x","subpath":"m/84h/0h/0h/0/0","addr_fmt":"p2sh-p2wpkh"}""")
        assertEquals(MessageSigning.AddressType.P2SH_P2WPKH, request.addressType)
        assertEquals(MessageSigning.AddressType.P2TR, parsed("""{"msg":"x","subpath":"m/86h/0h/0h/0/0","addr_fmt":"p2tr"}""").addressType)
    }

    @Test
    fun coinkiteDefaultsFollowThePurposeThenClassic() {
        assertEquals(MessageSigning.AddressType.P2WPKH, parsed("""{"msg":"x","subpath":"m/84h/0h/0h/0/0"}""").addressType)
        val bare = parsed("""{"msg":"x"}""")
        assertEquals("m", bare.displayPath)
        assertEquals(MessageSigning.AddressType.P2PKH, bare.addressType)
    }

    // ---- rejection ----

    @Test
    fun rejectsMalformedRequests() {
        // A bad path component must fail the request, never be skipped (that would pick another key).
        assertNull(SignMessageRequest.parse("signmessage m/84h/xh/0h/0/5 ascii:hello"))
        assertNull(SignMessageRequest.parse("signmessage m/84h//0h/0/5 ascii:hello"))
        assertNull(SignMessageRequest.parse("signmessage m/84h/4294967296/0 ascii:hello"))
        assertNull(SignMessageRequest.parse("signmessage 84h/0h/0h/0/5 ascii:hello"))
        assertNull(SignMessageRequest.parse("signmessage m/84h/0h/0h/0/5"))
        assertNull(SignMessageRequest.parse("signmessage m/84h/0h/0h/0/5 ascii:"))
        assertNull(SignMessageRequest.parse("signmessage m/84h/0h/0h/0/5 hex:6g"))
        assertNull(SignMessageRequest.parse("signmessage m/84h/0h/0h/0/5 hex:abc"))
        assertNull(SignMessageRequest.parse("signmessage m/84h/0h/0h/0/5 hex:ff")) // not UTF-8 text
        assertNull(SignMessageRequest.parse("""{"msg":"x","subpath":"m/84h/0h/0h/0/0","addr_fmt":"p2wsh"}"""))
        assertNull(SignMessageRequest.parse("""{"subpath":"m/84h/0h/0h/0/0"}"""))
        assertNull(SignMessageRequest.parse("""{"msg":"x","subpath":"m/84h/zz"}"""))
        assertNull(SignMessageRequest.parse("""{"msg": "x", """))
        assertNull(SignMessageRequest.parse("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"))
    }

    @Test
    fun recognisesRequestsButNotPlainPayloads() {
        assertTrue(SignMessageRequest.looksLikeRequest("signmessage m/84h/0h/0h/0/0 ascii:x"))
        assertTrue(SignMessageRequest.looksLikeRequest("""{"msg": "hello"}"""))
        // Recognised as a request even when malformed, so it is reported rather than pasted in.
        assertTrue(SignMessageRequest.looksLikeRequest("signmessage nonsense"))
        assertFalse(SignMessageRequest.looksLikeRequest("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"))
        assertFalse(SignMessageRequest.looksLikeRequest("just a message"))
        assertFalse(SignMessageRequest.looksLikeRequest("""{"note": "some other json"}"""))
    }

    // ---- derivation ----

    @Test
    fun derivesStandardMainnetAddresses() {
        fun address(text: String) = parsed(text).resolve(master, mainnet).address
        assertEquals("1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA", address("signmessage m/44h/0h/0h/0/0 ascii:x"))
        assertEquals("37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf", address("signmessage m/49h/0h/0h/0/0 ascii:x"))
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", address("signmessage m/84h/0h/0h/0/0 ascii:x"))
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", address("signmessage m/86h/0h/0h/0/0 ascii:x"))
    }

    @Test
    fun coinTypeDecidesTheChainNotTheWallet() {
        // The reported bug: a testnet path must give a testnet address even on a mainnet wallet…
        val testnetRequest = parsed("signmessage m/84h/1h/0h/0/0 ascii:x")
        assertEquals("tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl", testnetRequest.resolve(master, mainnet).address)
        // …and a mainnet path a mainnet address on a testnet wallet.
        val mainnetRequest = parsed("signmessage m/84h/0h/0h/0/0 ascii:x")
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", mainnetRequest.resolve(master, testnet).address)
    }

    @Test
    fun pathWithoutCoinTypeUsesTheWalletChain() {
        val request = parsed("""{"msg":"x","addr_fmt":"p2wpkh"}""")
        assertTrue(request.resolve(master, mainnet).address.startsWith("bc1q"))
        assertTrue(request.resolve(master, testnet).address.startsWith("tb1q"))
    }

    // ---- end to end: the two requests from the bug report ----

    @Test
    fun bothReportedRequestsSignAndVerify() {
        val requests = listOf(
            "signmessage m/84h/1h/0h/0/5 ascii:hello",
            """{"msg": "hello", "subpath": "m/84h/1h/0h/0/5 ", "addr_fmt": "p2wpkh"}""",
        ).map { parsed(it) }
        assertEquals(requests[0], requests[1])

        for (request in requests) {
            val resolved = request.resolve(master, mainnet)
            assertTrue(resolved.address.startsWith("tb1q"))
            // The derived key is the one behind the derived address.
            assertEquals(resolved.address, resolved.privateKey.publicKey().p2wpkhAddress(testnet))

            for (format in listOf(MessageSigning.SignatureFormat.ELECTRUM, MessageSigning.SignatureFormat.BIP137)) {
                val signature = MessageSigning.signMessage(request.message, resolved.privateKey, format, request.addressType)
                val outcome = WalletMessageSigner.verify(resolved.address, request.message, signature, format)
                assertTrue("$format signature should verify", outcome.isValid)
                assertFalse(WalletMessageSigner.verify(resolved.address, "other", signature, format).isValid)
            }

            val bip322 = Bip322.signSimple(resolved.address, request.message, resolved.privateKey, testnet)
            assertTrue(WalletMessageSigner.verify(resolved.address, request.message, bip322, MessageSigning.SignatureFormat.BIP322).isValid)
        }
    }
}
