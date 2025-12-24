package com.gorunjinian.metrovault.feature.wallet.create

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.WalletCreationResult
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Import Wallet multi-step wizard.
 * Manages the 3-step flow: Configuration -> Seed Phrase Input -> Passphrase
 */
class ImportWalletViewModel(application: Application) : AndroidViewModel(application) {

    @SuppressLint("StaticFieldLeak")
    private val context = application.applicationContext

    // Dependencies
    private val wallet: Wallet by lazy { Wallet.getInstance(context) }

    // ========== UI State ==========

    data class UiState(
        // Current step (1-3)
        val currentStep: Int = 1,

        // Step 1: Configuration
        val expectedWordCount: Int = 12,
        val selectedDerivationPath: String = DerivationPaths.NATIVE_SEGWIT,
        val accountNumber: Int = 0,
        val isTestnet: Boolean = false,

        // Step 2: Mnemonic input
        val mnemonicWords: List<String> = emptyList(),
        val currentWord: String = "",
        val isKeyboardVisible: Boolean = true,

        // Step 3: Passphrase
        val useBip39Passphrase: Boolean = false,
        val bip39Passphrase: String = "",
        val confirmBip39Passphrase: String = "",
        val savePassphraseLocally: Boolean = true,
        val realtimeFingerprint: String = "",

        // Common
        val errorMessage: String = "",
        val isImportingWallet: Boolean = false
    ) {
        // Derived properties
        val isMnemonicComplete: Boolean get() = mnemonicWords.size == expectedWordCount
        val isMnemonicValid: Boolean get() = isMnemonicComplete && validateMnemonic(mnemonicWords)
        
        companion object {
            private fun validateMnemonic(words: List<String>): Boolean {
                return try {
                    MnemonicCode.validate(words)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ========== Events ==========

    sealed class ImportWalletEvent {
        object WalletImported : ImportWalletEvent()
        object NavigateBack : ImportWalletEvent()
    }

    private val _events = MutableSharedFlow<ImportWalletEvent>()
    val events: SharedFlow<ImportWalletEvent> = _events.asSharedFlow()

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
                _events.emit(ImportWalletEvent.NavigateBack)
            }
        }
    }

    // ========== Step 1: Configuration ==========

    fun setWordCount(count: Int) {
        _uiState.update { it.copy(expectedWordCount = count) }
    }

    fun setDerivationPath(path: String) {
        _uiState.update { it.copy(selectedDerivationPath = path) }
    }

    fun setAccountNumber(accountNumber: Int) {
        _uiState.update { it.copy(accountNumber = accountNumber) }
    }

    /**
     * Toggles testnet mode and updates the derivation path accordingly.
     * Preserves the current address type (purpose) when switching.
     */
    fun setTestnetMode(enabled: Boolean) {
        _uiState.update { state ->
            val currentPurpose = DerivationPaths.getPurpose(state.selectedDerivationPath)
            val newPath = when (currentPurpose) {
                86 -> if (enabled) DerivationPaths.TAPROOT_TESTNET else DerivationPaths.TAPROOT
                84 -> if (enabled) DerivationPaths.NATIVE_SEGWIT_TESTNET else DerivationPaths.NATIVE_SEGWIT
                49 -> if (enabled) DerivationPaths.NESTED_SEGWIT_TESTNET else DerivationPaths.NESTED_SEGWIT
                44 -> if (enabled) DerivationPaths.LEGACY_TESTNET else DerivationPaths.LEGACY
                else -> if (enabled) DerivationPaths.NATIVE_SEGWIT_TESTNET else DerivationPaths.NATIVE_SEGWIT
            }
            state.copy(isTestnet = enabled, selectedDerivationPath = newPath)
        }
    }

    // ========== Step 2: Mnemonic Input ==========

    fun setCurrentWord(word: String) {
        _uiState.update { it.copy(currentWord = word) }
    }

    fun addWord(word: String) {
        _uiState.update { state ->
            if (state.mnemonicWords.size < state.expectedWordCount) {
                state.copy(
                    mnemonicWords = state.mnemonicWords + word.lowercase().trim(),
                    currentWord = ""
                )
            } else {
                state
            }
        }
    }

    fun clearMnemonic() {
        _uiState.update { it.copy(mnemonicWords = emptyList(), currentWord = "") }
    }

    fun setKeyboardVisible(visible: Boolean) {
        _uiState.update { it.copy(isKeyboardVisible = visible) }
    }

    // ========== Step 3: Passphrase ==========

    fun setUseBip39Passphrase(use: Boolean) {
        _uiState.update { it.copy(useBip39Passphrase = use) }
    }

    fun setBip39Passphrase(passphrase: String) {
        _uiState.update { it.copy(bip39Passphrase = passphrase) }
    }

    fun setConfirmBip39Passphrase(passphrase: String) {
        _uiState.update { it.copy(confirmBip39Passphrase = passphrase) }
    }

    fun setSavePassphraseLocally(save: Boolean) {
        _uiState.update { it.copy(savePassphraseLocally = save) }
    }

    /**
     * Updates the real-time fingerprint preview based on current mnemonic and passphrase.
     * Should be called when passphrase or mnemonic changes.
     */
    fun updateRealtimeFingerprint() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!state.isMnemonicValid) {
                _uiState.update { it.copy(realtimeFingerprint = "") }
                return@launch
            }
            
            val passphrase = if (state.useBip39Passphrase) state.bip39Passphrase else ""
            val fingerprint = withContext(Dispatchers.IO) {
                wallet.calculateFingerprint(state.mnemonicWords, passphrase)
            }
            _uiState.update { it.copy(realtimeFingerprint = fingerprint ?: "") }
        }
    }

    fun importWallet() {
        val state = _uiState.value

        // Validate mnemonic
        if (!state.isMnemonicValid) {
            _uiState.update { it.copy(errorMessage = "Invalid mnemonic phrase") }
            return
        }

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

        _uiState.update { it.copy(errorMessage = "", isImportingWallet = true) }

        val finalPassphrase = if (state.useBip39Passphrase) state.bip39Passphrase else ""

        viewModelScope.launch {
            val result = wallet.createWallet(
                name = "Imported Wallet",
                mnemonic = state.mnemonicWords,
                derivationPath = state.selectedDerivationPath,
                passphrase = finalPassphrase,
                savePassphraseLocally = state.savePassphraseLocally,
                accountNumber = state.accountNumber
            )

            _uiState.update { it.copy(isImportingWallet = false) }

            when (result) {
                is WalletCreationResult.Success -> {
                    // Clear sensitive data
                    _uiState.update {
                        it.copy(
                            mnemonicWords = emptyList(),
                            currentWord = "",
                            bip39Passphrase = "",
                            confirmBip39Passphrase = ""
                        )
                    }
                    _events.emit(ImportWalletEvent.WalletImported)
                }
                is WalletCreationResult.Error -> {
                    _uiState.update { it.copy(errorMessage = result.reason.message) }
                }
            }
        }
    }

    /**
     * Clears all sensitive data when the ViewModel is destroyed.
     */
    override fun onCleared() {
        super.onCleared()
        _uiState.update {
            it.copy(
                mnemonicWords = emptyList(),
                currentWord = "",
                bip39Passphrase = "",
                confirmBip39Passphrase = ""
            )
        }
    }
}
