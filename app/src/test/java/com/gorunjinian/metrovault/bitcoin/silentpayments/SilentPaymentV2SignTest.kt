package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.data.model.SilentPaymentError
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentReceiveSigner
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentSender
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.DataEntry
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.Global
import com.gorunjinian.metrovault.lib.bitcoin.Input
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.KeyPathWithMaster
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.lib.bitcoin.OutPoint
import com.gorunjinian.metrovault.lib.bitcoin.Output
import com.gorunjinian.metrovault.lib.bitcoin.Psbt
import com.gorunjinian.metrovault.lib.bitcoin.Satoshi
import com.gorunjinian.metrovault.lib.bitcoin.Script
import com.gorunjinian.metrovault.lib.bitcoin.Transaction
import com.gorunjinian.metrovault.lib.bitcoin.TxId
import com.gorunjinian.metrovault.lib.bitcoin.TxIn
import com.gorunjinian.metrovault.lib.bitcoin.TxOut
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.DleqProof
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPaymentAddress
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPayments
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.silentPaymentDleqProofs
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.silentPaymentEcdhShares
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end (M3): a PSBT v2 paying to a silent-payment recipient is resolved (the derived P2TR
 * script is spliced into the scriptless output), signed, and re-serialized as v2 — proving the
 * v2 read/write adapter and the existing (version-agnostic) signer cooperate.
 */
class SilentPaymentV2SignTest {
    private val standardAddress =
        "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv"

