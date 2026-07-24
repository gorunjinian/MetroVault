package com.gorunjinian.metrovault.multisig

import com.gorunjinian.metrovault.core.util.Bip48MultisigPrefixes
import com.gorunjinian.metrovault.data.model.MultisigScriptType
import com.gorunjinian.metrovault.data.model.Result
import com.gorunjinian.metrovault.domain.service.multisig.ColdCardSetupFile
import com.gorunjinian.metrovault.lib.bitcoin.Base58Check
import com.gorunjinian.metrovault.lib.bitcoin.Descriptor
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the ColdCard multisig setup file parser against the format variants
 * emitted by ColdCard, Passport, Blockstream Jade, Sparrow, and Bitcoin Safe.
 */
class ColdCardSetupFileTest {

    // ==================== Key Fixtures (real, deterministic) ====================

    private fun accountXpub(words: String, path: String, testnet: Boolean = false): String {
        val seed = MnemonicCode.toSeed(words.split(" "), "")
        val master = DeterministicWallet.generate(seed)
        val accountPriv = master.derivePrivateKey(KeyPath(path))
        val accountPub = DeterministicWallet.publicKey(accountPriv)
        val prefix = if (testnet) DeterministicWallet.tpub else DeterministicWallet.xpub
        return DeterministicWallet.encode(accountPub, prefix)
    }

    private val mnemonic1 =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val mnemonic2 =
        "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val mnemonic3 =
        "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"

    private val xpub1 by lazy { accountXpub(mnemonic1, "m/48'/0'/0'/2'") }
    private val xpub2 by lazy { accountXpub(mnemonic2, "m/48'/0'/0'/2'") }
    private val xpub3 by lazy { accountXpub(mnemonic3, "m/48'/0'/0'/2'") }
    private val tpub1 by lazy { accountXpub(mnemonic1, "m/48'/1'/0'/2'", testnet = true) }
    private val tpub2 by lazy { accountXpub(mnemonic2, "m/48'/1'/0'/2'", testnet = true) }

    /** Re-encode an xpub with a SLIP-132 prefix (e.g. Zpub) for prefix-handling tests. */
    private fun slip132(xpub: String, prefix: Int): String {
        val (_, payload) = Base58Check.decodeWithIntPrefix(xpub)
        return Base58Check.encode(prefix, payload)
    }

    private fun parseOk(input: String): ColdCardSetupFile.ParsedSetupFile =
        when (val r = ColdCardSetupFile.parse(input)) {
            is Result.Success -> r.value
            is Result.Error -> throw AssertionError("Expected success but got error: ${r.error}")
        }

    private fun parseErr(input: String): String =
        when (val r = ColdCardSetupFile.parse(input)) {
            is Result.Success -> throw AssertionError("Expected error but parse succeeded")
            is Result.Error -> r.error
        }

    // ==================== Format Variants ====================

    @Test
    fun parsesModernColdCardExport() {
        val file = """
            # Coldcard Multisig setup file (created by Sparrow)
            #
            Name: Family Vault
            Policy: 2 of 3
            Format: P2WSH

            Derivation: m/48'/0'/0'/2'
            AAAAAAAA: $xpub1
            Derivation: m/48'/0'/0'/2'
            bbbbbbbb: $xpub2
            Derivation: m/48'/0'/0'/2'
            0F056943: $xpub3
        """.trimIndent()

        val parsed = parseOk(file)
        assertEquals("Family Vault", parsed.name)
        assertEquals(2, parsed.threshold)
        assertEquals(MultisigScriptType.P2WSH, parsed.scriptType)
        assertEquals(3, parsed.keys.size)
        assertFalse(parsed.isTestnet)
        // Fingerprints normalized to lowercase
        assertEquals(listOf("aaaaaaaa", "bbbbbbbb", "0f056943"), parsed.keys.map { it.fingerprint })
        // Derivation applies per key, m/ prefix stripped
        assertTrue(parsed.keys.all { it.derivationPath == "48'/0'/0'/2'" })
        assertEquals(listOf(xpub1, xpub2, xpub3), parsed.keys.map { it.xpub })
    }

