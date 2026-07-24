package com.gorunjinian.metrovault.domain.service.multisig

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.core.util.Bip48MultisigPrefixes
import com.gorunjinian.metrovault.core.util.NetworkUtils
import com.gorunjinian.metrovault.data.model.MultisigScriptType
import com.gorunjinian.metrovault.data.model.Result
import com.gorunjinian.metrovault.lib.bitcoin.Base58Check
import com.gorunjinian.metrovault.lib.bitcoin.Crypto
import com.gorunjinian.metrovault.lib.bitcoin.Descriptor
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet

/**
 * Parser for the ColdCard multisig setup file format.
 *
 * This is the de-facto text format for exchanging multisig configurations between
 * hardware signers and coordinators. It is emitted (with minor header variations) by
 * ColdCard, Passport, Blockstream Jade, Keystone, Nunchuk, Sparrow ("Export ColdCard"),
 * and Bitcoin Safe ("Passport/ColdCard legacy" exports).
 *
 * Although the ecosystem calls it a "setup file", MetroVault never reads files: this
 * content arrives exclusively via QR (plain text, animated UR:BYTES, or BBQr), already
 * unwrapped to text by the QR transport layer before it reaches this parser.
 *
 * Example:
 * ```
 * # Coldcard Multisig setup file (created by Sparrow)
 * #
 * Name: My Wallet
 * Policy: 2 of 3
 * Format: P2WSH
 *
 * Derivation: m/48'/0'/0'/2'
 * 748CC6AA: xpub6F...
 * Derivation: m/48'/0'/0'/2'
 * C2202A77: xpub6E...
 * Derivation: m/48'/0'/0'/2'
 * 0F056943: xpub6D...
 * ```
 *
 * Parsing follows ColdCard firmware semantics:
 * - Lines are `key: value`; keys are case-insensitive; unknown keys are ignored.
 * - `#` comment lines and blank lines are skipped.
 * - A `Derivation:` line applies to all subsequent xpub lines until the next one
 *   (older exports have a single global `Derivation:` line before the key list).
 * - A key of exactly 8 hex characters denotes a cosigner: `fingerprint: xpub`.
 * - `Format:` is authoritative for the script type; without it the type is inferred
 *   from SLIP-132 xpub prefixes, defaulting to P2SH like the ColdCard firmware.
 *
 * SLIP-132 xpubs (Zpub/Ypub/Vpub/Upub and single-sig zpub/ypub/vpub/upub) are accepted
 * and normalized to canonical xpub/tpub encoding.
 */
object ColdCardSetupFile {

    private const val TAG = "ColdCardSetupFile"

    /** ColdCard's own signer limit; also the P2SH standardness limit. */
    private const val MAX_COSIGNERS = 15

    private val POLICY_PATTERN = Regex("""^(\d+)\s*of\s*(\d+)$""", RegexOption.IGNORE_CASE)
    private val FINGERPRINT_PATTERN = Regex("""^[0-9a-fA-F]{8}$""")
    private val DERIVATION_PATTERN = Regex("""^([mM]/)?\d+['hH]?(/\d+['hH]?)*$""")

    /**
     * A single cosigner entry from the setup file.
     *
     * @property fingerprint Master fingerprint, normalized to lowercase hex
     * @property derivationPath Derivation path without the `m/` prefix (may be empty if the
     *   file carried no `Derivation:` line)
     * @property xpub Extended public key, normalized to canonical xpub/tpub encoding
     */
    data class SetupFileKey(
        val fingerprint: String,
        val derivationPath: String,
        val xpub: String
    )

    /**
     * Fully parsed and validated setup file.
     */
    data class ParsedSetupFile(
        val name: String?,
        val threshold: Int,
        val scriptType: MultisigScriptType,
        val keys: List<SetupFileKey>,
        val isTestnet: Boolean
    )

    /**
     * Cheap detection: the format is the only multisig input with a `Policy:` line.
     * Descriptors and BSMS records can never contain one. The value is deliberately
     * not validated here so a malformed policy still routes to this parser and gets
     * a specific error message instead of a generic descriptor error.
     */
    fun isSetupFile(input: String): Boolean {
        return contentLines(input).any { line ->
            val (key, _) = splitKeyValue(line) ?: return@any false
            key.equals("policy", ignoreCase = true)
        }
    }

