package com.gorunjinian.metrovault.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the network flip inside `Wallet.changeDerivation`: `forNetwork` flips the coin type while
 * keeping the BIP purpose, and `withAccountNumber` on that base re-applies the active account.
 * A wallet moved to testnet and back must land on exactly the path it started from.
 */
class DerivationPathsNetworkTest {

    private val purposes = listOf(84, 86, 49, 44, 352)

    private fun rewrite(path: String, testnet: Boolean, account: Int): String =
        DerivationPaths.withAccountNumber(DerivationPaths.forNetwork(path, testnet), account)

    @Test
    fun `mainnet to testnet keeps purpose and account`() {
        for (purpose in purposes) {
            val original = "m/$purpose'/0'/3'"
            val moved = rewrite(original, testnet = true, account = 3)
            assertEquals("m/$purpose'/1'/3'", moved)
            assertTrue(DerivationPaths.isTestnet(moved))
            assertEquals(purpose, DerivationPaths.getPurpose(moved))
            assertEquals(3, DerivationPaths.getAccountNumber(moved))
        }
    }

    @Test
    fun `testnet to mainnet keeps purpose and account`() {
        for (purpose in purposes) {
            val original = "m/$purpose'/1'/7'"
            val moved = rewrite(original, testnet = false, account = 7)
            assertEquals("m/$purpose'/0'/7'", moved)
            assertFalse(DerivationPaths.isTestnet(moved))
            assertEquals(purpose, DerivationPaths.getPurpose(moved))
            assertEquals(7, DerivationPaths.getAccountNumber(moved))
        }
    }

    @Test
    fun `round trip restores the original path`() {
        for (purpose in purposes) {
            val original = "m/$purpose'/0'/2'"
            val there = rewrite(original, testnet = true, account = 2)
            val back = rewrite(there, testnet = false, account = 2)
            assertEquals(original, back)
        }
    }

    @Test
    fun `address format survives the network flip`() {
        for (format in AddressFormat.entries) {
            val mainnet = DerivationPaths.baseForFormat(format, testnet = false)
            val moved = rewrite(mainnet, testnet = true, account = 0)
            assertEquals(format, AddressFormat.fromPath(moved))
            assertEquals(DerivationPaths.baseForFormat(format, testnet = true), moved)
            format.scriptType?.let { assertEquals(it, DerivationPaths.getScriptType(moved)) }
        }
    }

    @Test
    fun `silent payment path maps to the silent payment testnet constant`() {
        assertEquals(
            DerivationPaths.SILENT_PAYMENT_TESTNET,
            rewrite(DerivationPaths.SILENT_PAYMENT, testnet = true, account = 0)
        )
        assertEquals(
            DerivationPaths.SILENT_PAYMENT,
            rewrite(DerivationPaths.SILENT_PAYMENT_TESTNET, testnet = false, account = 0)
        )
    }

    @Test
    fun `active account wins over the account embedded in the stored path`() {
        // Metadata may carry a path at account 0 while activeAccountNumber is higher; the rewrite
        // must follow the active account, matching what getActiveDerivationPath() would resolve.
        val moved = rewrite("m/84'/0'/0'", testnet = true, account = 5)
        assertEquals("m/84'/1'/5'", moved)
    }
}
