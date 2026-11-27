package com.gorunjinian.metrovault.scan

import com.gorunjinian.metrovault.core.qr.OutputFormat
import com.gorunjinian.metrovault.data.model.CosignerInfo
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.MultisigScriptType
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.WalletProfile
import com.gorunjinian.metrovault.domain.service.bitcoin.SignMessageRequest
import com.gorunjinian.metrovault.domain.service.scan.ChooseReason
import com.gorunjinian.metrovault.domain.service.scan.InvalidKind
import com.gorunjinian.metrovault.domain.service.scan.KeyMatch
import com.gorunjinian.metrovault.domain.service.multisig.MultisigSetupFormat
import com.gorunjinian.metrovault.domain.service.scan.ScanAction
import com.gorunjinian.metrovault.domain.service.scan.ScanContext
import com.gorunjinian.metrovault.domain.service.scan.ScanNetwork
import com.gorunjinian.metrovault.domain.service.scan.ScanRouter
import com.gorunjinian.metrovault.domain.service.scan.ScanWallet
import com.gorunjinian.metrovault.domain.service.scan.ScannedExtendedKey
import com.gorunjinian.metrovault.domain.service.scan.ScannedPayload
import com.gorunjinian.metrovault.domain.service.scan.SeedFormat
import com.gorunjinian.metrovault.domain.service.scan.SignatureEncoding
import com.gorunjinian.metrovault.domain.service.scan.WalletTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scan routing: from Home a payload goes to the wallet its keys name, or the user chooses among
 * the wallets able to act on it; inside a wallet it goes to that wallet or is refused with a reason.
 */
class ScanRouterTest {

    private val seedWords = List(12) { "word$it" }

    private val single = metadata("single", "m/84'/0'/0'", "73c5da0a")
    private val singleTestnet = metadata("single-testnet", "m/84'/1'/0'", "11111111")
    private val otherSingle = metadata("other", "m/84'/0'/0'", "22222222")
    private val silentPayment = metadata("sp", "m/352'/0'/0'", "73c5da0a").copy(
        isSilentPayment = true,
        silentPaymentScanPubKey = "02AA",
        silentPaymentSpendPubKey = "03BB"
    )
    private val multisig = metadata("multi", "multisig/2of2", "73c5da0a").copy(
        isMultisig = true,
        multisigConfig = MultisigConfig(
            m = 2,
            n = 2,
            cosigners = listOf(
                CosignerInfo("xpubA", "73c5da0a", "48'/0'/0'/2'", isLocal = true),
                CosignerInfo("xpubB", "33333333", "48'/0'/0'/2'", isLocal = false)
            ),
            localKeyFingerprints = listOf("73C5DA0A"),
            scriptType = MultisigScriptType.P2WSH,
            rawDescriptor = "wsh(sortedmulti(2,...))"
        )
    )

    private val vault = listOf(single, singleTestnet, otherSingle, silentPayment, multisig).map { ScanWallet.of(it) }
    private fun home(wallets: List<ScanWallet> = vault) = ScanContext(wallets)
    private fun inWallet(metadata: WalletMetadata) = ScanContext(vault, activeWallet = ScanWallet.of(metadata))

    private val router = ScanRouter { words -> if (words == seedWords) "73C5DA0A" else null }

    private fun metadata(id: String, path: String, fingerprint: String) = WalletMetadata(
        id = id,
        name = id,
        derivationPath = path,
        masterFingerprint = fingerprint,
        hasPassphrase = false,
        createdAt = 0L
    )

    private fun psbt(vararg fingerprints: String) =
        ScannedPayload.Psbt("cHNidP8=", OutputFormat.UR_MODERN, fingerprints.toSet())

    private fun address(network: ScanNetwork) = ScannedPayload.Address("bc1qexample", network)

    private inline fun <reified T : ScanAction> ScanAction.expect(): T {
        assertTrue("expected ${T::class.simpleName}, got $this", this is T)
        return this as T
    }

    // ==================== Wallet metadata ====================

    @Test
    fun scanWalletReadsNetworkAndFingerprintsFromMetadata() {
        val multi = ScanWallet.of(multisig)
        assertEquals(setOf("73c5da0a"), multi.fingerprints)
        assertEquals(false, multi.isTestnet)

        val testnetMultisig = multisig.copy(
            multisigConfig = multisig.multisigConfig!!.copy(
                cosigners = multisig.multisigConfig!!.cosigners.map { it.copy(derivationPath = "48'/1'/0'/2'") }
            )
        )
        assertEquals(true, ScanWallet.of(testnetMultisig).isTestnet)
        assertEquals(true, ScanWallet.of(singleTestnet).isTestnet)
        assertEquals("02aa", ScanWallet.of(silentPayment).silentPaymentScanPubKey)
    }

    // ==================== PSBTs from Home ====================

    @Test
    fun psbtGoesToTheOneWalletItsInputsName() {
        val action = router.route(psbt("22222222"), home()).expect<ScanAction.SignPsbt>()

        assertEquals(WalletTarget.Resolved("other"), action.target)
        assertEquals(KeyMatch.MATCHED, action.keyMatch)
    }

