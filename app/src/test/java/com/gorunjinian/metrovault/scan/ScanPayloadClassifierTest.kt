package com.gorunjinian.metrovault.scan

import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bbqr.SplitResult
import com.gorunjinian.bcur.Cbor
import com.gorunjinian.bcur.UR
import com.gorunjinian.bcur.UREncoder
import com.gorunjinian.bcur.registry.CryptoAccount
import com.gorunjinian.bcur.registry.CryptoBip39
import com.gorunjinian.bcur.registry.CryptoCoinInfo
import com.gorunjinian.bcur.registry.CryptoHDKey
import com.gorunjinian.bcur.registry.CryptoKeypath
import com.gorunjinian.bcur.registry.CryptoOutput
import com.gorunjinian.bcur.registry.MultiKey
import com.gorunjinian.bcur.registry.ScriptExpression
import com.gorunjinian.bcur.registry.UROutputDescriptor
import com.gorunjinian.bcur.registry.pathcomponent.IndexPathComponent
import com.gorunjinian.bcur.registry.pathcomponent.PairPathComponent
import com.gorunjinian.bcur.registry.pathcomponent.WildcardPathComponent
import com.gorunjinian.metrovault.core.qr.AssembledQR
import com.gorunjinian.metrovault.core.qr.OutputFormat
import com.gorunjinian.metrovault.core.qr.QRFrameAssembler
import com.gorunjinian.metrovault.core.qr.SeedQRUtils
import com.gorunjinian.metrovault.domain.service.scan.InvalidKind
import com.gorunjinian.metrovault.domain.service.multisig.MultisigSetupFormat
import com.gorunjinian.metrovault.domain.service.scan.ScanNetwork
import com.gorunjinian.metrovault.domain.service.scan.ScanPayloadClassifier
import com.gorunjinian.metrovault.domain.service.scan.ScannedPayload
import com.gorunjinian.metrovault.domain.service.scan.SeedFormat
import com.gorunjinian.metrovault.domain.service.scan.SignatureEncoding
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
import com.gorunjinian.vaultovich.Base58Check
import com.gorunjinian.vaultovich.Bip322
import com.gorunjinian.vaultovich.Block
import com.gorunjinian.vaultovich.ByteVector
import com.gorunjinian.vaultovich.ByteVector32
import com.gorunjinian.vaultovich.DataEntry
import com.gorunjinian.vaultovich.Descriptor
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.Global
import com.gorunjinian.vaultovich.Input
import com.gorunjinian.vaultovich.KeyPath
import com.gorunjinian.vaultovich.KeyPathWithMaster
import com.gorunjinian.vaultovich.MessageSigning
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
import com.gorunjinian.vaultovich.byteVector
import com.gorunjinian.vaultovich.silentpayments.SilentPaymentAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Random

/**
 * Scan-anything classification: each payload kind is recognized from every transport it arrives
 * in, malformed versions of a kind are reported as such, and nothing else is claimed by mistake.
 */
class ScanPayloadClassifierTest {

