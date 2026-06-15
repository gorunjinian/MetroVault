package com.gorunjinian.metrovault.domain.service.psbt

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.domain.service.bitcoin.AddressService
import com.gorunjinian.metrovault.domain.service.util.WalletConstants
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.Input
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey
import com.gorunjinian.metrovault.lib.bitcoin.XonlyPublicKey

/**
 * Resolves the signing private key for a PSBT input, independent of the act of signing.
 *
 * This is the per-input key-derivation logic extracted from [PsbtSigner] so it can be shared by
 * the silent-payment services (the sender must sum the spent input private keys; the receive-side
 * signer derives `b_spend + tweak`). [PsbtSigner] still owns the orchestration, logging, and the
 * actual `Psbt.sign` calls — this object only answers "which private key signs this input?".
 *
 * Resolution mirrors PsbtSigner's two stages:
 *  - [resolveFromDerivationPaths]: BIP-174 `PSBT_IN_BIP32_DERIVATION` metadata, with the same
 *    path-agnostic fallback across standard purposes / accounts that coordinators may have rewritten.
 *  - [resolveFromAddressLookup]: scriptPubKey match against the wallet's own derived addresses.
 */
internal object PsbtKeyResolver {
    private const val TAG = "PsbtKeyResolver"

    // Standard derivation path purposes to try when the fingerprint matches but the pubkey doesn't.
    private val STANDARD_SINGLE_SIG_PURPOSES = listOf(84, 44, 49, 86) // P2WPKH, P2PKH, P2SH-P2WPKH, P2TR
    private val BIP48_SCRIPT_TYPES = listOf(2, 1)  // P2WSH (2'), P2SH-P2WSH (1')

    private val addressService = AddressService()

    /**
     * A private key resolved for a given PSBT input, the public key it derives to, and — when the
     * match was found at a non-declared standard path — the alternative path string used.
     */
    data class ResolvedKey(
        val privateKey: PrivateKey,
        val publicKey: PublicKey,
        val alternativePath: String?,
    )

