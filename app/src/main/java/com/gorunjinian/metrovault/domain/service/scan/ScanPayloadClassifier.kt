package com.gorunjinian.metrovault.domain.service.scan

import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bcur.CborItem
import com.gorunjinian.bcur.UR
import com.gorunjinian.bcur.registry.CryptoAccount
import com.gorunjinian.bcur.registry.CryptoBip39
import com.gorunjinian.bcur.registry.CryptoCoinInfo
import com.gorunjinian.bcur.registry.CryptoHDKey
import com.gorunjinian.bcur.registry.CryptoOutput
import com.gorunjinian.bcur.registry.URAccountDescriptor
import com.gorunjinian.bcur.registry.URHDKey
import com.gorunjinian.bcur.registry.UROutputDescriptor
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.core.qr.AssembledQR
import com.gorunjinian.metrovault.core.qr.OutputFormat
import com.gorunjinian.metrovault.core.qr.SeedQRUtils
import com.gorunjinian.metrovault.core.qr.URDescriptors
import com.gorunjinian.metrovault.domain.service.bitcoin.SignMessageRequest
import com.gorunjinian.metrovault.domain.service.multisig.MultisigDescriptorParser
import com.gorunjinian.vaultovich.Base58Check
import com.gorunjinian.vaultovich.BIP39Wordlist
import com.gorunjinian.vaultovich.Bip322
import com.gorunjinian.vaultovich.Bitcoin
import com.gorunjinian.vaultovich.Block
import com.gorunjinian.vaultovich.Descriptor
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.MnemonicCode
import com.gorunjinian.vaultovich.Psbt
import com.gorunjinian.vaultovich.ScriptType
import com.gorunjinian.vaultovich.ScriptWitness
import com.gorunjinian.vaultovich.Transaction
import com.gorunjinian.vaultovich.utils.Either
import java.math.BigDecimal
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import com.gorunjinian.vaultovich.silentpayments.SilentPaymentAddress as SilentPaymentCodec

/**
 * Decides what an assembled scan is, from its content alone: a PSBT, an address, a signature, a
 * seed, a multisig setup, keys, a transaction, or plain text. No wallet is consulted and nothing
 * is signed or stored; [ScanRouter] turns the result into an action.
 *
 * Every path is defensive: malformed input becomes [ScannedPayload.Invalid] (when it clearly
 * meant to be a known kind) or [ScannedPayload.Text], never an exception.
 */
object ScanPayloadClassifier {

    /** Classify a scan from [com.gorunjinian.metrovault.core.qr.QRFrameAssembler]. */
    fun classify(assembled: AssembledQR): ScannedPayload = when (assembled) {
        // A plain PSBT QR gets a modern UR reply, as the Sign PSBT screen has always chosen.
        is AssembledQR.Text -> classifyText(assembled.text, assembled.byteSegment, OutputFormat.UR_MODERN)
        is AssembledQR.Ur -> classifyUr(assembled.ur)
        is AssembledQR.BBQr -> classifyBBQr(assembled.fileType, assembled.data)
    }

    /**
     * Classify text, in order of how specific each form is.
     *
     * @param byteSegment The single byte-mode segment of a plain QR, needed only for CompactSeedQR
     * @param psbtReplyFormat The reply format for a PSBT found in the text
     */
    fun classifyText(
        text: String,
        byteSegment: ByteArray? = null,
        psbtReplyFormat: OutputFormat = OutputFormat.UR_MODERN
    ): ScannedPayload {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ScannedPayload.Text(text)

        psbtFromText(trimmed, psbtReplyFormat)?.let { return it }

        if (SignedMessageArmor.isArmored(text)) {
            return SignedMessageArmor.parse(text) ?: ScannedPayload.Invalid(
                InvalidKind.SIGNED_MESSAGE,
                "This signed message is incomplete. It needs the message, the address and the signature."
            )
        }

        // Not `trimmed`: the message runs to the end of the text, and every character is signed.
        if (SignMessageRequest.looksLikeRequest(text)) {
            return SignMessageRequest.parse(text)?.let { ScannedPayload.SignRequest(it) }
                ?: ScannedPayload.Invalid(
                    InvalidKind.SIGN_REQUEST,
                    "This message-signing request couldn't be read. Check its derivation path and address format."
                )
        }

        if (trimmed.startsWith(BIP21_SCHEME, ignoreCase = true)) return classifyBip21(trimmed)

        if (trimmed.none { it.isWhitespace() }) {
            classifyAddress(trimmed)?.let { return it }
        }

        classifySeed(trimmed, text, byteSegment)?.let { return it }
        classifyMultisig(trimmed, walletName = null)?.let { return it }
        classifyExtendedKeys(trimmed)?.let { return it }
        transactionFromHex(trimmed)?.let { return it }
        classifySignature(trimmed)?.let { return it }

        return ScannedPayload.Text(text)
    }

