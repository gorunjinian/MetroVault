package com.gorunjinian.metrovault.multisig

import com.gorunjinian.metrovault.data.model.CosignerInfo
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.MultisigScriptType
import com.gorunjinian.metrovault.domain.service.multisig.MultisigAddressService
import com.gorunjinian.metrovault.domain.service.multisig.MultisigChangeValidator
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
import com.gorunjinian.vaultovich.ByteVector
import com.gorunjinian.vaultovich.ByteVector32
import com.gorunjinian.vaultovich.Descriptor
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
import com.gorunjinian.vaultovich.ScriptElt
import com.gorunjinian.vaultovich.Transaction
import com.gorunjinian.vaultovich.TxId
import com.gorunjinian.vaultovich.TxIn
import com.gorunjinian.vaultovich.TxOut
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The multisig signing gate on top of vaultovich's `Psbt.verifyOutput`: an output whose derivation
 * entries claim one of our cosigners must rebuild to exactly the registered 2-of-2 script, or
 * signing is refused.
 */
class MultisigChangeValidatorTest {

    private val validator = MultisigChangeValidator(MultisigAddressService())
    private val accountPath = KeyPath("m/48'/0'/0'/2'")

    private inner class Signer(words: String) {
        val master = DeterministicWallet.generate(MnemonicCode.toSeed(words.split(" "), ""))
        val fingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey)
        val account = master.derivePrivateKey(accountPath).extendedPublicKey
        fun key(branch: Long, index: Long): PublicKey = account.derivePublicKey(listOf(branch, index)).publicKey
        fun cosignerInfo(path: String = "48h/0h/0h/2h") = CosignerInfo(
            xpub = DeterministicWallet.encode(account, DeterministicWallet.xpub),
            fingerprint = Descriptor.formatFingerprint(fingerprint),
            derivationPath = path,
            isLocal = false
        )
    }

    private val a = Signer("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
    private val b = Signer("legal winner thank year wave sausage worth useful legal winner thank yellow")
    private val attacker = Signer("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong")

    private fun config(cosigners: List<CosignerInfo> = listOf(a.cosignerInfo(), b.cosignerInfo())) = MultisigConfig(
        m = 2,
        n = 2,
        cosigners = cosigners,
        localKeyFingerprints = emptyList(),
        scriptType = MultisigScriptType.P2WSH,
        rawDescriptor = ""
    )

    /** The 2-of-2 sortedmulti P2WSH script [signers] produce at `branch/index`. */
    private fun multisigScript(branch: Long, index: Long, signers: List<Signer> = listOf(a, b)): List<ScriptElt> {
        val keys = signers.map { it.key(branch, index) }.sortedBy { it.value.toHex() }
        return Script.pay2wsh(Script.createMultiSigMofN(2, keys))
    }

    /** Derivation entries for our cosigners, as an honest coordinator writes them for change. */
    private fun ourClaims(branch: Long, index: Long, path: KeyPath = accountPath.append(listOf(branch, index))) =
        listOf(a, b).associate { it.key(branch, index) to KeyPathWithMaster(it.fingerprint, path) }

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

    private val payment = Script.pay2wpkh(attacker.key(0, 0)) to emptyMap<PublicKey, KeyPathWithMaster>()

    @Test
    fun honestChangeOutputPasses() {
        val change = multisigScript(1, 5) to ourClaims(1, 5)
        assertEquals(MultisigChangeValidator.Result.Valid, validator.validate(psbt(payment, change), config()))
    }

    @Test
    fun outputsWithoutOurFingerprintsAreLeftAlone() {
        assertEquals(MultisigChangeValidator.Result.Valid, validator.validate(psbt(payment), config()))
    }

    @Test
    fun attackerScriptLabelledAsOurChangeIsRefused() {
        val fake = Script.pay2wpkh(attacker.key(1, 5)) to ourClaims(1, 5)
        assertEquals(MultisigChangeValidator.Result.Mismatch(1), validator.validate(psbt(payment, fake), config()))
    }

    @Test
    fun cosignerKeySwappedForTheAttackersIsRefused() {
        // Turns our 2-of-2 into a 2-of-2 the attacker holds a key of, while keeping our claims
        val swapped = multisigScript(1, 5, listOf(a, attacker)) to ourClaims(1, 5)
        assertEquals(MultisigChangeValidator.Result.Mismatch(0), validator.validate(psbt(swapped), config()))
    }

    @Test
    fun claimOutsideTheCosignerAccountIsRefused() {
        // The script is ours at 1/5, but the claimed path sits under a different account. The old
        // app-side check read only the last two components and would have accepted it.
        val otherAccount = KeyPath("m/48'/0'/1'/2'/1/5")
        val claimed = multisigScript(1, 5) to ourClaims(1, 5, otherAccount)
        assertEquals(MultisigChangeValidator.Result.Mismatch(0), validator.validate(psbt(claimed), config()))
    }

    @Test
    fun unreadableDescriptorFailsClosedOnlyForClaimedOutputs() {
        val broken = config(listOf(a.cosignerInfo(), b.cosignerInfo().copy(xpub = "not-an-xpub")))
        val change = multisigScript(1, 5) to ourClaims(1, 5)

        assertEquals(MultisigChangeValidator.Result.Valid, validator.validate(psbt(payment), broken))
        assertEquals(MultisigChangeValidator.Result.Mismatch(1), validator.validate(psbt(payment, change), broken))
    }

    @Test
    fun slip132CosignerKeysAndApostrophePathsAreAccepted() {
        val zpubA = a.cosignerInfo("m/48'/0'/0'/2'").copy(xpub = DeterministicWallet.encode(a.account, DeterministicWallet.Zpub))
        val zpubB = b.cosignerInfo("48H/0H/0H/2H").copy(xpub = DeterministicWallet.encode(b.account, DeterministicWallet.Zpub))
        val change = multisigScript(1, 5) to ourClaims(1, 5)
        assertEquals(MultisigChangeValidator.Result.Valid, validator.validate(psbt(change), config(listOf(zpubA, zpubB))))
    }
}