    /** The exact shape produced by Bitcoin Safe's Passport/ColdCard-legacy export (the reported bug). */
    @Test
    fun parsesBitcoinSafePassportExport() {
        val file = """
            # Passport Multisig setup file (created by Bitcoin Safe)
            #
            Name: My_Wallet
            Policy: 2 of 2
            Format: P2WSH

            Derivation: m/48'/0'/0'/2'
            aaaaaaaa: $xpub1
            Derivation: m/48'/0'/0'/2'
            bbbbbbbb: $xpub2
        """.trimIndent()

        val parsed = parseOk(file)
        assertEquals("My_Wallet", parsed.name)
        assertEquals(2, parsed.threshold)
        assertEquals(2, parsed.keys.size)
        assertEquals(MultisigScriptType.P2WSH, parsed.scriptType)
    }

    /** Older ColdCard exports have a single global Derivation line before the key list. */
    @Test
    fun parsesLegacyGlobalDerivation() {
        val file = """
            # Coldcard Multisig setup file (created on 0F056943)
            Name: CC-2-of-2
            Policy: 2 of 2
            Derivation: m/48'/0'/0'/2'
            Format: P2WSH

            aaaaaaaa: $xpub1
            bbbbbbbb: $xpub2
        """.trimIndent()

        val parsed = parseOk(file)
        assertTrue(parsed.keys.all { it.derivationPath == "48'/0'/0'/2'" })
    }

    @Test
    fun parsesNestedSegwitFormatAndAlias() {
        val file = { format: String ->
            """
            Name: Nested
            Policy: 1 of 2
            Derivation: m/48'/0'/0'/1'
            Format: $format
            aaaaaaaa: $xpub1
            bbbbbbbb: $xpub2
            """.trimIndent()
        }
        assertEquals(MultisigScriptType.P2SH_P2WSH, parseOk(file("P2SH-P2WSH")).scriptType)
        assertEquals(MultisigScriptType.P2SH_P2WSH, parseOk(file("P2WSH-P2SH")).scriptType)
        assertEquals(MultisigScriptType.P2SH, parseOk(file("p2sh")).scriptType)
    }

    @Test
    fun parsesJadeExportVerbatim() {
        val file = """
            # Exported by Blockstream Jade
            Name: hwi3374c2e55c4b
            Policy: 2 of 3
            Format: P2WSH
            Derivation: m/48'/1'/0'/2'
            14c949b4: tpubDDvtDSGt5JmgxgpRp3nyZj3ULZvFWuU9AaS6x3UwkNE6vaNgzd6oyKYEQUzSevUQs2ste5QznpbN8Nt5bVbZvrJFpCqw9UPXCtnCutEvEwW
            Derivation: m/48'/1'/0'/2'
            d8cf7475: tpubDEDUiUcwmoC92QJ2kGPQwtikGqLrjdyUfuRMhm5ab4nYmgRkkKPF9mp2FcunzMu9y5Ea2urGUJh4t1o7Wb6KjKddzJKcE8BoAyTWK6ughFK
            Derivation: m/48'/1'/0'/2'
            d5b43540: tpubDFnCcKU3iUF4sPeQC68r2ewDaBB7TvLmQBTs12hnNS8nu6CPjZPmzapp7Woz6bkFuLfSjSpg6gacheKBaWBhDnEbEpKtCnVFdQnfhYGkPQF
        """.trimIndent()

        val parsed = parseOk(file)
        assertEquals(2, parsed.threshold)
        assertEquals(3, parsed.keys.size)
        assertTrue(parsed.isTestnet)
        assertEquals(MultisigScriptType.P2WSH, parsed.scriptType)
        assertTrue(parsed.keys.all { it.derivationPath == "48'/1'/0'/2'" })
    }

    @Test
    fun handlesCrlfAndHNotation() {
        val file = listOf(
            "Name: CRLF",
            "Policy: 1 of 2",
            "Derivation: m/48h/0h/0h/2h",
            "Format: P2WSH",
            "aaaaaaaa: $xpub1",
            "bbbbbbbb: $xpub2"
        ).joinToString("\r\n")

        val parsed = parseOk(file)
        assertEquals(2, parsed.keys.size)
        assertTrue(parsed.keys.all { it.derivationPath == "48h/0h/0h/2h" })
    }

