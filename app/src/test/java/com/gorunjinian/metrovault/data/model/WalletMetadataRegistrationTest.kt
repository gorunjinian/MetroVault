package com.gorunjinian.metrovault.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the multisig registration/verification binding logic on [WalletMetadata].
 *
 * The security property: a multisig wallet counts as "registered" (signing-eligible) only when it
 * was explicitly verified AND the stored descriptor checksum matches the current one. A changed
 * descriptor therefore auto-resets to unverified.
 */
class WalletMetadataRegistrationTest {

    private fun multisig(verified: Boolean, storedChecksum: String) = WalletMetadata(
        id = "w1",
        name = "Multisig",
        derivationPath = "multisig/2of3",
        masterFingerprint = "1a2b3c4d",
        hasPassphrase = false,
        createdAt = 0L,
        isMultisig = true,
        multisigVerified = verified,
        multisigVerifiedDescriptorChecksum = storedChecksum
    )

    @Test
    fun registered_whenVerifiedAndChecksumMatches() {
        assertTrue(multisig(verified = true, storedChecksum = "abcd1234").isMultisigRegistered("abcd1234"))
    }

    @Test
    fun notRegistered_whenChecksumDiffers() {
        // Descriptor changed since verification → binding broken → unverified
        assertFalse(multisig(verified = true, storedChecksum = "abcd1234").isMultisigRegistered("zzzz9999"))
    }

    @Test
    fun notRegistered_whenNotVerified() {
        assertFalse(multisig(verified = false, storedChecksum = "").isMultisigRegistered("abcd1234"))
    }

    @Test
    fun notRegistered_whenStoredChecksumEmpty() {
        // Empty stored checksum must never count as registered, even against an empty current checksum
        assertFalse(multisig(verified = true, storedChecksum = "").isMultisigRegistered(""))
    }

    @Test
    fun notRegistered_whenSingleSig() {
        val singleSig = WalletMetadata(
            id = "w2",
            name = "Single",
            derivationPath = "m/84'/0'/0'",
            masterFingerprint = "1a2b3c4d",
            hasPassphrase = false,
            createdAt = 0L
        )
        assertFalse(singleSig.isMultisigRegistered(""))
    }
}
