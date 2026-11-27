package com.gorunjinian.metrovault.feature.wallet.create

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.Result
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.domain.service.multisig.MultisigAddressService
import com.gorunjinian.metrovault.domain.service.multisig.MultisigDescriptorParser
import com.gorunjinian.metrovault.domain.service.multisig.MultisigSetupFormat
import com.gorunjinian.metrovault.domain.service.scan.CompletedScan
import com.gorunjinian.metrovault.domain.service.scan.ScannedPayload
import com.gorunjinian.metrovault.feature.scan.mismatchMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for importing multisig wallets from descriptors.
 */
class ImportMultisigViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ImportMultisigVM"
    }

    @SuppressLint("StaticFieldLeak")
    private val context = application.applicationContext

    // Dependencies
    private val wallet: Wallet by lazy { Wallet.getInstance(context) }
    private val descriptorParser = MultisigDescriptorParser()
    private val addressService = MultisigAddressService()

    // ========== UI State ==========

    enum class ScreenState {
        INITIAL,    // Show instructions and scan button
        SCANNING,   // Camera active, scanning for QR code
        PARSED,     // Descriptor parsed, showing confirmation
        ERROR       // Error state with retry option
    }

    data class UiState(
        val screenState: ScreenState = ScreenState.INITIAL,
        val isScanning: Boolean = false,
        val parsedConfig: MultisigConfig? = null,
        val walletName: String = "Multisig Wallet",
        val isImporting: Boolean = false,
        val errorMessage: String = "",
        // BSMS address verification
        val isBsmsFormat: Boolean = false,
        val addressVerified: Boolean? = null, // null = not applicable, true/false = result
        val verificationAddress: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ========== Events ==========

    sealed class ImportEvent {
        /** Wallet created (unverified). [walletId] is used to open the verify/register ceremony. */
        data class WalletImported(val walletId: String) : ImportEvent()
        object NavigateBack : ImportEvent()
    }

    private val _events = MutableSharedFlow<ImportEvent>()
    val events: SharedFlow<ImportEvent> = _events.asSharedFlow()

    // ========== Actions ==========

    fun startScanning() {
        _uiState.update {
            it.copy(screenState = ScreenState.SCANNING, isScanning = true, errorMessage = "")
        }
    }

    fun cancelScanning() {
        _uiState.update {
            it.copy(screenState = ScreenState.INITIAL, isScanning = false)
        }
    }

    fun resetToInitial() {
        _uiState.update {
            UiState() // Reset to default state
        }
    }

    fun setWalletName(name: String) {
        _uiState.update { it.copy(walletName = name) }
    }

    /** A finished scan: parse it if it is a multisig setup, otherwise show what it was instead. */
    fun onScan(scan: CompletedScan) {
        when (val payload = scan.payload) {
            is ScannedPayload.MultisigSetup -> viewModelScope.launch {
                processDescriptor(payload.content, payload.walletName)
            }
            else -> showError(payload.mismatchMessage("a multisig setup"))
        }
    }

    /**
     * Process a decoded multisig input string.
     * Supports plain descriptors, BSMS (BIP-0129) records, and ColdCard setup files.
     * When BSMS format is detected, performs address verification.
     *
     * The wallet name field is prefilled from (in priority order): the content's own
     * name (setup file `Name:`), the QR transport's embedded name (UR:OUTPUT-DESCRIPTOR),
     * or a generic "M-of-N Multisig" fallback.
     *
     * @param descriptor The decoded text content
     * @param qrWalletName Wallet name embedded in the QR transport, if any
     */
    private suspend fun processDescriptor(descriptor: String, qrWalletName: String? = null) {
        try {
            AppLog.d(TAG) { "Processing descriptor" }

            _uiState.update { it.copy(isScanning = false) }

            // Get local wallet fingerprints for matching
            val localFingerprints = getLocalWalletFingerprints()
            AppLog.d(TAG) { "Matching against ${localFingerprints.size} local fingerprint(s)" }

            // Parse the input (format detection happens inside the parser)
            val result = withContext(Dispatchers.Default) {
                descriptorParser.parse(descriptor, localFingerprints)
            }

            when (result) {
                is Result.Success -> {
                    val parsed = result.value
                    val config = parsed.config
                    val isBsmsFormat = parsed.sourceFormat == MultisigSetupFormat.BSMS
                    val verificationAddress = parsed.verificationAddress
                    val defaultName = parsed.suggestedName
                        ?: qrWalletName
                        ?: "${config.m}-of-${config.n} Multisig"

                    // Perform BSMS address verification if applicable
                    var addressVerified: Boolean? = null
                    if (isBsmsFormat && verificationAddress != null) {
                        addressVerified = withContext(Dispatchers.Default) {
                            verifyBsmsAddress(config, verificationAddress)
                        }
                        AppLog.d(TAG) { "BSMS address verification result: $addressVerified" }
                    }

                    _uiState.update {
                        it.copy(
                            screenState = ScreenState.PARSED,
                            parsedConfig = config,
                            walletName = defaultName,
                            isBsmsFormat = isBsmsFormat,
                            addressVerified = addressVerified,
                            verificationAddress = verificationAddress
                        )
                    }
                }
                is Result.Error -> {
                    showError(result.error)
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Error processing descriptor: ${e.message}" }
            showError("Failed to process descriptor: ${e.message}")
        }
    }

    /**
     * Verify that the BSMS verification address matches the expected first receive address.
     *
     * @param config The parsed multisig configuration
     * @param expectedAddress The verification address from BSMS record
     * @return True if address matches, false otherwise
     */
    private fun verifyBsmsAddress(config: MultisigConfig, expectedAddress: String): Boolean {
        return try {
            val isTestnet = config.isTestnet()
            val result = addressService.generateMultisigAddress(
                config = config,
                index = 0,
                isChange = false,
                isTestnet = isTestnet
            )

            when (result) {
                is Result.Success -> {
                    val bitcoinAddress = result.value
                    val generatedAddress = bitcoinAddress.address
                    val matches = generatedAddress == expectedAddress
                    if (!matches) {
                        AppLog.w(TAG) { "BSMS address mismatch between expected and generated" }
                    }
                    matches
                }
                is Result.Error -> {
                    AppLog.e(TAG) { "Failed to generate address for verification: ${result.error}" }
                    false
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "BSMS address verification error: ${e.message}" }
            false
        }
    }

    /**
     * Imports the multisig wallet with the parsed configuration.
     */
    fun importMultisigWallet() {
        val state = _uiState.value
        val config = state.parsedConfig ?: return

        if (state.walletName.isBlank()) {
            showError("Please enter a wallet name")
            return
        }

        _uiState.update { it.copy(isImporting = true) }

        viewModelScope.launch {
            try {
                val walletId = wallet.createMultisigWallet(
                    name = state.walletName.trim(),
                    config = config
                )

                _uiState.update { it.copy(isImporting = false) }

                if (walletId != null) {
                    AppLog.d(TAG) { "Multisig wallet imported successfully" }
                    _events.emit(ImportEvent.WalletImported(walletId))
                } else {
                    showError("Failed to import multisig wallet")
                }

            } catch (e: Exception) {
                AppLog.e(TAG, e) { "Error importing multisig wallet: ${e.message}" }
                _uiState.update { it.copy(isImporting = false) }
                showError("Import failed: ${e.message}")
            }
        }
    }

    // ========== Private Helpers ==========

    private fun showError(message: String) {
        _uiState.update {
            it.copy(
                screenState = ScreenState.ERROR,
                errorMessage = message,
                isScanning = false
            )
        }
    }

    private fun getLocalWalletFingerprints(): List<String> {
        return wallet.wallets.value.map { it.masterFingerprint.lowercase() }
    }
}