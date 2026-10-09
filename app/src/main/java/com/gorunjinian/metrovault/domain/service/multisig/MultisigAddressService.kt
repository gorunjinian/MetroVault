package com.gorunjinian.metrovault.domain.service.multisig

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.data.model.BitcoinAddress
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.MultisigScriptType
import com.gorunjinian.metrovault.data.model.Result
import com.gorunjinian.vaultovich.ScriptType
import com.gorunjinian.vaultovich.*
import com.gorunjinian.vaultovich.MultisigScriptType as VaultovichMultisigScriptType
import com.gorunjinian.vaultovich.utils.Either
import com.gorunjinian.metrovault.domain.service.bitcoin.AddressCheckResult
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
import com.gorunjinian.metrovault.domain.service.util.WalletConstants

/**
 * Service for generating and verifying multisig addresses.
 *
 * Generates P2WSH, P2SH-P2WSH and P2SH addresses from multisig configurations, for both
 * `sortedmulti` (keys sorted lexicographically) and `multi` (keys in descriptor order) wallets.
 */
class MultisigAddressService {

    companion object {
        private const val TAG = "MultisigAddressService"
    }

    /**
     * Result of address generation.
     * Returns Result<BitcoinAddress, String> where success carries the generated address and failure carries error message.
     */

    /**
     * Generates a multisig address at the specified index.
     *
     * @param config The multisig configuration with all cosigner xpubs
     * @param index Address index
     * @param isChange Whether this is a change address (1) or receive address (0)
     * @param isTestnet Whether to generate testnet addresses
     * @return Result<BitcoinAddress, String> with the generated address or error
     */
    fun generateMultisigAddress(
        config: MultisigConfig,
        index: Int,
        isChange: Boolean,
        isTestnet: Boolean
    ): Result<BitcoinAddress, String> {
        return try {
            val chainHash = BitcoinUtils.getChainHash(isTestnet)
            val changeIndex = if (isChange) 1L else 0L

            // Derive child public keys from each cosigner's xpub
            // Track failures for better error reporting
            val failedCosigners = mutableListOf<String>()
            val childPubKeys = config.cosigners.mapIndexedNotNull { i, cosigner ->
                deriveChildPublicKey(cosigner.xpub, changeIndex, index.toLong())
                    ?: run {
                        failedCosigners.add("cosigner ${i + 1} (fingerprint: ${cosigner.fingerprint})")
                        null
                    }
            }

            if (childPubKeys.size != config.n) {
                val failedList = failedCosigners.joinToString(", ")
                return Result.Error(
                    "Failed to derive keys from $failedList (got ${childPubKeys.size}/${config.n} keys)"
                )
            }

            val encoded = Bitcoin.addressFromPublicKeyScript(chainHash, scriptPubKey(config, childPubKeys))
            val address = when (encoded) {
                is Either.Right -> encoded.value
                is Either.Left -> return Result.Error("Failed to compute multisig address: ${encoded.value.message}")
            }

            val scriptType = when (config.scriptType) {
                MultisigScriptType.P2WSH -> ScriptType.P2WPKH // Closest match for display
                MultisigScriptType.P2SH_P2WSH -> ScriptType.P2SH_P2WPKH
                MultisigScriptType.P2SH -> ScriptType.P2PKH
            }

            Result.Success(
                BitcoinAddress(
                    address = address,
                    derivationPath = "multisig/$changeIndex/$index",
                    index = index,
                    isChange = isChange,
                    publicKey = "", // Multisig doesn't have single public key
                    scriptType = scriptType
                )
            )
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to generate multisig address: ${e.message}" }
            Result.Error("Failed to generate address: ${e.message}")
        }
    }

    /**
     * Derives the expected output scriptPubKey for a specific (change, index) from the multisig
     * descriptor. Comparing raw scriptPubKey bytes (rather than encoded addresses) avoids
     * address-encoding edge cases (network prefix, case, bech32 vs bech32m).
     *
     * @return the scriptPubKey bytes, or null if key derivation fails
     */
    fun generateMultisigScriptPubKey(
        config: MultisigConfig,
        index: Int,
        isChange: Boolean
    ): ByteArray? {
        return try {
            val changeIndex = if (isChange) 1L else 0L
            val childPubKeys = config.cosigners.mapNotNull { cosigner ->
                deriveChildPublicKey(cosigner.xpub, changeIndex, index.toLong())
            }
            if (childPubKeys.size != config.n) return null
            Script.write(scriptPubKey(config, childPubKeys))
        } catch (e: Exception) {
            AppLog.e(TAG) { "Failed to derive multisig scriptPubKey: ${e.message}" }
            null
        }
    }

    /**
     * The registered wallet as vaultovich's [MultisigAccount], the input `Psbt.verifyOutput` checks
     * outputs against. Each cosigner key carries the descriptor's key origin as its path; see
     * [accountKey] for why it is not decoded with the library's own decoder.
     *
     * @return null if any cosigner's fingerprint, derivation path or key cannot be read
     */
    fun toMultisigAccount(config: MultisigConfig): MultisigAccount? {
        return try {
            val cosigners = config.cosigners.map { cosigner ->
                val accountPath = KeyPath(cosigner.derivationPath.replace('H', 'h'))
                Cosigner(
                    masterFingerprint = cosigner.fingerprint.toLong(16),
                    accountPath = accountPath,
                    accountKey = accountKey(cosigner.xpub, accountPath) ?: return null
                )
            }
            MultisigAccount(
                threshold = config.m,
                cosigners = cosigners,
                scriptType = when (config.scriptType) {
                    MultisigScriptType.P2WSH -> VaultovichMultisigScriptType.P2WSH
                    MultisigScriptType.P2SH_P2WSH -> VaultovichMultisigScriptType.P2SH_P2WSH
                    MultisigScriptType.P2SH -> VaultovichMultisigScriptType.P2SH
                },
                // Cosigners are in descriptor order, which is the script order for `multi`
                sortedKeys = config.sortedKeys
            )
        } catch (e: Exception) {
            AppLog.e(TAG) { "Failed to build multisig account: ${e.message}" }
            null
        }
    }

