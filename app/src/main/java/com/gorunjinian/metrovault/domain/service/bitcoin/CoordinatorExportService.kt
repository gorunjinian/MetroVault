package com.gorunjinian.metrovault.domain.service.bitcoin

import com.gorunjinian.metrovault.data.model.CoordinatorExportData
import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet

/**
 * Builds deterministic, public-only coordinator exports. This service performs no I/O and accepts
 * an account public key rather than seed or private-key material.
 */
class CoordinatorExportService(
    private val keyEncodingService: KeyEncodingService = KeyEncodingService(),
    private val addressService: AddressService = AddressService()
) {
    data class AccountPath(
        val purpose: Int,
        val coinType: Int,
        val accountNumber: Int,
        val normalized: String,
        val scriptType: ScriptType
    ) {
        val isTestnet: Boolean get() = coinType == 1

        /**
         * The path with apostrophe hardened markers (`m/84'/0'/0'`). Coldcard writes its `deriv`
         * field and its multisig `Derivation:` lines this way, so the Coldcard-shaped JSON uses
         * this form while descriptors keep the `h` form their checksum was computed over.
         */
        val apostropheForm: String get() = normalized.replace('h', '\'')
    }

    fun buildExport(
        walletName: String,
        masterFingerprint: String,
        derivationPath: String,
        accountPublicKey: DeterministicWallet.ExtendedPublicKey
    ): CoordinatorExportData {
        val path = parseAccountPath(derivationPath)
        val fingerprint = normalizeFingerprint(masterFingerprint)
        val standardXpub = keyEncodingService.getStandardAccountXpub(accountPublicKey, path.isTestnet)
        val slip132Xpub = keyEncodingService.getAccountXpub(accountPublicKey, path.scriptType, path.isTestnet)
        validateAccountPublicKey(standardXpub, path)
        validateEquivalentPublicKeys(standardXpub, slip132Xpub)

        val descriptor = keyEncodingService.getWalletDescriptor(
            fingerprint = fingerprint,
            accountPath = path.normalized,
            accountPublicKey = accountPublicKey,
            scriptType = path.scriptType,
            isTestnet = path.isTestnet
        )
        val firstAddress = requireNotNull(
            addressService.generateAddress(
                accountPublicKey = accountPublicKey,
                index = 0,
                isChange = false,
                scriptType = path.scriptType,
                isTestnet = path.isTestnet
            )
        ) { "Could not derive the first receiving address" }.address

        val signerRecord = buildNunchukSignerRecord(fingerprint, path.normalized, standardXpub)
        val json = buildColdcardJson(
            fingerprint = fingerprint,
            accountFingerprint = formatFingerprint(accountPublicKey.fingerprint()),
            path = path,
            standardXpub = standardXpub,
            slip132Xpub = slip132Xpub,
            descriptor = descriptor,
            firstAddress = firstAddress
        )
        validateDescriptor(descriptor, fingerprint, path, standardXpub)

        return CoordinatorExportData(
            walletName = walletName,
            networkName = if (path.isTestnet) "Bitcoin testnet" else "Bitcoin mainnet",
            addressType = addressTypeName(path.scriptType),
            accountNumber = path.accountNumber,
            derivationPath = path.normalized,
            masterFingerprint = fingerprint,
            standardAccountXpub = standardXpub,
            slip132AccountXpub = slip132Xpub,
            descriptor = descriptor,
            firstReceiveAddress = firstAddress,
            nunchukSignerRecord = signerRecord,
            coldcardJson = json
        )
    }

    companion object {
        // Groups: 1 = purpose, 2 = coin type, 3 = account. The hardened markers are matched but
        // not captured — any of ' h H is accepted and normalized to h on the way out.
        private val ACCOUNT_PATH = Regex("^m/(44|49|84|86)['hH]/([01])['hH]/(0|[1-9][0-9]*)['hH]$")
        private val FINGERPRINT = Regex("^[0-9a-fA-F]{8}$")
        private const val MAX_ACCOUNT = 0x7fffffffL

        @JvmStatic
        fun buildNunchukSignerRecord(
            masterFingerprint: String,
            derivationPath: String,
            standardAccountXpub: String
        ): String {
            val fingerprint = normalizeFingerprint(masterFingerprint)
            val path = parseAccountPath(derivationPath)
            validateAccountPublicKey(standardAccountXpub, path)
            val origin = path.normalized.removePrefix("m/")
            return "[$fingerprint/$origin]$standardAccountXpub/<0;1>/*"
        }

        @JvmStatic
        fun parseAccountPath(path: String): AccountPath {
            val match = ACCOUNT_PATH.matchEntire(path)
                ?: throw IllegalArgumentException("Expected a supported BIP44/49/84/86 account path")
            val purpose = match.groupValues[1].toInt()
            val coinType = match.groupValues[2].toInt()
            val accountLong = match.groupValues[3].toLongOrNull()
                ?: throw IllegalArgumentException("Malformed account number")
            require(accountLong <= MAX_ACCOUNT) { "Account number is outside the BIP32 range" }
            val account = accountLong.toInt()
            val scriptType = when (purpose) {
                44 -> ScriptType.P2PKH
                49 -> ScriptType.P2SH_P2WPKH
                84 -> ScriptType.P2WPKH
                86 -> ScriptType.P2TR
                else -> error("Path regex admitted an unsupported purpose")
            }
            return AccountPath(purpose, coinType, account, "m/${purpose}h/${coinType}h/${account}h", scriptType)
        }

        private fun normalizeFingerprint(value: String): String {
            require(FINGERPRINT.matches(value)) { "Master fingerprint must be exactly eight hexadecimal characters" }
            return value.uppercase()
        }

        /** Formats a BIP32 key fingerprint as eight uppercase hex digits, matching Coldcard. */
        private fun formatFingerprint(value: Long): String =
            value.toString(16).padStart(8, '0').uppercase()

        private fun validateAccountPublicKey(xpub: String, path: AccountPath) {
            val (prefix, decoded) = runCatching {
                DeterministicWallet.ExtendedPublicKey.decode(xpub)
            }.getOrElse { throw IllegalArgumentException("Malformed account xpub/tpub") }
            val expectedPrefix = if (path.isTestnet) DeterministicWallet.tpub else DeterministicWallet.xpub
            require(prefix == expectedPrefix) { "Account public key does not match the selected network" }
            require(decoded.depth == 3) { "Account public key must be at depth three" }
            val expectedChild = DeterministicWallet.hardened(path.accountNumber.toLong())
            require(decoded.path.lastChildNumber == expectedChild) { "Account public key does not match the account path" }
        }

        private fun validateEquivalentPublicKeys(standard: String, slip132: String) {
            val (_, standardKey) = DeterministicWallet.ExtendedPublicKey.decode(standard)
            val (_, slipKey) = runCatching { DeterministicWallet.ExtendedPublicKey.decode(slip132) }
                .getOrElse { throw IllegalArgumentException("Malformed SLIP-132 public key") }
            require(standardKey == slipKey) { "Public key encodings do not identify the same account" }
        }

        private fun validateDescriptor(
            descriptor: String,
            fingerprint: String,
            path: AccountPath,
            standardXpub: String
        ) {
            val origin = "[${fingerprint.lowercase()}/${path.normalized.removePrefix("m/")}]"
            require(descriptor.contains(origin)) { "Descriptor origin does not match the active wallet" }
            require(descriptor.contains(standardXpub)) { "Descriptor does not contain the normalized account xpub" }
            require(descriptor.contains("/<0;1>/*")) { "Descriptor is not a receive/change multipath descriptor" }
            require(Regex("#[a-z0-9]{8}$").containsMatchIn(descriptor)) { "Descriptor checksum is missing" }
        }


        /**
         * Builds the Coldcard "Generic JSON" export for one account, the payload Sparrow reads
         * under Airgapped Hardware Wallet → Coldcard.
         *
         * [fingerprint] is the wallet master fingerprint and appears at the top level, which is
         * where coordinators take the keystore origin from. [accountFingerprint] is the account
         * node's *own* fingerprint and appears inside the section, mirroring Coldcard, which
         * derives it per node — a section value that merely repeated the master would carry no
         * information. The origin of record stays the top-level value and the descriptor.
         *
         * Coldcard also emits a top-level `xpub` holding the master key at `m`. MetroVault omits
         * it deliberately: it would let a recipient derive every other account and script type,
         * far beyond the single account being exported, and no coordinator needs it to import.
         */
        private fun buildColdcardJson(
            fingerprint: String,
            accountFingerprint: String,
            path: AccountPath,
            standardXpub: String,
            slip132Xpub: String,
            descriptor: String,
            firstAddress: String
        ): String {
            val section = "bip${path.purpose}"
            val fields = mutableListOf(
                "    \"name\": \"${jsonEscape(sectionName(path.scriptType))}\"",
                "    \"xfp\": \"${jsonEscape(accountFingerprint)}\"",
                "    \"deriv\": \"${jsonEscape(path.apostropheForm)}\"",
                "    \"xpub\": \"${jsonEscape(standardXpub)}\"",
                "    \"desc\": \"${jsonEscape(descriptor)}\""
            )
            if (slip132Xpub != standardXpub) {
                fields += "    \"_pub\": \"${jsonEscape(slip132Xpub)}\""
            }
            fields += "    \"first\": \"${jsonEscape(firstAddress)}\""
            return buildString {
                appendLine("{")
                // XTN is the Coldcard format's only testnet code. These keys are testnet4, which
                // is indistinguishable from testnet3 here (coin type 1, tpub, tb1), so XTN holds.
                appendLine("  \"chain\": \"${if (path.isTestnet) "XTN" else "BTC"}\",")
                appendLine("  \"xfp\": \"$fingerprint\",")
                appendLine("  \"account\": ${path.accountNumber},")
                appendLine("  \"$section\": {")
                appendLine(fields.joinToString(",\n"))
                appendLine("  }")
                append("}")
            }
        }

        private fun sectionName(scriptType: ScriptType): String = when (scriptType) {
            ScriptType.P2PKH -> "p2pkh"
            ScriptType.P2SH_P2WPKH -> "p2sh-p2wpkh"
            ScriptType.P2WPKH -> "p2wpkh"
            ScriptType.P2TR -> "p2tr"
        }

        private fun addressTypeName(scriptType: ScriptType): String = when (scriptType) {
            ScriptType.P2PKH -> "Legacy (BIP44)"
            ScriptType.P2SH_P2WPKH -> "Nested SegWit (BIP49)"
            ScriptType.P2WPKH -> "Native SegWit (BIP84)"
            ScriptType.P2TR -> "Taproot (BIP86)"
        }

        /**
         * Escapes a JSON string body. Deliberately *not* [org.json.JSONObject.quote]: that also
         * escapes `/` as `\/`, which is legal JSON but is not what Coldcard emits, inflates the
         * QR payload, and trips consumers that string-match instead of parsing. Derivation paths
         * and descriptors are almost all slashes, so the difference is not academic.
         */
        private fun jsonEscape(value: String): String = buildString(value.length) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
        }
    }
}