    // ==================== SLIP-132 Handling ====================

    @Test
    fun infersScriptTypeFromSlip132WhenFormatMissing() {
        val file = """
            Name: Slip
            Policy: 1 of 2
            Derivation: m/48'/0'/0'/2'
            aaaaaaaa: ${slip132(xpub1, Bip48MultisigPrefixes.Zpub)}
            bbbbbbbb: ${slip132(xpub2, Bip48MultisigPrefixes.Zpub)}
        """.trimIndent()

        val parsed = parseOk(file)
        assertEquals(MultisigScriptType.P2WSH, parsed.scriptType)
        // Zpubs are normalized back to canonical xpub encoding
        assertEquals(listOf(xpub1, xpub2), parsed.keys.map { it.xpub })
    }

    @Test
    fun formatLineOverridesSlip132Prefix() {
        val file = """
            Policy: 1 of 2
            Derivation: m/48'/0'/0'/1'
            Format: P2SH-P2WSH
            aaaaaaaa: ${slip132(xpub1, Bip48MultisigPrefixes.Zpub)}
            bbbbbbbb: ${slip132(xpub2, Bip48MultisigPrefixes.Zpub)}
        """.trimIndent()

        assertEquals(MultisigScriptType.P2SH_P2WSH, parseOk(file).scriptType)
    }

    /** Without Format or SLIP-132 hints, default is P2SH (ColdCard firmware behavior). */
    @Test
    fun defaultsToLegacyP2shWithoutFormatOrSlip132() {
        val file = """
            Policy: 1 of 2
            Derivation: m/45'
            aaaaaaaa: $xpub1
            bbbbbbbb: $xpub2
        """.trimIndent()

        val parsed = parseOk(file)
        assertEquals(MultisigScriptType.P2SH, parsed.scriptType)
        assertNull(parsed.name)
    }

    @Test
    fun parsesTestnetKeys() {
        val file = """
            Policy: 2 of 2
            Derivation: m/48'/1'/0'/2'
            Format: P2WSH
            aaaaaaaa: $tpub1
            bbbbbbbb: $tpub2
        """.trimIndent()

        assertTrue(parseOk(file).isTestnet)
    }

    // ==================== Error Cases ====================

    @Test
    fun rejectsPolicyCountMismatch() {
        val file = """
            Policy: 2 of 3
            Derivation: m/48'/0'/0'/2'
            Format: P2WSH
            aaaaaaaa: $xpub1
            bbbbbbbb: $xpub2
        """.trimIndent()

        assertTrue(parseErr(file).contains("Policy says 3"))
    }

    @Test
    fun rejectsThresholdGreaterThanKeyCount() {
        val file = """
            Policy: 3 of 2
            Derivation: m/48'/0'/0'/2'
            Format: P2WSH
            aaaaaaaa: $xpub1
            bbbbbbbb: $xpub2
        """.trimIndent()

        assertTrue(parseErr(file).contains("Threshold"))
    }

    @Test
    fun rejectsInvalidXpubWithFingerprintContext() {
        val corrupted = xpub1.dropLast(4) + "1111"
        val file = """
            Policy: 1 of 2
            Derivation: m/48'/0'/0'/2'
            Format: P2WSH
            aaaaaaaa: $corrupted
            bbbbbbbb: $xpub2
        """.trimIndent()

        assertTrue(parseErr(file).contains("aaaaaaaa"))
    }

    @Test
    fun rejectsMixedNetworks() {
        val file = """
            Policy: 1 of 2
            Derivation: m/48'/0'/0'/2'
            Format: P2WSH
            aaaaaaaa: $xpub1
            bbbbbbbb: $tpub2
        """.trimIndent()

        assertTrue(parseErr(file).contains("mixes mainnet and testnet"))
    }

    @Test
    fun rejectsDuplicateXpubs() {
        val file = """
            Policy: 1 of 2
            Derivation: m/48'/0'/0'/2'
            Format: P2WSH
            aaaaaaaa: $xpub1
            bbbbbbbb: $xpub1
        """.trimIndent()

        assertTrue(parseErr(file).contains("duplicate"))
    }

