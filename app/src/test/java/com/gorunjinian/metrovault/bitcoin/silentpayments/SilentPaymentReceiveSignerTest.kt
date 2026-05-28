package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.data.model.SpSpendingError
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentReceiveSigner
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentWalletService
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.Crypto
import com.gorunjinian.metrovault.lib.bitcoin.DataEntry
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.Global
import com.gorunjinian.metrovault.lib.bitcoin.Input
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.lib.bitcoin.OutPoint
import com.gorunjinian.metrovault.lib.bitcoin.Output
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.Psbt
import com.gorunjinian.metrovault.lib.bitcoin.Satoshi
import com.gorunjinian.metrovault.lib.bitcoin.Script
import com.gorunjinian.metrovault.lib.bitcoin.SigHash
import com.gorunjinian.metrovault.lib.bitcoin.Transaction
import com.gorunjinian.metrovault.lib.bitcoin.TxId
import com.gorunjinian.metrovault.lib.bitcoin.TxIn
import com.gorunjinian.metrovault.lib.bitcoin.TxOut
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
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
    ): Psbt {
        val d = keys.spendPrivateKey + PrivateKey(outputKeyTweak)
        val scriptPubKey = if (taprootInput) {
            Script.write(Script.pay2tr(d.xOnlyPublicKey())).byteVector()
        } else {
            Script.write(Script.pay2wpkh(keys.spendPublicKey)).byteVector()
        }
        val unknown = inputTweak?.let { listOf(DataEntry(ByteVector("20"), it)) } ?: emptyList()
        val input = Input.WitnessInput.PartiallySignedWitnessInput(
            TxOut(Satoshi(50_000), scriptPubKey), null, null, emptyMap(), emptyMap(), null, null,
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
