package com.gorunjinian.metrovault.domain.service.bitcoin

import android.util.Log
import com.gorunjinian.metrovault.lib.bitcoin.*
import com.gorunjinian.metrovault.data.model.BitcoinAddress
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.DerivedWalletKeys
import com.gorunjinian.metrovault.data.model.PsbtDetails
import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.data.model.SigningResult
import com.gorunjinian.metrovault.domain.service.psbt.PsbtService
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
import com.gorunjinian.metrovault.domain.service.util.hexToByteArray

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
    }

    // Delegate services
    private val mnemonicService = MnemonicService()
    private val addressService = AddressService()
    private val keyEncodingService = KeyEncodingService()
    private val psbtService = PsbtService()

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

    /**
     * Calculates master fingerprint from a hex-encoded BIP39 seed.
     * Used for calculating fingerprints from session seeds (after passphrase derivation).
     *
     * @param seedHex Hex-encoded 64-byte BIP39 seed
     * @return 8-character hex fingerprint or null on error
     */
    fun calculateFingerprintFromSeed(seedHex: String): String? {
        return try {
            val seedBytes = seedHex.hexToByteArray()
            val masterPrivateKey = DeterministicWallet.generate(seedBytes.byteVector())
            BitcoinUtils.computeFingerprintHex(masterPrivateKey.publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate fingerprint from seed: ${e.message}")
            null
        }
    }

    // ==================== Wallet Creation ====================

    fun createWalletFromMnemonic(
        mnemonicWords: List<String>,
        passphrase: String = "",
        derivationPath: String
    ): DerivedWalletKeys? {
        return try {
            val seed = MnemonicCode.toSeed(mnemonicWords, passphrase)
            val masterPrivateKey = DeterministicWallet.generate(seed.byteVector())
            val path = BitcoinUtils.parseDerivationPath(derivationPath)
            val accountPrivateKey = masterPrivateKey.derivePrivateKey(path)
            val accountPublicKey = accountPrivateKey.extendedPublicKey

            val fingerprint = BitcoinUtils.computeFingerprintHex(masterPrivateKey.publicKey)

            DerivedWalletKeys(
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
    ): DerivedWalletKeys? {
        return try {
            Log.d(TAG, "createWalletFromSeed: path=$derivationPath, seedLen=${seedHex.length}")
            val seedBytes = seedHex.hexToByteArray()
            Log.d(TAG, "Seed bytes length: ${seedBytes.size}")
            val masterPrivateKey = DeterministicWallet.generate(seedBytes.byteVector())
            Log.d(TAG, "Master key generated")
            val path = BitcoinUtils.parseDerivationPath(derivationPath)
            Log.d(TAG, "Parsed path: $path")
            val accountPrivateKey = masterPrivateKey.derivePrivateKey(path)
            val accountPublicKey = accountPrivateKey.extendedPublicKey

            val fingerprint = BitcoinUtils.computeFingerprintHex(masterPrivateKey.publicKey)
            Log.d(TAG, "Wallet created, fingerprint: $fingerprint")

            DerivedWalletKeys(
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

    fun getAddressKeys(
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        index: Int,
        isChange: Boolean,
        isTestnet: Boolean = false
    ): AddressService.AddressKeyPair? =
        addressService.getAddressKeys(accountPrivateKey, index, isChange, isTestnet)

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

    fun canFinalize(psbtBase64: String): Boolean =
        psbtService.canFinalize(psbtBase64)
    
    @Deprecated("Use canFinalize() instead", ReplaceWith("canFinalize(psbtBase64)"))
    fun canFinalizeSingleSig(psbtBase64: String): Boolean =
        canFinalize(psbtBase64)

    fun finalizePsbt(psbtBase64: String): PsbtService.FinalizePsbtResult =
        psbtService.finalizePsbt(psbtBase64)
}
