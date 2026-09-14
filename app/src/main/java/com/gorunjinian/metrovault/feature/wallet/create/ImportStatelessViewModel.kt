package com.gorunjinian.metrovault.feature.wallet.create

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.domain.Wallet
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
 * ViewModel for the stateless (memory-only) wallet import wizard.
 * Manages the 3-step flow: Configuration -> Seed Phrase Input (typed or SeedQR) -> Passphrase.
 * Mirrors [ImportWalletViewModel]; the difference is that nothing is persisted —
 * the wallet lives only in memory until lock/exit.
 */
class ImportStatelessViewModel(application: Application) : AndroidViewModel(application) {

    @SuppressLint("StaticFieldLeak")
    private val context = application.applicationContext

    // Dependencies
    private val wallet: Wallet by lazy { Wallet.getInstance(context) }

    // ========== UI State ==========

    data class UiState(
        // Current step: 1 = Configuration, 2 = Seed Phrase, 3 = Passphrase
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
        val usePassphrase: Boolean = false,
        val passphrase: String = "",
        val confirmPassphrase: String = "",
        val realtimeFingerprint: String = "",

        // Common
        val errorMessage: String = "",
        val isCreating: Boolean = false
    ) {
        // Full derivation path with the account number applied
        val fullDerivationPath: String
            get() = DerivationPaths.withAccountNumber(selectedDerivationPath, accountNumber)

        // Derived properties
        val isMnemonicComplete: Boolean get() = mnemonicWords.size == expectedWordCount
        val isMnemonicValid: Boolean get() = isMnemonicComplete && isValidBip39Mnemonic(mnemonicWords)
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ========== Events ==========

    sealed class ImportStatelessEvent {
        object WalletCreated : ImportStatelessEvent()
        object NavigateBack : ImportStatelessEvent()
    }

    private val _events = MutableSharedFlow<ImportStatelessEvent>()
    val events: SharedFlow<ImportStatelessEvent> = _events.asSharedFlow()

    // ========== Step Navigation ==========

    fun goToNextStep() {
        _uiState.update { it.copy(currentStep = it.currentStep + 1) }
    }

    /**
     * Steps back through the wizard. The mnemonic is cleared when leaving step 2 for
     * step 1 (but kept when returning from step 3, so a typed phrase isn't lost).
     * From step 1, wipes everything and emits [ImportStatelessEvent.NavigateBack].
     */
    fun goToPreviousStep() {
        val currentStep = _uiState.value.currentStep
        if (currentStep > 1) {
            _uiState.update { state ->
                if (currentStep == 2) {
                    state.copy(
                        currentStep = 1,
                        mnemonicWords = emptyList(),
                        currentWord = ""
                    )
                } else {
                    state.copy(currentStep = currentStep - 1, errorMessage = "")
                }
            }
        } else {
            clearSensitiveData()
            viewModelScope.launch {
                _events.emit(ImportStatelessEvent.NavigateBack)
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
            state.copy(
                isTestnet = enabled,
                selectedDerivationPath = DerivationPaths.forNetwork(state.selectedDerivationPath, enabled)
            )
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

    /**
     * Replaces the mnemonic with a scanned SeedQR. Either supported length is accepted,
     * and the expected word count follows the scan so the step 1 choice never blocks it.
     */
    fun onSeedQrScanned(words: List<String>) {
        _uiState.update {
            it.copy(expectedWordCount = words.size, mnemonicWords = words, currentWord = "")
        }
    }

    fun setKeyboardVisible(visible: Boolean) {
        _uiState.update { it.copy(isKeyboardVisible = visible) }
    }

    // ========== Step 3: Passphrase ==========

    fun setUsePassphrase(use: Boolean) {
        _uiState.update { it.copy(usePassphrase = use) }
    }

    fun setPassphrase(passphrase: String) {
        _uiState.update { it.copy(passphrase = passphrase) }
    }

    fun setConfirmPassphrase(passphrase: String) {
        _uiState.update { it.copy(confirmPassphrase = passphrase) }
    }

    /**
     * Updates the real-time fingerprint preview from the mnemonic and passphrase.
     * Uses computeFingerprintOnly to avoid race conditions with createStatelessWallet.
     */
    fun updateRealtimeFingerprint() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!state.isMnemonicValid) {
                _uiState.update { it.copy(realtimeFingerprint = "") }
                return@launch
            }

            val passphrase = if (state.usePassphrase) state.passphrase else ""
            val fingerprint = withContext(Dispatchers.IO) {
                try {
                    wallet.computeFingerprintOnly(state.mnemonicWords, passphrase, state.fullDerivationPath)
                } catch (_: Exception) {
                    null
                }
            }
            _uiState.update { it.copy(realtimeFingerprint = fingerprint?.uppercase() ?: "") }
        }
    }

    fun createWallet() {
        val state = _uiState.value

        // Validate mnemonic
        if (!state.isMnemonicValid) {
            _uiState.update { it.copy(errorMessage = "Invalid mnemonic phrase") }
            return
        }

        val passphraseError = validateBip39Passphrase(
            state.usePassphrase, state.passphrase, state.confirmPassphrase
        )
        if (passphraseError != null) {
            _uiState.update { it.copy(errorMessage = passphraseError) }
            return
        }

        _uiState.update { it.copy(errorMessage = "", isCreating = true) }

        val finalPassphrase = if (state.usePassphrase) state.passphrase else ""

        viewModelScope.launch {
            val walletState = withContext(Dispatchers.IO) {
                wallet.createStatelessWallet(state.mnemonicWords, finalPassphrase, state.fullDerivationPath)
            }
            if (walletState != null) {
                clearSensitiveData()
                _events.emit(ImportStatelessEvent.WalletCreated)
            } else {
                _uiState.update { it.copy(errorMessage = "Failed to create wallet", isCreating = false) }
            }
        }
    }

    // ========== Cleanup ==========

    private fun clearSensitiveData() {
        _uiState.update {
            it.copy(
                mnemonicWords = emptyList(),
                currentWord = "",
                passphrase = "",
                confirmPassphrase = "",
                realtimeFingerprint = ""
            )
        }
    }

    /**
     * Clears all sensitive data when the ViewModel is destroyed.
     */
    override fun onCleared() {
        super.onCleared()
        clearSensitiveData()
    }
}
