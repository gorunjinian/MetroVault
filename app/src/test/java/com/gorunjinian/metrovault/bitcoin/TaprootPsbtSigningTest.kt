
package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.domain.service.psbt.PsbtKeyResolver
import com.gorunjinian.metrovault.domain.service.psbt.PsbtSigner
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.Crypto
import com.gorunjinian.metrovault.lib.bitcoin.DataEntry
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.Global
import com.gorunjinian.metrovault.lib.bitcoin.Input
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.OP_CHECKSIG
import com.gorunjinian.metrovault.lib.bitcoin.OP_PUSHDATA
import com.gorunjinian.metrovault.lib.bitcoin.OutPoint
import com.gorunjinian.metrovault.lib.bitcoin.Output
import com.gorunjinian.metrovault.lib.bitcoin.Psbt
import com.gorunjinian.metrovault.lib.bitcoin.Satoshi
import com.gorunjinian.metrovault.lib.bitcoin.Script
import com.gorunjinian.metrovault.lib.bitcoin.SigHash
import com.gorunjinian.metrovault.lib.bitcoin.ScriptFlags
import com.gorunjinian.metrovault.lib.bitcoin.ScriptTree
import com.gorunjinian.metrovault.lib.bitcoin.ScriptWitness
import com.gorunjinian.metrovault.lib.bitcoin.TaprootBip32DerivationPath
import com.gorunjinian.metrovault.lib.bitcoin.Transaction
import com.gorunjinian.metrovault.lib.bitcoin.TxHash
import com.gorunjinian.metrovault.lib.bitcoin.TxIn
import com.gorunjinian.metrovault.lib.bitcoin.TxOut
import com.gorunjinian.metrovault.lib.bitcoin.UpdateFailure
import com.gorunjinian.metrovault.lib.bitcoin.taprootMerkleRoot
import com.gorunjinian.metrovault.lib.bitcoin.XonlyPublicKey
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for signing BIP-86 (key-path P2TR) PSBTs produced by Sparrow / Nunchuk.
 *
 * The first two bugs only bit taproot inputs, which is why native segwit wallets with the same key
 * material signed fine:
 *  - `PSBT_IN_TAP_BIP32_DERIVATION` fingerprints were sign-extended on parse, so every wallet whose
 *    master fingerprint has the high bit set (half of all wallets) failed Stage 1 key resolution.
 *  - The Stage 3 address-lookup fallback built P2TR scriptPubKeys from the *internal* key instead of
 *    the BIP-86 tweaked output key, so it could never rescue a taproot input either.
 *
 * The third was found while investigating those: key-path signing always applies the no-script
 * TapTweak, but nothing checked that this actually reproduced the output key being spent, so an
 * output committing to a script tree was signed with the wrong tweak and produced a silently
 * invalid signature. Verifying against the scriptPubKey also removes the need to trust the declared
 * `PSBT_IN_TAP_INTERNAL_KEY`.
 */
class TaprootPsbtSigningTest {

    /** Seed whose master fingerprint is 8dfc9b34 — high bit set, so it triggers sign extension. */
    private val highBitSeed = ByteVector(ByteArray(32) { 2 })

    /** Seed whose master fingerprint is 4ba43603 — high bit clear, so it parsed correctly before. */
    private val lowBitSeed = ByteVector(ByteArray(32) { 1 })

    private val accountPath = KeyPath("m/86'/1'/0'")

    @Test
    fun taprootDerivationPathRoundTripsFingerprintWithHighBitSet() {
        val fingerprint = 0x8dfc9b34L
        val path = TaprootBip32DerivationPath(
            leaves = emptyList(),
            masterKeyFingerprint = fingerprint,
            keyPath = KeyPath("m/86'/1'/0'/0/0"),
        )

        val reread = TaprootBip32DerivationPath.read(path.write())

        assertEquals(
            "PSBT_IN_TAP_BIP32_DERIVATION fingerprint must round-trip as an unsigned 32-bit value",
            fingerprint,
            reread.masterKeyFingerprint,
        )
        assertEquals(path.keyPath, reread.keyPath)
    }

