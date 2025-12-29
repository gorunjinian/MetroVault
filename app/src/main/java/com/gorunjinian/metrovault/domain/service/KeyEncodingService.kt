package com.gorunjinian.metrovault.domain.service

import android.util.Log
import com.gorunjinian.metrovault.lib.bitcoin.Descriptor
import com.gorunjinian.metrovault.lib.bitcoin.DescriptorExtensions
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.ScriptType

/**
 * Service responsible for encoding extended keys and generating descriptors.
 * Handles xpub/xpriv encoding with proper prefixes and descriptor generation.
 */
class KeyEncodingService {

    companion object {
        private const val TAG = "KeyEncodingService"
    }

    /**
     * Gets the account extended public key encoded with appropriate prefix.
     * @param isTestnet Whether to use testnet prefixes (tpub/upub/vpub)
     */
    fun getAccountXpub(
        accountPublicKey: DeterministicWallet.ExtendedPublicKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false
    ): String {
        val prefix = getPublicKeyPrefix(scriptType, isTestnet)
        return accountPublicKey.encode(prefix)
    }

    /**
     * Gets the account extended private key encoded with appropriate prefix.
     * @param isTestnet Whether to use testnet prefixes (tprv/uprv/vprv)
     */
    fun getAccountXpriv(
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false
    ): String {
        val prefix = getPrivateKeyPrefix(scriptType, isTestnet)
        return accountPrivateKey.encode(prefix)
    }

    /**
     * Gets the wallet descriptors using the Descriptor library.
     *
     * @param isTestnet Whether to use testnet chain hash
     * @return Pair of (receive descriptor, change descriptor) with checksums, or null on failure
     */
    fun getWalletDescriptors(
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        isTestnet: Boolean = false
    ): Pair<String, String>? {
        return try {
            val chainHash = BitcoinUtils.getChainHash(isTestnet)
            Descriptor.BIP84Descriptors(chainHash, masterPrivateKey)
        } catch (_: Exception) {
            Log.e(TAG, "Failed to generate wallet descriptors")
            null
        }
    }

    /**
     * Gets a unified output descriptor for the wallet with multipath syntax.
     * Compatible with Sparrow, Bitcoin Core, and other modern wallets.
     * 
     * @param fingerprint Master fingerprint as hex string
     * @param accountPath Account derivation path (e.g., "m/84'/0'/0'")
     * @param accountPublicKey Account-level extended public key
     * @param scriptType Script type for the wallet
     * @param isTestnet Whether to use testnet key prefixes
     * @return Unified descriptor string with checksum
     */
    fun getWalletDescriptor(
        fingerprint: String,
        accountPath: String,
        accountPublicKey: DeterministicWallet.ExtendedPublicKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false
    ): String {
        val fp = fingerprint.toLongOrNull(16) ?: 0L
        val xpub = getAccountXpub(accountPublicKey, scriptType, isTestnet)
        return DescriptorExtensions.getUnifiedDescriptor(fp, accountPath, xpub, scriptType)
    }

    /**
     * Gets a private (spending) unified output descriptor for the wallet.
     * WARNING: Contains private keys - handle with extreme care!
     * 
     * @param fingerprint Master fingerprint as hex string
     * @param accountPath Account derivation path (e.g., "m/84'/0'/0'")
     * @param accountPrivateKey Account-level extended private key
     * @param scriptType Script type for the wallet
     * @param isTestnet Whether to use testnet key prefixes
     * @return Private descriptor string with checksum
     */
    fun getPrivateWalletDescriptor(
        fingerprint: String,
        accountPath: String,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false
    ): String {
        val fp = fingerprint.toLongOrNull(16) ?: 0L
        val xpriv = getAccountXpriv(accountPrivateKey, scriptType, isTestnet)
        return DescriptorExtensions.getUnifiedDescriptor(fp, accountPath, xpriv, scriptType)
    }

    // ==================== BIP48 Multisig Key Encoding ====================