    /**
     * Parse a setup file into a validated [ParsedSetupFile].
     */
    fun parse(input: String): Result<ParsedSetupFile, String> {
        var name: String? = null
        var threshold: Int? = null
        var totalKeys: Int? = null
        var explicitFormat: MultisigScriptType? = null
        var currentDerivation = ""
        val keys = mutableListOf<SetupFileKey>()
        val slip132Types = mutableListOf<MultisigScriptType>()
        val networks = mutableSetOf<Boolean>()

        for (line in contentLines(input)) {
            val (key, value) = splitKeyValue(line) ?: continue

            when {
                key.equals("name", ignoreCase = true) -> {
                    name = value
                }

                key.equals("policy", ignoreCase = true) -> {
                    val match = POLICY_PATTERN.matchEntire(value)
                        ?: return Result.Error("Invalid Policy line: \"$value\" (expected \"M of N\")")
                    val m = match.groupValues[1].toIntOrNull()
                    val n = match.groupValues[2].toIntOrNull()
                    if (m == null || n == null) {
                        return Result.Error("Invalid Policy line: \"$value\"")
                    }
                    if (threshold != null && (threshold != m || totalKeys != n)) {
                        return Result.Error("Conflicting Policy lines in setup file")
                    }
                    threshold = m
                    totalKeys = n
                }

                key.equals("derivation", ignoreCase = true) -> {
                    if (!DERIVATION_PATTERN.matches(value)) {
                        return Result.Error("Invalid derivation path: \"$value\"")
                    }
                    currentDerivation = value.removePrefix("m/").removePrefix("M/")
                }

                key.equals("format", ignoreCase = true) -> {
                    explicitFormat = parseFormat(value)
                        ?: return Result.Error("Unsupported Format: \"$value\" (expected P2WSH, P2SH-P2WSH, or P2SH)")
                }

                FINGERPRINT_PATTERN.matches(key) -> {
                    val validated = validateAndNormalizeXpub(value)
                        ?: return Result.Error("Invalid xpub for cosigner $key")
                    networks.add(validated.isTestnet)
                    validated.slip132ScriptType?.let { slip132Types.add(it) }
                    keys.add(
                        SetupFileKey(
                            fingerprint = key.lowercase(),
                            derivationPath = currentDerivation,
                            xpub = validated.canonical
                        )
                    )
                }

                // Unknown keys are ignored for forward compatibility (firmware behavior).
            }
        }

        // ==================== Validation ====================

        val m = threshold ?: return Result.Error("Setup file is missing a Policy line")
        val n = totalKeys ?: return Result.Error("Setup file is missing a Policy line")

        if (keys.isEmpty()) {
            return Result.Error("Setup file contains no cosigner keys")
        }
        if (keys.size != n) {
            return Result.Error("Policy says $n cosigners but file contains ${keys.size} xpub(s)")
        }
        if (m < 1) {
            return Result.Error("Threshold must be at least 1")
        }
        if (m > n) {
            return Result.Error("Threshold ($m) cannot be greater than number of keys ($n)")
        }
        if (n > MAX_COSIGNERS) {
            return Result.Error("Too many cosigners ($n, maximum is $MAX_COSIGNERS)")
        }
        if (keys.map { it.xpub }.toSet().size != keys.size) {
            return Result.Error("Setup file contains duplicate xpubs")
        }
        if (networks.size > 1) {
            return Result.Error("Setup file mixes mainnet and testnet keys")
        }

        val distinctSlipTypes = slip132Types.distinct()
        if (explicitFormat == null && distinctSlipTypes.size > 1) {
            return Result.Error("Cosigner keys use conflicting SLIP-132 prefixes and no Format line is present")
        }

        // Format line is authoritative; otherwise infer from SLIP-132 prefixes,
        // defaulting to P2SH like the ColdCard firmware.
        val scriptType = explicitFormat
            ?: distinctSlipTypes.firstOrNull()
            ?: MultisigScriptType.P2SH

        AppLog.d(TAG) { "Parsed setup file: $m of $n, script=$scriptType, name=${name != null}" }

        return Result.Success(
            ParsedSetupFile(
                name = name?.takeIf { it.isNotBlank() },
                threshold = m,
                scriptType = scriptType,
                keys = keys,
                isTestnet = networks.first()
            )
        )
    }