    @Test
    fun psbtNamingASharedSeedOffersEveryWalletHoldingIt() {
        val action = router.route(psbt("73c5da0a"), home()).expect<ScanAction.SignPsbt>()

        assertEquals(WalletTarget.Choose(listOf("single", "sp", "multi"), ChooseReason.SEVERAL_MATCH), action.target)
        assertEquals(KeyMatch.MATCHED, action.keyMatch)
    }

    @Test
    fun psbtNamingNoVaultKeyOffersEveryWallet() {
        val action = router.route(psbt("deadbeef"), home()).expect<ScanAction.SignPsbt>()

        assertEquals(WalletTarget.Choose(vault.map { it.id!! }, ChooseReason.NO_MATCH), action.target)
        assertEquals(KeyMatch.NOT_MATCHED, action.keyMatch)
    }

    @Test
    fun psbtWithoutDerivationsOffersEveryWallet() {
        val action = router.route(psbt(), home()).expect<ScanAction.SignPsbt>()

        assertEquals(ChooseReason.NOT_DETERMINABLE, (action.target as WalletTarget.Choose).reason)
        assertEquals(KeyMatch.NO_KEY_INFO, action.keyMatch)
    }

    @Test
    fun psbtInAnEmptyVaultIsUnsupported() {
        router.route(psbt("73c5da0a"), home(emptyList())).expect<ScanAction.Unsupported>()
    }

    @Test
    fun messagePsbtSkipsMultisigWallets() {
        val message = ScannedPayload.MessagePsbt("cHNidP8=", OutputFormat.UR_LEGACY, setOf("73c5da0a"))
        val action = router.route(message, home()).expect<ScanAction.SignMessagePsbt>()

        assertEquals(WalletTarget.Choose(listOf("single", "sp"), ChooseReason.SEVERAL_MATCH), action.target)
    }

    // ==================== In a wallet ====================

    @Test
    fun psbtScannedInAWalletTargetsItAndReportsTheKeyMatch() {
        val matched = router.route(psbt("73c5da0a"), inWallet(single)).expect<ScanAction.SignPsbt>()
        assertEquals(WalletTarget.ActiveWallet, matched.target)
        assertEquals(KeyMatch.MATCHED, matched.keyMatch)

        // Still routed: signing can fall back to address lookup.
        val unmatched = router.route(psbt("deadbeef"), inWallet(single)).expect<ScanAction.SignPsbt>()
        assertEquals(KeyMatch.NOT_MATCHED, unmatched.keyMatch)

        assertEquals(KeyMatch.NO_KEY_INFO, router.route(psbt(), inWallet(single)).expect<ScanAction.SignPsbt>().keyMatch)
    }

    @Test
    fun multisigWalletRefusesMessageSigning() {
        val message = ScannedPayload.MessagePsbt("cHNidP8=", OutputFormat.UR_LEGACY, setOf("73c5da0a"))
        val refused = router.route(message, inWallet(multisig)).expect<ScanAction.Unsupported>()

        assertTrue(refused.reason.contains("Multisig"))
    }

    @Test
    fun statelessWalletIsTheActiveTarget() {
        val stateless = ScanWallet(
            id = null,
            profile = WalletProfile.stateless("m/84'/0'/0'"),
            isTestnet = false,
            fingerprints = setOf("44444444")
        )
        val action = router.route(psbt("44444444"), ScanContext(vault, stateless)).expect<ScanAction.SignPsbt>()

        assertEquals(WalletTarget.ActiveWallet, action.target)
        assertEquals(KeyMatch.MATCHED, action.keyMatch)
    }

    // ==================== Addresses ====================

    @Test
    fun addressFromHomeOffersWalletsOnItsNetworkThatCanCheckAddresses() {
        val mainnet = router.route(address(ScanNetwork.MAINNET), home()).expect<ScanAction.CheckAddress>()
        assertEquals(WalletTarget.Choose(listOf("single", "other", "multi"), ChooseReason.NOT_DETERMINABLE), mainnet.target)

        val testnet = router.route(address(ScanNetwork.TESTNET), home()).expect<ScanAction.CheckAddress>()
        assertEquals(WalletTarget.Choose(listOf("single-testnet"), ChooseReason.NOT_DETERMINABLE), testnet.target)

        router.route(address(ScanNetwork.REGTEST), home(listOf(ScanWallet.of(single)))).expect<ScanAction.Unsupported>()
    }

    @Test
    fun addressInAWalletIsRefusedOnNetworkMismatchOrForSilentPayments() {
        assertEquals(
            WalletTarget.ActiveWallet,
            router.route(address(ScanNetwork.MAINNET), inWallet(single)).expect<ScanAction.CheckAddress>().target
        )

        val mismatch = router.route(address(ScanNetwork.TESTNET), inWallet(single)).expect<ScanAction.Unsupported>()
        assertEquals("This is a testnet address, but this wallet is on mainnet.", mismatch.reason)

        router.route(address(ScanNetwork.MAINNET), inWallet(silentPayment)).expect<ScanAction.Unsupported>()
    }