    /**
     * Gets the BIP48 extended public key encoded with Zpub/Vpub/Ypub/Upub prefix.
     *
     * @param accountPublicKey Extended public key at BIP48 path (m/48'/coin'/account'/script_type')
     * @param bip48ScriptType BIP48 script type (P2WSH or P2SH-P2WSH)
     * @param isTestnet Whether to use testnet prefixes
     * @return Zpub/Vpub (P2WSH) or Ypub/Upub (P2SH-P2WSH) encoded string
     */
    fun getBip48Xpub(
        accountPublicKey: DeterministicWallet.ExtendedPublicKey,
        bip48ScriptType: DerivationPaths.Bip48ScriptType,
        isTestnet: Boolean = false
    ): String {
        val prefix = getBip48PublicKeyPrefix(bip48ScriptType, isTestnet)
        return accountPublicKey.encode(prefix)
    }

    /**
     * Gets the BIP48 extended private key encoded with Zprv/Vprv/Yprv/Uprv prefix.
     *
     * @param accountPrivateKey Extended private key at BIP48 path
     * @param bip48ScriptType BIP48 script type (P2WSH or P2SH-P2WSH)
     * @param isTestnet Whether to use testnet prefixes
     * @return Zprv/Vprv (P2WSH) or Yprv/Uprv (P2SH-P2WSH) encoded string
     */
    fun getBip48Xpriv(
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        bip48ScriptType: DerivationPaths.Bip48ScriptType,
        isTestnet: Boolean = false
    ): String {
        val prefix = getBip48PrivateKeyPrefix(bip48ScriptType, isTestnet)
        return accountPrivateKey.encode(prefix)
    }

    /**
     * Get BIP48 public key prefix based on script type and network.
     */
    private fun getBip48PublicKeyPrefix(bip48ScriptType: DerivationPaths.Bip48ScriptType, isTestnet: Boolean): Int {
        return when (bip48ScriptType) {
            DerivationPaths.Bip48ScriptType.P2WSH -> {
                if (isTestnet) Bip48MultisigPrefixes.Vpub else Bip48MultisigPrefixes.Zpub
            }
            DerivationPaths.Bip48ScriptType.P2SH_P2WSH -> {
                if (isTestnet) Bip48MultisigPrefixes.Upub else Bip48MultisigPrefixes.Ypub
            }
        }
    }

    /**
     * Get BIP48 private key prefix based on script type and network.
     */
    private fun getBip48PrivateKeyPrefix(bip48ScriptType: DerivationPaths.Bip48ScriptType, isTestnet: Boolean): Int {
        return when (bip48ScriptType) {
            DerivationPaths.Bip48ScriptType.P2WSH -> {
                if (isTestnet) Bip48MultisigPrefixes.Vprv else Bip48MultisigPrefixes.Zprv
            }
            DerivationPaths.Bip48ScriptType.P2SH_P2WSH -> {
                if (isTestnet) Bip48MultisigPrefixes.Uprv else Bip48MultisigPrefixes.Yprv
            }
        }
    }

    // ==================== Private Helpers ====================

    private fun getPublicKeyPrefix(scriptType: ScriptType, isTestnet: Boolean): Int {
        return if (isTestnet) {
            when (scriptType) {
                ScriptType.P2PKH -> DeterministicWallet.tpub
                ScriptType.P2SH_P2WPKH -> DeterministicWallet.upub
                ScriptType.P2WPKH -> DeterministicWallet.vpub
                ScriptType.P2TR -> DeterministicWallet.tpub
            }
        } else {
            when (scriptType) {
                ScriptType.P2PKH -> DeterministicWallet.xpub
                ScriptType.P2SH_P2WPKH -> DeterministicWallet.ypub
                ScriptType.P2WPKH -> DeterministicWallet.zpub
                ScriptType.P2TR -> DeterministicWallet.xpub
            }
        }
    }

    private fun getPrivateKeyPrefix(scriptType: ScriptType, isTestnet: Boolean): Int {
        return if (isTestnet) {
            when (scriptType) {
                ScriptType.P2PKH -> DeterministicWallet.tprv
                ScriptType.P2SH_P2WPKH -> DeterministicWallet.uprv
                ScriptType.P2WPKH -> DeterministicWallet.vprv
                ScriptType.P2TR -> DeterministicWallet.tprv
            }
        } else {
            when (scriptType) {
                ScriptType.P2PKH -> DeterministicWallet.xprv
                ScriptType.P2SH_P2WPKH -> DeterministicWallet.yprv
                ScriptType.P2WPKH -> DeterministicWallet.zprv
                ScriptType.P2TR -> DeterministicWallet.xprv
            }
        }
    }
}
