package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.domain.service.bitcoin.AddressService
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.KeyPath
import com.gorunjinian.vaultovich.MnemonicCode
import com.gorunjinian.vaultovich.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `AddressService.findAddresses` agrees with the one-address scan it batches. */
class AddressLookupTest {

    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val master = DeterministicWallet.generate(MnemonicCode.toSeed(words, ""))
    private val service = AddressService()
    private val range = 30

    private fun account(path: String) = master.derivePrivateKey(KeyPath(path)).extendedPublicKey

    private fun address(path: String, scriptType: ScriptType, index: Int, isChange: Boolean, isTestnet: Boolean = false) =
        service.generateAddress(account(path), index, isChange, scriptType, isTestnet)!!.address

    @Test
    fun everyAddressFoundSitsWhereTheSingleScanFindsIt() {
        for ((path, scriptType) in listOf(
            "m/84'/0'/0'" to ScriptType.P2WPKH,
            "m/86'/0'/0'" to ScriptType.P2TR,
            "m/49'/0'/0'" to ScriptType.P2SH_P2WPKH,
            "m/44'/0'/0'" to ScriptType.P2PKH
        )) {
            val receive = address(path, scriptType, 4, isChange = false)
            val change = address(path, scriptType, 29, isChange = true)
            val found = service.findAddresses(setOf(receive, change), account(path), scriptType, scanRange = range)

            assertEquals(
                mapOf(
                    receive to service.checkAddressBelongsToWallet(receive, account(path), scriptType, scanRange = range),
                    change to service.checkAddressBelongsToWallet(change, account(path), scriptType, scanRange = range)
                ),
                found
            )
        }
    }

    @Test
    fun addressesOutsideTheWalletOrRangeAreAbsent() {
        val path = "m/84'/0'/0'"
        val beyondRange = address(path, ScriptType.P2WPKH, range, isChange = false)
        val otherAccount = address("m/84'/0'/1'", ScriptType.P2WPKH, 0, isChange = false)
        val ours = address(path, ScriptType.P2WPKH, 0, isChange = false)

        val found = service.findAddresses(setOf(beyondRange, otherAccount, ours), account(path), ScriptType.P2WPKH, scanRange = range)
        assertEquals(setOf(ours), found.keys)
        assertTrue(service.findAddresses(emptySet(), account(path), ScriptType.P2WPKH, scanRange = range).isEmpty())
    }

    @Test
    fun testnetAddressesAreFoundOnTestnet() {
        val path = "m/84'/1'/0'"
        val testnet = address(path, ScriptType.P2WPKH, 2, isChange = true, isTestnet = true)
        val found = service.findAddresses(setOf(testnet), account(path), ScriptType.P2WPKH, isTestnet = true, scanRange = range)
        assertEquals("m/84'/1'/0'/1/2", found.getValue(testnet).derivationPath)
        assertTrue(service.findAddresses(setOf(testnet), account(path), ScriptType.P2WPKH, scanRange = range).isEmpty())
    }
}
