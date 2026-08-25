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
        val physicalEntropy: PhysicalEntropyState = PhysicalEntropyState(),
        val physicalEntropyMode: PhysicalEntropyMode = PhysicalEntropyMode.MIX_WITH_DEVICE,
        val entropyErrorMessage: String = "",

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
        val hasShownEntropyInfo: Boolean = false
    ) {
        // Derived properties
        val requiredEntropyBits: Int get() = if (wordCount == 12) 128 else 256

        val entropyType: String get() = when (physicalEntropy.source) {
            EntropySource.COIN -> "coin"
            EntropySource.DICE -> "dice"
            EntropySource.CARDS -> "cards"
            null -> ""
        }
        val collectedEntropy: List<Int> get() = physicalEntropy.inputs
        val cardsWithReplacement: Boolean get() = physicalEntropy.cardsWithReplacement

        val entropyBytes: ByteArray get() = if (collectedEntropy.isEmpty()) {
            ByteArray(0)
        } else {
            PhysicalEntropy.normalizedHash(physicalEntropy)
        }

        // Calculate bits collected based on entropy type
        // Coin flip: 1 bit per flip (log2(2) = 1)
        // Dice roll: ~2.58 bits per roll (log2(6) ≈ 2.585)
        val bitsCollected: Double get() = physicalEntropy.bitsCollected

        // Progress is based on bits collected, not packed byte array size
        val entropyProgress: Float get() =
            (bitsCollected.toFloat() / requiredEntropyBits).coerceIn(0f, 1f)

        val canGenerateMnemonic: Boolean get() =
            physicalEntropyMode == PhysicalEntropyMode.MIX_WITH_DEVICE ||
                PhysicalEntropy.hasRequiredEntropy(physicalEntropy, wordCount)

        val entropyInputCount: String get() = "${collectedEntropy.size} ${
            when (physicalEntropy.source) {
                EntropySource.COIN -> "coin tosses"
                EntropySource.DICE -> "dice rolls"
                EntropySource.CARDS -> "card draws"
                null -> "inputs"
            }
        }"

        val hasUnsavedDraft: Boolean get() =
            currentStep > 1 ||
                hasShownEntropyInfo ||
                wordCount != 12 ||
                selectedDerivationPath != DerivationPaths.NATIVE_SEGWIT ||
                accountNumber != 0 ||
                isTestnet ||
                physicalEntropy.source != null ||
                generatedMnemonic.isNotEmpty() ||
                useBip39Passphrase ||
                bip39Passphrase.isNotEmpty() ||
                confirmBip39Passphrase.isNotEmpty()
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
        if (currentStep > 1) {
            _uiState.update { it.copy(currentStep = currentStep - 1) }
        } else {
            discardDraftAndExit()
        }
    }

    fun discardDraftAndExit() {
        clearSensitiveData()
        viewModelScope.launch {
            _events.emit(CreateWalletEvent.NavigateBack)
        }
    }

    // ========== Step 1: Configuration ==========

    fun setWordCount(count: Int) {
        _uiState.update { it.copy(wordCount = count, entropyErrorMessage = "") }
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
        val source = when (type) {
            "coin" -> EntropySource.COIN
            "dice" -> EntropySource.DICE
            "cards" -> EntropySource.CARDS
            else -> return
        }
        _uiState.update {
            it.copy(
                physicalEntropy = it.physicalEntropy.selectSource(source),
                entropyErrorMessage = ""
            )
        }
    }

    fun addEntropyInput(value: Int) {
        _uiState.update {
            it.copy(physicalEntropy = it.physicalEntropy.add(value), entropyErrorMessage = "")
        }
    }

    fun removeLastEntropyInput() {
        _uiState.update {
            it.copy(physicalEntropy = it.physicalEntropy.removeLast(), entropyErrorMessage = "")
        }
    }

    fun removeEntropyInput(index: Int) {
        _uiState.update {
            it.copy(physicalEntropy = it.physicalEntropy.removeAt(index), entropyErrorMessage = "")
        }
    }

    fun setCardsWithReplacement(enabled: Boolean) {
        _uiState.update {
            it.copy(
                physicalEntropy = it.physicalEntropy.setCardsWithReplacement(enabled),
                entropyErrorMessage = ""
            )
        }
    }

    fun setPhysicalEntropyMode(mode: PhysicalEntropyMode) {
        _uiState.update { it.copy(physicalEntropyMode = mode, entropyErrorMessage = "") }
    }

    fun resetEntropy() {
        _uiState.update {
            it.copy(physicalEntropy = it.physicalEntropy.reset(), entropyErrorMessage = "")
        }
    }

    fun dismissEntropyInfo() {
        _uiState.update { it.copy(showEntropyInfoDialog = false) }
    }

    fun showSecurityWarning() {
        _uiState.update { state ->
            if (state.canGenerateMnemonic) {
                state.copy(showWarningDialog = true, entropyErrorMessage = "")
            } else {
                state.copy(
                    entropyErrorMessage = "Physical-only mode requires at least ${state.requiredEntropyBits} estimated bits."
                )
            }
        }
    }

    fun dismissSecurityWarning() {
        _uiState.update { it.copy(showWarningDialog = false) }
    }

    fun generateMnemonic() {
        viewModelScope.launch {
            _uiState.update { it.copy(showWarningDialog = false) }

            val state = _uiState.value
            if (!state.canGenerateMnemonic) {
                _uiState.update {
                    it.copy(
                        showWarningDialog = false,
                        entropyErrorMessage = "Physical-only mode requires at least ${state.requiredEntropyBits} estimated bits."
                    )
                }
                return@launch
            }

            val entropyBytes = when {
                state.physicalEntropyMode == PhysicalEntropyMode.PHYSICAL_ONLY ->
                    PhysicalEntropy.deterministicBip39Entropy(state.physicalEntropy, state.wordCount)
                state.collectedEntropy.isNotEmpty() -> state.entropyBytes
                else -> null
            }

            val mnemonic = try {
                withContext(Dispatchers.IO) {
                    if (state.physicalEntropyMode == PhysicalEntropyMode.PHYSICAL_ONLY) {
                        wallet.generateMnemonicFromEntropy(state.wordCount, requireNotNull(entropyBytes))
                    } else {
                        wallet.generateMnemonic(state.wordCount, entropyBytes)
                    }
                }
            } finally {
                entropyBytes?.fill(0)
            }

            _uiState.update {
                it.copy(
                    generatedMnemonic = mnemonic,
                    currentStep = 3,
                    errorMessage = "",
                    entropyErrorMessage = ""
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
                            physicalEntropy = PhysicalEntropyState(),
                            physicalEntropyMode = PhysicalEntropyMode.MIX_WITH_DEVICE,
                            entropyErrorMessage = ""
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
        _uiState.value = UiState()
    }
}
