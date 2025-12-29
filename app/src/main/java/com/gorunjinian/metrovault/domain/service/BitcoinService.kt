package com.gorunjinian.metrovault.domain.service

import android.util.Log
import com.gorunjinian.metrovault.lib.bitcoin.*
import com.gorunjinian.metrovault.data.model.BitcoinAddress
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.ScriptType

/**
 * Facade service for all Bitcoin-related operations.
 * Delegates to focused services for specific functionality:
 * - MnemonicService: BIP39 mnemonic operations
 * - AddressService: Address generation and verification
 * - KeyEncodingService: Extended key encoding and descriptors
 * - PsbtService: PSBT parsing and signing
 *
 * This class maintains backward compatibility with existing callers.
 */
class BitcoinService {

    companion object {
        private const val TAG = "BitcoinService"

        /**
         * Gets the appropriate chain hash (genesis block hash) based on network type.
         * @see BitcoinUtils.getChainHash
         */
        fun getChainHash(isTestnet: Boolean): BlockHash = BitcoinUtils.getChainHash(isTestnet)

        /**
         * Gets the chain hash from a derivation path by checking the coin type.
         * @see BitcoinUtils.getChainHashFromPath
         */
        fun getChainHashFromPath(derivationPath: String): BlockHash = BitcoinUtils.getChainHashFromPath(derivationPath)
    }

    // Delegate services
    private val mnemonicService = MnemonicService()
    private val addressService = AddressService()
    private val keyEncodingService = KeyEncodingService()
    private val psbtService = PsbtService()

    /**
     * Result of wallet creation operations.
     */
    data class WalletCreationResult(
        val masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        val accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        val accountPublicKey: DeterministicWallet.ExtendedPublicKey,
        val fingerprint: String
    )

    // ==================== Mnemonic Operations (delegated to MnemonicService) ====================

    @Suppress("unused") // Public API for future use/testing
    fun generateMnemonic(wordCount: Int = 24): List<String> = 
        mnemonicService.generateMnemonic(wordCount)

    fun generateMnemonicWithUserEntropy(wordCount: Int = 24, userEntropy: ByteArray?): List<String> =
        mnemonicService.generateMnemonicWithUserEntropy(wordCount, userEntropy)

    fun validateMnemonic(words: List<String>): Boolean = 
        mnemonicService.validateMnemonic(words)

    fun calculateFingerprint(mnemonicWords: List<String>, passphrase: String): String? =
        mnemonicService.calculateFingerprint(mnemonicWords, passphrase)

    // ==================== Wallet Creation ====================

    fun createWalletFromMnemonic(
        mnemonicWords: List<String>,
        passphrase: String = "",
        derivationPath: String
    ): WalletCreationResult? {
        return try {
            val seed = MnemonicCode.toSeed(mnemonicWords, passphrase)
            val masterPrivateKey = DeterministicWallet.generate(seed.byteVector())
            val path = BitcoinUtils.parseDerivationPath(derivationPath)
            val accountPrivateKey = masterPrivateKey.derivePrivateKey(path)
            val accountPublicKey = accountPrivateKey.extendedPublicKey

            val fingerprint = BitcoinUtils.computeFingerprintHex(masterPrivateKey.publicKey)

            WalletCreationResult(
                masterPrivateKey = masterPrivateKey,
                accountPrivateKey = accountPrivateKey,
                accountPublicKey = accountPublicKey,
                fingerprint = fingerprint
            )
        } catch (_: Exception) {
            Log.e(TAG, "Failed to create wallet from mnemonic")
            null
        }
    }