    @Test
    fun rejectsConflictingPolicyLines() {
        val file = """
            Policy: 1 of 2
            Policy: 2 of 2
            Derivation: m/48'/0'/0'/2'
            Format: P2WSH
            aaaaaaaa: $xpub1
            bbbbbbbb: $xpub2
        """.trimIndent()

        assertTrue(parseErr(file).contains("Conflicting Policy"))
    }

    @Test
    fun rejectsInvalidDerivationPath() {
        val file = """
            Policy: 1 of 1
            Derivation: not/a/path
            Format: P2WSH
            aaaaaaaa: $xpub1
        """.trimIndent()

        assertTrue(parseErr(file).contains("Invalid derivation path"))
    }

    @Test
    fun rejectsUnsupportedFormat() {
        val file = """
            Policy: 1 of 1
            Derivation: m/48'/0'/0'/2'
            Format: P2TR
            aaaaaaaa: $xpub1
        """.trimIndent()

        assertTrue(parseErr(file).contains("Unsupported Format"))
    }

    @Test
    fun rejectsPrivateKeys() {
        val seed = MnemonicCode.toSeed(mnemonic1.split(" "), "")
        val master = DeterministicWallet.generate(seed)
        val accountPriv = master.derivePrivateKey(KeyPath("m/48'/0'/0'/2'"))
        val xprv = DeterministicWallet.encode(accountPriv, DeterministicWallet.xprv)
        val file = """
            Policy: 1 of 1
            Derivation: m/48'/0'/0'/2'
            Format: P2WSH
            aaaaaaaa: $xprv
        """.trimIndent()

        assertTrue(parseErr(file).contains("Invalid xpub"))
    }

    // ==================== Detection ====================

    @Test
    fun detectsSetupFilesOnly() {
        val setupFile = """
            Name: X
            Policy: 2 of 3
            aaaaaaaa: $xpub1
        """.trimIndent()
        assertTrue(ColdCardSetupFile.isSetupFile(setupFile))

        assertFalse(ColdCardSetupFile.isSetupFile("wsh(sortedmulti(2,[aaaaaaaa/48'/0'/0'/2']$xpub1/<0;1>/*))"))
        assertFalse(ColdCardSetupFile.isSetupFile("BSMS 1.0\nwsh(sortedmulti(2,...))\n/0/*,/1/*\nbc1qexample"))
        assertFalse(ColdCardSetupFile.isSetupFile("Name: something without a policy line"))
        assertFalse(ColdCardSetupFile.isSetupFile(""))
    }

    // ==================== Descriptor Synthesis ====================

    @Test
    fun synthesizedDescriptorIsCanonicalWithValidChecksum() {
        val file = """
            Name: Synth
            Policy: 2 of 2
            Derivation: m/48'/0'/0'/2'
            Format: P2WSH
            aaaaaaaa: $xpub1
            bbbbbbbb: $xpub2
        """.trimIndent()

        val descriptor = ColdCardSetupFile.toDescriptor(parseOk(file))
        val body = descriptor.substringBefore("#")
        val checksum = descriptor.substringAfter("#")

        assertEquals(
            "wsh(sortedmulti(2,[aaaaaaaa/48'/0'/0'/2']$xpub1/<0;1>/*,[bbbbbbbb/48'/0'/0'/2']$xpub2/<0;1>/*))",
            body
        )
        assertEquals(Descriptor.checksum(body), checksum)
    }

    @Test
    fun synthesizedDescriptorWrapsByScriptType() {
        val file = { format: String ->
            """
            Policy: 1 of 1
            Derivation: m/48'/0'/0'/1'
            Format: $format
            aaaaaaaa: $xpub1
            """.trimIndent()
        }
        assertTrue(ColdCardSetupFile.toDescriptor(parseOk(file("P2WSH"))).startsWith("wsh(sortedmulti("))
        assertTrue(ColdCardSetupFile.toDescriptor(parseOk(file("P2SH-P2WSH"))).startsWith("sh(wsh(sortedmulti("))
        assertTrue(ColdCardSetupFile.toDescriptor(parseOk(file("P2SH"))).startsWith("sh(sortedmulti("))
    }
}