    // ==================== Transports ====================

    private fun classifyUr(ur: UR): ScannedPayload {
        val type = ur.type.lowercase()
        return try {
            when (type) {
                "crypto-psbt" -> classifyPsbtBytes(ur.toBytes(), OutputFormat.UR_LEGACY)
                "psbt" -> classifyPsbtBytes(ur.toBytes(), OutputFormat.UR_MODERN)
                "bytes" -> classifyBytes(ur.toBytes(), OutputFormat.UR_MODERN)
                "crypto-output" -> {
                    val descriptor = URDescriptors.fromCryptoOutput(CryptoOutput.fromCbor(cbor(ur)))
                        ?: return unsupported("This output descriptor has no usable keys.")
                    classifyDescriptor(descriptor, walletName = null)
                }
                "output-descriptor" -> {
                    val record = UROutputDescriptor.fromCbor(cbor(ur))
                    classifyDescriptor(
                        URDescriptors.fromOutputDescriptor(record),
                        walletName = record.name?.trim()?.takeIf { it.isNotEmpty() }
                    )
                }
                "crypto-hdkey" -> hdKeysPayload(listOf(CryptoHDKey.fromCbor(cbor(ur))), fallbackFingerprint = null)
                "hdkey" -> hdKeysPayload(listOf(URHDKey.fromCbor(cbor(ur))), fallbackFingerprint = null)
                "crypto-account" -> {
                    val account = CryptoAccount.fromCbor(cbor(ur))
                    accountPayload(account.outputDescriptors.mapNotNull { URDescriptors.fromCryptoOutput(it) }, account.masterFingerprint)
                }
                "account-descriptor" -> {
                    val account = URAccountDescriptor.fromCbor(cbor(ur))
                    accountPayload(account.outputDescriptors.map { URDescriptors.fromOutputDescriptor(it) }, account.masterFingerprint)
                }
                "crypto-bip39" -> seedFromWords(CryptoBip39.fromCbor(cbor(ur)).words, SeedFormat.UR_BIP39)
                else -> unsupported("ur:$type QR codes aren't supported.")
            }
        } catch (_: Exception) {
            unsupported("The ur:$type QR code couldn't be decoded.")
        }
    }

    private fun classifyBBQr(fileType: FileType, data: ByteArray): ScannedPayload = when (fileType) {
        FileType.Psbt -> classifyPsbtBytes(data, OutputFormat.BBQR)
        FileType.Transaction -> transactionFromBytes(data)
            ?: ScannedPayload.Invalid(InvalidKind.TRANSACTION, "The transaction in this BBQr couldn't be decoded.")
        FileType.Json, FileType.UnicodeText -> decodeUtf8(data)?.let { classifyText(it, null, OutputFormat.BBQR) }
            ?: unsupported("The text in this BBQr isn't valid UTF-8.")
        FileType.Cbor -> unsupported("BBQr CBOR payloads aren't supported.")
    }