    /**
     * Checks if an address belongs to the multisig wallet.
     */
    fun checkAddressBelongsToMultisig(
        address: String,
        config: MultisigConfig,
        isTestnet: Boolean,
        scanRange: Int = WalletConstants.MULTISIG_ADDRESS_GAP
    ): AddressCheckResult {
        // Check receive addresses
        for (i in 0 until scanRange) {
            val result = generateMultisigAddress(config, i, isChange = false, isTestnet)
            if (result is Result.Success<*, *> && (result.value as BitcoinAddress).address.equals(address, ignoreCase = true)) {
                val bitcoinAddress = result.value
                return AddressCheckResult(true, bitcoinAddress.derivationPath, i, false)
            }
        }

        // Check change addresses
        for (i in 0 until scanRange) {
            val result = generateMultisigAddress(config, i, isChange = true, isTestnet)
            if (result is Result.Success<*, *> && (result.value as BitcoinAddress).address.equals(address, ignoreCase = true)) {
                val bitcoinAddress = result.value
                return AddressCheckResult(true, bitcoinAddress.derivationPath, i, true)
            }
        }

        return AddressCheckResult(false, null, null, null)
    }

    // ==================== Private Helpers ====================

    /**
     * Derives `xpub/changeIndex/addressIndex` with vaultovich's BIP-32 public derivation.
     * The xpub should already be at the account level (e.g., m/48'/0'/0'/2').
     */
    private fun deriveChildPublicKey(xpubString: String, changeIndex: Long, addressIndex: Long): PublicKey? {
        val accountKey = accountKey(xpubString) ?: return null
        return try {
            accountKey.derivePublicKey(listOf(changeIndex, addressIndex)).publicKey
        } catch (e: Exception) {
            // A hardened index, or an IL outside the curve order (probability below 2^-127)
            AppLog.e(TAG) { "Failed to derive child key from xpub: ${e.message}" }
            null
        }
    }

    /**
     * A cosigner's account key, rebuilt from the public key and chain code in [xpubString], the
     * only fields BIP-32 derivation reads. Depth and path come from [path], not from the serialized
     * key: descriptor xpubs do not always carry consistent depth bytes, and
     * `ExtendedPublicKey.decode` rejects those over metadata that cannot change a derived key.
     *
     * @return null if [xpubString] is not a 78-byte extended public key holding a valid point
     */
    private fun accountKey(
        xpubString: String,
        path: KeyPath = KeyPath.empty
    ): DeterministicWallet.ExtendedPublicKey? {
        return try {
            val (prefix, bin) = Base58Check.decodeWithIntPrefix(xpubString.trim())
            // Public prefixes only, single-sig (lowercase) and BIP48 multisig (uppercase) alike
            if (prefix !in DeterministicWallet.mainnetPublicPrefixes &&
                prefix !in DeterministicWallet.testnetPublicPrefixes
            ) {
                AppLog.e(TAG) { "Invalid xpub prefix" }
                return null
            }

            // depth (1) + parent (4) + childNumber (4) + chaincode (32) + publicKey (33) = 74 bytes.
            // Exact, as vaultovich's decoder requires: extra bytes are not silently ignored.
            if (bin.size != 74) {
                AppLog.e(TAG) { "xpub data has wrong length" }
                return null
            }

            // The constructor rejects a public key that is not a point on the curve
            DeterministicWallet.ExtendedPublicKey(
                publickeybytes = bin.copyOfRange(41, 74).byteVector(),
                chaincode = bin.copyOfRange(9, 41).byteVector32(),
                depth = path.path.size,
                path = path,
                parent = 0L
            )
        } catch (e: Exception) {
            AppLog.e(TAG) { "Failed to decode xpub: ${e.message}" }
            null
        }
    }

    /**
     * The wallet's output script for one set of derived cosigner keys:
     * `OP_m <pubkey1> … <pubkeyN> OP_n OP_CHECKMULTISIG`, wrapped for the script type.
     * [childPubKeys] must be in cosigner (descriptor) order: `sortedmulti` sorts them
     * lexicographically (BIP-67), `multi` keeps that order. [toMultisigAccount] follows the same rule.
     */
    private fun scriptPubKey(config: MultisigConfig, childPubKeys: List<PublicKey>): List<ScriptElt> {
        val keys = if (config.sortedKeys) childPubKeys.sortedBy { it.toHex() } else childPubKeys
        val multisig = Script.createMultiSigMofN(config.m, keys)
        return when (config.scriptType) {
            MultisigScriptType.P2WSH -> Script.pay2wsh(multisig)
            MultisigScriptType.P2SH_P2WSH -> Script.pay2sh(Script.pay2wsh(multisig))
            MultisigScriptType.P2SH -> Script.pay2sh(multisig)
        }
    }
}