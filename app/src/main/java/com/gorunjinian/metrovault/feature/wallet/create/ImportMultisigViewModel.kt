package com.gorunjinian.metrovault.feature.wallet.create

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.domain.service.MultisigDescriptorParser
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
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
        // Animated QR scan progress
        val scanProgress: Int = 0,
        val isAnimatedScan: Boolean = false,
        val scanProgressString: String = ""
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    // Scanner for animated QR codes - exposed for UI access
    val descriptorScanner = QRCodeUtils.DescriptorQRScanner()

    // ========== Events ==========

    sealed class ImportEvent {
        object WalletImported : ImportEvent()
        object NavigateBack : ImportEvent()
        data class ScanComplete(val descriptor: String) : ImportEvent()
    }

    private val _events = MutableSharedFlow<ImportEvent>()
    val events: SharedFlow<ImportEvent> = _events.asSharedFlow()

    // ========== Actions ==========

    fun startScanning() {
        descriptorScanner.reset()
        _uiState.update {
            it.copy(
                screenState = ScreenState.SCANNING, 
                isScanning = true, 
                errorMessage = "",
                scanProgress = 0,
                isAnimatedScan = false,
                scanProgressString = ""
            )
        }
    }

    fun cancelScanning() {
        descriptorScanner.reset()
        _uiState.update {
            it.copy(screenState = ScreenState.INITIAL, isScanning = false)
        }
    }

    fun resetToInitial() {
        descriptorScanner.reset()
        _uiState.update {
            UiState() // Reset to default state
        }
    }

    fun setWalletName(name: String) {
        _uiState.update { it.copy(walletName = name) }
    }
    
    /**
     * Process a single scanned frame. Used by the camera scanner.
     * Handles both single-frame and multi-frame animated QR codes.
     * 
     * @return Progress percentage (0-100), or null if frame is invalid
     */
    fun processScannedFrame(content: String): Int? {
        val progress = descriptorScanner.processFrame(content)
        
        if (progress != null) {
            val detectedFormat = descriptorScanner.getDetectedFormat()
            val isAnimated = detectedFormat != null && detectedFormat != "plain" && detectedFormat != "unknown"
            
            _uiState.update {
                it.copy(
                    scanProgress = progress,
                    isAnimatedScan = isAnimated,
                    scanProgressString = descriptorScanner.getProgressString()
                )
            }
            
            // Check if scan is complete
            if (descriptorScanner.isComplete()) {
                viewModelScope.launch {
                    finishScanning()
                }
            }
        }
        
        return progress
    }
    
    /**
     * Called when all frames have been received.
     * Assembles the descriptor and processes it.
     */
    private suspend fun finishScanning() {
        val descriptor = withContext(Dispatchers.Default) {
            descriptorScanner.getResult()
        }
        
        if (descriptor != null) {
            Log.d(TAG, "Scan complete, descriptor: ${descriptor.take(100)}...")
            processDescriptor(descriptor)
        } else {
            Log.e(TAG, "Scanner returned null result")
            showError("Failed to decode QR code. Please try again.")
        }
    }

    /**
     * Process a decoded descriptor string.
     */
    private suspend fun processDescriptor(descriptor: String) {
        try {
            Log.d(TAG, "Processing descriptor: ${descriptor.take(100)}...")
            
            _uiState.update { it.copy(isScanning = false) }
            
            // Get local wallet fingerprints for matching
            val localFingerprints = getLocalWalletFingerprints()
            Log.d(TAG, "Local fingerprints: $localFingerprints")
            
            // Parse the descriptor
            val result = withContext(Dispatchers.Default) {
                descriptorParser.parse(descriptor, localFingerprints)
            }
            
            when (result) {
                is MultisigDescriptorParser.ParseResult.Success -> {
                    val config = result.config
                    val defaultName = "${config.m}-of-${config.n} Multisig"
                    
                    _uiState.update {
                        it.copy(
                            screenState = ScreenState.PARSED,
                            parsedConfig = config,
                            walletName = defaultName
                        )
                    }
                }
                is MultisigDescriptorParser.ParseResult.Error -> {
                    showError(result.message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing descriptor: ${e.message}", e)
            showError("Failed to process descriptor: ${e.message}")
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
                val success = wallet.createMultisigWallet(
                    name = state.walletName.trim(),
                    config = config
                )
                
                _uiState.update { it.copy(isImporting = false) }
                
                if (success) {
                    Log.d(TAG, "Multisig wallet imported successfully")
                    _events.emit(ImportEvent.WalletImported)
                } else {
                    showError("Failed to import multisig wallet")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error importing multisig wallet: ${e.message}", e)
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