    @Test
    fun silentPaymentAddressIsAnsweredFromStoredKeys() {
        val ours = ScannedPayload.SilentPaymentAddress("sp1ours", ScanNetwork.MAINNET, "02aa", "03bb")
        val fromHome = router.route(ours, home()).expect<ScanAction.SilentPaymentAddressOwnership>()
        assertEquals(listOf("sp"), fromHome.ownerWalletIds)
        assertEquals(null, fromHome.activeWalletOwns)

        assertEquals(true, router.route(ours, inWallet(silentPayment)).expect<ScanAction.SilentPaymentAddressOwnership>().activeWalletOwns)
        assertEquals(false, router.route(ours, inWallet(single)).expect<ScanAction.SilentPaymentAddressOwnership>().activeWalletOwns)

        val theirs = ours.copy(spendPubKeyHex = "03cc")
        assertEquals(emptyList<String>(), router.route(theirs, home()).expect<ScanAction.SilentPaymentAddressOwnership>().ownerWalletIds)

        val wrongNetwork = ours.copy(network = ScanNetwork.TESTNET)
        assertEquals(emptyList<String>(), router.route(wrongNetwork, home()).expect<ScanAction.SilentPaymentAddressOwnership>().ownerWalletIds)
    }

    // ==================== Text signing requests ====================

    @Test
    fun textRequestFromHomeOffersSingleSigWalletsOnThePathsNetwork() {
        val mainnetRequest = ScannedPayload.SignRequest(SignMessageRequest.parse("signmessage m/84h/0h/0h/0/5 ascii:hi")!!)
        val action = router.route(mainnetRequest, home()).expect<ScanAction.SignTextRequest>()
        // Multisig can't sign messages; silent payment wallets sign only BIP-322 message PSBTs.
        assertEquals(WalletTarget.Choose(listOf("single", "other"), ChooseReason.NOT_DETERMINABLE), action.target)

        val testnetRequest = ScannedPayload.SignRequest(SignMessageRequest.parse("signmessage m/84h/1h/0h/0/5 ascii:hi")!!)
        assertEquals(
            WalletTarget.Choose(listOf("single-testnet"), ChooseReason.NOT_DETERMINABLE),
            router.route(testnetRequest, home()).expect<ScanAction.SignTextRequest>().target
        )
    }

    @Test
    fun textRequestInASilentPaymentWalletIsRefused() {
        val request = ScannedPayload.SignRequest(SignMessageRequest.parse("signmessage m/84h/0h/0h/0/5 ascii:hi")!!)
        router.route(request, inWallet(silentPayment)).expect<ScanAction.Unsupported>()
    }

    // ==================== Extended keys ====================

    @Test
    fun extendedKeyGoesToTheSingleSigWalletWithItsFingerprint() {
        val keys = ScannedPayload.ExtendedKeys(
            listOf(ScannedExtendedKey("zpub...", "73c5da0a", "84'/0'/0'", ScanNetwork.MAINNET))
        )
        val action = router.route(keys, home()).expect<ScanAction.CheckExtendedKeys>()

        // The silent payment and multisig wallets share the fingerprint but can't check xpubs.
        assertEquals(WalletTarget.Resolved("single"), action.target)
        assertEquals(KeyMatch.MATCHED, action.keyMatch)

        router.route(keys, inWallet(multisig)).expect<ScanAction.Unsupported>()
    }

    // ==================== Wallet-free actions ====================

    @Test
    fun seedImportNamesWalletsAlreadyMadeFromIt() {
        val seed = ScannedPayload.Seed(seedWords, SeedFormat.WORDS)
        val action = router.route(seed, home()).expect<ScanAction.ImportSeed>()

        // The multisig shares the fingerprint as a cosigner, but wasn't made from this seed.
        assertEquals(listOf("single", "sp"), action.existingWalletIds)

        val unknown = ScannedPayload.Seed(List(12) { "other$it" }, SeedFormat.WORDS)
        assertEquals(emptyList<String>(), router.route(unknown, inWallet(single)).expect<ScanAction.ImportSeed>().existingWalletIds)
    }

    @Test
    fun walletFreePayloadsRouteTheSameEverywhere() {
        val payloads = listOf(
            ScannedPayload.SignedMessage("m", "a", "s") to ScanAction.VerifySignedMessage::class,
            ScannedPayload.Signature("sig", SignatureEncoding.BIP322_SIMPLE) to ScanAction.VerifySignature::class,
            ScannedPayload.MultisigSetup("wsh(...)", MultisigSetupFormat.DESCRIPTOR) to ScanAction.ImportMultisig::class,
            ScannedPayload.RawTransaction("00", "txid") to ScanAction.ShowTransaction::class,
            ScannedPayload.Text("hello") to ScanAction.ShowText::class,
            ScannedPayload.Invalid(InvalidKind.PSBT, "bad") to ScanAction.Rejected::class
        )
        for ((payload, expected) in payloads) {
            for (context in listOf(home(), inWallet(multisig))) {
                val action = router.route(payload, context)
                assertTrue("$payload in $context routed to $action", expected.isInstance(action))
            }
        }
    }
}