    fun createWalletFromSeed(
        seedHex: String,
        derivationPath: String
    ): WalletCreationResult? {
        return try {
            Log.d(TAG, "createWalletFromSeed: path=$derivationPath, seedLen=${seedHex.length}")
            val seedBytes = seedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Log.d(TAG, "Seed bytes length: ${seedBytes.size}")
            val masterPrivateKey = DeterministicWallet.generate(seedBytes.byteVector())
            Log.d(TAG, "Master key generated")
            val path = BitcoinUtils.parseDerivationPath(derivationPath)
            Log.d(TAG, "Parsed path: $path")
            val accountPrivateKey = masterPrivateKey.derivePrivateKey(path)
            val accountPublicKey = accountPrivateKey.extendedPublicKey

            val fingerprint = BitcoinUtils.computeFingerprintHex(masterPrivateKey.publicKey)
            Log.d(TAG, "Wallet created, fingerprint: $fingerprint")

            WalletCreationResult(
                masterPrivateKey = masterPrivateKey,
                accountPrivateKey = accountPrivateKey,
                accountPublicKey = accountPublicKey,
                fingerprint = fingerprint
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create wallet from seed: ${e.message}", e)
            null
        }
    }

    // ==================== Address Operations (delegated to AddressService) ====================

    fun generateAddress(
        accountPublicKey: DeterministicWallet.ExtendedPublicKey,
        index: Int,
        isChange: Boolean,
        scriptType: ScriptType,
        isTestnet: Boolean = false
    ): BitcoinAddress? = addressService.generateAddress(accountPublicKey, index, isChange, scriptType, isTestnet)

    /**
     * Data class for address key pair.
     */
    data class AddressKeyPair(
        val publicKey: String,
        val privateKeyWIF: String
    )

    fun getAddressKeys(
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        index: Int,
        isChange: Boolean,
        isTestnet: Boolean = false
    ): AddressKeyPair? {
        val result = addressService.getAddressKeys(accountPrivateKey, index, isChange, isTestnet)
        return result?.let { AddressKeyPair(it.publicKey, it.privateKeyWIF) }
    }

    fun checkAddressBelongsToWallet(
        address: String,
        accountPublicKey: DeterministicWallet.ExtendedPublicKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false,
        scanRange: Int = 2000
    ): AddressCheckResult? = addressService.checkAddressBelongsToWallet(address, accountPublicKey, scriptType, isTestnet, scanRange)

    // ==================== Key Encoding (delegated to KeyEncodingService) ====================

    fun getAccountXpub(
        accountPublicKey: DeterministicWallet.ExtendedPublicKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false
    ): String = keyEncodingService.getAccountXpub(accountPublicKey, scriptType, isTestnet)

    fun getAccountXpriv(
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false
    ): String = keyEncodingService.getAccountXpriv(accountPrivateKey, scriptType, isTestnet)

    fun getWalletDescriptors(
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        isTestnet: Boolean = false
    ): Pair<String, String>? = keyEncodingService.getWalletDescriptors(masterPrivateKey, isTestnet)

    fun getWalletDescriptor(
        fingerprint: String,
        accountPath: String,
        accountPublicKey: DeterministicWallet.ExtendedPublicKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false
    ): String = keyEncodingService.getWalletDescriptor(fingerprint, accountPath, accountPublicKey, scriptType, isTestnet)

    fun getPrivateWalletDescriptor(
        fingerprint: String,
        accountPath: String,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false
    ): String = keyEncodingService.getPrivateWalletDescriptor(fingerprint, accountPath, accountPrivateKey, scriptType, isTestnet)

    // ==================== BIP48 Multisig Key Encoding ====================

    fun getBip48Xpub(
        accountPublicKey: DeterministicWallet.ExtendedPublicKey,
        bip48ScriptType: DerivationPaths.Bip48ScriptType,
        isTestnet: Boolean = false
    ): String = keyEncodingService.getBip48Xpub(accountPublicKey, bip48ScriptType, isTestnet)

    fun getBip48Xpriv(
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        bip48ScriptType: DerivationPaths.Bip48ScriptType,
        isTestnet: Boolean = false
    ): String = keyEncodingService.getBip48Xpriv(accountPrivateKey, bip48ScriptType, isTestnet)

    // ==================== PSBT Operations (delegated to PsbtService) ====================

    fun signPsbt(
        psbtBase64: String,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false
    ): SigningResult? = psbtService.signPsbt(psbtBase64, masterPrivateKey, accountPrivateKey, scriptType, isTestnet)

    @Suppress("unused") // Public API for future use/testing
    fun isPsbtFullySigned(psbtBase64: String): Boolean = psbtService.isPsbtFullySigned(psbtBase64)

    fun getPsbtDetails(psbtBase64: String, isTestnet: Boolean = false): PsbtDetails? =
        psbtService.getPsbtDetails(psbtBase64, isTestnet)
}