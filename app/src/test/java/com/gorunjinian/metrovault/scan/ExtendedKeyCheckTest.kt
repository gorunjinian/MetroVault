package com.gorunjinian.metrovault.scan

import com.gorunjinian.metrovault.domain.service.scan.ExtendedKeyChecker
import com.gorunjinian.metrovault.domain.service.scan.KeyCheckWallet
import com.gorunjinian.metrovault.domain.service.scan.KeyIssue
import com.gorunjinian.metrovault.domain.service.scan.KeyOwnership
import com.gorunjinian.metrovault.domain.service.scan.ScanPayloadClassifier
import com.gorunjinian.metrovault.domain.service.scan.ScannedPayload
import com.gorunjinian.vaultovich.Descriptor
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.KeyPath
import com.gorunjinian.vaultovich.MnemonicCode
import com.gorunjinian.vaultovich.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extended-key check: every verdict comes from deriving the key from the wallet's seed, the
 * origin only says where to look, and a match lists what would still make a watch-only wallet
 * disagree.
 */
class ExtendedKeyCheckTest {

    // BIP-39 / BIP-84 test vector seed, fingerprint 73c5da0a.
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val master = DeterministicWallet.generate(MnemonicCode.toSeed(words, ""))
    private val fingerprint = "73c5da0a"
    private val otherMaster = DeterministicWallet.generate(MnemonicCode.toSeed(List(11) { "zoo" } + "wrong", ""))

    private val wallet = KeyCheckWallet(master, KeyPath("m/84'/0'/0'"), ScriptType.P2WPKH, isTestnet = false)

    private fun publicKey(path: String, from: DeterministicWallet.ExtendedPrivateKey = master) =
        from.derivePrivateKey(KeyPath(path)).extendedPublicKey

    private fun encoded(path: String, prefix: Int, from: DeterministicWallet.ExtendedPrivateKey = master) =
        publicKey(path, from).encode(prefix)

    private val accountXpub = encoded("m/84'/0'/0'", DeterministicWallet.xpub)
    private val accountZpub = encoded("m/84'/0'/0'", DeterministicWallet.zpub)

    private fun withChecksum(descriptor: String) = "$descriptor#${Descriptor.checksum(descriptor)}"

    /** Through the classifier, as a scan arrives, then the check. */
    private fun check(text: String, against: KeyCheckWallet = wallet): KeyOwnership {
        val payload = ScanPayloadClassifier.classifyText(text)
        assertTrue("expected keys, got $payload", payload is ScannedPayload.ExtendedKeys)
        return ExtendedKeyChecker.check((payload as ScannedPayload.ExtendedKeys).keys, against).single().ownership
    }

    private fun account(vararg issues: KeyIssue) = KeyOwnership.WalletAccount(issues.toSet())

    private fun sameSeed(path: String, vararg issues: KeyIssue) = KeyOwnership.SameSeed(KeyPath(path), issues.toSet())

    // ==================== The open account ====================

    @Test
    fun bareAccountKeyMatchesInAnyNeutralOrMatchingEncoding() {
        assertEquals(account(), check(accountZpub))
        assertEquals(account(), check(accountXpub))
    }

    @Test
    fun coordinatorDescriptorMatchesCleanly() {
        val descriptor = withChecksum("wpkh([$fingerprint/84h/0h/0h]$accountXpub/<0;1>/*)")
        assertEquals(account(), check(descriptor))
        assertEquals(account(), check("[73C5DA0A/84'/0'/0']$accountZpub/0/*"))
    }

    @Test
    fun aWrongAddressTypeIsFlagged() {
        assertEquals(account(KeyIssue.ADDRESS_TYPE), check(withChecksum("sh(wpkh([$fingerprint/84'/0'/0']$accountXpub/<0;1>/*))")))
        assertEquals(account(KeyIssue.ADDRESS_TYPE), check(withChecksum("tr($accountXpub/<0;1>/*)")))
        assertEquals(account(KeyIssue.ADDRESS_TYPE), check(encoded("m/84'/0'/0'", DeterministicWallet.ypub)))
        assertEquals(account(KeyIssue.ADDRESS_TYPE), check(encoded("m/84'/0'/0'", DeterministicWallet.Zpub)))
    }

