package com.gorunjinian.metrovault.multisig

import com.gorunjinian.metrovault.data.model.Result
import com.gorunjinian.metrovault.domain.service.multisig.MultisigAddressService
import com.gorunjinian.metrovault.domain.service.multisig.MultisigDescriptorParser
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests for the multisig content-format router: plain descriptors,
 * BSMS records, and ColdCard setup files must all produce equivalent configs.
 */
class MultisigDescriptorParserTest {

    private val parser = MultisigDescriptorParser()

    private fun accountXpub(words: String): String {
        val seed = MnemonicCode.toSeed(words.split(" "), "")
        val master = DeterministicWallet.generate(seed)
        val accountPriv = master.derivePrivateKey(KeyPath("m/48'/0'/0'/2'"))
        val accountPub = DeterministicWallet.publicKey(accountPriv)
        return DeterministicWallet.encode(accountPub, DeterministicWallet.xpub)
    }

    private val xpub1 by lazy {
        accountXpub("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
    }
    private val xpub2 by lazy {
        accountXpub("legal winner thank year wave sausage worth useful legal winner thank yellow")
    }

    private fun parseOk(
        input: String,
        localFingerprints: List<String> = listOf("aaaaaaaa")
    ): MultisigDescriptorParser.ParsedMultisig =
        when (val r = parser.parse(input, localFingerprints)) {
            is Result.Success -> r.value
            is Result.Error -> throw AssertionError("Expected success but got error: ${r.error}")
        }

    // ==================== Routing ====================

    @Test
    fun routesSetupFileAndSuggestsName() {
        val file = """
            # Passport Multisig setup file (created by Bitcoin Safe)
            Name: My_Wallet
            Policy: 2 of 2
            Format: P2WSH
            Derivation: m/48'/0'/0'/2'
            aaaaaaaa: $xpub1
            Derivation: m/48'/0'/0'/2'
            bbbbbbbb: $xpub2
        """.trimIndent()

        val parsed = parseOk(file)
        assertEquals(MultisigDescriptorParser.SourceFormat.SETUP_FILE, parsed.sourceFormat)
        assertEquals("My_Wallet", parsed.suggestedName)
        assertNull(parsed.verificationAddress)
        assertEquals(2, parsed.config.m)
        assertEquals(2, parsed.config.n)
        assertEquals(listOf("aaaaaaaa"), parsed.config.localKeyFingerprints)
        assertTrue(parsed.config.cosigners.first { it.fingerprint == "aaaaaaaa" }.isLocal)
        // rawDescriptor is synthesized and parseable by the descriptor branch
        val reparsed = parseOk(parsed.config.rawDescriptor)
        assertEquals(MultisigDescriptorParser.SourceFormat.DESCRIPTOR, reparsed.sourceFormat)
        assertEquals(parsed.config.cosigners.map { it.xpub }, reparsed.config.cosigners.map { it.xpub })
    }

    @Test
    fun routesPlainDescriptor() {
        val descriptor =
            "wsh(sortedmulti(2,[aaaaaaaa/48'/0'/0'/2']$xpub1/<0;1>/*,[bbbbbbbb/48'/0'/0'/2']$xpub2/<0;1>/*))"

        val parsed = parseOk(descriptor)
        assertEquals(MultisigDescriptorParser.SourceFormat.DESCRIPTOR, parsed.sourceFormat)
        assertNull(parsed.suggestedName)
        assertEquals(2, parsed.config.m)
    }

    @Test
    fun routesBsmsRecordWithVerificationAddress() {
        val address = "bc1q0000000000000000000000000000000000000"
        val bsms = """
            BSMS 1.0
            wsh(sortedmulti(2,[aaaaaaaa/48'/0'/0'/2']$xpub1/<0;1>/*,[bbbbbbbb/48'/0'/0'/2']$xpub2/<0;1>/*))
            /0/*,/1/*
            $address
        """.trimIndent()

        val parsed = parseOk(bsms)
        assertEquals(MultisigDescriptorParser.SourceFormat.BSMS, parsed.sourceFormat)
        assertEquals(address, parsed.verificationAddress)
    }

    // ==================== Validation ====================

    @Test
    fun rejectsSetupFileWithoutLocalKey() {
        val file = """
            Name: Foreign
            Policy: 2 of 2
            Format: P2WSH
            Derivation: m/48'/0'/0'/2'
            cccccccc: $xpub1
            dddddddd: $xpub2
        """.trimIndent()

        val result = parser.parse(file, listOf("aaaaaaaa"))
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).error.contains("local wallets"))
    }

    @Test
    fun matchesLocalFingerprintsCaseInsensitively() {
        val file = """
            Policy: 1 of 2
            Format: P2WSH
            Derivation: m/48'/0'/0'/2'
            AAAAAAAA: $xpub1
            bbbbbbbb: $xpub2
        """.trimIndent()

        val parsed = parseOk(file, localFingerprints = listOf("AaAaAaAa"))
        assertEquals(listOf("aaaaaaaa"), parsed.config.localKeyFingerprints)
    }

    // ==================== Cross-Format Equivalence ====================

    /**
     * A wallet imported from a setup file must generate the same addresses as the
     * same wallet imported from an equivalent descriptor.
     */
    @Test
    fun setupFileImportMatchesDescriptorImport() {
        val addressService = MultisigAddressService()

        val setupFile = """
            Name: Equivalence
            Policy: 2 of 2
            Format: P2WSH
            Derivation: m/48'/0'/0'/2'
            aaaaaaaa: $xpub1
            Derivation: m/48'/0'/0'/2'
            bbbbbbbb: $xpub2
        """.trimIndent()
        val descriptor =
            "wsh(sortedmulti(2,[aaaaaaaa/48'/0'/0'/2']$xpub1/<0;1>/*,[bbbbbbbb/48'/0'/0'/2']$xpub2/<0;1>/*))"

        val fromSetupFile = parseOk(setupFile).config
        val fromDescriptor = parseOk(descriptor).config

        fun firstAddress(config: com.gorunjinian.metrovault.data.model.MultisigConfig): String =
            when (val r = addressService.generateMultisigAddress(config, 0, isChange = false, isTestnet = false)) {
                is Result.Success -> r.value.address
                is Result.Error -> throw AssertionError("Address generation failed: ${r.error}")
            }

        assertEquals(firstAddress(fromDescriptor), firstAddress(fromSetupFile))
    }
}
