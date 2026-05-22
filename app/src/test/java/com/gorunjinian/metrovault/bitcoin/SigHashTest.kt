package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.Base58
import com.gorunjinian.metrovault.lib.bitcoin.OP_PUSHDATA
import com.gorunjinian.metrovault.lib.bitcoin.OutPoint
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.Script
import com.gorunjinian.metrovault.lib.bitcoin.ScriptFlags
import com.gorunjinian.metrovault.lib.bitcoin.ScriptWitness
import com.gorunjinian.metrovault.lib.bitcoin.SigHash.SIGHASH_ALL
import com.gorunjinian.metrovault.lib.bitcoin.SigHash.SIGHASH_ANYONECANPAY
import com.gorunjinian.metrovault.lib.bitcoin.SigVersion
import com.gorunjinian.metrovault.lib.bitcoin.Transaction
import com.gorunjinian.metrovault.lib.bitcoin.TxIn
import com.gorunjinian.metrovault.lib.bitcoin.TxOut
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import com.gorunjinian.metrovault.lib.bitcoin.toSatoshi
import com.gorunjinian.metrovault.lib.bitcoin.updated
import org.junit.Assert.assertTrue
import org.junit.Test

class SigHashTest {

    @Test
    fun sighashAnyOneCanPayPermitsAddingInputs_legacyP2PKH() {
        val privateKeys = listOf(
            PrivateKey.fromBase58("cV7LGVeY2VPuCyCSarqEqFCUNig2NzwiAEBTTA89vNRQ4Vqjfurs", Base58.Prefix.SecretKeyTestnet).first,
            PrivateKey.fromBase58("cV5oyXUgySSMcUvKNdKtuYg4t4NTaxkwYrrocgsJZuYac2ogEdZX", Base58.Prefix.SecretKeyTestnet).first
        )
        val publicKeys = privateKeys.map { it.publicKey() }
        val previousTx = listOf(
            Transaction(version = 2, txIn = listOf(), txOut = listOf(TxOut(4_200_000L.toSatoshi(), Script.pay2pkh(publicKeys[0]))), lockTime = 0),
            Transaction(version = 2, txIn = listOf(), txOut = listOf(TxOut(4_200_000L.toSatoshi(), Script.pay2pkh(publicKeys[1]))), lockTime = 0)
        )

        val tx = Transaction(
            version = 2,
            txIn = listOf(),
            txOut = listOf(TxOut(8_000_000L.toSatoshi(), Script.pay2wsh(Script.createMultiSigMofN(2, publicKeys)))),
            lockTime = 0L
        )

        val tx1 = run {
            val tmp = tx.addInput(TxIn(OutPoint(previousTx[0], 0), sequence = 0xFFFFFFFFL))
            val sig = Transaction.signInput(tmp, 0, Script.pay2pkh(publicKeys[0]), SIGHASH_ALL or SIGHASH_ANYONECANPAY, previousTx[0].txOut[0].amount, SigVersion.SIGVERSION_BASE, privateKeys[0])
            tmp.updateSigScript(0, listOf(OP_PUSHDATA(sig), OP_PUSHDATA(publicKeys[0])))
        }
        Transaction.correctlySpends(tx1, previousTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        // Adding a second input does not invalidate the first signature.
        val tx2 = run {
            val tmp = tx1.addInput(TxIn(OutPoint(previousTx[1], 0), sequence = 0xFFFFFFFFL))
            val sig = Transaction.signInput(tmp, 1, Script.pay2pkh(publicKeys[1]), SIGHASH_ALL or SIGHASH_ANYONECANPAY, previousTx[1].txOut[0].amount, SigVersion.SIGVERSION_BASE, privateKeys[1])
            tmp.updateSigScript(1, listOf(OP_PUSHDATA(sig), OP_PUSHDATA(publicKeys[1])))
        }
        Transaction.correctlySpends(tx2, previousTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        // Mutating an output invalidates the existing signatures.
        val tx3 = tx2.copy(txOut = tx2.txOut.updated(0, tx2.txOut[0].copy(amount = 4_000_000L.toSatoshi())))
        assertThrows {
            Transaction.correctlySpends(tx3, previousTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }
    }

    @Test
    fun sighashAnyOneCanPayPermitsAddingInputs_segwitP2WPKH() {
        val privateKeys = listOf(
            PrivateKey.fromBase58("cV7LGVeY2VPuCyCSarqEqFCUNig2NzwiAEBTTA89vNRQ4Vqjfurs", Base58.Prefix.SecretKeyTestnet).first,
            PrivateKey.fromBase58("cV5oyXUgySSMcUvKNdKtuYg4t4NTaxkwYrrocgsJZuYac2ogEdZX", Base58.Prefix.SecretKeyTestnet).first
        )
        val publicKeys = privateKeys.map { it.publicKey() }
        val previousTx = listOf(
            Transaction(version = 2, txIn = listOf(), txOut = listOf(TxOut(4_200_000L.toSatoshi(), Script.pay2wpkh(publicKeys[0]))), lockTime = 0),
            Transaction(version = 2, txIn = listOf(), txOut = listOf(TxOut(4_200_000L.toSatoshi(), Script.pay2wpkh(publicKeys[1]))), lockTime = 0)
        )

        val tx = Transaction(
            version = 2,
            txIn = listOf(),
            txOut = listOf(TxOut(8_000_000L.toSatoshi(), Script.pay2wsh(Script.createMultiSigMofN(2, publicKeys)))),
            lockTime = 0L
        )

        val tx1 = run {
            val tmp = tx.addInput(TxIn(OutPoint(previousTx[0], 0), sequence = 0xFFFFFFFFL))
            val sig = Transaction.signInput(tmp, 0, Script.pay2pkh(publicKeys[0]), SIGHASH_ALL or SIGHASH_ANYONECANPAY, previousTx[0].txOut[0].amount, SigVersion.SIGVERSION_WITNESS_V0, privateKeys[0])
            tmp.updateWitness(0, ScriptWitness(listOf(sig.byteVector(), publicKeys[0].value)))
        }
        Transaction.correctlySpends(tx1, previousTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val tx2 = run {
            val tmp = tx1.addInput(TxIn(OutPoint(previousTx[1], 0), sequence = 0xFFFFFFFFL))
            val sig = Transaction.signInput(tmp, 1, Script.pay2pkh(publicKeys[1]), SIGHASH_ALL or SIGHASH_ANYONECANPAY, previousTx[1].txOut[0].amount, SigVersion.SIGVERSION_WITNESS_V0, privateKeys[1])
            tmp.updateWitness(1, ScriptWitness(listOf(sig.byteVector(), publicKeys[1].value)))
        }
        Transaction.correctlySpends(tx2, previousTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val tx3 = tx2.copy(txOut = tx2.txOut.updated(0, tx2.txOut[0].copy(amount = 4_000_000L.toSatoshi())))
        assertThrows {
            Transaction.correctlySpends(tx3, previousTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }
    }

    private inline fun assertThrows(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (_: Throwable) {
            threw = true
        }
        assertTrue("expected an exception", threw)
    }
}