    @Test
    fun theDescriptorDecidesTheAddressTypeOverTheVersionBytes() {
        // Sparrow accepts SLIP-132 keys inside descriptors; the wrapper is what derives addresses
        assertEquals(account(), check(withChecksum("wpkh(${encoded("m/84'/0'/0'", DeterministicWallet.ypub)}/<0;1>/*)")))
    }

    @Test
    fun theOtherNetworksEncodingIsFlagged() {
        assertEquals(account(KeyIssue.NETWORK), check(encoded("m/84'/0'/0'", DeterministicWallet.tpub)))
        assertEquals(
            setOf(KeyIssue.NETWORK, KeyIssue.ADDRESS_TYPE),
            (check(encoded("m/84'/0'/0'", DeterministicWallet.upub)) as KeyOwnership.WalletAccount).issues
        )
    }

    @Test
    fun aWrongOriginIsFlagged() {
        assertEquals(account(KeyIssue.ORIGIN), check("[deadbeef/84'/0'/0']$accountZpub"))
        assertEquals(account(KeyIssue.ORIGIN), check("[$fingerprint/84'/0'/1']$accountZpub"))
        // Sparrow's placeholder for an unknown fingerprint is a wrong origin too
        assertEquals(account(KeyIssue.ORIGIN), check("[00000000/84'/0'/0']$accountZpub"))
    }

    @Test
    fun aBranchOutsideReceiveAndChangeIsFlagged() {
        assertEquals(account(), check(withChecksum("wpkh([$fingerprint/84'/0'/0']$accountXpub/1/*)")))
        assertEquals(account(KeyIssue.BRANCH), check(withChecksum("wpkh([$fingerprint/84'/0'/0']$accountXpub/2/*)")))
        assertEquals(account(KeyIssue.BRANCH), check(withChecksum("wpkh([$fingerprint/84'/0'/0']$accountXpub/*)")))
    }

    @Test
    fun wrongDepthBytesDontHideTheAccount() {
        // Same key and chain code, serialized as if it were a master key
        val accountKey = publicKey("m/84'/0'/0'")
        val flattened = DeterministicWallet.ExtendedPublicKey(accountKey.publickeybytes, accountKey.chaincode, 0, KeyPath.empty, 0L)
        assertEquals(account(), check("[$fingerprint/84'/0'/0']${flattened.encode(DeterministicWallet.zpub)}"))
    }

    @Test
    fun aTestnetWalletMatchesItsOwnEncoding() {
        val testnet = KeyCheckWallet(master, KeyPath("m/84'/1'/0'"), ScriptType.P2WPKH, isTestnet = true)
        assertEquals(account(), check(encoded("m/84'/1'/0'", DeterministicWallet.vpub), testnet))
        assertEquals(account(KeyIssue.NETWORK), check(encoded("m/84'/1'/0'", DeterministicWallet.zpub), testnet))
    }

    // ==================== Elsewhere in the seed ====================

    @Test
    fun anotherAccountIsFoundWithoutAnOrigin() {
        assertEquals(sameSeed("m/84'/0'/1'"), check(encoded("m/84'/0'/1'", DeterministicWallet.zpub)))
        assertEquals(sameSeed("m/86'/0'/0'"), check(withChecksum("tr(${encoded("m/86'/0'/0'", DeterministicWallet.xpub)}/<0;1>/*)")))
        assertEquals(sameSeed("m/44'/1'/5'"), check(encoded("m/44'/1'/5'", DeterministicWallet.tpub)))
    }

    @Test
    fun anotherAccountKeepsItsOwnPathsAddressType() {
        val taproot = encoded("m/86'/0'/0'", DeterministicWallet.xpub)
        assertEquals(sameSeed("m/86'/0'/0'", KeyIssue.ADDRESS_TYPE), check(withChecksum("wpkh($taproot/<0;1>/*)")))
    }

    @Test
    fun aMisplacedOriginStillFindsTheKeyAndFlagsTheOrigin() {
        val accountOne = encoded("m/84'/0'/1'", DeterministicWallet.zpub)
        assertEquals(sameSeed("m/84'/0'/1'", KeyIssue.ORIGIN), check("[$fingerprint/84'/0'/0']$accountOne"))
    }

    @Test
    fun multisigCosignerKeysAreFound() {
        val cosigner = encoded("m/48'/0'/0'/2'", DeterministicWallet.Zpub)
        assertEquals(sameSeed("m/48'/0'/0'/2'"), check("[$fingerprint/48h/0h/0h/2h]$cosigner"))
        // Without an origin, the first accounts are searched
        assertEquals(sameSeed("m/48'/0'/3'/1'"), check(encoded("m/48'/0'/3'/1'", DeterministicWallet.Ypub)))
        val beyondSearch = "m/48'/0'/${ExtendedKeyChecker.BIP48_ACCOUNTS_SEARCHED}'/2'"
        assertEquals(KeyOwnership.NotOurs, check(encoded(beyondSearch, DeterministicWallet.Zpub)))
    }

    @Test
    fun theMasterKeyIsFound() {
        assertEquals(sameSeed("m"), check("[$fingerprint]${master.extendedPublicKey.encode(DeterministicWallet.xpub)}"))
        assertEquals(sameSeed("m"), check(master.extendedPublicKey.encode(DeterministicWallet.xpub)))
    }

    // ==================== Not ours ====================

    @Test
    fun anotherSeedsKeyIsNotOurs() {
        assertEquals(KeyOwnership.NotOurs, check(encoded("m/84'/0'/0'", DeterministicWallet.zpub, otherMaster)))
        val otherFingerprint = Descriptor.formatFingerprint(otherMaster.fingerprint())
        assertEquals(KeyOwnership.NotOurs, check("[$otherFingerprint/84'/0'/0']${encoded("m/84'/0'/0'", DeterministicWallet.zpub, otherMaster)}"))
    }

    @Test
    fun ourFingerprintOnAForeignKeyIsAClaimMismatch() {
        val foreign = encoded("m/84'/0'/0'", DeterministicWallet.zpub, otherMaster)
        assertEquals(KeyOwnership.ClaimMismatch(KeyPath("m/84'/0'/0'")), check("[$fingerprint/84'/0'/0']$foreign"))
    }

    @Test
    fun aPassphraseMakesItAnotherSeed() {
        val withPassphrase = DeterministicWallet.generate(MnemonicCode.toSeed(words, "TREZOR"))
        val sessionWallet = KeyCheckWallet(withPassphrase, KeyPath("m/84'/0'/0'"), ScriptType.P2WPKH, isTestnet = false)
        // The key exported without the passphrase, under the no-passphrase fingerprint
        assertEquals(KeyOwnership.NotOurs, check("[$fingerprint/84'/0'/0']$accountZpub", sessionWallet))
    }

    @Test
    fun everyKeyOfAMultiDescriptorExportIsChecked() {
        val export = listOf(
            "# Receive and change",
            withChecksum("wpkh([$fingerprint/84'/0'/0']$accountXpub/0/*)"),
            withChecksum("wpkh([$fingerprint/84'/0'/0']$accountXpub/1/*)")
        ).joinToString("\n")
        val keys = (ScanPayloadClassifier.classifyText(export) as ScannedPayload.ExtendedKeys).keys
        assertEquals(listOf(account(), account()), ExtendedKeyChecker.check(keys, wallet).map { it.ownership })
    }
}
