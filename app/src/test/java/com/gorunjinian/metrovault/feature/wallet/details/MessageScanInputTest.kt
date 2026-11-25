package com.gorunjinian.metrovault.feature.wallet.details

import com.gorunjinian.metrovault.core.qr.OutputFormat
import com.gorunjinian.metrovault.domain.service.scan.CompletedScan
import com.gorunjinian.metrovault.domain.service.scan.InvalidKind
import com.gorunjinian.metrovault.domain.service.scan.ScanNetwork
import com.gorunjinian.metrovault.domain.service.scan.ScanSession
import com.gorunjinian.metrovault.domain.service.scan.ScannedPayload
import com.gorunjinian.metrovault.domain.service.scan.SeedFormat
import com.gorunjinian.metrovault.domain.service.scan.SignatureEncoding
import com.gorunjinian.metrovault.feature.scan.addressText
import com.gorunjinian.metrovault.feature.scan.mismatchMessage
import com.gorunjinian.metrovault.feature.wallet.details.MessageScanTarget.ADDRESS
import com.gorunjinian.metrovault.feature.wallet.details.MessageScanTarget.MESSAGE
import com.gorunjinian.vaultovich.Bip322
import com.gorunjinian.vaultovich.Block
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.KeyPath
import com.gorunjinian.vaultovich.MessageSigning
import com.gorunjinian.vaultovich.MnemonicCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each kind of scan does to the Sign/Verify Message form. */
class MessageScanInputTest {

    private val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"

    /** Scan [text] as a plain QR, through the real session. */
    private fun scan(text: String): CompletedScan = (ScanSession().receive(text) as ScanSession.Update.Complete).scan

    private fun input(text: String, target: MessageScanTarget) = MessageScanInput.from(scan(text), target)

    private fun input(payload: ScannedPayload, target: MessageScanTarget, text: String? = null) =
        MessageScanInput.from(CompletedScan(payload, text), target)

    @Test
    fun anAddressOrBitcoinLinkFillsTheAddress() {
        assertEquals(MessageScanInput.Address(address), input(address, ADDRESS))
        assertEquals(MessageScanInput.Address(address), input("bitcoin:$address?amount=1", ADDRESS))
        assertEquals(MessageScanInput.Address(address), input("BITCOIN:${address.uppercase()}", ADDRESS))
    }

    @Test
    fun textTheClassifierDoesNotKnowStillReachesTheAddressField() {
        assertEquals(MessageScanInput.Address("bc1zunknown"), input(" bc1zunknown ", ADDRESS))
    }

    @Test
    fun aMessageIsTakenExactlyAsEncoded() {
        assertEquals(MessageScanInput.Message(" spaced \n"), input(" spaced \n", MESSAGE))
        // The signature commits to the text, so nothing about it may be normalized.
        assertEquals(MessageScanInput.Message("bitcoin:$address?amount=1"), input("bitcoin:$address?amount=1", MESSAGE))
        assertEquals(MessageScanInput.Message(address.uppercase()), input(address.uppercase(), MESSAGE))
    }

    @Test
    fun aSigningRequestIsRecognizedWhicheverFieldTheScanWasFor() {
        for (target in MessageScanTarget.entries) {
            val request = input("signmessage m/84h/0h/0h/0/5 ascii:hello ", target)
            assertTrue("$request", request is MessageScanInput.Request)
            assertEquals("the trailing space is part of the message", "hello ", (request as MessageScanInput.Request).request.message)
        }
    }

    @Test
    fun aMalformedRequestIsReportedInsteadOfBecomingTheMessage() {
        for (target in MessageScanTarget.entries) {
            assertTrue(input("signmessage m/84x/0 ascii:hello", target) is MessageScanInput.Unusable)
        }
    }

    @Test
    fun psbtsAreStagedAsSigningRequests() {
        // A spending PSBT too: staging explains that it belongs on Sign PSBT.
        val psbt = ScannedPayload.Psbt("cHNidP8=", OutputFormat.UR_MODERN, emptySet())
        val message = ScannedPayload.MessagePsbt("cHNidP8=", OutputFormat.UR_MODERN, emptySet())
        for (target in MessageScanTarget.entries) {
            assertEquals(MessageScanInput.MessagePsbt("cHNidP8="), input(psbt, target))
            assertEquals(MessageScanInput.MessagePsbt("cHNidP8="), input(message, target))
        }
    }

    @Test
    fun aSeedOrPrivateKeyNeverReachesTheForm() {
        val words = (List(11) { "abandon" } + "about").joinToString(" ")
        val badChecksum = List(12) { "abandon" }.joinToString(" ")
        for (text in listOf(words, badChecksum)) {
            for (target in MessageScanTarget.entries) {
                val result = input(text, target)
                assertTrue("$result", result is MessageScanInput.Unusable)
            }
        }
        val privateKey = ScannedPayload.Invalid(InvalidKind.EXTENDED_KEY, "private")
        assertEquals(MessageScanInput.Unusable("private"), input(privateKey, MESSAGE, text = "xprv..."))
    }

    @Test
    fun aScanThatIsNotTextCannotBeAMessageOrAnAddress() {
        val transaction = ScannedPayload.RawTransaction("00", "txid")
        assertEquals(
            MessageScanInput.Unusable("This QR code holds a finished transaction, not a message."),
            input(transaction, MESSAGE)
        )
        assertEquals(
            MessageScanInput.Unusable("This QR code holds a finished transaction, not an address."),
            input(transaction, ADDRESS)
        )
    }

    // ==================== Scans handed over by another screen ====================

