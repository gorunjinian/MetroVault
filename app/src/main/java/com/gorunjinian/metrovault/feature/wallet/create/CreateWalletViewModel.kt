package com.gorunjinian.metrovault.feature.wallet.create

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.WalletCreationResult
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log2

/**
 * ViewModel for the Create Wallet multi-step wizard.
 * Manages the 4-step flow: Word Count -> Entropy -> Seed Display -> Passphrase
 */
class CreateWalletViewModel(application: Application) : AndroidViewModel(application) {

    @SuppressLint("StaticFieldLeak")
    private val context = application.applicationContext

    // Dependencies
    private val wallet: Wallet by lazy { Wallet.getInstance(context) }

    // ========== UI State ==========

    data class UiState(
        // Current step (1-4)
        val currentStep: Int = 1,

        // Step 1: Configuration
        val wordCount: Int = 12,
        val selectedDerivationPath: String = DerivationPaths.NATIVE_SEGWIT,

        // Step 2: Entropy
        val entropyType: String = "", // "coin" or "dice"
        val collectedEntropy: List<Int> = emptyList(),

        // Step 3: Generated mnemonic
        val generatedMnemonic: List<String> = emptyList(),

        // Step 4: Passphrase
        val useBip39Passphrase: Boolean = false,
        val bip39Passphrase: String = "",
        val confirmBip39Passphrase: String = "",

        // Common
        val errorMessage: String = "",
        val isCreatingWallet: Boolean = false,
        val showWarningDialog: Boolean = false
    ) {
        // Derived properties
        val requiredEntropyBytes: Int get() = if (wordCount == 12) 16 else 32

        val entropyBytes: ByteArray get() = calculateEntropyBytes(entropyType, collectedEntropy)

        val entropyProgress: Float get() =
            (entropyBytes.size.toFloat() / requiredEntropyBytes).coerceIn(0f, 1f)

        val bitsCollected: Int get() = if (entropyType == "coin") {
            collectedEntropy.size
        } else {
            (collectedEntropy.size * log2(6.0)).toInt()
        }

        val bytesCollected: Double get() = bitsCollected / 8.0

        val entropyInputCount: String get() = "${collectedEntropy.size} ${
            if (entropyType == "coin") "coin flips" else "dice rolls"
        }"
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ========== Events ==========

    sealed class CreateWalletEvent {
        object WalletCreated : CreateWalletEvent()
        object NavigateBack : CreateWalletEvent()
    }

    private val _events = MutableSharedFlow<CreateWalletEvent>()
    val events: SharedFlow<CreateWalletEvent> = _events.asSharedFlow()

    // ========== Step Navigation ==========

    fun goToNextStep() {
        _uiState.update { it.copy(currentStep = it.currentStep + 1) }
    }

    fun goToPreviousStep() {
        val currentStep = _uiState.value.currentStep
        if (currentStep > 1) {
            _uiState.update { it.copy(currentStep = currentStep - 1) }
        } else {
            viewModelScope.launch {
                _events.emit(CreateWalletEvent.NavigateBack)
            }
        }
    }

    // ========== Step 1: Configuration ==========

    fun setWordCount(count: Int) {
        _uiState.update { it.copy(wordCount = count) }
    }

    fun setDerivationPath(path: String) {
        _uiState.update { it.copy(selectedDerivationPath = path) }
    }

    // ========== Step 2: Entropy ==========

    fun setEntropyType(type: String) {
        _uiState.update {
            it.copy(entropyType = type, collectedEntropy = emptyList())
        }
    }

    fun addEntropyInput(value: Int) {
        _uiState.update {
            it.copy(collectedEntropy = it.collectedEntropy + value)
        }
    }

    fun resetEntropy() {
        _uiState.update { it.copy(collectedEntropy = emptyList()) }
    }

    fun showSecurityWarning() {
        _uiState.update { it.copy(showWarningDialog = true) }
    }

    fun dismissSecurityWarning() {
        _uiState.update { it.copy(showWarningDialog = false) }
    }

