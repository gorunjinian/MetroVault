package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.Global
import com.gorunjinian.metrovault.lib.bitcoin.Input
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.OutPoint
import com.gorunjinian.metrovault.lib.bitcoin.Output
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.Psbt
import com.gorunjinian.metrovault.lib.bitcoin.Satoshi
import com.gorunjinian.metrovault.lib.bitcoin.Script
import com.gorunjinian.metrovault.lib.bitcoin.ScriptFlags
import com.gorunjinian.metrovault.lib.bitcoin.ScriptWitness
import com.gorunjinian.metrovault.lib.bitcoin.SigHash
import com.gorunjinian.metrovault.lib.bitcoin.Transaction
import com.gorunjinian.metrovault.lib.bitcoin.TxHash
import com.gorunjinian.metrovault.lib.bitcoin.TxIn
import com.gorunjinian.metrovault.lib.bitcoin.TxOut
import com.gorunjinian.metrovault.lib.bitcoin.UpdateFailure
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sighash validation on the legacy / segwit-v0 (ECDSA) signing paths.
 *
 * These branches previously signed with whatever `PSBT_IN_SIGHASH_TYPE` declared. Two things went
 * wrong, both silently: an undefined hashtype byte produced a signature no verifier accepts, and a
 * value wider than one byte made the digest (which commits to all 32 bits) disagree with the byte
 * appended to the signature.
 */
class SegwitV0SighashTest {

    private val seed = ByteVector(ByteArray(32) { 2 })
    private val addressPath = KeyPath("m/84'/1'/0'/0/0")

    private fun signingKey(): PrivateKey =
        DeterministicWallet.generate(seed).derivePrivateKey(addressPath).privateKey

    /** A single-input, single-output P2WPKH PSBT. */
    private fun p2wpkhPsbt(sighashType: Int? = null): Psbt {
        val publicKey = signingKey().publicKey()
        val scriptPubKey = Script.write(Script.pay2wpkh(publicKey)).byteVector()
        val previousOutput = TxOut(Satoshi(100_000), scriptPubKey)
        val tx = Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(TxHash("aa".repeat(32)), 0), ByteVector.empty, TxIn.SEQUENCE_FINAL)),
            txOut = listOf(TxOut(Satoshi(90_000), scriptPubKey)),
            lockTime = 0,
        )
        val input = Input.WitnessInput.PartiallySignedWitnessInput(
            previousOutput, null, sighashType, emptyMap(), emptyMap(), null, null,
            emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null, emptyList(),
        )
        return Psbt(
            Global(version = 0, tx = tx, extendedPublicKeys = emptyList(), unknown = emptyList()),
            listOf(input),
            listOf(Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), emptyList())),
        )
    }

    private fun assertSignsAndVerifies(sighashType: Int?) {
        val key = signingKey()
        val psbt = p2wpkhPsbt(sighashType)
        val signed = (psbt.sign(key, 0) as Either.Right).value.psbt
        val sig = signed.inputs[0].partialSigs[key.publicKey()]!!

        assertEquals(
            "the appended byte must match the declared sighash type",
            (sighashType ?: SigHash.SIGHASH_ALL).toByte(),
            sig[sig.size() - 1],
        )

        val finalized = (
            signed.finalizeWitnessInput(0, ScriptWitness(listOf(sig, key.publicKey().value))) as Either.Right
            ).value
        val tx = (finalized.extract() as Either.Right).value
        Transaction.correctlySpends(
            tx,
            mapOf(tx.txIn[0].outPoint to psbt.inputs[0].witnessUtxo!!),
            ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS,
        )
    }

    @Test
    fun signsAndVerifiesWithNoDeclaredSighashType() = assertSignsAndVerifies(null)

    @Test
    fun signsAndVerifiesWithSighashAll() = assertSignsAndVerifies(SigHash.SIGHASH_ALL)

    @Test
    fun signsAndVerifiesWithSighashSingleAnyoneCanPay() = assertSignsAndVerifies(0x83)

    /** SIGHASH_DEFAULT is taproot-only; on segwit v0 the trailing 0x00 byte is undefined. */
    @Test
    fun refusesSighashDefaultOnSegwitV0() {
        val result = p2wpkhPsbt(SigHash.SIGHASH_DEFAULT).sign(signingKey(), 0)

        assertTrue("0x00 is not a defined ECDSA hashtype: $result", result is Either.Left)
        assertTrue((result as Either.Left).value is UpdateFailure.UnsupportedSighashType)
    }

    @Test
    fun refusesUndefinedSighashTypeOnSegwitV0() {
        val result = p2wpkhPsbt(0x41).sign(signingKey(), 0)

        assertTrue("0x41 is not a defined ECDSA hashtype: $result", result is Either.Left)
        assertTrue((result as Either.Left).value is UpdateFailure.UnsupportedSighashType)
    }

    /**
     * The subtle one: the low byte looks like SIGHASH_ALL, so the verifier recomputes the digest
     * with 0x00000001 while we signed over 0x00000101. The signature could never verify.
     */
    @Test
    fun refusesSighashTypeWiderThanOneByte() {
        val result = p2wpkhPsbt(0x101).sign(signingKey(), 0)

        assertTrue("0x101 must be refused: $result", result is Either.Left)
        assertTrue((result as Either.Left).value is UpdateFailure.UnsupportedSighashType)
    }

    @Test
    fun refusesNegativeSighashTypeOnSegwitV0() {
        val result = p2wpkhPsbt(-1).sign(signingKey(), 0)

        assertTrue("a negative sighash type must be refused: $result", result is Either.Left)
        assertTrue((result as Either.Left).value is UpdateFailure.UnsupportedSighashType)
    }
}