    private val seed = MnemonicCode.toSeed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "), ""
    )
    private val master = DeterministicWallet.generate(seed)
    private val accountKey = master.derivePrivateKey(KeyPath("m/84'/1'/0'"))
    private val fingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey)
    private val recipient = SilentPaymentAddress.decode(standardAddress)
    private val op0 = OutPoint(TxId(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")), 0L)

    @Test
    fun resolveSignAndReserializeV2() {
        val inputKey = master.derivePrivateKey(KeyPath("m/84'/1'/0'/0/0"))
        val pub = inputKey.publicKey

        // P2WPKH input belonging to this wallet (declared via BIP-32 derivation).
        val witnessUtxo = TxOut(Satoshi(100_000), Script.pay2wpkh(pub))
        val input = Input.WitnessInput.PartiallySignedWitnessInput(
            witnessUtxo, null, null, emptyMap(),
            mapOf(pub to KeyPathWithMaster(fingerprint, KeyPath("m/84'/1'/0'/0/0"))),
            null, null, emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null, emptyList()
        )

        // Scriptless silent-payment recipient output (PSBT_OUT_SP_V0_INFO only), as Sparrow emits.
        val spInfo = ByteVector(recipient.scanPubKey.value.toByteArray() + recipient.spendPubKey.value.toByteArray())
        val output = Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), listOf(DataEntry(ByteVector("09"), spInfo)))

        val tx = Transaction(
            2,
            listOf(TxIn(op0, ByteVector.empty, 0xfffffffeL)),
            listOf(TxOut(Satoshi(90_000), ByteVector.empty)), // SP output: no script yet
            0
        )
        val psbt = Psbt(Global(2, tx, emptyList(), emptyList(), fallbackLocktime = 0L), listOf(input), listOf(output))

        // 1) Resolve: derive the real taproot output and splice its script in.
        val resolved = (SilentPaymentSender.resolve(psbt, master, accountKey, ScriptType.P2WPKH, isTestnet = true) as Either.Right).value
        assertTrue("SP output script must be filled after resolution", Script.isPay2tr(resolved.global.tx.txOut[0].publicKeyScript))

        // 2) Sign the input (must happen after resolution, since the P2WPKH sighash commits to the output script).
        val signResult = resolved.sign(inputKey.privateKey, 0)
        assertTrue("signing must succeed, got $signResult", signResult is Either.Right)
        val signed = (signResult as Either.Right).value.psbt

        // 3) Re-serialize as v2 and read back: still v2, input signed, SP output script + field preserved.
        val reread = (Psbt.read(Psbt.write(signed).toByteArray()) as Either.Right).value
        assertEquals(2L, reread.global.version)
        assertTrue("input must carry a partial signature", reread.inputs[0].partialSigs.isNotEmpty())
        assertTrue("derived P2TR script must survive the round-trip", Script.isPay2tr(reread.global.tx.txOut[0].publicKeyScript))
        assertTrue(
            "PSBT_OUT_SP_V0_INFO must be preserved",
            reread.outputs[0].unknown.any { it.key.size() == 1 && it.key[0] == 0x09.toByte() }
        )
        assertEquals(resolved.global.tx.txOut[0].publicKeyScript, reread.global.tx.txOut[0].publicKeyScript)

        // 4) BIP-375 proof fields must survive the round-trip (Sparrow reads them at extraction).
        assertEquals(1, reread.global.silentPaymentEcdhShares.size)
        assertEquals(1, reread.global.silentPaymentDleqProofs.size)
    }

    /**
     * Replays drongo's `PSBT.validateSilentPayments` global-share path on the resolved PSBT:
     * the DLEQ proof must verify against the summed input public key, and the derived output
     * script must recompute from the attached ECDH share alone (`input_hash` applied verifier-side).
     * This is the check Sparrow runs before allowing View Final / broadcast.
     */
    @Test
    fun resolvedPsbtPassesSparrowSideValidation() {
        val inputKey = master.derivePrivateKey(KeyPath("m/84'/1'/0'/0/0"))
        val pub = inputKey.publicKey

        val witnessUtxo = TxOut(Satoshi(100_000), Script.pay2wpkh(pub))
        val input = Input.WitnessInput.PartiallySignedWitnessInput(
            witnessUtxo, null, null, emptyMap(),
            mapOf(pub to KeyPathWithMaster(fingerprint, KeyPath("m/84'/1'/0'/0/0"))),
            null, null, emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null, emptyList()
        )
        val spInfo = ByteVector(recipient.scanPubKey.value.toByteArray() + recipient.spendPubKey.value.toByteArray())
        val output = Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), listOf(DataEntry(ByteVector("09"), spInfo)))
        val tx = Transaction(
            2,
            listOf(TxIn(op0, ByteVector.empty, 0xfffffffeL)),
            listOf(TxOut(Satoshi(90_000), ByteVector.empty)),
            0
        )
        val psbt = Psbt(Global(2, tx, emptyList(), emptyList(), fallbackLocktime = 0L), listOf(input), listOf(output))

        val resolved = (SilentPaymentSender.resolve(psbt, master, accountKey, ScriptType.P2WPKH, isTestnet = true) as Either.Right).value

        // The verifier sums the public keys it extracts from the (final) witnesses — here one P2WPKH key.
        val summedPubKey = pub

        val share = resolved.global.silentPaymentEcdhShares[recipient.scanPubKey]
            ?: error("PSBT_GLOBAL_SP_ECDH_SHARE missing for the recipient scan key")
        val proof = resolved.global.silentPaymentDleqProofs[recipient.scanPubKey]
            ?: error("PSBT_GLOBAL_SP_DLEQ missing for the recipient scan key")
        assertTrue(
            "DLEQ proof must verify against the summed input public key",
            DleqProof.verify(summedPubKey, recipient.scanPubKey, share, proof.toByteArray())
        )

        // Recompute the output script from the share, exactly like drongo's validateOutputAddresses:
        // ecdh_shared_secret = input_hash·share, then P_0 = B_spend + t_0·G.
        val inputHash = SilentPayments.inputHash(SilentPayments.smallestOutpoint(listOf(op0)), summedPubKey)
        val sharedSecret = share * PrivateKey(inputHash)
        val expectedKey = SilentPayments.outputKey(recipient.spendPubKey, sharedSecret, 0)
        val expectedScript = Script.write(Script.pay2tr(expectedKey)).byteVector()
        assertEquals(
            "output script must recompute from the attached ECDH share",
            expectedScript, resolved.global.tx.txOut[0].publicKeyScript
        )

        // Re-resolving must replace the proof entries, not duplicate them (drongo rejects duplicate keys).
        val reResolved = (SilentPaymentSender.resolve(resolved, master, accountKey, ScriptType.P2WPKH, isTestnet = true) as Either.Right).value
        assertEquals(1, reResolved.global.silentPaymentEcdhShares.size)
        assertEquals(1, reResolved.global.silentPaymentDleqProofs.size)
        assertEquals("ECDH share must be deterministic across re-resolution", share, reResolved.global.silentPaymentEcdhShares[recipient.scanPubKey])
    }

    /**
     * Spending a *received* SP output (input carries `PSBT_IN_SP_TWEAK`, no BIP-32 derivations)
     * while paying two recipients at the same SP address plus change — the full self-transfer
     * round trip. The input's key `d = b_spend + tweak` must feed the BIP-352 send derivation,
     * the DLEQ proof must verify against `d·G`, and the receive signer must still sign the input
     * on the resolved PSBT.
     */
    @Test
    fun resolvesSpendOfReceivedSilentPaymentOutput() {
        val spendKey = master.derivePrivateKey(KeyPath("m/352'/1'/0'/0'/0")).privateKey
        val tweak = ByteVector32("0303030303030303030303030303030303030303030303030303030303030303")
        val d = spendKey + PrivateKey(tweak)
        val receivedSpScript = Script.write(Script.pay2tr(d.xOnlyPublicKey())).byteVector()

        // The received SP output being spent: P2TR, key = d·G directly, tweak in PSBT_IN_SP_TWEAK.
        val witnessUtxo = TxOut(Satoshi(152_000), receivedSpScript)
        val input = Input.WitnessInput.PartiallySignedWitnessInput(
            witnessUtxo, null, null, emptyMap(), emptyMap(),
            null, null, emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null,
            listOf(DataEntry(ByteVector("20"), ByteVector(tweak.toByteArray())))
        )

        // Two recipients at the same SP address (k=0, k=1) + a plain change output.
        val spInfo = ByteVector(recipient.scanPubKey.value.toByteArray() + recipient.spendPubKey.value.toByteArray())
        val spOutput = { Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), listOf(DataEntry(ByteVector("09"), spInfo))) }
        val changeScript = Script.write(Script.pay2wpkh(master.derivePrivateKey(KeyPath("m/84'/1'/0'/1/0")).publicKey)).byteVector()
        val tx = Transaction(
            2,
            listOf(TxIn(op0, ByteVector.empty, 0xfffffffeL)),
            listOf(TxOut(Satoshi(80_000), ByteVector.empty), TxOut(Satoshi(50_000), ByteVector.empty), TxOut(Satoshi(22_000), changeScript)),
            0
        )
        val psbt = Psbt(
            Global(2, tx, emptyList(), emptyList(), fallbackLocktime = 0L),
            listOf(input),
            listOf(spOutput(), spOutput(), Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), emptyList()))
        )

        // Without the spend key the input is unresolvable (the pre-fix failure mode).
        val withoutKey = SilentPaymentSender.resolve(psbt, master, accountKey, ScriptType.P2WPKH, isTestnet = true)
        assertTrue(withoutKey is Either.Left && withoutKey.value is SilentPaymentError.MissingPrivateKey)

        // A wrong tweak must be rejected before any derivation output is produced.
        val badTweak = ByteVector32("0404040404040404040404040404040404040404040404040404040404040404")
        val badInput = input.copy(unknown = listOf(DataEntry(ByteVector("20"), ByteVector(badTweak.toByteArray()))))
        val mismatch = SilentPaymentSender.resolve(psbt.copy(inputs = listOf(badInput)), master, accountKey, ScriptType.P2WPKH, isTestnet = true, spendPrivateKey = spendKey)
        assertTrue(mismatch is Either.Left && mismatch.value is SilentPaymentError.TweakMismatch)

        // With the spend key: both SP outputs derive, change is untouched.
        val resolved = (SilentPaymentSender.resolve(psbt, master, accountKey, ScriptType.P2WPKH, isTestnet = true, spendPrivateKey = spendKey) as Either.Right).value
        assertTrue(Script.isPay2tr(resolved.global.tx.txOut[0].publicKeyScript))
        assertTrue(Script.isPay2tr(resolved.global.tx.txOut[1].publicKeyScript))
        assertTrue("same-address outputs must derive distinct scripts (k=0 vs k=1)",
            resolved.global.tx.txOut[0].publicKeyScript != resolved.global.tx.txOut[1].publicKeyScript)
        assertEquals(changeScript, resolved.global.tx.txOut[2].publicKeyScript)

        // Sparrow-side validation: it extracts the x-only key from the witness and lifts it to
        // even Y, so the DLEQ must verify against that lift of d·G.
        val summedPubKey = if (d.publicKey().isOdd()) (-d).publicKey() else d.publicKey()
        val share = resolved.global.silentPaymentEcdhShares.getValue(recipient.scanPubKey)
        val proof = resolved.global.silentPaymentDleqProofs.getValue(recipient.scanPubKey)
        assertTrue(DleqProof.verify(summedPubKey, recipient.scanPubKey, share, proof.toByteArray()))

        val inputHash = SilentPayments.inputHash(SilentPayments.smallestOutpoint(listOf(op0)), summedPubKey)
        val sharedSecret = share * PrivateKey(inputHash)
        for (k in 0..1) {
            val expectedKey = SilentPayments.outputKey(recipient.spendPubKey, sharedSecret, k)
            assertEquals(Script.write(Script.pay2tr(expectedKey)).byteVector(), resolved.global.tx.txOut[k].publicKeyScript)
        }

        // The receive signer still signs the tweaked input on the resolved PSBT.
        val signed = (SilentPaymentReceiveSigner.signTweakedInputs(resolved, spendKey) as Either.Right).value
        assertTrue("SP input must carry a taproot key-path signature", signed.inputs[0].taprootKeySignature != null)
    }
}
