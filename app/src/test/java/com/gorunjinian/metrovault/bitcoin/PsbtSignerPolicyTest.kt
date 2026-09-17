package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.data.model.InputSigningRefusal
import com.gorunjinian.metrovault.domain.service.psbt.PsbtSigner
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
import com.gorunjinian.vaultovich.ByteVector
import com.gorunjinian.vaultovich.ByteVector32
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.Global
import com.gorunjinian.vaultovich.Input
import com.gorunjinian.vaultovich.KeyPath
import com.gorunjinian.vaultovich.KeyPathWithMaster
import com.gorunjinian.vaultovich.MnemonicCode
import com.gorunjinian.vaultovich.OutPoint
import com.gorunjinian.vaultovich.Output
import com.gorunjinian.vaultovich.Psbt
import com.gorunjinian.vaultovich.Satoshi
import com.gorunjinian.vaultovich.Script
import com.gorunjinian.vaultovich.ScriptType
import com.gorunjinian.vaultovich.SigHash
import com.gorunjinian.vaultovich.Transaction
import com.gorunjinian.vaultovich.TxId
import com.gorunjinian.vaultovich.TxIn
import com.gorunjinian.vaultovich.TxOut
import com.gorunjinian.vaultovich.utils.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The previous-transaction rule for segwit v0 inputs as MetroVault applies it on top of
 * vaultovich's strict default signing policy.
 *
 * The BIP-143 fee attack needs two or more inputs and two signing rounds, so a single-input
 * transaction signs without `PSBT_IN_NON_WITNESS_UTXO`. A multi-input transaction that omits it
 * is refused with nothing signed — releasing the inputs that *could* be signed would hand out the
 * first round of the attack — until the user waives the check with "Sign Anyway", which relaxes
 * that one rule and nothing else.
 */
class PsbtSignerPolicyTest {

