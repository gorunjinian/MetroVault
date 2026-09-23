package com.gorunjinian.metrovault.feature.wallet.create

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.WalletCreationResult
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
        val accountNumber: Int = 0,
        val isTestnet: Boolean = false,  // Testnet wallet toggle

        // Step 2: Entropy
        val entropyType: String = "", // "coin" or "dice"
        val collectedEntropy: List<Int> = emptyList(),

        // Step 3: Generated mnemonic
        val generatedMnemonic: List<String> = emptyList(),

        // Step 4: Passphrase
        val useBip39Passphrase: Boolean = false,
        val bip39Passphrase: String = "",
        val confirmBip39Passphrase: String = "",
        val savePassphraseLocally: Boolean = false, // true = save to disk, false = session only (default)
        val realtimeFingerprint: String = "",       // Calculated in real-time as passphrase is typed

        // Common
        val errorMessage: String = "",
        val isCreatingWallet: Boolean = false,
        val showWarningDialog: Boolean = false,
        val showEntropyInfoDialog: Boolean = false,
        val hasShownEntropyInfo: Boolean = false,

        // Confirmations for actions that throw away entered data
        val pendingEntropyType: String? = null, // Source the user asked to switch to, awaiting confirmation
        val showResetEntropyDialog: Boolean = false,
        val showDiscardSeedDialog: Boolean = false
    ) {
        // Derived properties
        val requiredEntropyBits: Int get() = if (wordCount == 12) 128 else 256

        // Calculate bits collected based on entropy type
        // Coin flip: 1 bit per flip (log2(2) = 1)
        // Dice roll: ~2.58 bits per roll (log2(6) ≈ 2.585)
        val bitsCollected: Double get() = if (entropyType == "coin") {
            collectedEntropy.size.toDouble()
        } else {
            collectedEntropy.size * log2(6.0)
        }

        // Progress is based on bits collected, not packed byte array size
        val entropyProgress: Float get() =
            (bitsCollected.toFloat() / requiredEntropyBits).coerceIn(0f, 1f)

        val entropyInputCount: String get() {
            val count = collectedEntropy.size
            val noun = if (entropyType == "coin") "coin flip" else "dice roll"
            return "$count $noun${if (count == 1) "" else "s"}"
        }
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
        _uiState.update { state ->
            val nextStep = state.currentStep + 1
            // Explain entropy sources the first time the user reaches the entropy step
            val showEntropyInfo = nextStep == 2 && !state.hasShownEntropyInfo
            state.copy(
                currentStep = nextStep,
                showEntropyInfoDialog = showEntropyInfo,
                hasShownEntropyInfo = state.hasShownEntropyInfo || showEntropyInfo
            )
        }
    }

    fun goToPreviousStep() {
        val currentStep = _uiState.value.currentStep
        when {
            // Leaving the seed step discards the seed (revealing again generates a new one), so ask first
            currentStep == 3 -> _uiState.update { it.copy(showDiscardSeedDialog = true) }
            currentStep > 1 -> _uiState.update { it.copy(currentStep = currentStep - 1) }
            else -> viewModelScope.launch {
                _events.emit(CreateWalletEvent.NavigateBack)
            }
        }
    }

    fun confirmDiscardSeed() {
        _uiState.update {
            it.copy(
                showDiscardSeedDialog = false,
                currentStep = 2,
                generatedMnemonic = emptyList(),
                realtimeFingerprint = "" // Belonged to the discarded seed
            )
        }
    }

    fun dismissDiscardSeed() {
        _uiState.update { it.copy(showDiscardSeedDialog = false) }
    }

    // ========== Step 1: Configuration ==========

    fun setWordCount(count: Int) {
        _uiState.update { it.copy(wordCount = count) }
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

    // ========== Step 2: Entropy ==========

    fun setEntropyType(type: String) {
        _uiState.update { state ->
            when {
                // Re-tapping the active source must not wipe its entries
                type == state.entropyType -> state
                state.collectedEntropy.isEmpty() -> state.copy(entropyType = type)
                // Switching discards what was entered, so confirm first
                else -> state.copy(pendingEntropyType = type)
            }
        }
    }

    fun confirmEntropyTypeSwitch() {
        _uiState.update { state ->
            val type = state.pendingEntropyType ?: return@update state
            state.copy(entropyType = type, collectedEntropy = emptyList(), pendingEntropyType = null)
        }
    }

    fun dismissEntropyTypeSwitch() {
        _uiState.update { it.copy(pendingEntropyType = null) }
    }

    fun addEntropyInput(value: Int) {
        _uiState.update {
            it.copy(collectedEntropy = it.collectedEntropy + value)
        }
    }

    fun requestResetEntropy() {
        _uiState.update {
            if (it.collectedEntropy.isEmpty()) it else it.copy(showResetEntropyDialog = true)
        }
    }

    fun confirmResetEntropy() {
        _uiState.update { it.copy(collectedEntropy = emptyList(), showResetEntropyDialog = false) }
    }

    fun dismissResetEntropy() {
        _uiState.update { it.copy(showResetEntropyDialog = false) }
    }

    fun dismissEntropyInfo() {
        _uiState.update { it.copy(showEntropyInfoDialog = false) }
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
                encodeUserEntropy(state.collectedEntropy)
            } else {
                null
            }

            val mnemonic = try {
                withContext(Dispatchers.IO) {
                    wallet.generateMnemonic(state.wordCount, userEntropyBytes)
                }
            } finally {
                userEntropyBytes?.fill(0)
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

    fun setSavePassphraseLocally(save: Boolean) {
        _uiState.update { it.copy(savePassphraseLocally = save) }
    }

    /**
     * Updates the real-time fingerprint preview based on current mnemonic and passphrase.
     * Should be called when passphrase changes.
     */
    fun updateRealtimeFingerprint() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.generatedMnemonic.isEmpty()) return@launch

            val passphrase = if (state.useBip39Passphrase) state.bip39Passphrase else ""
            val fingerprint = withContext(Dispatchers.IO) {
                wallet.calculateFingerprint(state.generatedMnemonic, passphrase)
            }
            _uiState.update { it.copy(realtimeFingerprint = fingerprint ?: "") }
        }
    }

    fun createWallet() {
        val state = _uiState.value

        val passphraseError = validateBip39Passphrase(
            state.useBip39Passphrase, state.bip39Passphrase, state.confirmBip39Passphrase
        )
        if (passphraseError != null) {
            _uiState.update { it.copy(errorMessage = passphraseError) }
            return
        }

        _uiState.update { it.copy(errorMessage = "", isCreatingWallet = true) }

        val finalPassphrase = if (state.useBip39Passphrase) state.bip39Passphrase else ""

        viewModelScope.launch {
            val result = wallet.createWallet(
                name = "New Wallet",
                mnemonic = state.generatedMnemonic,
                derivationPath = state.selectedDerivationPath,
                passphrase = finalPassphrase,
                savePassphraseLocally = state.savePassphraseLocally,
                accountNumber = state.accountNumber
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
}