    /**
     * Resolve a signing key from the input's BIP-174 derivation metadata (regular and taproot),
     * with the path-agnostic fallback. Returns null if no key whose fingerprint and derived public
     * key match the input could be found.
     */
    fun resolveFromDerivationPaths(
        input: Input,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        walletFingerprint: Long,
        isTestnet: Boolean,
    ): ResolvedKey? {
        return try {
            for ((publicKey, keyPathWithMaster) in input.derivationPaths) {
                if (keyPathWithMaster.masterKeyFingerprint == walletFingerprint) {
                    val signingPrivateKey = masterPrivateKey
                        .derivePrivateKey(keyPathWithMaster.keyPath)
                        .privateKey

                    if (signingPrivateKey.publicKey() == publicKey) {
                        return ResolvedKey(signingPrivateKey, publicKey, null)
                    }

                    // Fingerprint matches but pubkey doesn't — try alternative standard paths.
                    val altResult = tryAlternativePaths(masterPrivateKey, publicKey, keyPathWithMaster.keyPath, isTestnet)
                    if (altResult != null) {
                        val (altSigningKey, altPath) = altResult
                        return ResolvedKey(altSigningKey, publicKey, altPath)
                    }
                }
            }

            for ((xOnlyPublicKey, taprootPath) in input.taprootDerivationPaths) {
                if (taprootPath.masterKeyFingerprint == walletFingerprint) {
                    val signingPrivateKey = masterPrivateKey
                        .derivePrivateKey(taprootPath.keyPath)
                        .privateKey

                    if (XonlyPublicKey(signingPrivateKey.publicKey()) == xOnlyPublicKey) {
                        return ResolvedKey(signingPrivateKey, signingPrivateKey.publicKey(), null)
                    }

                    val altResult = tryAlternativePaths(masterPrivateKey, signingPrivateKey.publicKey(), taprootPath.keyPath, isTestnet)
                    if (altResult != null) {
                        val (altSigningKey, altPath) = altResult
                        if (XonlyPublicKey(altSigningKey.publicKey()) == xOnlyPublicKey) {
                            return ResolvedKey(altSigningKey, altSigningKey.publicKey(), altPath)
                        }
                    }
                }
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolve a signing key by matching the input's scriptPubKey against the wallet's own derived
     * addresses. Returns the resolved key together with the [AddressKeyInfo] so the caller can write
     * the derivation provenance back into the input (BIP-174 §4.1.1) before signing.
     */
    fun resolveFromAddressLookup(
        input: Input,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        addressLookup: Map<ByteVector, AddressKeyInfo>,
    ): Pair<ResolvedKey, AddressKeyInfo>? {
        val scriptPubKey = PsbtUtils.getInputScriptPubKey(input) ?: return null
        val keyInfo = addressLookup[scriptPubKey] ?: return null
        val signingPrivateKey = accountPrivateKey
            .derivePrivateKey(keyInfo.changeIndex)
            .derivePrivateKey(keyInfo.addressIndex)
            .privateKey
        return Pair(ResolvedKey(signingPrivateKey, keyInfo.publicKey, null), keyInfo)
    }

    /**
     * Builds an address → (key info) lookup map for fallback resolution, covering both the receive
     * and change branches up to the standard single-sig gap limit.
     */
    fun buildAddressLookup(
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType,
    ): Map<ByteVector, AddressKeyInfo> {
        val lookup = mutableMapOf<ByteVector, AddressKeyInfo>()
        val accountPublicKey = accountPrivateKey.extendedPublicKey

        for (isChange in listOf(false, true)) {
            val changeIndex = if (isChange) 1L else 0L

            for (i in 0 until WalletConstants.SINGLE_SIG_ADDRESS_GAP) {
                try {
                    val addressKey = accountPublicKey
                        .derivePublicKey(changeIndex)
                        .derivePublicKey(i.toLong())

                    val publicKey = addressKey.publicKey
                    val scriptPubKey = addressService.createScriptPubKey(publicKey, scriptType)

                    if (scriptPubKey != null) {
                        lookup[scriptPubKey] = AddressKeyInfo(changeIndex, i.toLong(), publicKey)
                    }
                } catch (_: Exception) {
                    // Skip addresses that fail to derive
                }
            }
        }

        return lookup
    }

    /**
     * Tries alternative standard derivation paths to find a matching public key. Handles cases where
     * coordinator wallets convert paths (e.g., m/84' -> m/48' for multisig) and normalize the account
     * number, by sweeping common purposes / accounts while keeping the original change/index suffix.
     *
     * @return Pair of (signing private key, path string) if found, null otherwise.
     */
    fun tryAlternativePaths(
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        expectedPublicKey: PublicKey,
        originalPath: KeyPath,
        isTestnet: Boolean,
    ): Pair<PrivateKey, String>? {
        val pathList = originalPath.path
        if (pathList.size < 3) return null  // Need at least purpose/coin/account

        val coinType = if (isTestnet) DeterministicWallet.hardened(1) else DeterministicWallet.hardened(0)

        // Get the child path (change/index portion after the account level).
        val childStartIndex = when {
            pathList.size >= 5 && DeterministicWallet.isHardened(pathList.getOrNull(3) ?: 0L) -> 4  // BIP48 with script type
            pathList.size >= 4 -> 3  // Standard paths
            else -> return null
        }
        val childPath = if (childStartIndex < pathList.size) pathList.subList(childStartIndex, pathList.size) else emptyList()

        val originalAccount = pathList.getOrNull(2) ?: return null
        val psbtAccountNum = if (DeterministicWallet.isHardened(originalAccount)) {
            originalAccount - DeterministicWallet.hardenedKeyIndex
        } else {
            originalAccount
        }

        // Account numbers to try - start with the PSBT account, then common accounts 0-9.
        val accountsToTry = (listOf(psbtAccountNum) + (0L..9L)).distinct()

        // Single-sig paths first (most common: coordinator converted m/84 to m/48).
        for (accountNum in accountsToTry) {
            for (purpose in STANDARD_SINGLE_SIG_PURPOSES) {
                try {
                    val altPath = KeyPath(
                        listOf(
                            DeterministicWallet.hardened(purpose.toLong()),
                            coinType,
                            DeterministicWallet.hardened(accountNum)
                        ) + childPath
                    )
                    val derivedKey = masterPrivateKey.derivePrivateKey(altPath)
                    if (derivedKey.publicKey == expectedPublicKey) {
                        return Pair(derivedKey.privateKey, altPath.toString())
                    }
                } catch (_: Exception) {
                    // Silent - just try next
                }
            }
        }

        // BIP48 multisig paths.
        for (accountNum in accountsToTry) {
            for (scriptType in BIP48_SCRIPT_TYPES) {
                try {
                    val altPath = KeyPath(
                        listOf(
                            DeterministicWallet.hardened(48),
                            coinType,
                            DeterministicWallet.hardened(accountNum),
                            DeterministicWallet.hardened(scriptType.toLong())
                        ) + childPath
                    )
                    val derivedKey = masterPrivateKey.derivePrivateKey(altPath)
                    if (derivedKey.publicKey == expectedPublicKey) {
                        return Pair(derivedKey.privateKey, altPath.toString())
                    }
                } catch (_: Exception) {
                    // Silent - just try next
                }
            }
        }

        AppLog.w(TAG) { "No alternative path matched after trying ${accountsToTry.size} accounts" }
        return null
    }
}