    /** `ur:bytes` is generic: sniff for a PSBT, then a transaction, then text. */
    private fun classifyBytes(bytes: ByteArray, replyFormat: OutputFormat): ScannedPayload {
        if (bytes.hasPsbtMagic()) return classifyPsbtBytes(bytes, replyFormat)
        transactionFromBytes(bytes)?.let { return it }
        val text = decodeUtf8(bytes) ?: return unsupported("This QR code holds binary data MetroVault doesn't recognize.")
        return classifyText(text, null, replyFormat)
    }

    // ==================== PSBT ====================

    private fun psbtFromText(trimmed: String, replyFormat: OutputFormat): ScannedPayload? {
        val bytes = when {
            trimmed.startsWith(PSBT_BASE64_PREFIX) ->
                runCatching { Base64.getMimeDecoder().decode(trimmed) }.getOrNull()
            trimmed.startsWith(PSBT_HEX_PREFIX, ignoreCase = true) -> hexToBytes(trimmed)
            else -> return null
        } ?: return ScannedPayload.Invalid(InvalidKind.PSBT, "This PSBT's encoding is damaged.")
        return classifyPsbtBytes(bytes, replyFormat)
    }

    private fun classifyPsbtBytes(bytes: ByteArray, replyFormat: OutputFormat): ScannedPayload {
        if (!bytes.hasPsbtMagic()) {
            return ScannedPayload.Invalid(InvalidKind.PSBT, "The scanned data isn't a PSBT.")
        }
        val psbt = when (val read = Psbt.read(bytes)) {
            is Either.Right -> read.value
            is Either.Left -> {
                AppLog.w(TAG) { "PSBT rejected: ${read.value::class.simpleName}" }
                return ScannedPayload.Invalid(
                    InvalidKind.PSBT,
                    "This PSBT couldn't be read. It may be damaged or of an unsupported version."
                )
            }
        }

        val base64 = Base64.getEncoder().encodeToString(bytes)
        val fingerprints = psbt.inputs.flatMap { input ->
            input.derivationPaths.values.map { it.masterKeyFingerprint } +
                input.taprootDerivationPaths.values.map { it.masterKeyFingerprint }
        }.mapTo(mutableSetOf()) { Descriptor.formatFingerprint(it) }

        val isMessageRequest = psbt.global.unknown.any {
            it.key.size() == 1 && it.key[0] == Bip322.PSBT_GLOBAL_GENERIC_SIGNED_MESSAGE
        }
        return if (isMessageRequest) {
            ScannedPayload.MessagePsbt(base64, replyFormat, fingerprints)
        } else {
            ScannedPayload.Psbt(base64, replyFormat, fingerprints)
        }
    }

    // ==================== Addresses ====================

    private fun classifyBip21(uri: String): ScannedPayload {
        val rest = uri.substring(BIP21_SCHEME.length)
        val target = normalizeBech32Case(rest.substringBefore('?').trim())
        val params = parseQuery(rest.substringAfter('?', ""))

        if (target.isEmpty()) {
            // BIP-352 lets a payment link carry only a silent payment address.
            val sp = params["sp"]?.let { normalizeBech32Case(it.trim()) }
                ?: return ScannedPayload.Invalid(InvalidKind.ADDRESS, "This bitcoin: link has no address.")
            return silentPaymentAddress(sp)
                ?: ScannedPayload.Invalid(InvalidKind.ADDRESS, "This bitcoin: link has an invalid silent payment address.")
        }
        if (looksLikeSilentPayment(target)) {
            return silentPaymentAddress(target)
                ?: ScannedPayload.Invalid(InvalidKind.ADDRESS, "This bitcoin: link has an invalid silent payment address.")
        }
        val network = addressNetwork(target)
            ?: return ScannedPayload.Invalid(InvalidKind.ADDRESS, "This bitcoin: link has an invalid address.")
        return ScannedPayload.Address(
            address = target,
            network = network,
            amountSats = params["amount"]?.let { btcToSats(it) },
            label = params["label"]?.takeIf { it.isNotBlank() },
            message = params["message"]?.takeIf { it.isNotBlank() }
        )
    }