    @Test
    fun resolvesTaprootInputForWalletWithHighBitFingerprint() {
        assertTaprootInputResolves(highBitSeed)
    }

    @Test
    fun resolvesTaprootInputForWalletWithLowBitFingerprint() {
        assertTaprootInputResolves(lowBitSeed)
    }

    @Test
    fun signsTaprootKeyPathInputEndToEnd() {
        val master = DeterministicWallet.generate(highBitSeed)
        val psbt = buildBip86Psbt(master)
        val resolved = PsbtKeyResolver.resolveFromDerivationPaths(
            input = psbt.inputs[0],
            masterPrivateKey = master,
            walletFingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey),
            isTestnet = true,
        )
        assertNotNull("could not resolve a signing key for the taproot input", resolved)

        val signed = psbt.sign(resolved!!.privateKey, 0)
        assertTrue("Psbt.sign rejected the taproot key-path input: $signed", signed is Either.Right)
        assertNotNull(
            "signing a key-path taproot input must produce PSBT_IN_TAP_KEY_SIG",
            (signed as Either.Right).value.psbt.inputs[0].taprootKeySignature,
        )
    }

    @Test
    fun signedTaprootInputPassesScriptVerification() {
        val master = DeterministicWallet.generate(highBitSeed)
        val psbt = buildBip86Psbt(master)
        val resolved = PsbtKeyResolver.resolveFromDerivationPaths(
            input = psbt.inputs[0],
            masterPrivateKey = master,
            walletFingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey),
            isTestnet = true,
        )!!

        val signedPsbt = (psbt.sign(resolved.privateKey, 0) as Either.Right).value.psbt
        val signature = signedPsbt.inputs[0].taprootKeySignature!!

        // A BIP-86 key-path spend witness is just the schnorr signature.
        val finalized = (
            signedPsbt.finalizeWitnessInput(0, ScriptWitness(listOf(signature))) as Either.Right
            ).value
        val tx = (finalized.extract() as Either.Right).value

        // Throws if the signature does not satisfy the P2TR output being spent.
        Transaction.correctlySpends(
            tx,
            mapOf(tx.txIn[0].outPoint to psbt.inputs[0].witnessUtxo!!),
            ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS,
        )
    }

    /**
     * A taproot output that commits to a script tree cannot be satisfied by a BIP-86 key-path
     * signature: the output key uses the merkle-root tweak, not the no-script one. We must refuse
     * rather than emit a signature that silently fails to verify.
     */
    @Test
    fun refusesToKeyPathSignOutputCommittingToAScriptTree() {
        val master = DeterministicWallet.generate(highBitSeed)
        val addressPath = KeyPath("m/86'/1'/0'/0/0")
        val signingKey = master.derivePrivateKey(addressPath).privateKey
        val internalKey = XonlyPublicKey(signingKey.publicKey())

        val tree = ScriptTree.Leaf(listOf(OP_PUSHDATA(internalKey.value), OP_CHECKSIG))
        val outputKey = internalKey.outputKey(Crypto.TaprootTweak.ScriptTweak(tree.hash())).first
        val psbt = buildBip86Psbt(master, scriptPubKeyOverride = Script.write(Script.pay2tr(outputKey)).byteVector())

        val signed = psbt.sign(signingKey, 0)

        assertTrue(
            "signing an output that commits to a script tree must fail loudly, got: $signed",
            signed is Either.Left,
        )
    }

    /**
     * `PSBT_IN_TAP_INTERNAL_KEY` is a convenience field, not something BIP-371 requires a signer to
     * have: the output key in the scriptPubKey is the real authority, and we verify against it.
     */
    @Test
    fun signsTaprootInputWithoutDeclaredInternalKey() {
        val master = DeterministicWallet.generate(highBitSeed)
        val psbt = buildBip86Psbt(master, includeInternalKey = false)
        val resolved = PsbtKeyResolver.resolveFromDerivationPaths(
            input = psbt.inputs[0],
            masterPrivateKey = master,
            walletFingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey),
            isTestnet = true,
        )!!

        val signed = psbt.sign(resolved.privateKey, 0)

        assertTrue("a taproot PSBT without PSBT_IN_TAP_INTERNAL_KEY should still sign: $signed", signed is Either.Right)
        assertNotNull((signed as Either.Right).value.psbt.inputs[0].taprootKeySignature)
    }

    @Test
    fun addressLookupResolvesTaprootInputWithoutDerivationMetadata() {
        val master = DeterministicWallet.generate(highBitSeed)
        val accountKey = master.derivePrivateKey(accountPath)
        // A PSBT that declares no derivation metadata at all, forcing the Stage 3 fallback.
        val psbt = buildBip86Psbt(master, includeDerivationMetadata = false)

        val lookup = PsbtKeyResolver.buildAddressLookup(accountKey, ScriptType.P2TR)
        val match = PsbtKeyResolver.resolveFromAddressLookup(psbt.inputs[0], accountKey, lookup)

        assertNotNull("Stage 3 address lookup failed to match the taproot input", match)
        assertEquals(
            master.derivePrivateKey(KeyPath("m/86'/1'/0'/0/0")).privateKey,
            match!!.first.privateKey,
        )
    }

    @Test
    fun addressLookupCoversTaprootScriptPubKeys() {
        val master = DeterministicWallet.generate(highBitSeed)
        val accountKey = master.derivePrivateKey(accountPath)

        val lookup = PsbtKeyResolver.buildAddressLookup(accountKey, ScriptType.P2TR)

        // The scriptPubKey actually seen on chain commits to the BIP-86 tweaked output key, not to
        // the internal key, so that is what the lookup has to be keyed by.
        val firstReceiveKey = accountKey.extendedPublicKey.derivePublicKey(0L).derivePublicKey(0L).publicKey
        val outputKey = XonlyPublicKey(firstReceiveKey).outputKey(Crypto.TaprootTweak.NoScriptTweak).first
        val onChainScript = Script.write(Script.pay2tr(outputKey)).byteVector()

        assertTrue(
            "Stage 3 address lookup does not contain the wallet's real P2TR scriptPubKey",
            lookup.containsKey(onChainScript),
        )
    }

    // ---- BIP-341 sighash handling ----

    /**
     * BIP-341: "If the sig is 65 bytes long, return sig[64] != 0x00" — a 65-byte signature ending in
     * 0x00 is invalid, so an explicitly-declared SIGHASH_DEFAULT must still produce 64 bytes.
     */
    @Test
    fun explicitSighashDefaultProducesA64ByteSignature() {
        val master = DeterministicWallet.generate(highBitSeed)
        val psbt = buildBip86Psbt(master, sighashType = SigHash.SIGHASH_DEFAULT)

        // Pin the premise: an explicit 0 survives the round trip as non-null, distinct from absent.
        assertEquals(SigHash.SIGHASH_DEFAULT, psbt.inputs[0].sighashType)

        val signed = (psbt.sign(signingKeyOf(master), 0) as Either.Right).value.psbt
        assertEquals(
            "an explicit SIGHASH_DEFAULT must not append a 0x00 byte",
            64,
            signed.inputs[0].taprootKeySignature!!.size(),
        )
    }

    /** The consensus-level version of the above: the verifier rejects a 65-byte sig ending in 0x00. */
    @Test
    fun explicitSighashDefaultSignatureVerifies() {
        val master = DeterministicWallet.generate(highBitSeed)
        assertSignedInputVerifies(buildBip86Psbt(master, sighashType = SigHash.SIGHASH_DEFAULT), signingKeyOf(master))
    }

    @Test
    fun sighashAllAppendsTheSighashByte() {
        val master = DeterministicWallet.generate(highBitSeed)
        val psbt = buildBip86Psbt(master, sighashType = SigHash.SIGHASH_ALL)

        val signed = (psbt.sign(signingKeyOf(master), 0) as Either.Right).value.psbt
        val sig = signed.inputs[0].taprootKeySignature!!

        assertEquals("a non-default sighash type must be appended", 65, sig.size())
        assertEquals(SigHash.SIGHASH_ALL.toByte(), sig[64])
        assertSignedInputVerifies(psbt, signingKeyOf(master))
    }

    @Test
    fun sighashSingleAnyoneCanPayStillSigns() {
        val master = DeterministicWallet.generate(highBitSeed)
        val psbt = buildBip86Psbt(master, sighashType = 0x83)

        val signed = psbt.sign(signingKeyOf(master), 0)

        assertTrue("0x83 is a valid BIP-341 sighash type: $signed", signed is Either.Right)
        val sig = (signed as Either.Right).value.psbt.inputs[0].taprootKeySignature!!
        assertEquals(65, sig.size())
        assertEquals(0x83.toByte(), sig[64])
    }

    /**
     * `hashForSigningSchnorr` throws for this, which escapes `Psbt.sign`'s Either contract and
     * aborts signing for every *other* input in the transaction too.
     */
    @Test
    fun refusesUnsupportedTaprootSighashType() {
        val master = DeterministicWallet.generate(highBitSeed)
        val signed = buildBip86Psbt(master, sighashType = 0x41).sign(signingKeyOf(master), 0)

        assertTrue("0x41 is not a valid taproot sighash type: $signed", signed is Either.Left)
        assertTrue(
            "expected a typed sighash failure, got ${(signed as Either.Left).value}",
            signed.value is UpdateFailure.UnsupportedSighashType,
        )
    }

    /**
     * The dangerous one: PSBT_IN_SIGHASH_TYPE is parsed as a *signed* Int, so 0xFFFFFFFF arrives as
     * -1, satisfies `hashForSigningSchnorr`'s `sighashType <= 0x03` guard, and is then masked into
     * SIGHASH_SINGLE | SIGHASH_ANYONECANPAY — semantics the user was never shown.
     */
    @Test
    fun refusesNegativeSighashType() {
        val master = DeterministicWallet.generate(highBitSeed)
        val signed = buildBip86Psbt(master, sighashType = -1).sign(signingKeyOf(master), 0)

        assertTrue("a negative sighash type must be refused, not masked: $signed", signed is Either.Left)
        assertTrue(
            "expected a typed sighash failure, got ${(signed as Either.Left).value}",
            signed.value is UpdateFailure.UnsupportedSighashType,
        )
    }

    // ---- PSBT_IN_TAP_MERKLE_ROOT (BIP-371) ----

    @Test
    fun refusalNamesTheScriptTreeWhenMerkleRootIsDeclared() {
        val master = DeterministicWallet.generate(highBitSeed)
        val signingKey = signingKeyOf(master)
        val internalKey = XonlyPublicKey(signingKey.publicKey())
        val tree = ScriptTree.Leaf(listOf(OP_PUSHDATA(internalKey.value), OP_CHECKSIG))
        val outputKey = internalKey.outputKey(Crypto.TaprootTweak.ScriptTweak(tree.hash())).first

        val psbt = buildBip86Psbt(
            master,
            scriptPubKeyOverride = Script.write(Script.pay2tr(outputKey)).byteVector(),
            merkleRoot = tree.hash(),
        )

        val failure = (psbt.sign(signingKey, 0) as Either.Left).value
        assertTrue("expected a script-tree refusal, got $failure", failure is UpdateFailure.CannotSignTaprootScriptTree)
        assertEquals(
            "the refusal should carry the proven merkle root",
            tree.hash(),
            (failure as UpdateFailure.CannotSignTaprootScriptTree).merkleRoot,
        )
    }

    /**
     * Guards the reader/writer split: 0x18 survives today only because it rides `unknown`. Anyone
     * later adding it to PsbtReader's key-type sets without a matching PsbtWriter branch would
     * silently drop it from every signed PSBT, and this test would catch that.
     */
    @Test
    fun merkleRootFieldSurvivesPsbtRoundTrip() {
        val master = DeterministicWallet.generate(highBitSeed)
        val root = ByteVector("ab".repeat(32))

        val parsed = buildBip86Psbt(master, merkleRoot = root)

        assertEquals(
            root.toByteArray().toList(),
            parsed.inputs[0].taprootMerkleRoot!!.toByteArray().toList(),
        )
    }

    /** Success must never be gated on 0x18 — the scriptPubKey remains the sole authority. */
    @Test
    fun malformedMerkleRootDoesNotBlockAValidKeyPathSpend() {
        val master = DeterministicWallet.generate(highBitSeed)
        val signed = buildBip86Psbt(master, merkleRoot = ByteVector.empty).sign(signingKeyOf(master), 0)

        assertTrue("a plain BIP-86 output must still sign: $signed", signed is Either.Right)
    }

    /** A key the PSBT declares only for script paths (tapleaf hashes attached) gets its own reason. */
    @Test
    fun refusalNamesTheScriptPathWhenOurKeyCarriesTapleafHashes() {
        val master = DeterministicWallet.generate(highBitSeed)
        val signingKey = signingKeyOf(master)
        val internalKey = XonlyPublicKey(signingKey.publicKey())
        val tree = ScriptTree.Leaf(listOf(OP_PUSHDATA(internalKey.value), OP_CHECKSIG))
        val outputKey = internalKey.outputKey(Crypto.TaprootTweak.ScriptTweak(tree.hash())).first

        val base = buildBip86Psbt(master, scriptPubKeyOverride = Script.write(Script.pay2tr(outputKey)).byteVector())
        val input = base.inputs[0] as Input.WitnessInput.PartiallySignedWitnessInput
        val psbt = base.copy(
            inputs = listOf(
                input.copy(
                    taprootDerivationPaths = mapOf(
                        internalKey to TaprootBip32DerivationPath(
                            leaves = listOf(ByteVector32(ByteArray(32) { 7 })),
                            masterKeyFingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey),
                            keyPath = KeyPath("m/86'/1'/0'/0/0"),
                        )
                    )
                )
            )
        )

        val failure = (psbt.sign(signingKey, 0) as Either.Left).value
        assertTrue(
            "expected a script-path-key refusal, got $failure",
            failure is UpdateFailure.CannotSignTaprootScriptPathKey,
        )
    }

    // ---- PsbtSigner orchestration ----

    /**
     * An input we already finalized carries its witness and needs nothing more. Stage 3 would
     * still match it by scriptPubKey and `Psbt.sign` would then decline it as "already finalized",
     * which used to surface as a partial-signature warning on a transaction that was complete.
     */
    @Test
    fun alreadyFinalizedInputIsSkippedRatherThanReportedAsRefused() {
        val master = DeterministicWallet.generate(highBitSeed)
        val signingKey = signingKeyOf(master)
        val psbt = buildBip86Psbt(master, inputCount = 2)

        // Sign and finalize input 0 ourselves, leaving input 1 untouched.
        val signedFirst = (psbt.sign(signingKey, 0) as Either.Right).value.psbt
        val witness = ScriptWitness(listOf(signedFirst.inputs[0].taprootKeySignature!!))
        val halfFinalized = (signedFirst.finalizeWitnessInput(0, witness) as Either.Right).value
        assertTrue(halfFinalized.inputs[0] is Input.WitnessInput.FinalizedWitnessInput)

        val result = PsbtSigner.signParsedPsbt(
            psbt = halfFinalized,
            masterPrivateKey = master,
            accountPrivateKey = master.derivePrivateKey(accountPath),
            scriptType = ScriptType.P2TR,
            isTestnet = true,
            accountPath = accountPath,
        )

        assertTrue("input 1 should still be signed: $result", result is Either.Right)
        val signed = (result as Either.Right).value
        assertTrue("a finalized input is complete, not refused: ${signed.refusals}", signed.refusals.isEmpty())
        assertNotNull(signed.psbt.inputs[1].taprootKeySignature)
        assertTrue(signed.psbt.inputs[0] is Input.WitnessInput.FinalizedWitnessInput)
    }

    private fun signingKeyOf(master: DeterministicWallet.ExtendedPrivateKey): PrivateKey =
        master.derivePrivateKey(KeyPath("m/86'/1'/0'/0/0")).privateKey

    /** Signs input 0, finalizes the key-path witness, and runs full script verification. */
    private fun assertSignedInputVerifies(psbt: Psbt, signingKey: PrivateKey) {
        val signedPsbt = (psbt.sign(signingKey, 0) as Either.Right).value.psbt
        val signature = signedPsbt.inputs[0].taprootKeySignature!!
        val finalized = (signedPsbt.finalizeWitnessInput(0, ScriptWitness(listOf(signature))) as Either.Right).value
        val tx = (finalized.extract() as Either.Right).value
        Transaction.correctlySpends(
            tx,
            mapOf(tx.txIn[0].outPoint to psbt.inputs[0].witnessUtxo!!),
            ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS,
        )
    }

    private fun assertTaprootInputResolves(seed: ByteVector) {
        val master = DeterministicWallet.generate(seed)
        val psbt = buildBip86Psbt(master)
        val walletFingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey)

        val resolved = PsbtKeyResolver.resolveFromDerivationPaths(
            input = psbt.inputs[0],
            masterPrivateKey = master,
            walletFingerprint = walletFingerprint,
            isTestnet = true,
        )

        assertNotNull(
            "taproot input declared by PSBT_IN_TAP_BIP32_DERIVATION was not recognised as ours",
            resolved,
        )
        val expected = master.derivePrivateKey(KeyPath("m/86'/1'/0'/0/0")).privateKey
        assertEquals(expected, resolved!!.privateKey)
    }

    /**
     * Builds a single-input, single-output BIP-86 key-path PSBT the way Sparrow/Nunchuk would, then
     * serializes and re-parses it so the test exercises the real reader rather than in-memory objects.
     */
    private fun buildBip86Psbt(
        master: DeterministicWallet.ExtendedPrivateKey,
        includeDerivationMetadata: Boolean = true,
        includeInternalKey: Boolean = true,
        scriptPubKeyOverride: ByteVector? = null,
        sighashType: Int? = null,
        merkleRoot: ByteVector? = null,
        inputCount: Int = 1,
    ): Psbt {
        val addressPath = KeyPath("m/86'/1'/0'/0/0")
        val addressKey = master.derivePrivateKey(addressPath)
        val internalKey = XonlyPublicKey(addressKey.publicKey)
        val outputKey = internalKey.outputKey(Crypto.TaprootTweak.NoScriptTweak).first
        val scriptPubKey = scriptPubKeyOverride ?: Script.write(Script.pay2tr(outputKey)).byteVector()

        // Every input spends the same address at a different outpoint index.
        val previousOutput = TxOut(Satoshi(100_000), scriptPubKey)
        val unsignedTx = Transaction(
            version = 2,
            txIn = (0 until inputCount).map { vout ->
                TxIn(OutPoint(TxHash("aa".repeat(32)), vout.toLong()), ByteVector.empty, TxIn.SEQUENCE_FINAL)
            },
            txOut = listOf(TxOut(Satoshi(90_000), scriptPubKey)),
            lockTime = 0,
        )

        val input = Input.WitnessInput.PartiallySignedWitnessInput(
            txOut = previousOutput,
            nonWitnessUtxo = null,
            sighashType = sighashType,
            partialSigs = emptyMap(),
            derivationPaths = emptyMap(),
            redeemScript = null,
            witnessScript = null,
            ripemd160 = emptySet(),
            sha256 = emptySet(),
            hash160 = emptySet(),
            hash256 = emptySet(),
            taprootKeySignature = null,
            taprootDerivationPaths = if (includeDerivationMetadata) {
                mapOf(
                    internalKey to TaprootBip32DerivationPath(
                        leaves = emptyList(),
                        masterKeyFingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey),
                        keyPath = addressPath,
                    )
                )
            } else {
                emptyMap()
            },
            taprootInternalKey = if (includeInternalKey) internalKey else null,
            // PSBT_IN_TAP_MERKLE_ROOT rides the `unknown` store: the reader does not model it,
            // and the writer re-emits unknowns verbatim.
            unknown = merkleRoot?.let { listOf(DataEntry(ByteVector("18"), it)) } ?: emptyList(),
        )

        val psbt = Psbt(
            global = Global(version = 0, tx = unsignedTx, extendedPublicKeys = emptyList(), unknown = emptyList()),
            inputs = List(inputCount) { input },
            outputs = listOf(Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), emptyList())),
        )

        return when (val parsed = Psbt.read(Psbt.write(psbt))) {
            is Either.Right -> parsed.value
            is Either.Left -> throw AssertionError("failed to re-parse the generated PSBT: ${parsed.value}")
        }
    }
}
