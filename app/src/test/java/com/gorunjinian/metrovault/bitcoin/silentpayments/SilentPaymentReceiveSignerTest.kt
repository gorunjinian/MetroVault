package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.data.model.SpSpendingError
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentReceiveSigner
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentWalletService
import com.gorunjinian.vaultovich.ByteVector
import com.gorunjinian.vaultovich.ByteVector32
import com.gorunjinian.vaultovich.Crypto
import com.gorunjinian.vaultovich.DataEntry
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.Global
import com.gorunjinian.vaultovich.Input
import com.gorunjinian.vaultovich.KeyPath
import com.gorunjinian.vaultovich.MnemonicCode
import com.gorunjinian.vaultovich.OutPoint
import com.gorunjinian.vaultovich.Output
import com.gorunjinian.vaultovich.PrivateKey
import com.gorunjinian.vaultovich.Psbt
import com.gorunjinian.vaultovich.Satoshi
import com.gorunjinian.vaultovich.Script
import com.gorunjinian.vaultovich.SigHash
import com.gorunjinian.vaultovich.Transaction
import com.gorunjinian.vaultovich.TxId
import com.gorunjinian.vaultovich.TxIn
import com.gorunjinian.vaultovich.TxOut
import com.gorunjinian.vaultovich.byteVector
import com.gorunjinian.vaultovich.utils.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentPaymentReceiveSignerTest {
    private val seed = MnemonicCode.toSeed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "), ""
    )
    private val master = DeterministicWallet.generate(seed)
    private val keys = SilentPaymentWalletService.deriveKeys(master, account = 0, isTestnet = true)
    private val op0 = OutPoint(TxId(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")), 0L)
    private val tweak = ByteVector32("0303030303030303030303030303030303030303030303030303030303030303")

    /** Build a PSBT spending an SP output: P_k = (b_spend + tweak)·G, scriptPubKey = P2TR(P_k). */
    private fun spSpendingPsbt(
        inputTweak: ByteVector32?,
        outputKeyTweak: ByteVector32 = tweak,
        taprootInput: Boolean = true,
        sighashType: Int? = null,
    ): Psbt {
        val d = keys.spendPrivateKey + PrivateKey(outputKeyTweak)
        val scriptPubKey = if (taprootInput) {
            Script.write(Script.pay2tr(d.xOnlyPublicKey())).byteVector()
        } else {
            Script.write(Script.pay2wpkh(keys.spendPublicKey)).byteVector()
        }
        val unknown = inputTweak?.let { listOf(DataEntry(ByteVector("20"), it)) } ?: emptyList()
        val input = Input.WitnessInput.PartiallySignedWitnessInput(
            TxOut(Satoshi(50_000), scriptPubKey), null, sighashType, emptyMap(), emptyMap(), null, null,
            emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null, unknown
        )
        val recipientOut = TxOut(Satoshi(40_000), Script.pay2wpkh(keys.spendPublicKey))
        val tx = Transaction(2, listOf(TxIn(op0, ByteVector.empty, 0xfffffffeL)), listOf(recipientOut), 0)
        val output = Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), emptyList())
        return Psbt(Global(2, tx, emptyList(), emptyList(), fallbackLocktime = 0L), listOf(input), listOf(output))
    }

    @Test
    fun signsReceivedSilentPaymentInputWithValidSchnorrSig() {
        val psbt = spSpendingPsbt(inputTweak = tweak)
        val result = SilentPaymentReceiveSigner.signTweakedInputs(psbt, keys.spendPrivateKey)
        assertTrue("expected Right, got $result", result is Either.Right)
        val signed = (result as Either.Right).value

        val sig = signed.inputs[0].taprootKeySignature
        assertNotNull("input must be signed", sig)
        assertEquals(64, sig!!.size())

        // The signature must verify against the output key P_k = (b_spend + tweak)·G.
        val pk = (keys.spendPrivateKey + PrivateKey(tweak)).xOnlyPublicKey()
        val sighash = signed.global.tx.hashForSigningTaprootKeyPath(
            0, listOf(signed.inputs[0].witnessUtxo!!), SigHash.SIGHASH_DEFAULT
        )
        assertTrue("Schnorr signature must verify against P_k", Crypto.verifySignatureSchnorr(sighash, sig, pk))
    }

    @Test
    fun rejectsWrongTweak() {
        // The PSBT declares a tweak that does NOT derive the input's output key.
        val wrongTweak = ByteVector32("0404040404040404040404040404040404040404040404040404040404040404")
        val psbt = spSpendingPsbt(inputTweak = wrongTweak, outputKeyTweak = tweak)
        val result = SilentPaymentReceiveSigner.signTweakedInputs(psbt, keys.spendPrivateKey)
        assertTrue(result is Either.Left)
        assertTrue((result as Either.Left).value is SpSpendingError.TweakMismatch)
    }

    /**
     * PSBT_IN_SIGHASH_TYPE is parsed as a signed Int, so a declared 0xFFFFFFFF arrives as -1. It
     * slips past `hashForSigningSchnorr`'s `sighashType <= 0x03` guard and would be masked into
     * SIGHASH_SINGLE | SIGHASH_ANYONECANPAY — a real signature over semantics never shown to the user.
     */
    @Test
    fun rejectsNegativeSighashTypeOnReceiveSpend() {
        val psbt = spSpendingPsbt(inputTweak = tweak, sighashType = -1)
        val result = SilentPaymentReceiveSigner.signTweakedInputs(psbt, keys.spendPrivateKey)
        assertTrue("a negative sighash type must be refused, not masked: $result", result is Either.Left)
        assertTrue((result as Either.Left).value is SpSpendingError.UnsupportedSighashType)
    }

    /** 0x41 makes `hashForSigningSchnorr` throw, escaping the Either contract entirely. */
    @Test
    fun rejectsOutOfRangeSighashTypeOnReceiveSpend() {
        val psbt = spSpendingPsbt(inputTweak = tweak, sighashType = 0x41)
        val result = SilentPaymentReceiveSigner.signTweakedInputs(psbt, keys.spendPrivateKey)
        assertTrue("0x41 must be refused without throwing: $result", result is Either.Left)
        assertTrue((result as Either.Left).value is SpSpendingError.UnsupportedSighashType)
    }

    /** BIP-341's valid non-default types must still sign, with the byte appended. */
    @Test
    fun signsReceiveSpendWithSighashAll() {
        val psbt = spSpendingPsbt(inputTweak = tweak, sighashType = SigHash.SIGHASH_ALL)
        val result = SilentPaymentReceiveSigner.signTweakedInputs(psbt, keys.spendPrivateKey)
        assertTrue("expected Right, got $result", result is Either.Right)
        val sig = (result as Either.Right).value.inputs[0].taprootKeySignature!!
        assertEquals(65, sig.size())
        assertEquals(SigHash.SIGHASH_ALL.toByte(), sig[64])
    }

    @Test
    fun rejectsNonTaprootInput() {
        val psbt = spSpendingPsbt(inputTweak = tweak, taprootInput = false)
        val result = SilentPaymentReceiveSigner.signTweakedInputs(psbt, keys.spendPrivateKey)
        assertTrue(result is Either.Left)
        assertTrue((result as Either.Left).value is SpSpendingError.InputNotTaproot)
    }

    @Test
    fun reportsNoTweakInputs() {
        val psbt = spSpendingPsbt(inputTweak = null)
        val result = SilentPaymentReceiveSigner.signTweakedInputs(psbt, keys.spendPrivateKey)
        assertTrue(result is Either.Left)
        assertEquals(SpSpendingError.NoTweakInputs, (result as Either.Left).value)
    }

    @Test
    fun signedPsbtRoundTripsAsV2() {
        val signed = (SilentPaymentReceiveSigner.signTweakedInputs(spSpendingPsbt(tweak), keys.spendPrivateKey) as Either.Right).value
        val reread = (Psbt.read(Psbt.write(signed).toByteArray()) as Either.Right).value
        assertEquals(2L, reread.global.version)
        assertNotNull(reread.inputs[0].taprootKeySignature)
    }
}
