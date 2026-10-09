package com.gorunjinian.metrovault.multisig

import com.gorunjinian.metrovault.data.model.CosignerInfo
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.MultisigScriptType
import com.gorunjinian.metrovault.data.model.Result
import com.gorunjinian.metrovault.domain.service.multisig.MultisigAddressService
import com.gorunjinian.vaultovich.Base58
import com.gorunjinian.vaultovich.Base58Check
import com.gorunjinian.vaultovich.Bech32
import com.gorunjinian.vaultovich.Bitcoin
import com.gorunjinian.vaultovich.Block
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.KeyPath
import com.gorunjinian.vaultovich.MnemonicCode
import com.gorunjinian.vaultovich.PublicKey
import com.gorunjinian.vaultovich.Script
import com.gorunjinian.vaultovich.utils.Either
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-checks multisig address generation against an independent scriptPubKey → address decode.
 *
 * For every script type, the address from [MultisigAddressService.generateMultisigAddress] must
 * equal the address obtained by decoding the independently-derived scriptPubKey via
 * [Bitcoin.addressFromPublicKeyScript]. This pins the P2SH-P2WSH single-vs-double sha256 fix (a
 * double hash would make the two disagree) and guards all three script types against regression.
 */
class MultisigAddressDerivationTest {

    private val service = MultisigAddressService()

    private fun accountXpub(words: String): String {
        val seed = MnemonicCode.toSeed(words.split(" "), "")
        val master = DeterministicWallet.generate(seed)
        val accountPriv = master.derivePrivateKey(KeyPath("m/48'/0'/0'/1'"))
        val accountPub = DeterministicWallet.publicKey(accountPriv)
        return DeterministicWallet.encode(accountPub, DeterministicWallet.xpub)
    }

    private fun config(scriptType: MultisigScriptType): MultisigConfig {
        val xpub1 = accountXpub(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        )
        val xpub2 = accountXpub(
            "legal winner thank year wave sausage worth useful legal winner thank yellow"
        )
        return MultisigConfig(
            m = 2,
            n = 2,
            cosigners = listOf(
                CosignerInfo(xpub1, "00000001", "48'/0'/0'/1'", isLocal = false),
                CosignerInfo(xpub2, "00000002", "48'/0'/0'/1'", isLocal = false)
            ),
            localKeyFingerprints = emptyList(),
            scriptType = scriptType,
            rawDescriptor = ""
        )
    }

    private fun genAddress(config: MultisigConfig): String =
        when (val r = service.generateMultisigAddress(config, index = 0, isChange = false, isTestnet = false)) {
            is Result.Success -> r.value.address
            is Result.Error -> throw AssertionError("address generation failed: ${r.error}")
        }

    private fun assertAddressMatchesScriptPubKey(scriptType: MultisigScriptType) {
        val config = config(scriptType)
        val address = genAddress(config)
        val spk = service.generateMultisigScriptPubKey(config, index = 0, isChange = false)
            ?: throw AssertionError("scriptPubKey derivation failed")
        val decoded = Bitcoin.addressFromPublicKeyScript(Block.LivenetGenesisBlock.hash, spk)
        assertTrue("scriptPubKey did not decode to an address: $decoded", decoded is Either.Right)
        assertEquals(address, (decoded as Either.Right).value)
    }

    @Test
    fun p2wshAddressMatchesScript() = assertAddressMatchesScriptPubKey(MultisigScriptType.P2WSH)

    @Test
    fun p2shP2wshAddressMatchesScript() = assertAddressMatchesScriptPubKey(MultisigScriptType.P2SH_P2WSH)

    @Test
    fun p2shAddressMatchesScript() = assertAddressMatchesScriptPubKey(MultisigScriptType.P2SH)

    @Test
    fun p2shP2wshHasMainnetP2shPrefix() {
        // Nested-segwit multisig on mainnet must be a P2SH address (starts with "3")
        assertTrue(genAddress(config(MultisigScriptType.P2SH_P2WSH)).startsWith("3"))
    }

    /**
     * Each cosigner's key at `branch/index`, in cosigner order, derived through the private keys so
     * it is independent of the xpub derivation under test.
     */
    private fun seedKeys(isChange: Boolean, index: Int): List<PublicKey> = listOf(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        "legal winner thank year wave sausage worth useful legal winner thank yellow"
    ).map { phrase ->
        DeterministicWallet.generate(MnemonicCode.toSeed(phrase.split(" "), ""))
            .derivePrivateKey(KeyPath("m/48'/0'/0'/1'/${if (isChange) 1 else 0}/$index"))
            .publicKey
    }