    fun generateMnemonic() {
        viewModelScope.launch {
            _uiState.update { it.copy(showWarningDialog = false) }

            val state = _uiState.value
            val userEntropyBytes = if (state.collectedEntropy.isNotEmpty()) {
                state.entropyBytes
            } else {
                null
            }

            val mnemonic = withContext(Dispatchers.IO) {
                wallet.generateMnemonic(state.wordCount, userEntropyBytes)
            }

            _uiState.update {
                it.copy(
                    generatedMnemonic = mnemonic,
                    currentStep = 3,
                    errorMessage = ""
                )
            }
        }
    }

    // ========== Step 4: Passphrase ==========

    fun setUseBip39Passphrase(use: Boolean) {
        _uiState.update { it.copy(useBip39Passphrase = use) }
    }

    fun setBip39Passphrase(passphrase: String) {
        _uiState.update { it.copy(bip39Passphrase = passphrase) }
    }

    fun setConfirmBip39Passphrase(passphrase: String) {
        _uiState.update { it.copy(confirmBip39Passphrase = passphrase) }
    }

    fun createWallet() {
        val state = _uiState.value

        // Validate passphrase if enabled
        if (state.useBip39Passphrase) {
            if (state.bip39Passphrase.isEmpty()) {
                _uiState.update {
                    it.copy(errorMessage = "Please enter a BIP39 passphrase or turn off the toggle to continue without one")
                }
                return
            }
            if (state.bip39Passphrase != state.confirmBip39Passphrase) {
                _uiState.update { it.copy(errorMessage = "BIP39 passphrases do not match") }
                return
            }
        }

        _uiState.update { it.copy(errorMessage = "", isCreatingWallet = true) }

        val finalPassphrase = if (state.useBip39Passphrase) state.bip39Passphrase else ""

        viewModelScope.launch {
            val result = wallet.createWallet(
                name = "New Wallet",
                mnemonic = state.generatedMnemonic,
                derivationPath = state.selectedDerivationPath,
                passphrase = finalPassphrase
            )

            _uiState.update { it.copy(isCreatingWallet = false) }

            when (result) {
                is WalletCreationResult.Success -> {
                    // Clear sensitive data
                    _uiState.update {
                        it.copy(
                            generatedMnemonic = emptyList(),
                            bip39Passphrase = "",
                            confirmBip39Passphrase = "",
                            collectedEntropy = emptyList()
                        )
                    }
                    _events.emit(CreateWalletEvent.WalletCreated)
                }
                is WalletCreationResult.Error -> {
                    _uiState.update { it.copy(errorMessage = result.reason.message) }
                }
            }
        }
    }

    // ========== Cleanup ==========

    fun clearSensitiveData() {
        _uiState.update {
            it.copy(
                generatedMnemonic = emptyList(),
                bip39Passphrase = "",
                confirmBip39Passphrase = "",
                collectedEntropy = emptyList()
            )
        }
    }

    companion object {
        /**
         * Converts collected entropy inputs to a byte array.
         * For coins: packs bits (0=Heads, 1=Tails) into bytes
         * For dice: converts dice values to bytes using the raw values
         */
        private fun calculateEntropyBytes(entropyType: String, inputs: List<Int>): ByteArray {
            if (inputs.isEmpty()) return ByteArray(0)

            return when (entropyType) {
                "coin" -> {
                    // Pack coin flips as bits into bytes
                    val bytes = mutableListOf<Byte>()
                    var currentByte = 0
                    var bitCount = 0

                    for (flip in inputs) {
                        currentByte = (currentByte shl 1) or flip
                        bitCount++

                        if (bitCount == 8) {
                            bytes.add(currentByte.toByte())
                            currentByte = 0
                            bitCount = 0
                        }
                    }

                    bytes.toByteArray()
                }
                "dice" -> {
                    // Use dice values directly, each roll contributes ~2.58 bits
                    // Pack pairs of dice rolls into bytes for efficiency
                    val bytes = mutableListOf<Byte>()

                    for (i in inputs.indices step 2) {
                        if (i + 1 < inputs.size) {
                            // Combine two dice values (1-6) into one byte
                            val combined = (inputs[i] - 1) * 6 + (inputs[i + 1] - 1)
                            bytes.add(combined.toByte())
                        }
                    }

                    bytes.toByteArray()
                }
                else -> ByteArray(0)
            }
        }
    }
}
