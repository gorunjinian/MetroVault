package com.gorunjinian.metrovault.domain.service

import android.util.Log
import com.gorunjinian.metrovault.lib.bitcoin.*
import com.gorunjinian.metrovault.data.model.BitcoinAddress
import com.gorunjinian.metrovault.data.model.ScriptType

/**
 * Service responsible for Bitcoin address operations.
 * Handles address generation, address verification, and address key retrieval.
 */
class AddressService {

    companion object {
        private const val TAG = "AddressService"
    }

    /**
     * Generates a Bitcoin address from an account public key.
     */
    fun generateAddress(
        accountPublicKey: DeterministicWallet.ExtendedPublicKey,
        index: Int,
        isChange: Boolean,
        scriptType: ScriptType,
        isTestnet: Boolean = false
    ): BitcoinAddress? {
        return try {
            val chainHash = BitcoinUtils.getChainHash(isTestnet)
            val changeIndex = if (isChange) 1L else 0L
            val addressKey = accountPublicKey
                .derivePublicKey(changeIndex)
                .derivePublicKey(index.toLong())

            val publicKey = addressKey.publicKey
            val address = when (scriptType) {
                ScriptType.P2PKH -> Bitcoin.computeP2PkhAddress(publicKey, chainHash)
                ScriptType.P2SH_P2WPKH -> Bitcoin.computeP2ShOfP2WpkhAddress(publicKey, chainHash)
                ScriptType.P2WPKH -> Bitcoin.computeP2WpkhAddress(publicKey, chainHash)
                ScriptType.P2TR -> {
                    val xOnlyPubKey = XonlyPublicKey(publicKey)
                    Bitcoin.computeBIP86Address(xOnlyPubKey, chainHash)
                }
            }

            val fullPath = "${accountPublicKey.path}/$changeIndex/$index"

            BitcoinAddress(
                address = address,
                derivationPath = fullPath,
                index = index,
                isChange = isChange,
                publicKey = publicKey.toHex(),
                scriptType = scriptType
            )
        } catch (_: Exception) {
            Log.e(TAG, "Failed to generate address at index $index")
            null
        }
    }

    /**
     * Data class for address key pair.
     */
    data class AddressKeyPair(
        val publicKey: String,      // Compressed public key (33 bytes hex, starts with 02 or 03)
        val privateKeyWIF: String   // Private key in WIF format (K/L for mainnet, c for testnet)
    )

    /**
     * Gets the public and private key for a specific address.
     * @param accountPrivateKey The wallet's account private key
     * @param index Address index
     * @param isChange Whether this is a change address
     * @param isTestnet Whether this is a testnet wallet (affects WIF encoding)
     * @return AddressKeyPair with public key (hex) and private key (WIF), or null on error
     */
    fun getAddressKeys(
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        index: Int,
        isChange: Boolean,
        isTestnet: Boolean = false
    ): AddressKeyPair? {
        return try {
            val changeIndex = if (isChange) 1L else 0L
            val addressPrivateKey = accountPrivateKey
                .derivePrivateKey(changeIndex)
                .derivePrivateKey(index.toLong())

            val privateKey = addressPrivateKey.privateKey
            val publicKey = privateKey.publicKey()

            // Use testnet WIF prefix (0xEF) for testnet, mainnet prefix (0x80) for mainnet
            val wifPrefix = if (isTestnet) Base58.Prefix.SecretKeyTestnet else Base58.Prefix.SecretKey

            AddressKeyPair(
                publicKey = publicKey.toHex(),
                privateKeyWIF = privateKey.toBase58(wifPrefix)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get address keys at index $index: ${e.message}")
            null
        }
    }

    /**
     * Checks if an address belongs to the wallet by scanning derived addresses.
     */
    fun checkAddressBelongsToWallet(
        address: String,
        accountPublicKey: DeterministicWallet.ExtendedPublicKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false,
        scanRange: Int = WalletConstants.SINGLE_SIG_ADDRESS_GAP
    ): AddressCheckResult? {
        return try {
            // Check receive addresses
            for (i in 0 until scanRange) {
                val addr = generateAddress(accountPublicKey, i, isChange = false, scriptType, isTestnet)
                if (addr?.address == address) {
                    return AddressCheckResult(true, addr.derivationPath, i, false)
                }
            }

            // Check change addresses
            for (i in 0 until scanRange) {
                val addr = generateAddress(accountPublicKey, i, isChange = true, scriptType, isTestnet)
                if (addr?.address == address) {
                    return AddressCheckResult(true, addr.derivationPath, i, true)
                }
            }

            AddressCheckResult(false, null, null, null)
        } catch (_: Exception) {
            Log.e(TAG, "Failed to check address")
            null
        }
    }

    /**
     * Creates a scriptPubKey for a given public key and script type.
     * Used internally for address lookup during PSBT signing.
     */
    fun createScriptPubKey(publicKey: PublicKey, scriptType: ScriptType): ByteVector? {
        return try {
            when (scriptType) {
                ScriptType.P2PKH -> Script.write(Script.pay2pkh(publicKey)).byteVector()
                ScriptType.P2SH_P2WPKH -> {
                    val p2wpkh = Script.pay2wpkh(publicKey)
                    Script.write(Script.pay2sh(p2wpkh)).byteVector()
                }
                ScriptType.P2WPKH -> Script.write(Script.pay2wpkh(publicKey)).byteVector()
                ScriptType.P2TR -> {
                    val xOnlyKey = XonlyPublicKey(publicKey)
                    Script.write(Script.pay2tr(xOnlyKey)).byteVector()
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Result of checking if an address belongs to a wallet.
 */
data class AddressCheckResult(
    val belongs: Boolean,
    val derivationPath: String?,
    val index: Int?,
    val isChange: Boolean?
)