    private fun expectedScript(type: MultisigScriptType, orderedKeys: List<PublicKey>): ByteArray {
        val multisig = Script.createMultiSigMofN(2, orderedKeys)
        return Script.write(
            when (type) {
                MultisigScriptType.P2WSH -> Script.pay2wsh(multisig)
                MultisigScriptType.P2SH_P2WSH -> Script.pay2sh(Script.pay2wsh(multisig))
                MultisigScriptType.P2SH -> Script.pay2sh(multisig)
            }
        )
    }

    @Test
    fun scriptsMatchKeysDerivedFromTheSeeds() {
        MultisigScriptType.entries.forEach { type ->
            listOf(false to 0, true to 7, false to 1_000).forEach { (isChange, index) ->
                assertArrayEquals(
                    "$type ${if (isChange) "change" else "receive"} #$index",
                    expectedScript(type, seedKeys(isChange, index).sortedBy { it.toHex() }),
                    service.generateMultisigScriptPubKey(config(type), index, isChange)
                )
            }
        }
    }

    @Test
    fun multiKeepsDescriptorOrderWhileSortedmultiIgnoresIt() {
        MultisigScriptType.entries.forEach { type ->
            // Only the multi/sortedmulti function is read from rawDescriptor; the wrapper is scriptType's
            val forward = config(type).copy(rawDescriptor = "wsh(multi(2,…))")
            val backward = forward.copy(cosigners = forward.cosigners.reversed())
            assertFalse(forward.sortedKeys)

            assertArrayEquals("$type multi", expectedScript(type, seedKeys(false, 0)),
                service.generateMultisigScriptPubKey(forward, 0, isChange = false))
            assertArrayEquals("$type multi, reversed", expectedScript(type, seedKeys(false, 0).reversed()),
                service.generateMultisigScriptPubKey(backward, 0, isChange = false))
            assertNotEquals("$type: key order is part of a multi wallet", genAddress(forward), genAddress(backward))

            val sorted = config(type)
            assertTrue(sorted.sortedKeys)
            assertEquals("$type: sortedmulti ignores key order",
                genAddress(sorted), genAddress(sorted.copy(cosigners = sorted.cosigners.reversed())))
        }
    }

    @Test
    fun addressesEncodeTheScriptForEachNetwork() {
        // Encoded straight from the scriptPubKey bytes, not through Bitcoin.addressFromPublicKeyScript
        listOf(false, true).forEach { isTestnet ->
            MultisigScriptType.entries.forEach { type ->
                val config = config(type)
                val spk = service.generateMultisigScriptPubKey(config, index = 3, isChange = true)!!
                val expected = if (type == MultisigScriptType.P2WSH) {
                    // OP_0 <32-byte program>
                    Bech32.encodeWitnessAddress(if (isTestnet) "tb" else "bc", 0, spk.copyOfRange(2, 34))
                } else {
                    // OP_HASH160 <20-byte hash> OP_EQUAL
                    val prefix = if (isTestnet) Base58.Prefix.ScriptAddressTestnet else Base58.Prefix.ScriptAddress
                    Base58Check.encode(prefix, spk.copyOfRange(2, 22))
                }
                val generated = service.generateMultisigAddress(config, index = 3, isChange = true, isTestnet = isTestnet)
                assertEquals("$type testnet=$isTestnet", expected, (generated as Result.Success).value.address)
            }
        }
    }

    @Test
    fun xpubWithInconsistentDepthBytesStillDerives() {
        val normal = config(MultisigScriptType.P2WSH)
        val zeroDepth = normal.copy(cosigners = normal.cosigners.map { it.copy(xpub = withDepthByte(it.xpub, 0)) })

        // vaultovich's decoder refuses these (depth 0 with a non-zero parent fingerprint), but the
        // depth byte plays no part in derivation, so imported wallets carrying one must keep working
        assertThrows(IllegalArgumentException::class.java) {
            DeterministicWallet.ExtendedPublicKey.decode(zeroDepth.cosigners[0].xpub)
        }
        assertEquals(genAddress(normal), genAddress(zeroDepth))
    }

    private fun withDepthByte(xpub: String, depth: Int): String {
        val (prefix, payload) = Base58Check.decodeWithIntPrefix(xpub)
        return Base58Check.encode(prefix, payload.copyOf().also { it[0] = depth.toByte() })
    }
}