    /**
     * Synthesize a canonical multipath output descriptor from a parsed setup file,
     * so the rest of the app (storage, export, address generation) works off the same
     * representation as descriptor-based imports.
     */
    fun toDescriptor(parsed: ParsedSetupFile): String {
        val keyList = parsed.keys.joinToString(",") { key ->
            val origin = if (key.derivationPath.isEmpty()) {
                "[${key.fingerprint}]"
            } else {
                "[${key.fingerprint}/${key.derivationPath}]"
            }
            "$origin${key.xpub}/<0;1>/*"
        }
        val inner = "sortedmulti(${parsed.threshold},$keyList)"
        val descriptor = when (parsed.scriptType) {
            MultisigScriptType.P2WSH -> "wsh($inner)"
            MultisigScriptType.P2SH_P2WSH -> "sh(wsh($inner))"
            MultisigScriptType.P2SH -> "sh($inner)"
        }
        return "$descriptor#${Descriptor.checksum(descriptor)}"
    }

    // ==================== Private Helpers ====================

    /** Non-empty, non-comment lines, trimmed. Handles LF and CRLF. */
    private fun contentLines(input: String): List<String> =
        input.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Split a line at the first colon into (key, value), or null if it has no colon. */
    private fun splitKeyValue(line: String): Pair<String, String>? {
        val idx = line.indexOf(':')
        if (idx <= 0) return null
        return line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }

    private fun parseFormat(value: String): MultisigScriptType? {
        return when (value.uppercase().replace("_", "-").replace(" ", "")) {
            "P2WSH" -> MultisigScriptType.P2WSH
            "P2SH-P2WSH", "P2WSH-P2SH" -> MultisigScriptType.P2SH_P2WSH
            "P2SH" -> MultisigScriptType.P2SH
            else -> null
        }
    }

    private data class ValidatedXpub(
        val canonical: String,
        val isTestnet: Boolean,
        val slip132ScriptType: MultisigScriptType?
    )

    /**
     * Decode and validate an extended public key, normalizing SLIP-132 variants
     * (Zpub/Ypub/Vpub/Upub/zpub/ypub/vpub/upub) to canonical xpub/tpub encoding.
     * Returns null if the string is not a valid extended public key.
     */
    private fun validateAndNormalizeXpub(raw: String): ValidatedXpub? {
        return try {
            val (prefix, payload) = Base58Check.decodeWithIntPrefix(raw.trim())
            if (!Bip48MultisigPrefixes.isValidXpubPrefix(prefix)) {
                AppLog.e(TAG) { "Rejected extended key with unknown or private prefix" }
                return null
            }
            // depth (1) + parent (4) + childNumber (4) + chaincode (32) + publicKey (33)
            if (payload.size != 74) {
                AppLog.e(TAG) { "Extended key payload has wrong length: ${payload.size}" }
                return null
            }
            val publicKeyBytes = payload.copyOfRange(41, 74)
            if (!Crypto.isPubKeyValid(publicKeyBytes)) {
                AppLog.e(TAG) { "Extended key contains an invalid public key" }
                return null
            }

            val isTestnet = NetworkUtils.isTestnetPrefix(prefix)
            val slip132ScriptType = when (prefix) {
                Bip48MultisigPrefixes.Zpub, Bip48MultisigPrefixes.Vpub,
                DeterministicWallet.zpub, DeterministicWallet.vpub -> MultisigScriptType.P2WSH
                Bip48MultisigPrefixes.Ypub, Bip48MultisigPrefixes.Upub,
                DeterministicWallet.ypub, DeterministicWallet.upub -> MultisigScriptType.P2SH_P2WSH
                else -> null
            }
            val canonicalPrefix = if (isTestnet) DeterministicWallet.tpub else DeterministicWallet.xpub
            ValidatedXpub(
                canonical = Base58Check.encode(canonicalPrefix, payload),
                isTestnet = isTestnet,
                slip132ScriptType = slip132ScriptType
            )
        } catch (e: Exception) {
            AppLog.e(TAG) { "Failed to decode extended key: ${e.message}" }
            null
        }
    }
}
