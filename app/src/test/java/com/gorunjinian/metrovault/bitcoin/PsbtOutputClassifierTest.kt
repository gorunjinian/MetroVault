package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.data.model.PsbtOutputOwnership
import com.gorunjinian.metrovault.domain.service.bitcoin.AddressCheckResult
import com.gorunjinian.metrovault.domain.service.psbt.PsbtOutputClassifier
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
import com.gorunjinian.vaultovich.OutputOwnership
import com.gorunjinian.vaultovich.Psbt
import com.gorunjinian.vaultovich.PublicKey
import com.gorunjinian.vaultovich.Satoshi
import com.gorunjinian.vaultovich.Script
import com.gorunjinian.vaultovich.ScriptElt
import com.gorunjinian.vaultovich.ScriptType
import com.gorunjinian.vaultovich.SingleSigAccount
import com.gorunjinian.vaultovich.Transaction
import com.gorunjinian.vaultovich.TxId
import com.gorunjinian.vaultovich.TxIn
import com.gorunjinian.vaultovich.TxOut
import com.gorunjinian.vaultovich.verifyOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the review screen decides which outputs are the wallet's: vaultovich's `Psbt.verifyOutput`
 * first, the PSBT-blind address scan for anything it does not prove ours.
 */
class PsbtOutputClassifierTest {

    private val notFound = AddressCheckResult(false, null, null, null)
    private fun found(isChange: Boolean) = AddressCheckResult(true, "m/84'/0'/0'/${if (isChange) 1 else 0}/3", 3, isChange)

    private fun classifyOne(verdict: OutputOwnership?, scanResult: AddressCheckResult?): PsbtOutputOwnership =
        PsbtOutputClassifier.classify(listOf("addr"), verdict?.let { v -> { _: Int -> v } }, { scanResult }).single()

    // ========== Decision table ==========

    @Test
    fun provenOursSkipsTheScan() {
        var scanned = false
        val result = PsbtOutputClassifier.classify(
            listOf("addr"),
            { OutputOwnership.Ours(branch = 1, index = 7) },
            { scanned = true; notFound }
        )
        assertEquals(listOf(PsbtOutputOwnership.Ours(isChange = true)), result)
        assertTrue("an Ours verdict is proof; the scan must not run", !scanned)
    }

    @Test
    fun externalFallsBackToTheScan() {
        // Coordinators leave derivation entries off outputs they don't recognize as the wallet's
        assertEquals(PsbtOutputOwnership.Ours(isChange = false), classifyOne(OutputOwnership.External, found(false)))
        assertEquals(PsbtOutputOwnership.External, classifyOne(OutputOwnership.External, notFound))
    }

    @Test
    fun mismatchTheScanCannotPlaceIsFlagged() {
        assertEquals(PsbtOutputOwnership.ClaimMismatch, classifyOne(OutputOwnership.Mismatch("test"), notFound))
    }

    @Test
    fun mismatchThatTheScanFindsIsStillOurs() {
        // The declared path was wrong, but the script provably pays this wallet
        assertEquals(PsbtOutputOwnership.Ours(isChange = true), classifyOne(OutputOwnership.Mismatch("test"), found(true)))
    }

    @Test
    fun withoutAnAccountOnlyTheScanDecides() {
        assertEquals(PsbtOutputOwnership.Ours(isChange = true), classifyOne(null, found(true)))
        assertEquals(PsbtOutputOwnership.External, classifyOne(null, null))
    }

    @Test
    fun aThrowingVerifierFallsBackToTheScan() {
        val result = PsbtOutputClassifier.classify(listOf("addr"), { error("boom") }, { found(false) })
        assertEquals(listOf(PsbtOutputOwnership.Ours(isChange = false)), result)
    }

    // ========== End to end with a real PSBT ==========

    private val master = DeterministicWallet.generate(
        MnemonicCode.toSeed(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "),
            ""
        )
    )
    private val fingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey)
    private val accountPath = KeyPath("m/84'/0'/0'")
    private val accountKey = master.derivePrivateKey(accountPath).extendedPublicKey
    private val account = SingleSigAccount(fingerprint, accountPath, accountKey, ScriptType.P2WPKH)
    private fun ourKey(branch: Long, index: Long): PublicKey = accountKey.derivePublicKey(listOf(branch, index)).publicKey
    private val attackerKey = DeterministicWallet.generate(ByteArray(32) { 7 }).publicKey

    private fun psbt(vararg outputs: Pair<List<ScriptElt>, Map<PublicKey, KeyPathWithMaster>>): Psbt {
        val tx = Transaction(
            2,
            listOf(TxIn(OutPoint(TxId(ByteVector32.Zeroes), 0L), ByteVector.empty, 0xfffffffeL)),
            outputs.map { (script, _) -> TxOut(Satoshi(10_000), script) },
            0
        )
        val input = Input.PartiallySignedInputWithoutUtxo(
            null, emptyMap(), emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null, emptyList()
        )
        return Psbt(
            Global(0, tx, emptyList(), emptyList()),
            listOf(input),
            outputs.map { (_, claims) -> Output.WitnessOutput(null, null, claims, null, emptyMap(), emptyList()) }
        )
    }

    @Test
    fun attackerAddressLabelledAsChangeIsFlaggedAndRealChangeIsFolded() {
        val changeClaim = mapOf(ourKey(1, 4) to KeyPathWithMaster(fingerprint, accountPath.append(listOf(1L, 4L))))
        val parsed = psbt(
            Script.pay2wpkh(attackerKey) to emptyMap(),     // the payment
            Script.pay2wpkh(attackerKey) to changeClaim,    // attacker's script, labelled as our change
            Script.pay2wpkh(ourKey(1, 4)) to changeClaim,   // our real change
        )

        val result = PsbtOutputClassifier.classify(
            listOf("payment", "fake change", "change"),
            { index -> parsed.verifyOutput(index, account) },
            { notFound }
        )

        assertEquals(
            listOf(
                PsbtOutputOwnership.External,
                PsbtOutputOwnership.ClaimMismatch,
                PsbtOutputOwnership.Ours(isChange = true),
            ),
            result
        )
    }
}
