package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.data.model.InputSigningRefusal
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.SigHash
import com.gorunjinian.metrovault.lib.bitcoin.UpdateFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BIP-341's taproot sighash allow-list, and the mapping from library failures to the messages a
 * cold-storage user actually reads.
 */
class TaprootSighashPolicyTest {

    @Test
    fun acceptsExactlyTheBip341SighashTypes() {
        val allowed = listOf(0x00, 0x01, 0x02, 0x03, 0x81, 0x82, 0x83)
        allowed.forEach {
            assertTrue("0x${it.toString(16)} is a valid taproot sighash type", SigHash.isValidTaproot(it))
        }
    }

    @Test
    fun rejectsEverythingElse() {
        // 0x80 is bare ANYONECANPAY with no output type — not a valid standalone sighash.
        // -1 is the dangerous one: PSBT_IN_SIGHASH_TYPE is parsed as a signed Int, so a declared
        // 0xFFFFFFFF arrives here and would otherwise mask into SIGHASH_SINGLE | ANYONECANPAY.
        val rejected = listOf(0x04, 0x41, 0x80, 0x84, 0xFF, -1, Int.MIN_VALUE, Int.MAX_VALUE)
        rejected.forEach {
            assertFalse("0x${it.toString(16)} is not a valid taproot sighash type", SigHash.isValidTaproot(it))
        }
    }

    /**
     * Bitcoin Core's `IsDefinedHashtypeSignature`: strip ANYONECANPAY, the remainder must be in
     * SIGHASH_ALL..SIGHASH_SINGLE. Note 0x00 is valid for taproot but *not* for ECDSA — the two
     * allow-lists genuinely differ, which is why they are separate predicates.
     */
    @Test
    fun ecdsaAcceptsOnlyTheDefinedHashtypes() {
        listOf(0x01, 0x02, 0x03, 0x81, 0x82, 0x83).forEach {
            assertTrue("0x${it.toString(16)} is a defined ECDSA hashtype", SigHash.isValidEcdsa(it))
        }
    }

    @Test
    fun ecdsaRejectsTaprootDefaultAndUndefinedTypes() {
        // 0x00 (taproot-only), 0x80 (bare ANYONECANPAY), 0x41/0x84 (undefined),
        // 0x101 (low byte looks like SIGHASH_ALL but the digest commits to the full 32 bits),
        // and the signed-parse artefacts.
        listOf(0x00, 0x80, 0x41, 0x84, 0x101, 0xFF, -1, Int.MIN_VALUE, Int.MAX_VALUE).forEach {
            assertFalse("0x${it.toUInt().toString(16)} is not a defined ECDSA hashtype", SigHash.isValidEcdsa(it))
        }
    }

    @Test
    fun theTwoAllowListsDisagreeOnlyWhereTheSpecsDo() {
        // SIGHASH_DEFAULT is the sole difference between the taproot and ECDSA sets.
        assertTrue(SigHash.isValidTaproot(SigHash.SIGHASH_DEFAULT))
        assertFalse(SigHash.isValidEcdsa(SigHash.SIGHASH_DEFAULT))
        listOf(0x01, 0x02, 0x03, 0x81, 0x82, 0x83).forEach {
            assertEquals(
                "0x${it.toString(16)} should be accepted by both",
                SigHash.isValidTaproot(it),
                SigHash.isValidEcdsa(it),
            )
        }
    }

    @Test
    fun mapsTaprootScriptTreeFailureToAProvenMessage() {
        val refusal = InputSigningRefusal.from(
            2,
            UpdateFailure.CannotSignTaprootScriptTree(2, ByteVector32(ByteArray(32) { 1 })),
        )

        assertEquals(2, refusal.inputIndex)
        assertTrue(refusal is InputSigningRefusal.TaprootScriptTree)
        assertTrue((refusal as InputSigningRefusal.TaprootScriptTree).merkleRootProven)
        assertTrue("message should name the input", refusal.message.contains("#2"))
        assertTrue("message should mention the script tree", refusal.message.contains("script tree"))
    }

    @Test
    fun mapsUnprovenScriptTreeFailureToAHedgedMessage() {
        val refusal = InputSigningRefusal.from(0, UpdateFailure.CannotSignTaprootScriptTree(0, null))

        assertFalse((refusal as InputSigningRefusal.TaprootScriptTree).merkleRootProven)
        assertTrue("message should hedge", refusal.message.contains("likely"))
    }

    @Test
    fun mapsScriptPathKeyFailure() {
        val refusal = InputSigningRefusal.from(1, UpdateFailure.CannotSignTaprootScriptPathKey(1))

        assertTrue(refusal is InputSigningRefusal.TaprootScriptPathKey)
        assertTrue(refusal.message.contains("script path"))
    }

    @Test
    fun mapsUnsupportedSighashFailureAndShowsTheTypeInHex() {
        val refusal = InputSigningRefusal.from(3, UpdateFailure.UnsupportedSighashType(3, 0x41))

        assertTrue(refusal is InputSigningRefusal.UnsupportedSighash)
        assertEquals(0x41, (refusal as InputSigningRefusal.UnsupportedSighash).sighashType)
        assertTrue("message should show the offending type", refusal.message.contains("0x41"))
    }

    @Test
    fun fallsBackToTheRawReasonForUnrecognisedFailures() {
        val refusal = InputSigningRefusal.from(4, UpdateFailure.CannotSignInput(4, "already finalized"))

        assertTrue(refusal is InputSigningRefusal.Other)
        assertTrue(refusal.message.contains("already finalized"))
    }
}