    // BIP-39 test vector seed; its first BIP-84 address is [address]
    private val signingKey = DeterministicWallet
        .generate(MnemonicCode.toSeed(List(11) { "abandon" } + "about", ""))
        .derivePrivateKey(KeyPath("m/84'/0'/0'/0/0")).privateKey
    private val signedText = "First line\nSecond line \n"
    private val ecdsaSignature = MessageSigning.signMessage(signedText, signingKey)
    private val bip322Signature = Bip322.signSimple(address, signedText, signingKey, Block.LivenetGenesisBlock.hash)

    private fun handoff(text: String) = MessageScanInput.fromHandoff(scan(text).payload)

    private fun armored(signature: String) = "-----BEGIN BITCOIN SIGNED MESSAGE-----\n$signedText\n" +
        "-----BEGIN SIGNATURE-----\n$address\n$signature\n-----END BITCOIN SIGNED MESSAGE-----"

    @Test
    fun aHandedOverSignedMessageFillsTheWholeForm() {
        assertEquals(
            MessageScanInput.Signature(ecdsaSignature, SignatureEncoding.RECOVERABLE_ECDSA, address, signedText),
            handoff(armored(ecdsaSignature))
        )
        // Verifying tries one protocol only, so a BIP-322 signature must be told apart
        assertEquals(
            MessageScanInput.Signature(bip322Signature, SignatureEncoding.BIP322_SIMPLE, address, signedText),
            handoff(armored(bip322Signature))
        )
    }

    @Test
    fun aHandedOverSignedMessageStillVerifies() {
        val ecdsa = handoff(armored(ecdsaSignature)) as MessageScanInput.Signature
        val chain = Block.LivenetGenesisBlock.hash
        assertTrue(MessageSigning.verifyMessage(ecdsa.message, ecdsa.signature, ecdsa.address, chain).isValid)

        val bip322 = handoff(armored(bip322Signature)) as MessageScanInput.Signature
        assertTrue(Bip322.verifySimple(bip322.address, bip322.message, bip322.signature, chain))
    }

    @Test
    fun aSignedMessageInAnUnknownFormLeavesTheFormatAlone() {
        assertEquals(
            MessageScanInput.Signature("SIG", encoding = null, address = address, message = signedText),
            handoff(armored("SIG"))
        )
    }

    @Test
    fun aHandedOverBareSignatureLeavesTheRestToTheUser() {
        assertEquals(
            MessageScanInput.Signature(ecdsaSignature, SignatureEncoding.RECOVERABLE_ECDSA),
            handoff(ecdsaSignature)
        )
        assertEquals(
            MessageScanInput.Signature(bip322Signature, SignatureEncoding.BIP322_SIMPLE),
            handoff(bip322Signature)
        )
    }

    @Test
    fun handedOverSigningRequestsAreStagedLikeScannedOnes() {
        val request = handoff("signmessage m/84h/0h/0h/0/5 ascii:hello ") as MessageScanInput.Request
        assertEquals("hello ", request.request.message)

        val psbt = ScannedPayload.MessagePsbt("cHNidP8=", OutputFormat.UR_MODERN, emptySet())
        assertEquals(MessageScanInput.MessagePsbt("cHNidP8="), MessageScanInput.fromHandoff(psbt))
    }

    @Test
    fun aHandedOverScanThatIsNeitherIsExplainedInsteadOfGuessedAt() {
        // Text could be an address or a message; nobody said which
        assertEquals(
            MessageScanInput.Unusable("This QR code holds plain text, not a message to sign or verify."),
            handoff("hello there")
        )
        assertEquals(
            MessageScanInput.Unusable("This QR code holds a Bitcoin address, not a message to sign or verify."),
            handoff(address)
        )
        val words = (List(11) { "abandon" } + "about").joinToString(" ")
        assertTrue(handoff(words) is MessageScanInput.Unusable)
        assertEquals(
            MessageScanInput.Unusable("damaged"),
            MessageScanInput.fromHandoff(ScannedPayload.Invalid(InvalidKind.SIGNED_MESSAGE, "damaged"))
        )
    }

    @Test
    fun theScreensOwnScannerTreatsSignaturesAsBefore() {
        // Its field was chosen by the user, so a message that happens to be a signature stays one
        assertEquals(MessageScanInput.Message(ecdsaSignature), input(ecdsaSignature, MESSAGE))
        assertEquals(MessageScanInput.Message(armored(ecdsaSignature)), input(armored(ecdsaSignature), MESSAGE))
        assertTrue(input(ecdsaSignature, ADDRESS) is MessageScanInput.Unusable)
    }

    // ==================== Wording ====================

    @Test
    fun mismatchMessagesNameWhatWasScanned() {
        val seed = ScannedPayload.Seed(emptyList(), SeedFormat.WORDS)
        assertEquals("This QR code holds a wallet seed, not a transaction to sign.", seed.mismatchMessage("a transaction to sign"))

        val damaged = ScannedPayload.Invalid(InvalidKind.PSBT, "This PSBT couldn't be read.")
        assertEquals("a damaged payload explains itself", "This PSBT couldn't be read.", damaged.mismatchMessage("an address"))
    }

    @Test
    fun onlyAddressesAndUnknownTextHaveAddressText() {
        assertEquals("sp1q", ScannedPayload.SilentPaymentAddress("sp1q", ScanNetwork.MAINNET, "02", "03").addressText)
        assertEquals("maybe", ScannedPayload.Text(" maybe ").addressText)
        assertNull(ScannedPayload.Seed(emptyList(), SeedFormat.WORDS).addressText)
        assertNull(ScannedPayload.Invalid(InvalidKind.ADDRESS, "bad").addressText)
    }
}
