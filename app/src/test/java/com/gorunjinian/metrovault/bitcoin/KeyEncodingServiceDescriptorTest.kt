package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.domain.service.bitcoin.KeyEncodingService
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
import com.gorunjinian.vaultovich.Descriptor
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.KeyPath
import com.gorunjinian.vaultovich.MnemonicCode
import com.gorunjinian.vaultovich.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The descriptor exports on top of vaultovich 0.2.0: the multisig export is a BIP-380 key
 * expression (the library no longer emits the invalid `wsh([fp/...]xpub/0/…)` form), and the
 * password-gated private exports are assembled in the app because the library refuses `xprv`.
 */
class KeyEncodingServiceDescriptorTest {

    private val seed = MnemonicCode.toSeed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "),
        ""
    )
    private val master = DeterministicWallet.generate(seed)
    private val fingerprint = Descriptor.formatFingerprint(BitcoinUtils.computeFingerprintLong(master.publicKey))
    private val service = KeyEncodingService()

    @Test
    fun bip48ExportIsTheOriginQualifiedAccountKey() {
        val expr = service.getBip48KeyExpressionForAccount(
            fingerprint, master, 0, DerivationPaths.Bip48ScriptType.P2WSH, isTestnet = true
        )

        val accountKey = master.derivePrivateKey(KeyPath("m/48'/1'/0'/2'")).extendedPublicKey
        assertEquals("[$fingerprint/48h/1h/0h/2h]${accountKey.encode(DeterministicWallet.tpub)}", expr)
        assertFalse("a key expression carries no wsh() wrapper", expr.startsWith("wsh("))
    }

    @Test
    fun bip48PrivateExportMirrorsThePublicOne() {
        val pub = service.getBip48KeyExpressionForAccount(
            fingerprint, master, 3, DerivationPaths.Bip48ScriptType.P2SH_P2WSH, isTestnet = true
        )
        val prv = service.getBip48PrivateKeyExpressionForAccount(
            fingerprint, master, 3, DerivationPaths.Bip48ScriptType.P2SH_P2WSH, isTestnet = true
        )

        val account = master.derivePrivateKey(KeyPath("m/48'/1'/3'/1'"))
        assertEquals("[$fingerprint/48h/1h/3h/1h]${account.extendedPublicKey.encode(DeterministicWallet.tpub)}", pub)
        assertEquals("[$fingerprint/48h/1h/3h/1h]${account.encode(DeterministicWallet.tprv)}", prv)
    }

    @Test
    fun privateDescriptorIsThePublicOneWithTheKeySwapped() {
        val accountPaths = mapOf(
            ScriptType.P2PKH to "m/44'/1'/0'",
            ScriptType.P2SH_P2WPKH to "m/49'/1'/0'",
            ScriptType.P2WPKH to "m/84'/1'/0'",
            ScriptType.P2TR to "m/86'/1'/0'",
        )
        for ((scriptType, path) in accountPaths) {
            val account = master.derivePrivateKey(KeyPath(path))
            val tpub = account.extendedPublicKey.encode(DeterministicWallet.tpub)
            val tprv = account.encode(DeterministicWallet.tprv)

            val pubDesc = service.getWalletDescriptor(fingerprint, path, account.extendedPublicKey, scriptType, isTestnet = true)
            val prvDesc = service.getPrivateWalletDescriptor(fingerprint, path, account, scriptType, isTestnet = true)

            val prvBody = prvDesc.substringBeforeLast('#')
            assertTrue("$scriptType: private descriptor should carry the tprv", prvBody.contains(tprv))
            assertEquals(
                "$scriptType: layout must match the library's public descriptor",
                pubDesc.substringBeforeLast('#'),
                prvBody.replace(tprv, tpub)
            )
            assertEquals(
                "$scriptType: checksum must be computed over the private body",
                Descriptor.checksum(prvBody),
                prvDesc.substringAfterLast('#')
            )
        }
    }
}
