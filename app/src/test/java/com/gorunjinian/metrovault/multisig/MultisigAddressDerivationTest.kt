package com.gorunjinian.metrovault.multisig

import com.gorunjinian.metrovault.data.model.CosignerInfo
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.MultisigScriptType
import com.gorunjinian.metrovault.data.model.Result
import com.gorunjinian.metrovault.domain.service.multisig.MultisigAddressService
import com.gorunjinian.metrovault.lib.bitcoin.Bitcoin
import com.gorunjinian.metrovault.lib.bitcoin.Block
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import org.junit.Assert.assertEquals
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
        val spk = service.generateMultisigScriptPubKey(config, index = 0, isChange = false, isTestnet = false)
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
}
