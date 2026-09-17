package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.vaultovich.ScriptType
import com.gorunjinian.metrovault.data.model.SilentPaymentError
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentSender
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
import com.gorunjinian.vaultovich.ByteVector
import com.gorunjinian.vaultovich.ByteVector32
import com.gorunjinian.vaultovich.Crypto
import com.gorunjinian.vaultovich.DataEntry
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.Global
import com.gorunjinian.vaultovich.Input
import com.gorunjinian.vaultovich.KeyPath
import com.gorunjinian.vaultovich.KeyPathWithMaster
import com.gorunjinian.vaultovich.MnemonicCode
import com.gorunjinian.vaultovich.OutPoint
import com.gorunjinian.vaultovich.Output
import com.gorunjinian.vaultovich.Psbt
import com.gorunjinian.vaultovich.PublicKey
import com.gorunjinian.vaultovich.Satoshi
import com.gorunjinian.vaultovich.Script
import com.gorunjinian.vaultovich.SigHash
import com.gorunjinian.vaultovich.TaprootBip32DerivationPath
import com.gorunjinian.vaultovich.Transaction
import com.gorunjinian.vaultovich.TxId
import com.gorunjinian.vaultovich.TxIn
import com.gorunjinian.vaultovich.TxOut
import com.gorunjinian.vaultovich.byteVector
import com.gorunjinian.vaultovich.silentpayments.SilentPaymentAddress
import com.gorunjinian.vaultovich.silentpayments.SilentPayments
import com.gorunjinian.vaultovich.silentpayments.SilentPayments.EligibleInputKey
import com.gorunjinian.vaultovich.silentpayments.SilentPayments.RecipientKeys
import com.gorunjinian.vaultovich.utils.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentPaymentSenderServiceTest {
    private val standardAddress =
        "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv"

    private val seed = MnemonicCode.toSeed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "),
        ""
    )
    private val master = DeterministicWallet.generate(seed)
    private val accountKey = master.derivePrivateKey(KeyPath("m/84'/1'/0'"))
    private val fingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey)
    private val recipient = SilentPaymentAddress.decode(standardAddress)

    private val op0 = OutPoint(TxId(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")), 0L)
    private val op1 = OutPoint(TxId(ByteVector32("0202020202020202020202020202020202020202020202020202020202020202")), 1L)

    private fun derivedKey(index: Int) = master.derivePrivateKey(KeyPath("m/84'/1'/0'/0/$index"))

    private fun walletP2wpkhInput(index: Int, sighash: Int? = null): Input.WitnessInput.PartiallySignedWitnessInput {
        val pub = derivedKey(index).publicKey
        return witnessInput(Script.write(Script.pay2wpkh(pub)).byteVector(), pub, "m/84'/1'/0'/0/$index", fingerprint, sighash)
    }

    private fun witnessInput(
        scriptPubKey: ByteVector,
        derivationPub: PublicKey?,
        fullPath: String?,
        fp: Long,
        sighash: Int? = null,
    ): Input.WitnessInput.PartiallySignedWitnessInput {
        val paths = if (derivationPub != null && fullPath != null) {
            mapOf(derivationPub to KeyPathWithMaster(fp, KeyPath(fullPath)))
        } else emptyMap()
        return Input.WitnessInput.PartiallySignedWitnessInput(
            TxOut(Satoshi(50_000), scriptPubKey), null, sighash, emptyMap(), paths, null, null,
            emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null, emptyList()
        )
    }

    private fun taprootKey(index: Int) = master.derivePrivateKey(KeyPath("m/86'/1'/0'/0/$index"))

    /** A BIP-86 input of this wallet: the script pays the tweaked output key, the PSBT declares the internal key. */
    private fun walletP2trInput(index: Int): Input.WitnessInput.PartiallySignedWitnessInput {
        val internal = taprootKey(index).publicKey.xOnly()
        val (outputKey, _) = internal.outputKey(Crypto.TaprootTweak.NoScriptTweak)
        val script = Script.write(Script.pay2tr(outputKey)).byteVector()
        val path = TaprootBip32DerivationPath(emptyList(), fingerprint, KeyPath("m/86'/1'/0'/0/$index"))
        return Input.WitnessInput.PartiallySignedWitnessInput(
            TxOut(Satoshi(50_000), script), null, null, emptyMap(), emptyMap(), null, null,
            emptySet(), emptySet(), emptySet(), emptySet(), null, mapOf(internal to path), internal, emptyList()
        )
    }

    private fun psbtOf(inputs: List<Input>, outpoints: List<OutPoint>): Psbt {
        val spInfo = ByteVector(recipient.scanPubKey.value.toByteArray() + recipient.spendPubKey.value.toByteArray())
        val txIns = outpoints.map { TxIn(it, ByteVector.empty, 0xffffffffL) }
        val placeholder = TxOut(Satoshi(90_000), Script.pay2tr(recipient.spendPubKey.xOnly()))
        val tx = Transaction(2, txIns, listOf(placeholder), 0)
        val output = Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), listOf(DataEntry(ByteVector("09"), spInfo)))
        return Psbt(Global(0, tx, emptyList(), emptyList()), inputs, listOf(output))
    }

    private fun resolve(psbt: Psbt) =
        SilentPaymentSender.resolve(psbt, master, accountKey, ScriptType.P2WPKH, isTestnet = true)

    @Test
    fun resolvesDerivedOutputMatchingTheMath() {
        val psbt = psbtOf(listOf(walletP2wpkhInput(0), walletP2wpkhInput(1)), listOf(op0, op1))

        val result = resolve(psbt)
        assertTrue("expected Right, got $result", result is Either.Right)
        val resolved = (result as Either.Right).value

        // Cross-check the spliced output against the math module driven with the same input keys.
        val eligible = listOf(
            EligibleInputKey(derivedKey(0).privateKey, false),
            EligibleInputKey(derivedKey(1).privateKey, false)
        )
        val derived = SilentPayments.deriveOutputs(
            eligible, listOf(op0, op1), listOf(RecipientKeys(recipient.scanPubKey, recipient.spendPubKey))
        )
        val expectedScript = Script.write(Script.pay2tr(derived.first().outputKey)).byteVector()
        assertEquals(expectedScript, resolved.global.tx.txOut[0].publicKeyScript)
    }

    @Test
    fun preservesSilentPaymentOutputField() {
        val psbt = psbtOf(listOf(walletP2wpkhInput(0), walletP2wpkhInput(1)), listOf(op0, op1))
        val resolved = (resolve(psbt) as Either.Right).value
        // The PSBT_OUT_SP_V0_INFO entry must survive resolution untouched.
        val hasInfo = resolved.outputs[0].unknown.any { it.key.size() == 1 && it.key[0] == 0x09.toByte() }
        assertTrue("SP info field must be preserved", hasInfo)
    }

    @Test
    fun psbtWithoutSpRecipientsIsUnchanged() {
        // Build a normal PSBT (no SP info on the output).
        val txIns = listOf(TxIn(op0, ByteVector.empty, 0xffffffffL))
        val tx = Transaction(2, txIns, listOf(TxOut(Satoshi(40_000), Script.pay2wpkh(derivedKey(0).publicKey))), 0)
        val output = Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), emptyList())
        val psbt = Psbt(Global(0, tx, emptyList(), emptyList()), listOf(walletP2wpkhInput(0)), listOf(output))

        val result = resolve(psbt)
        assertTrue(result is Either.Right)
        assertEquals(psbt, (result as Either.Right).value)
    }

    @Test
    fun rejectsSighashAnyoneCanPay() {
        val acp = SigHash.SIGHASH_ALL or SigHash.SIGHASH_ANYONECANPAY
        val psbt = psbtOf(listOf(walletP2wpkhInput(0, sighash = acp), walletP2wpkhInput(1)), listOf(op0, op1))
        val result = resolve(psbt)
        assertTrue(result is Either.Left)
        assertEquals(SilentPaymentError.SighashAnyoneCanPayForbidden, (result as Either.Left).value)
    }

    @Test
    fun rejectsSegwitVersionAboveOne() {
        // scriptPubKey = OP_2 <32-byte program> (witness v2).
        val v2Script = ByteVector("5220" + "00".repeat(32))
        val input = witnessInput(v2Script, null, null, fingerprint)
        val psbt = psbtOf(listOf(input), listOf(op0))
        val result = resolve(psbt)
        assertTrue(result is Either.Left)
        assertEquals(SilentPaymentError.UnsupportedSegwitVersion(2), (result as Either.Left).value)
    }

    @Test
    fun rejectsWhenNoEligibleInputs() {
        // A P2WSH input is not SP-eligible.
        val p2wsh = Script.write(Script.pay2wsh(Script.pay2wpkh(derivedKey(0).publicKey))).byteVector()
        val input = witnessInput(p2wsh, null, null, fingerprint)
        val psbt = psbtOf(listOf(input), listOf(op0))
        val result = resolve(psbt)
        assertTrue(result is Either.Left)
        assertEquals(SilentPaymentError.NoEligibleInputs, (result as Either.Left).value)
    }

    @Test
    fun rejectsWhenInputKeyCannotBeResolved() {
        // Eligible P2WPKH input but with a foreign pubkey + wrong fingerprint: not ours to sign.
        val foreignPub = com.gorunjinian.vaultovich.PrivateKey
            .fromHex("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1").publicKey()
        val input = witnessInput(
            Script.write(Script.pay2wpkh(foreignPub)).byteVector(),
            foreignPub, "m/84'/1'/0'/0/0", fp = 0xdeadbeefL
        )
        val psbt = psbtOf(listOf(input), listOf(op0))
        val result = resolve(psbt)
        assertTrue(result is Either.Left)
        assertTrue((result as Either.Left).value is SilentPaymentError.MissingPrivateKey)
    }

    @Test
    fun taprootInputsEnterTheSumAsTheirOutputKeyNotTheInternalKey() {
        val psbt = psbtOf(listOf(walletP2trInput(0), walletP2wpkhInput(1)), listOf(op0, op1))
        val result = resolve(psbt)
        assertTrue("expected Right, got $result", result is Either.Right)
        val resolvedScript = (result as Either.Right).value.global.tx.txOut[0].publicKeyScript
        val recipients = listOf(RecipientKeys(recipient.scanPubKey, recipient.spendPubKey))

        // BIP-352: a P2TR input contributes the private key of its taproot *output* key (d = k + t).
        val expected = SilentPayments.deriveOutputs(
            listOf(
                EligibleInputKey(SilentPayments.taprootOutputPrivateKey(taprootKey(0).privateKey), true),
                EligibleInputKey(derivedKey(1).privateKey, false),
            ),
            listOf(op0, op1), recipients
        )
        assertEquals(Script.write(Script.pay2tr(expected.first().outputKey)).byteVector(), resolvedScript)

        // Summing the untweaked internal key instead derives an output the receiver could never find.
        val wrong = SilentPayments.deriveOutputs(
            listOf(EligibleInputKey(taprootKey(0).privateKey, true), EligibleInputKey(derivedKey(1).privateKey, false)),
            listOf(op0, op1), recipients
        )
        assertNotEquals(Script.write(Script.pay2tr(wrong.first().outputKey)).byteVector(), resolvedScript)
    }

    @Test
    fun rejectsAKeyThatDoesNotMatchTheInputScript() {
        // The derivation entry names our key 0 but the script pays key 1. A sum over key 0 would
        // derive outputs the receiver can never find, so the sender must refuse rather than guess.
        val input = witnessInput(
            Script.write(Script.pay2wpkh(derivedKey(1).publicKey)).byteVector(),
            derivedKey(0).publicKey, "m/84'/1'/0'/0/0", fingerprint
        )
        val psbt = psbtOf(listOf(input), listOf(op0))
        val result = resolve(psbt)
        assertTrue("expected Left, got $result", result is Either.Left)
        assertEquals(SilentPaymentError.KeyDoesNotMatchInput(op0.toString()), (result as Either.Left).value)
    }
}
