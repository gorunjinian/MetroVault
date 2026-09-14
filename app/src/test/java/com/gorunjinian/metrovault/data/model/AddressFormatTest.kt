package com.gorunjinian.metrovault.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the mapping the "Address Type & Network" screen and `Wallet.changeDerivation` rely on:
 * a stored derivation path resolves to exactly one [AddressFormat], each format maps back to its
 * base path on either network, and the labels users see carry the right prefix.
 */
class AddressFormatTest {

    @Test
    fun `every format round-trips through its base path on both networks`() {
        for (format in AddressFormat.entries) {
            for (testnet in listOf(false, true)) {
                val base = DerivationPaths.baseForFormat(format, testnet)
                assertEquals(format, AddressFormat.fromPath(base))
                assertEquals(testnet, DerivationPaths.isTestnet(base))
                assertEquals(format.purpose, DerivationPaths.getPurpose(base))
            }
        }
    }

    @Test
    fun `account-level paths resolve by purpose regardless of account or coin type`() {
        assertEquals(AddressFormat.TAPROOT, AddressFormat.fromPath("m/86'/0'/0'"))
        assertEquals(AddressFormat.NATIVE_SEGWIT, AddressFormat.fromPath("m/84'/1'/9'"))
        assertEquals(AddressFormat.NESTED_SEGWIT, AddressFormat.fromPath("m/49'/0'/2'"))
        assertEquals(AddressFormat.LEGACY, AddressFormat.fromPath("m/44'/1'/0'"))
        assertEquals(AddressFormat.SILENT_PAYMENTS, AddressFormat.fromPath("m/352'/0'/3'"))
    }

    @Test
    fun `unknown purpose falls back to native segwit like getScriptType does`() {
        assertEquals(AddressFormat.NATIVE_SEGWIT, AddressFormat.fromPath("m/1000'/0'/0'"))
        assertEquals(AddressFormat.NATIVE_SEGWIT, AddressFormat.fromPath(""))
    }

    @Test
    fun `only silent payments has no script type`() {
        for (format in AddressFormat.entries) {
            if (format == AddressFormat.SILENT_PAYMENTS) {
                assertTrue(format.isSilentPayment)
                assertNull(format.scriptType)
            } else {
                assertFalse(format.isSilentPayment)
                assertEquals(DerivationPaths.getScriptType(DerivationPaths.baseForFormat(format, false)), format.scriptType)
            }
        }
    }

    @Test
    fun `labels carry the network-specific prefix`() {
        assertEquals("Native SegWit (bc1q…)", AddressFormat.NATIVE_SEGWIT.label(testnet = false))
        assertEquals("Native SegWit (tb1q…)", AddressFormat.NATIVE_SEGWIT.label(testnet = true))
        assertEquals("Taproot (bc1p…)", AddressFormat.TAPROOT.label(testnet = false))
        assertEquals("Nested SegWit (2…)", AddressFormat.NESTED_SEGWIT.label(testnet = true))
        assertEquals("Legacy (1…)", AddressFormat.LEGACY.label(testnet = false))
        assertEquals("Silent Payments (sp1q…)", AddressFormat.SILENT_PAYMENTS.label(testnet = false))
        assertEquals("Silent Payments (tsp1q…)", AddressFormat.SILENT_PAYMENTS.label(testnet = true))
    }
}