    /** A bare address, or null when [token] isn't one (so later checks can claim it). */
    private fun classifyAddress(token: String): ScannedPayload? {
        val candidate = normalizeBech32Case(token)
        if (looksLikeSilentPayment(candidate)) {
            return silentPaymentAddress(candidate)
                ?: ScannedPayload.Invalid(InvalidKind.ADDRESS, "This silent payment address is invalid.")
        }
        if (candidate.length !in 14..100) return null
        return addressNetwork(candidate)?.let { ScannedPayload.Address(candidate, it) }
    }

    private fun addressNetwork(address: String): ScanNetwork? = ADDRESS_CHAINS.firstOrNull { (_, chainHash) ->
        Bitcoin.addressToPublicKeyScript(chainHash, address) is Either.Right
    }?.first

    private fun looksLikeSilentPayment(candidate: String): Boolean {
        val lower = candidate.lowercase()
        return (lower.startsWith("sp1") || lower.startsWith("tsp1")) && candidate.length >= 100
    }

    private fun silentPaymentAddress(candidate: String): ScannedPayload.SilentPaymentAddress? {
        val lower = candidate.lowercase()
        val network = when {
            lower.startsWith("sp1") -> ScanNetwork.MAINNET
            lower.startsWith("tsp1") -> ScanNetwork.TESTNET
            else -> return null
        }
        val decoded = runCatching { SilentPaymentCodec.decode(lower) }.getOrNull() ?: return null
        return ScannedPayload.SilentPaymentAddress(
            address = lower,
            network = network,
            scanPubKeyHex = decoded.scanPubKey.value.toHex().lowercase(),
            spendPubKeyHex = decoded.spendPubKey.value.toHex().lowercase()
        )
    }