    private val seed = MnemonicCode.toSeed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "),
        ""
    )
    private val master = DeterministicWallet.generate(seed)
    private val accountPath = KeyPath("m/84'/1'/0'")
    private val accountKey = master.derivePrivateKey(accountPath)
    private val fingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey)

    private fun key(index: Int) = master.derivePrivateKey(KeyPath("m/84'/1'/0'/0/$index"))

    /** A P2WPKH coin of this wallet together with the transaction that created it. */
    private class Coin(val prevTx: Transaction, val txOut: TxOut) {
        val outPoint: OutPoint get() = OutPoint(prevTx, 0L)
    }

    private fun coin(index: Int, amount: Long = 50_000): Coin {
        val txOut = TxOut(Satoshi(amount), Script.pay2wpkh(key(index).publicKey))
        val prevTx = Transaction(
            2,
            listOf(TxIn(OutPoint(TxId(ByteVector32.Zeroes), 0xffffffffL), ByteVector.empty, 0xffffffffL)),
            listOf(txOut),
            0
        )
        return Coin(prevTx, txOut)
    }

    private fun input(coin: Coin, index: Int, withPrevTx: Boolean, sighash: Int? = null) =
        Input.WitnessInput.PartiallySignedWitnessInput(
            coin.txOut, if (withPrevTx) coin.prevTx else null, sighash, emptyMap(),
            mapOf(key(index).publicKey to KeyPathWithMaster(fingerprint, KeyPath("m/84'/1'/0'/0/$index"))),
            null, null, emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null, emptyList()
        )

    private fun psbtOf(coins: List<Coin>, inputs: List<Input>): Psbt {
        val txIns = coins.map { TxIn(it.outPoint, ByteVector.empty, 0xfffffffeL) }
        val total = coins.sumOf { it.txOut.amount.toLong() }
        val tx = Transaction(2, txIns, listOf(TxOut(Satoshi(total - 1_000), Script.pay2wpkh(key(9).publicKey))), 0)
        val output = Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), emptyList())
        return Psbt(Global(0, tx, emptyList(), emptyList()), inputs, listOf(output))
    }

    private fun sign(psbt: Psbt, trustWitnessUtxo: Boolean = false) = PsbtSigner.signParsedPsbt(
        psbt, master, accountKey, ScriptType.P2WPKH,
        isTestnet = true, accountPath = accountPath, trustWitnessUtxo = trustWitnessUtxo,
    )

    @Test
    fun singleInputSignsWithoutThePreviousTransaction() {
        val a = coin(0)
        val result = sign(psbtOf(listOf(a), listOf(input(a, 0, withPrevTx = false))))

        assertTrue("a single-input spend needs no previous transaction: $result", result is Either.Right)
        val signed = (result as Either.Right).value
        assertTrue(signed.refusals.isEmpty())
        assertTrue(signed.psbt.inputs[0].partialSigs.isNotEmpty())
    }

    @Test
    fun multiInputWithoutPreviousTransactionsIsRefusedWithNothingSigned() {
        val (a, b) = listOf(coin(0), coin(1))
        val result = sign(psbtOf(listOf(a, b), listOf(input(a, 0, withPrevTx = false), input(b, 1, withPrevTx = false))))

        assertTrue("expected a refusal: $result", result is Either.Left)
        val refusals = (result as Either.Left).value
        assertEquals(listOf(0, 1), refusals.map { it.inputIndex })
        assertTrue(refusals.all { it is InputSigningRefusal.MissingPreviousTransaction })
    }

    @Test
    fun signaturesAreWithheldWhenOnlySomeInputsCarryThePreviousTransaction() {
        // Input 0 could be signed on its own. Releasing it would be the first round of the attack.
        val (a, b) = listOf(coin(0), coin(1))
        val result = sign(psbtOf(listOf(a, b), listOf(input(a, 0, withPrevTx = true), input(b, 1, withPrevTx = false))))

        assertTrue("the whole attempt must be held back: $result", result is Either.Left)
        val refusals = (result as Either.Left).value
        assertEquals(listOf(1), refusals.map { it.inputIndex })
        assertTrue(refusals.single() is InputSigningRefusal.MissingPreviousTransaction)
    }

    @Test
    fun multiInputWithPreviousTransactionsSignsWithoutTheOverride() {
        val (a, b) = listOf(coin(0), coin(1))
        val result = sign(psbtOf(listOf(a, b), listOf(input(a, 0, withPrevTx = true), input(b, 1, withPrevTx = true))))

        assertTrue("$result", result is Either.Right)
        val signed = (result as Either.Right).value
        assertTrue(signed.refusals.isEmpty())
        assertTrue(signed.psbt.inputs.all { it.partialSigs.isNotEmpty() })
    }

    @Test
    fun signAnywaySignsEveryInputWithoutPreviousTransactions() {
        val (a, b) = listOf(coin(0), coin(1))
        val psbt = psbtOf(listOf(a, b), listOf(input(a, 0, withPrevTx = false), input(b, 1, withPrevTx = false)))

        val result = sign(psbt, trustWitnessUtxo = true)

        assertTrue("the override must sign: $result", result is Either.Right)
        val signed = (result as Either.Right).value
        assertTrue(signed.refusals.isEmpty())
        assertTrue(signed.psbt.inputs.all { it.partialSigs.isNotEmpty() })
    }

    @Test
    fun theOverrideWaivesNothingElse() {
        // SIGHASH_NONE is well-defined but outside the policy: still refused, and only that input.
        val (a, b) = listOf(coin(0), coin(1))
        val psbt = psbtOf(
            listOf(a, b),
            listOf(input(a, 0, withPrevTx = false, sighash = SigHash.SIGHASH_NONE), input(b, 1, withPrevTx = false))
        )

        val result = sign(psbt, trustWitnessUtxo = true)

        assertTrue("$result", result is Either.Right)
        val signed = (result as Either.Right).value
        assertTrue(signed.refusals.single() is InputSigningRefusal.SighashNotAllowed)
        assertTrue(signed.psbt.inputs[0].partialSigs.isEmpty())
        assertTrue(signed.psbt.inputs[1].partialSigs.isNotEmpty())
    }
}