    // BIP-39 / BIP-84 test vector seed.
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val master = DeterministicWallet.generate(MnemonicCode.toSeed(words, ""))
    private val fingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey)
    private val fingerprintHex = "73c5da0a"
    private val mainnet = Block.LivenetGenesisBlock.hash
    private val testnet = Block.Testnet4GenesisBlock.hash

    private fun key(path: String) = master.derivePrivateKey(KeyPath(path))
    private val receiveKey = key("m/84'/0'/0'/0/0")
    private val mainnetAddress = receiveKey.publicKey.p2wpkhAddress(mainnet)
    private val accountKey = key("m/84'/0'/0'")
    private val accountZpub = DeterministicWallet.publicKey(accountKey).encode(DeterministicWallet.zpub)
    private val accountXpub = DeterministicWallet.publicKey(accountKey).encode(DeterministicWallet.xpub)

    // ==================== Helpers ====================

    private fun text(content: String) = ScanPayloadClassifier.classifyText(content)

    /** Full pipeline: frames through the assembler, then the classifier. */
    private fun scan(vararg frames: String): ScannedPayload {
        val assembler = QRFrameAssembler()
        frames.forEach { frame ->
            val result = assembler.receive(frame)
            if (result is QRFrameAssembler.FrameResult.Complete) return ScanPayloadClassifier.classify(result.payload)
        }
        throw AssertionError("frames did not complete")
    }

    private fun ur(type: String, data: ByteArray) = UREncoder.encode(UR.fromBytes(data, type))

    private fun bbqr(fileType: FileType, data: ByteArray) =
        ScanPayloadClassifier.classify(AssembledQR.BBQr(fileType, data, numParts = 1))

    private inline fun <reified T : ScannedPayload> ScannedPayload.expect(): T {
        assertTrue("expected ${T::class.simpleName}, got $this", this is T)
        return this as T
    }

    private fun ScannedPayload.expectInvalid(kind: InvalidKind) {
        val invalid = expect<ScannedPayload.Invalid>()
        assertEquals(kind, invalid.kind)
        assertTrue(invalid.reason.isNotBlank())
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun withChecksum(descriptor: String) = "$descriptor#${Descriptor.checksum(descriptor)}"

    /** A one-input P2WPKH spend whose input names this seed's key, as a coordinator builds it. */
    private fun spendPsbt(withDerivation: Boolean = true): ByteArray {
        val inputKey = key("m/84'/1'/0'/0/0")
        val txOut = TxOut(Satoshi(50_000), Script.pay2wpkh(inputKey.publicKey))
        val prevTx = Transaction(
            2,
            listOf(TxIn(OutPoint(TxId(ByteVector32.Zeroes), 0xffffffffL), ByteVector.empty, 0xffffffffL)),
            listOf(txOut),
            0
        )
        val tx = Transaction(
            2,
            listOf(TxIn(OutPoint(prevTx, 0L), ByteVector.empty, 0xfffffffeL)),
            listOf(TxOut(Satoshi(49_000), Script.pay2wpkh(key("m/84'/1'/0'/1/0").publicKey))),
            0
        )
        val derivations = if (withDerivation) {
            mapOf(inputKey.publicKey to KeyPathWithMaster(fingerprint, KeyPath("m/84'/1'/0'/0/0")))
        } else {
            emptyMap()
        }
        val input = Input.WitnessInput.PartiallySignedWitnessInput(
            txOut, prevTx, null, emptyMap(), derivations,
            null, null, emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null, emptyList()
        )
        val output = Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), emptyList())
        return Psbt.write(Psbt(Global(0, tx, emptyList(), emptyList()), listOf(input), listOf(output))).toByteArray()
    }

    /** A BIP-322 message-signing PSBT for this seed's first receive address. */
    private fun messagePsbt(): ByteArray {
        val outputScript = Script.write(Script.pay2wpkh(receiveKey.publicKey)).byteVector()
        val toSpend = Bip322.toSpend(outputScript, "Hello World")
        val input = Input.WitnessInput.PartiallySignedWitnessInput(
            toSpend.txOut[0], null, SigHash.SIGHASH_ALL, emptyMap(),
            mapOf(receiveKey.publicKey to KeyPathWithMaster(fingerprint, KeyPath("m/84'/0'/0'/0/0"))),
            null, null, emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null, emptyList()
        )
        val output = Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), emptyList())
        val messageEntry = DataEntry(
            ByteVector(byteArrayOf(Bip322.PSBT_GLOBAL_GENERIC_SIGNED_MESSAGE)),
            ByteVector("Hello World".encodeToByteArray())
        )
        val psbt = Psbt(Global(0, Bip322.toSign(toSpend), emptyList(), listOf(messageEntry)), listOf(input), listOf(output))
        return Psbt.write(psbt).toByteArray()
    }

    private fun rawTransaction(): Transaction = Transaction(
        2,
        listOf(TxIn(OutPoint(TxId(ByteVector32.One), 1L), ByteVector.empty, 0xfffffffdL)),
        listOf(TxOut(Satoshi(12_345), Script.pay2wpkh(receiveKey.publicKey))),
        0
    )

    private fun hdKey(isPrivate: Boolean = false) = CryptoHDKey(
        privateKey = isPrivate,
        key = DeterministicWallet.publicKey(accountKey).publickeybytes.toByteArray(),
        chainCode = accountKey.chaincode.toByteArray(),
        useInfo = CryptoCoinInfo(CryptoCoinInfo.Type.BITCOIN, CryptoCoinInfo.Network.MAINNET),
        origin = CryptoKeypath(
            listOf(IndexPathComponent(84, true), IndexPathComponent(0, true), IndexPathComponent(0, true)),
            fingerprintHex.hexToByteArray()
        ),
        children = CryptoKeypath(
            listOf(PairPathComponent(IndexPathComponent(0, false), IndexPathComponent(1, false)), WildcardPathComponent(false)),
            null
        ),
        parentFingerprint = null
    )

    // ==================== PSBTs ====================

    @Test
    fun base64PsbtCarriesItsInputFingerprints() {
        val bytes = spendPsbt()
        val psbt = text(Base64.getEncoder().encodeToString(bytes)).expect<ScannedPayload.Psbt>()

        assertEquals(Base64.getEncoder().encodeToString(bytes), psbt.base64)
        assertEquals(setOf(fingerprintHex), psbt.inputFingerprints)
        assertEquals(OutputFormat.UR_MODERN, psbt.replyFormat)
    }

    @Test
    fun hexPsbtIsReencodedAsBase64() {
        val bytes = spendPsbt()
        val psbt = text(bytes.hex()).expect<ScannedPayload.Psbt>()

        assertEquals(Base64.getEncoder().encodeToString(bytes), psbt.base64)
    }

    @Test
    fun psbtReplyFormatFollowsItsTransport() {
        val bytes = spendPsbt()

        assertEquals(OutputFormat.UR_LEGACY, scan(ur("crypto-psbt", bytes)).expect<ScannedPayload.Psbt>().replyFormat)
        assertEquals(OutputFormat.UR_MODERN, scan(ur("psbt", bytes)).expect<ScannedPayload.Psbt>().replyFormat)
        assertEquals(OutputFormat.UR_MODERN, scan(ur("bytes", bytes)).expect<ScannedPayload.Psbt>().replyFormat)
        assertEquals(OutputFormat.BBQR, bbqr(FileType.Psbt, bytes).expect<ScannedPayload.Psbt>().replyFormat)
    }

    @Test
    fun psbtWithoutDerivationsHasNoFingerprints() {
        val psbt = text(Base64.getEncoder().encodeToString(spendPsbt(withDerivation = false))).expect<ScannedPayload.Psbt>()
        assertTrue(psbt.inputFingerprints.isEmpty())
    }

    @Test
    fun damagedPsbtIsReportedAsPsbt() {
        val damaged = spendPsbt().copyOf(40)
        text(Base64.getEncoder().encodeToString(damaged)).expectInvalid(InvalidKind.PSBT)
        bbqr(FileType.Psbt, "not a psbt".toByteArray()).expectInvalid(InvalidKind.PSBT)
    }

    @Test
    fun bip322MessagePsbtIsSeparatedFromSpends() {
        val message = scan(ur("crypto-psbt", messagePsbt())).expect<ScannedPayload.MessagePsbt>()

        assertEquals(setOf(fingerprintHex), message.inputFingerprints)
        assertEquals(OutputFormat.UR_LEGACY, message.replyFormat)
    }

    // ==================== Addresses ====================

    @Test
    fun bareAddressesAreTaggedWithTheirNetwork() {
        assertEquals(ScannedPayload.Address(mainnetAddress, ScanNetwork.MAINNET), text(mainnetAddress))

        val p2pkh = receiveKey.publicKey.p2pkhAddress(mainnet)
        assertEquals(ScanNetwork.MAINNET, text(p2pkh).expect<ScannedPayload.Address>().network)

        val testnetAddress = receiveKey.publicKey.p2wpkhAddress(testnet)
        assertEquals(ScanNetwork.TESTNET, text(testnetAddress).expect<ScannedPayload.Address>().network)

        val regtestAddress = receiveKey.publicKey.p2wpkhAddress(Block.RegtestGenesisBlock.hash)
        assertEquals(ScanNetwork.REGTEST, text(regtestAddress).expect<ScannedPayload.Address>().network)
    }

    @Test
    fun upperCaseBech32IsNormalizedToLowerCase() {
        assertEquals(mainnetAddress, text(mainnetAddress.uppercase()).expect<ScannedPayload.Address>().address)
    }

    @Test
    fun addressWithBadChecksumIsNotClaimed() {
        val mangled = mainnetAddress.dropLast(1) + if (mainnetAddress.last() == 'q') "p" else "q"
        text(mangled).expect<ScannedPayload.Text>()
    }

    @Test
    fun bip21UriKeepsAmountLabelAndMessage() {
        val uri = "BITCOIN:${mainnetAddress.uppercase()}?amount=0.0015&label=Coffee%20shop&message=a+b"
        val address = text(uri).expect<ScannedPayload.Address>()

        assertEquals(mainnetAddress, address.address)
        assertEquals(150_000L, address.amountSats)
        assertEquals("Coffee shop", address.label)
        assertEquals("a+b", address.message)
    }

    @Test
    fun bip21UriWithInvalidAddressIsRejected() {
        text("bitcoin:notanaddress?amount=1").expectInvalid(InvalidKind.ADDRESS)
    }

    @Test
    fun silentPaymentAddressCarriesItsKeys() {
        val scanKey = key("m/352'/0'/0'/1'/0").publicKey
        val spendKey = key("m/352'/0'/0'/0'/0").publicKey
        val encoded = SilentPaymentAddress(scanKey, spendKey).encode(isTestnet = false)

        val sp = text(encoded).expect<ScannedPayload.SilentPaymentAddress>()
        assertEquals(ScanNetwork.MAINNET, sp.network)
        assertEquals(scanKey.value.toHex(), sp.scanPubKeyHex)
        assertEquals(spendKey.value.toHex(), sp.spendPubKeyHex)

        assertEquals(sp, text("bitcoin:?sp=$encoded"))

        val testnetSp = SilentPaymentAddress(scanKey, spendKey).encode(isTestnet = true)
        assertEquals(ScanNetwork.TESTNET, text(testnetSp).expect<ScannedPayload.SilentPaymentAddress>().network)
    }

    // ==================== Message signing ====================

    @Test
    fun textSigningRequestsAreParsed() {
        val request = text("signmessage m/84h/0h/0h/0/5 ascii:hello").expect<ScannedPayload.SignRequest>()
        assertEquals("hello", request.request.message)

        val json = bbqr(FileType.Json, """{"msg": "hi", "subpath": "m/84h/0h/0h/0/1", "addr_fmt": "p2wpkh"}""".toByteArray())
        assertEquals("hi", json.expect<ScannedPayload.SignRequest>().request.message)
    }

    @Test
    fun signingRequestKeepsEveryCharacterOfItsMessage() {
        // The signature commits to the message, so trailing whitespace must not be trimmed away.
        val request = text("signmessage m/84h/0h/0h/0/5 ascii:hello \n").expect<ScannedPayload.SignRequest>()
        assertEquals("hello \n", request.request.message)
    }

    @Test
    fun malformedSigningRequestIsReportedRatherThanShownAsText() {
        text("signmessage m/84x/0 ascii:hello").expectInvalid(InvalidKind.SIGN_REQUEST)
    }

    @Test
    fun armoredSignedMessagePartsVerify() {
        val message = "First line\nSecond line\n"
        val signature = MessageSigning.signMessage(message, receiveKey.privateKey)

        for (marker in listOf("-----BEGIN SIGNATURE-----", "-----BEGIN BITCOIN SIGNATURE-----")) {
            val armored = "-----BEGIN BITCOIN SIGNED MESSAGE-----\n$message\n$marker\n" +
                "$mainnetAddress\n$signature\n-----END BITCOIN SIGNED MESSAGE-----"

            val signed = text(armored).expect<ScannedPayload.SignedMessage>()
            assertEquals("message must survive byte for byte ($marker)", message, signed.message)
            assertEquals(mainnetAddress, signed.address)
            assertEquals(signature, signed.signature)
            assertTrue(MessageSigning.verifyMessage(signed.message, signed.signature, signed.address, mainnet).isValid)
        }
    }

    @Test
    fun crlfArmoredMessageParses() {
        val armored = "-----BEGIN BITCOIN SIGNED MESSAGE-----\r\nHello\r\n-----BEGIN BITCOIN SIGNATURE-----\r\n" +
            "$mainnetAddress\r\nSIG\r\n-----END BITCOIN SIGNATURE-----\r\n"

        assertEquals(ScannedPayload.SignedMessage("Hello", mainnetAddress, "SIG"), text(armored))
    }

    @Test
    fun armoredMessageMissingItsSignatureIsRejected() {
        text("-----BEGIN BITCOIN SIGNED MESSAGE-----\nHello\n-----BEGIN SIGNATURE-----\n$mainnetAddress\n")
            .expectInvalid(InvalidKind.SIGNED_MESSAGE)
    }

    @Test
    fun bareSignaturesAreRecognizedByShape() {
        val ecdsa = MessageSigning.signMessage("hello", receiveKey.privateKey)
        assertEquals(SignatureEncoding.RECOVERABLE_ECDSA, text(ecdsa).expect<ScannedPayload.Signature>().encoding)

        val bip322 = Bip322.signSimple(mainnetAddress, "hello", receiveKey.privateKey, mainnet)
        assertEquals(SignatureEncoding.BIP322_SIMPLE, text(bip322).expect<ScannedPayload.Signature>().encoding)
        val withoutPrefix = bip322.removePrefix(Bip322.SIMPLE_PREFIX)
        assertEquals(SignatureEncoding.BIP322_SIMPLE, text(withoutPrefix).expect<ScannedPayload.Signature>().encoding)
    }

    @Test
    fun arbitraryBase64IsNotASignature() {
        val bytes = ByteArray(65).also { Random(7).nextBytes(it) }.apply { this[0] = 0 }
        text(Base64.getEncoder().encodeToString(bytes)).expect<ScannedPayload.Text>()
    }

    // ==================== Seeds ====================

    @Test
    fun seedWordsAreRecognizedAndNeverPrinted() {
        val seed = text(words.joinToString(" ").uppercase()).expect<ScannedPayload.Seed>()

        assertEquals(words, seed.words)
        assertEquals(SeedFormat.WORDS, seed.format)
        assertFalse(seed.toString().contains("abandon"))
    }

    @Test
    fun seedWordsWithBadChecksumAreRejected() {
        text(List(12) { "abandon" }.joinToString(" ")).expectInvalid(InvalidKind.SEED)
    }

    @Test
    fun standardSeedQrDecodes() {
        val digits = SeedQRUtils.mnemonicToStandardSeedQR(words)!!
        val seed = text(digits).expect<ScannedPayload.Seed>()

        assertEquals(words, seed.words)
        assertEquals(SeedFormat.STANDARD_SEEDQR, seed.format)
        text("0".repeat(48)).expectInvalid(InvalidKind.SEED)
    }

    // CompactSeedQR needs real QR codes to test: see SeedQRUtilsTest.

    @Test
    fun urBip39Decodes() {
        val seed = scan(UREncoder.encode(CryptoBip39(words, "en").toUR())).expect<ScannedPayload.Seed>()
        assertEquals(words, seed.words)
        assertEquals(SeedFormat.UR_BIP39, seed.format)
    }

    // ==================== Multisig setups ====================

    private val multisigDescriptor =
        "wsh(sortedmulti(2,[aaaaaaaa/48'/0'/0'/2']xpubPlaceholderKey1/<0;1>/*,[bbbbbbbb/48'/0'/0'/2']xpubPlaceholderKey2/<0;1>/*))#abcdefgh"

    @Test
    fun multisigSetupsAreRecognizedInEachFormat() {
        assertEquals(MultisigSetupFormat.DESCRIPTOR, text(multisigDescriptor).expect<ScannedPayload.MultisigSetup>().format)

        val bsms = "BSMS 1.0\n$multisigDescriptor\n/0/*,/1/*\nbc1qexampleaddress"
        assertEquals(MultisigSetupFormat.BSMS, text(bsms).expect<ScannedPayload.MultisigSetup>().format)

        val setupFile = "Name: Vault\nPolicy: 2 of 2\nFormat: P2WSH\nDerivation: m/48'/0'/0'/2'\naaaaaaaa: xpubKey1\nbbbbbbbb: xpubKey2"
        val viaUr = scan(ur("bytes", setupFile.toByteArray())).expect<ScannedPayload.MultisigSetup>()
        assertEquals(MultisigSetupFormat.SETUP_FILE, viaUr.format)
        assertEquals(setupFile, viaUr.content)

        val viaBBQr = bbqr(FileType.UnicodeText, multisigDescriptor.toByteArray()).expect<ScannedPayload.MultisigSetup>()
        assertEquals(multisigDescriptor, viaBBQr.content)
    }

    @Test
    fun urOutputDescriptorKeepsItsWalletName() {
        val record = UROutputDescriptor(source = multisigDescriptor, name = "Family Vault")
        val setup = scan(UREncoder.encode(record.toUR())).expect<ScannedPayload.MultisigSetup>()

        assertEquals("Family Vault", setup.walletName)
        assertEquals(multisigDescriptor, setup.content)
    }

    @Test
    fun cryptoOutputMultisigBecomesADescriptor() {
        val output = CryptoOutput(
            listOf(ScriptExpression.WITNESS_SCRIPT_HASH, ScriptExpression.SORTED_MULTISIG),
            MultiKey(2, emptyList(), listOf(hdKey(), hdKey()))
        )
        val setup = scan(UREncoder.encode(output.toUR())).expect<ScannedPayload.MultisigSetup>()

        assertEquals(MultisigSetupFormat.DESCRIPTOR, setup.format)
        assertTrue(setup.content.startsWith("wsh(sortedmulti(2,[$fingerprintHex/84'/0'/0']xpub"))
    }

    @Test
    fun taprootMultisigIsASetupEvenThoughImportRefusesIt() {
        val descriptor = "tr($accountXpub,multi_a(2,$accountXpub/0/*,$accountXpub/1/*))"
        assertEquals(MultisigSetupFormat.DESCRIPTOR, text(descriptor).expect<ScannedPayload.MultisigSetup>().format)
    }

    // ==================== Extended keys ====================

    @Test
    fun bareSlip132KeyIsAKeyCheck() {
        val keys = text(accountZpub).expect<ScannedPayload.ExtendedKeys>()

        assertEquals(1, keys.keys.size)
        assertEquals(accountZpub, keys.keys[0].xpub)
        assertNull(keys.keys[0].fingerprint)
        assertEquals(ScanNetwork.MAINNET, keys.keys[0].network)
        assertNull(keys.descriptor)
    }

    @Test
    fun keyExpressionCarriesItsOrigin() {
        val key = text("[73C5DA0A/84'/0'/0']$accountZpub").expect<ScannedPayload.ExtendedKeys>().keys.single()

        assertEquals(fingerprintHex, key.fingerprint)
        assertEquals("84'/0'/0'", key.derivationPath)
    }

    @Test
    fun singleSigDescriptorIsAKeyCheckKeepingTheDescriptor() {
        val descriptor = withChecksum("wpkh([$fingerprintHex/84h/0h/0h]$accountXpub/<0;1>/*)")
        val keys = text(descriptor).expect<ScannedPayload.ExtendedKeys>()

        assertEquals(descriptor, keys.descriptor)
        val key = keys.keys.single()
        assertEquals("84h/0h/0h", key.derivationPath)
        assertEquals("<0;1>/*", key.childPath)
        assertEquals(ScriptType.P2WPKH, key.descriptorScript)
    }

    @Test
    fun eachKeyCarriesTheScriptOfItsOwnDescriptor() {
        val export = listOf(
            "# Single-sig descriptors",
            "pkh([$fingerprintHex/44'/0'/0']$accountXpub/0/*)",
            "sh(wpkh([$fingerprintHex/49'/0'/0']$accountXpub/0/*))",
            withChecksum("tr([$fingerprintHex/86'/0'/0']$accountXpub/1/*)")
        ).joinToString("\n")
        val keys = text(export).expect<ScannedPayload.ExtendedKeys>().keys

        assertEquals(listOf(ScriptType.P2PKH, ScriptType.P2SH_P2WPKH, ScriptType.P2TR), keys.map { it.descriptorScript })
        assertEquals(listOf("0/*", "0/*", "1/*"), keys.map { it.childPath })
    }

    @Test
    fun aKeyOutsideADescriptorHasNoScript() {
        val key = text("[$fingerprintHex/84'/0'/0']$accountZpub/0/*").expect<ScannedPayload.ExtendedKeys>().keys.single()
        assertNull(key.descriptorScript)
        assertEquals("0/*", key.childPath)
        assertNull(text(accountZpub).expect<ScannedPayload.ExtendedKeys>().keys.single().childPath)
    }

    @Test
    fun aDescriptorWithAWrongChecksumIsRejected() {
        val descriptor = "wpkh([$fingerprintHex/84'/0'/0']$accountXpub/<0;1>/*)"
        val checksum = Descriptor.checksum(descriptor)
        val wrong = checksum.dropLast(1) + if (checksum.last() == 'q') 'p' else 'q'
        text("$descriptor#$wrong").expectInvalid(InvalidKind.EXTENDED_KEY)

        // One bad line in an export spoils it, since its keys can't be trusted
        text("${withChecksum(descriptor)}\n$descriptor#$wrong").expectInvalid(InvalidKind.EXTENDED_KEY)
        // A path altered after the checksum was taken
        text(withChecksum(descriptor).replace("/<0;1>/*", "/2/*")).expectInvalid(InvalidKind.EXTENDED_KEY)
    }

    @Test
    fun testnetKeyIsTaggedTestnet() {
        val tpub = DeterministicWallet.publicKey(key("m/84'/1'/0'")).encode(DeterministicWallet.tpub)
        assertEquals(ScanNetwork.TESTNET, text(tpub).expect<ScannedPayload.ExtendedKeys>().keys.single().network)
    }

    @Test
    fun damagedOrPrivateKeysAreRejected() {
        val index = 20
        val replacement = if (accountZpub[index] == 'A') 'B' else 'A'
        val damaged = accountZpub.substring(0, index) + replacement + accountZpub.substring(index + 1)
        text(damaged).expectInvalid(InvalidKind.EXTENDED_KEY)

        text(master.encode(DeterministicWallet.xprv)).expectInvalid(InvalidKind.EXTENDED_KEY)
    }

    @Test
    fun urHdKeyCarriesTheSameKeyAndOrigin() {
        val key = scan(UREncoder.encode(hdKey().toUR())).expect<ScannedPayload.ExtendedKeys>().keys.single()

        assertEquals(fingerprintHex, key.fingerprint)
        assertEquals("84'/0'/0'", key.derivationPath)
        val (_, payload) = Base58Check.decodeWithIntPrefix(key.xpub)
        assertArrayEquals(
            DeterministicWallet.publicKey(accountKey).publickeybytes.toByteArray(),
            payload.copyOfRange(payload.size - 33, payload.size)
        )
    }

    @Test
    fun urPrivateHdKeyIsRejected() {
        scan(UREncoder.encode(hdKey(isPrivate = true).toUR())).expectInvalid(InvalidKind.EXTENDED_KEY)
    }

    @Test
    fun urAccountExportListsItsKeys() {
        val account = CryptoAccount(
            fingerprintHex.hexToByteArray(),
            listOf(CryptoOutput(listOf(ScriptExpression.WITNESS_PUBLIC_KEY_HASH), hdKey()))
        )
        val key = scan(UREncoder.encode(account.toUR())).expect<ScannedPayload.ExtendedKeys>().keys.single()

        assertEquals(fingerprintHex, key.fingerprint)
        assertEquals("84'/0'/0'", key.derivationPath)
    }

    // ==================== Transactions ====================

    @Test
    fun rawTransactionIsDecodedFromEveryTransport() {
        val tx = rawTransaction()
        val bytes = Transaction.write(tx)
        val expected = ScannedPayload.RawTransaction(bytes.hex(), tx.txid.toString())

        assertEquals(expected, text(bytes.hex()))
        assertEquals(expected, text(bytes.hex().uppercase()))
        assertEquals(expected, bbqr(FileType.Transaction, bytes))
        assertEquals(expected, scan(ur("bytes", bytes)))
    }

    @Test
    fun hexThatIsNotATransactionIsText() {
        val hex = "00".repeat(80)
        assertEquals(ScannedPayload.Text(hex), text(hex))
    }

    // ==================== Robustness ====================

    @Test
    fun damagedPayloadsNeverThrow() {
        val random = Random(99)
        val psbt = spendPsbt()
        repeat(2_000) {
            val bytes = psbt.copyOf(if (random.nextInt(4) == 0) 5 + random.nextInt(psbt.size - 5) else psbt.size)
            repeat(1 + random.nextInt(6)) {
                if (bytes.size > 5) bytes[5 + random.nextInt(bytes.size - 5)] = random.nextInt(256).toByte()
            }
            text(Base64.getEncoder().encodeToString(bytes))
            FileType.entries.forEach { bbqr(it, bytes) }
            ScanPayloadClassifier.classify(AssembledQR.Ur(UR.fromBytes(bytes, "crypto-psbt"), isMultipart = false))
        }
    }

    // ==================== Everything else ====================

    @Test
    fun unsupportedUrTypeIsReportedAsUnsupported() {
        scan(UREncoder.encode(UR("crypto-sskr", Cbor.encodeByteString(byteArrayOf(1, 2, 3)))))
            .expectInvalid(InvalidKind.UNSUPPORTED)
    }

    @Test
    fun binaryUrBytesThatAreNothingKnownAreUnsupported() {
        scan(ur("bytes", byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00, 0x01))).expectInvalid(InvalidKind.UNSUPPORTED)
    }

    @Test
    fun plainTextFallsThrough() {
        assertEquals(ScannedPayload.Text("hello world"), text("hello world"))
        assertEquals(ScannedPayload.Text("   "), text("   "))
    }

    @Test
    fun bbqrSplitTextReachesTheTextClassifier() {
        val parts = SplitResult.fromData("signmessage m/84h/0h/0h/0/5 ascii:hello".toByteArray(), FileType.UnicodeText).parts
        assertEquals("hello", scan(*parts.toTypedArray()).expect<ScannedPayload.SignRequest>().request.message)
    }

}