    /** Bech32 is case-insensitive and QR alphanumeric mode is upper case; Base58 is left alone. */
    private fun normalizeBech32Case(candidate: String): String {
        val lower = candidate.lowercase()
        val isBech32 = BECH32_PREFIXES.any { lower.startsWith(it) }
        return if (isBech32 && candidate == candidate.uppercase()) lower else candidate
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').lowercase()
            if (key.isEmpty()) return@mapNotNull null
            // BIP-21 percent-encodes; a literal '+' is not a space.
            val value = runCatching {
                URLDecoder.decode(pair.substringAfter('=', "").replace("+", "%2B"), "UTF-8")
            }.getOrNull() ?: return@mapNotNull null
            key to value
        }.toMap()
    }

    private fun btcToSats(amount: String): Long? = runCatching {
        BigDecimal(amount).movePointRight(8).longValueExact().takeIf { it > 0 }
    }.getOrNull()

    // ==================== Seeds ====================

    private fun classifySeed(trimmed: String, original: String, byteSegment: ByteArray?): ScannedPayload? {
        val tokens = trimmed.lowercase().split(WHITESPACE)
        if ((tokens.size == 12 || tokens.size == 24) && tokens.all { it in englishWords }) {
            return seedFromWords(tokens, SeedFormat.WORDS)
        }

        if ((trimmed.length == 48 || trimmed.length == 96) && trimmed.all { it.isDigit() }) {
            return SeedQRUtils.standardSeedQRToMnemonic(trimmed)
                ?.let { ScannedPayload.Seed(it, SeedFormat.STANDARD_SEEDQR) }
                ?: ScannedPayload.Invalid(InvalidKind.SEED, "This looks like a SeedQR, but it doesn't decode to a valid seed.")
        }

        return SeedQRUtils.decodeCompactSeedQR(original, byteSegment)
            ?.let { ScannedPayload.Seed(it, SeedFormat.COMPACT_SEEDQR) }
    }

    private fun seedFromWords(words: List<String>, format: SeedFormat): ScannedPayload {
        val normalized = words.map { it.trim().lowercase() }
        return try {
            MnemonicCode.validate(normalized)
            ScannedPayload.Seed(normalized, format)
        } catch (_: Exception) {
            ScannedPayload.Invalid(InvalidKind.SEED, "These look like seed words, but the checksum doesn't match.")
        }
    }

    // ==================== Multisig setups and keys ====================

    /** UR descriptors: a multisig goes to Import Multisig, a single-key one is a key check. */
    private fun classifyDescriptor(descriptor: String, walletName: String?): ScannedPayload =
        classifyMultisig(descriptor, walletName)
            ?: classifyExtendedKeys(descriptor)
            ?: unsupported("This output descriptor isn't supported.")

    private fun classifyMultisig(trimmed: String, walletName: String?): ScannedPayload.MultisigSetup? =
        MultisigDescriptorParser.detectFormat(trimmed)
            ?.let { ScannedPayload.MultisigSetup(trimmed, it, walletName) }

    private fun classifyExtendedKeys(trimmed: String): ScannedPayload? {
        if (PRIVATE_EXTENDED_KEY.containsMatchIn(trimmed)) {
            return ScannedPayload.Invalid(
                InvalidKind.EXTENDED_KEY,
                "This QR code holds an extended private key, which the scanner doesn't accept."
            )
        }
        val isDescriptor = SINGLE_SIG_SCRIPTS.keys.any { descriptorBody(trimmed).startsWith(it) }
        val wholeKey = KEY_EXPRESSION.matchEntire(trimmed)
        if (!isDescriptor && wholeKey == null) return null

        val matches = wholeKey?.let { listOf(it) } ?: KEY_EXPRESSION.findAll(trimmed).toList()
        if (matches.isEmpty()) return null
        if (isDescriptor && !descriptorChecksumsMatch(trimmed)) {
            return ScannedPayload.Invalid(
                InvalidKind.EXTENDED_KEY,
                "This descriptor's checksum doesn't match its contents. It may have been altered or " +
                    "damaged on the way here; export it again."
            )
        }
        val keys = matches.map { match ->
            scannedKey(
                xpub = match.groupValues[3],
                fingerprint = match.groupValues[1],
                derivationPath = match.groupValues[2],
                childPath = match.groupValues[4],
                descriptorScript = if (isDescriptor) scriptWrapping(trimmed, match.range.first) else null
            ) ?: return ScannedPayload.Invalid(
                InvalidKind.EXTENDED_KEY,
                "An extended public key in this QR code is damaged (its checksum doesn't match)."
            )
        }
        return ScannedPayload.ExtendedKeys(keys, descriptor = trimmed.takeIf { isDescriptor })
    }

    private fun hdKeysPayload(hdKeys: List<CryptoHDKey>, fallbackFingerprint: String?): ScannedPayload {
        if (hdKeys.any { it.isPrivateKey }) {
            return ScannedPayload.Invalid(
                InvalidKind.EXTENDED_KEY,
                "This QR code holds an extended private key, which the scanner doesn't accept."
            )
        }
        val keys = hdKeys.map { hdKey ->
            val expression = KEY_EXPRESSION.find(URDescriptors.formatHDKey(hdKey))
                ?: return unsupported("This key record has no usable extended public key.")
            val origin = hdKey.origin
            val fingerprint = origin?.sourceFingerprint?.takeIf { it.size == 4 }?.toHex() ?: fallbackFingerprint
            ScannedExtendedKey(
                xpub = expression.groupValues[3],
                fingerprint = fingerprint,
                derivationPath = origin?.path,
                network = if (hdKey.useInfo?.resolvedNetwork == CryptoCoinInfo.Network.TESTNET) {
                    ScanNetwork.TESTNET
                } else {
                    ScanNetwork.MAINNET
                },
                childPath = expression.groupValues[4].removePrefix("/").ifEmpty { null }
            )
        }
        return ScannedPayload.ExtendedKeys(keys)
    }

    /** An account export: one single-key descriptor per script type, all from one seed. */
    private fun accountPayload(descriptors: List<String>, masterFingerprint: ByteArray): ScannedPayload {
        val accountFingerprint = masterFingerprint.takeIf { it.size == 4 }?.toHex()
        val keys = descriptors.flatMap { descriptor ->
            when (val payload = classifyExtendedKeys(descriptor)) {
                is ScannedPayload.ExtendedKeys -> payload.keys
                is ScannedPayload.Invalid -> return payload
                else -> emptyList()
            }
        }.map { key -> if (key.fingerprint == null) key.copy(fingerprint = accountFingerprint) else key }
        if (keys.isEmpty()) return unsupported("This account export has no usable keys.")
        return ScannedPayload.ExtendedKeys(keys)
    }

    /**
     * Validate [xpub] by its Base58 checksum and version bytes only. A full decode is avoided on
     * purpose: it rejects coordinator xpubs whose depth bytes don't match their origin path.
     */
    private fun scannedKey(
        xpub: String,
        fingerprint: String,
        derivationPath: String,
        childPath: String,
        descriptorScript: ScriptType?
    ): ScannedExtendedKey? {
        val (prefix, data) = runCatching { Base58Check.decodeWithIntPrefix(xpub) }.getOrNull() ?: return null
        if (data.size != EXTENDED_KEY_PAYLOAD_SIZE) return null
        val network = when (prefix) {
            in DeterministicWallet.mainnetPublicPrefixes -> ScanNetwork.MAINNET
            in DeterministicWallet.testnetPublicPrefixes -> ScanNetwork.TESTNET
            else -> return null
        }
        return ScannedExtendedKey(
            xpub = xpub,
            fingerprint = fingerprint.lowercase().ifEmpty { null },
            derivationPath = derivationPath.removePrefix("/").ifEmpty { null },
            network = network,
            childPath = childPath.removePrefix("/").ifEmpty { null },
            descriptorScript = descriptorScript
        )
    }

    /**
     * The address type of the single-sig descriptor that wraps the key starting at [keyStart]: the
     * text between the start of its line and the key must be exactly one wrapper, so a key nested
     * deeper (in a script tree, say) gets none.
     */
    private fun scriptWrapping(text: String, keyStart: Int): ScriptType? {
        val lineStart = text.lastIndexOf('\n', keyStart - 1) + 1
        val prefix = text.substring(lineStart, keyStart).filterNot { it.isWhitespace() }.lowercase()
        return SINGLE_SIG_SCRIPTS[prefix]
    }

    /**
     * BIP-380: every descriptor line's `#checksum`, when present, matches the text before it. The
     * key check relies on the wrapper and child path, which a key's own Base58 checksum doesn't
     * cover. Lines are checked one by one, since an export can list several descriptors.
     */
    private fun descriptorChecksumsMatch(text: String): Boolean = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && '#' in it }
        .all { line ->
            val parts = line.split('#')
            parts.size == 2 && runCatching { Descriptor.checksum(parts[0].trim()) }.getOrNull() == parts[1].trim()
        }

    /** A descriptor's script without comment lines, checksum or whitespace, lower-cased for matching. */
    private fun descriptorBody(text: String): String = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .joinToString("")
        .substringBefore('#')
        .filterNot { it.isWhitespace() }
        .lowercase()

    // ==================== Transactions ====================

    private fun transactionFromHex(trimmed: String): ScannedPayload.RawTransaction? {
        if (trimmed.length < MIN_TRANSACTION_HEX_LENGTH) return null
        return hexToBytes(trimmed)?.let { transactionFromBytes(it) }
    }

    /** A transaction only if the bytes are exactly one serialized transaction with inputs and outputs. */
    private fun transactionFromBytes(bytes: ByteArray): ScannedPayload.RawTransaction? = try {
        val tx = Transaction.read(bytes)
        if (tx.txIn.isNotEmpty() && tx.txOut.isNotEmpty() && Transaction.write(tx).contentEquals(bytes)) {
            ScannedPayload.RawTransaction(hex = bytes.toHex(), txid = tx.txid.toString())
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    // ==================== Signatures ====================

    private fun classifySignature(token: String): ScannedPayload.Signature? =
        signatureEncoding(token)?.let { ScannedPayload.Signature(token, it) }

    /**
     * How [token] encodes a signature, or null if it isn't one: base64 of 65-byte recoverable ECDSA
     * with an Electrum/BIP-137 header, or of a BIP-322 simple signature (a witness stack shaped
     * like a key-path or P2WPKH spend).
     */
    fun signatureEncoding(token: String): SignatureEncoding? {
        if (token.length !in 80..400 || token.any { it.isWhitespace() }) return null
        val hasBip322Prefix = token.startsWith(Bip322.SIMPLE_PREFIX)
        val bytes = runCatching { Base64.getDecoder().decode(token.removePrefix(Bip322.SIMPLE_PREFIX)) }.getOrNull()
            ?: return null

        if (!hasBip322Prefix && bytes.size == 65 && (bytes[0].toInt() and 0xFF) in 27..42) {
            return SignatureEncoding.RECOVERABLE_ECDSA
        }
        val witness = runCatching { ScriptWitness.read(bytes) }.getOrNull() ?: return null
        val isSignatureShaped = when (witness.stack.size) {
            1 -> witness.stack[0].size() == 64 || witness.stack[0].size() == 65
            2 -> witness.stack[0].size() in 9..73 && witness.stack[1].size() == 33
            else -> false
        }
        return if (isSignatureShaped && ScriptWitness.write(witness).contentEquals(bytes)) {
            SignatureEncoding.BIP322_SIMPLE
        } else {
            null
        }
    }

    // ==================== Helpers ====================

    private fun unsupported(reason: String) = ScannedPayload.Invalid(InvalidKind.UNSUPPORTED, reason)

    private fun cbor(ur: UR): CborItem = CborItem.decode(ur.cborData)

    private fun ByteArray.hasPsbtMagic(): Boolean =
        size >= PSBT_MAGIC.size && PSBT_MAGIC.indices.all { this[it] == PSBT_MAGIC[it] }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || !HEX.matches(hex)) return null
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val englishWords: Set<String> by lazy { BIP39Wordlist.getEnglishWordlist().toHashSet() }

    private const val TAG = "ScanPayloadClassifier"
    private const val BIP21_SCHEME = "bitcoin:"
    private const val PSBT_BASE64_PREFIX = "cHNidP"
    private const val PSBT_HEX_PREFIX = "70736274ff"
    private val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())

    /** Version bytes excluded: depth, parent, child number, chain code and key. */
    private const val EXTENDED_KEY_PAYLOAD_SIZE = 74

    /** The smallest real transaction (one input, one output) is about 60 bytes. */
    private const val MIN_TRANSACTION_HEX_LENGTH = 120

    private val ADDRESS_CHAINS = listOf(
        ScanNetwork.MAINNET to Block.LivenetGenesisBlock.hash,
        ScanNetwork.TESTNET to Block.Testnet4GenesisBlock.hash,
        ScanNetwork.REGTEST to Block.RegtestGenesisBlock.hash
    )
    private val BECH32_PREFIXES = listOf("bc1", "tb1", "bcrt1", "sp1", "tsp1")
    private val SINGLE_SIG_SCRIPTS = mapOf(
        "pkh(" to ScriptType.P2PKH,
        "wpkh(" to ScriptType.P2WPKH,
        "sh(wpkh(" to ScriptType.P2SH_P2WPKH,
        "tr(" to ScriptType.P2TR
    )

    private val WHITESPACE = Regex("\\s+")
    private val HEX = Regex("^[0-9a-fA-F]+$")

    /** `[fingerprint/origin]xpub/children`, origin and children optional. Groups: 1 fp, 2 origin, 3 key, 4 children. */
    private val KEY_EXPRESSION = Regex(
        """(?:\[([0-9a-fA-F]{8})((?:/[0-9]+['hH]?)*)])?([xtyzuvYZUV]pub[1-9A-HJ-NP-Za-km-z]{100,120})((?:/(?:[0-9]+['hH]?|\*|<[0-9;]+>))*)"""
    )
    private val PRIVATE_EXTENDED_KEY = Regex("""[xtyzuvYZUV]prv[1-9A-HJ-NP-Za-km-z]{100,120}""")
}
