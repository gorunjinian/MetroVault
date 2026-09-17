package com.gorunjinian.metrovault.feature.auth

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorunjinian.metrovault.core.crypto.BiometricPasswordManager
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.WalletCreationResult
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

/**
 * ViewModel for authentication screens (UnlockScreen, SetupPasswordScreen).
 * Centralizes auth-related state management and business logic.
 */
class AuthViewModel(application: Application) : AndroidViewModel(application) {

    @SuppressLint("StaticFieldLeak")
    private val context = application.applicationContext

    // Dependencies - in a real app, these would be injected via Hilt/Koin
    private val secureStorage: SecureStorage by lazy { SecureStorage(context) }
    private val wallet: Wallet by lazy { Wallet.getInstance(context) }
    private val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(context)
    }
    private val biometricPasswordManager: BiometricPasswordManager by lazy {
        BiometricPasswordManager(context)
    }

    companion object {
        private const val TAG = "AuthViewModel"

        // The throwaway wallet a duress session shows so Home is not empty.
        private const val DURESS_WALLET_NAME = "My Wallet"
        private const val DURESS_WALLET_WORDS = 12
    }

    // ========== UI State ==========

    data class UnlockUiState(
        val password: String = "",
        val errorMessage: String = "",
        val isAuthenticating: Boolean = false,
        val biometricsEnabled: Boolean = false,
        val biometricTarget: String = UserPreferencesRepository.BIOMETRIC_TARGET_NONE,
        val hasBiometricPassword: Boolean = false
    )

    data class SetupUiState(
        val password: String = "",
        val confirmPassword: String = "",
        val errorMessage: String = "",
        val isProcessing: Boolean = false
    )

    private val _unlockState = MutableStateFlow(UnlockUiState())
    val unlockState: StateFlow<UnlockUiState> = _unlockState.asStateFlow()

    private val _setupState = MutableStateFlow(SetupUiState())
    val setupState: StateFlow<SetupUiState> = _setupState.asStateFlow()

    // ========== Events ==========

    sealed class AuthEvent {
        data class UnlockSuccess(val autoOpenRequested: Boolean) : AuthEvent()
        object SetupComplete : AuthEvent()
        object DataWiped : AuthEvent()
    }

    private val _events = MutableSharedFlow<AuthEvent>()
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    // ========== Initialization ==========

    init {
        // Observe biometric settings
        viewModelScope.launch {
            combine(
                userPreferencesRepository.biometricsEnabled,
                userPreferencesRepository.biometricTarget
            ) { enabled, target ->
                val hasBioPwd = biometricPasswordManager.hasEncodedPassword(target)
                Triple(enabled, target, hasBioPwd)
            }.collect { (enabled, target, hasBioPwd) ->
                _unlockState.update {
                    it.copy(
                        biometricsEnabled = enabled,
                        biometricTarget = target,
                        hasBiometricPassword = hasBioPwd
                    )
                }
            }
        }
    }

    // ========== Unlock Screen Actions ==========

    fun updatePassword(password: String) {
        _unlockState.update {
            it.copy(password = password, errorMessage = "")
        }
    }

    fun unlockWithPassword(fromBiometric: Boolean = false) {
        val password = _unlockState.value.password
        if (password.isEmpty()) {
            _unlockState.update { it.copy(errorMessage = "Password cannot be empty") }
            return
        }

        viewModelScope.launch {
            _unlockState.update { it.copy(isAuthenticating = true) }

            // Machine-originated attempts (biometric-stored password) must not
            // count toward the lockout/wipe counters - they are not brute force -
            // and must never be able to trigger the duress wipe.
            val result = withContext(Dispatchers.IO) {
                secureStorage.verifyPassword(
                    password,
                    recordFailure = !fromBiometric,
                    allowDuress = !fromBiometric
                )
            }

            if (result.isDuress) {
                openDuressSession()
            } else if (result.success) {
                wallet.setSession(result.isDecoy)
                val isDecoy = result.isDecoy
                val loaded = withContext(Dispatchers.IO) {
                    wallet.loadWalletList()
                }

                if (loaded) {
                    val wallets = wallet.wallets.value
                    val shouldAutoOpen = if (isDecoy) {
                        userPreferencesRepository.autoOpenSingleWalletDecoy.value
                    } else {
                        userPreferencesRepository.autoOpenSingleWalletMain.value
                    }
                    val autoOpenRequested = shouldAutoOpen && wallets.size == 1

                    // Clear password from state
                    _unlockState.update { it.copy(password = "", errorMessage = "") }
                    System.gc()

                    _events.emit(AuthEvent.UnlockSuccess(autoOpenRequested))
                } else {
                    _unlockState.update {
                        it.copy(isAuthenticating = false, errorMessage = "Failed to load wallets")
                    }
                }
            } else if (fromBiometric && !result.lockedOut) {
                // The biometric-stored password no longer matches any vault -
                // it is stale (e.g. the password was changed without updating
                // biometric unlock). Disable biometrics rather than letting
                // repeated taps feed the failed-attempt counters.
                withContext(Dispatchers.IO) {
                    biometricPasswordManager.removeBiometricData(_unlockState.value.biometricTarget)
                }
                userPreferencesRepository.setBiometricsEnabled(false)
                userPreferencesRepository.setBiometricTarget(UserPreferencesRepository.BIOMETRIC_TARGET_NONE)
                _unlockState.update {
                    it.copy(
                        isAuthenticating = false,
                        password = "",
                        errorMessage = "Saved biometric credentials are outdated. Biometric unlock has been disabled — please use your password."
                    )
                }
            } else {
                // Check if wipe on failed attempts is enabled
                val wipeEnabled = userPreferencesRepository.wipeOnFailedAttempts.value
                val failedAttempts = secureStorage.getFailedAttemptCount()

                if (!fromBiometric && wipeEnabled && failedAttempts >= 4) {
                    // Wipe all data and navigate to setup
                    withContext(Dispatchers.IO) {
                        secureStorage.wipeAllData()
                    }
                    // The wipe cleared the settings file; drop the stale
                    // in-memory flags too (same as the duress path)
                    userPreferencesRepository.reload()
                    _unlockState.update { it.copy(isAuthenticating = false, password = "", errorMessage = "") }
                    _events.emit(AuthEvent.DataWiped)
                } else {
                    _unlockState.update {
                        it.copy(
                            isAuthenticating = false,
                            password = "",
                            errorMessage = result.errorMessage ?: "Incorrect password"
                        )
                    }
                }
            }
        }
    }

    fun unlockWithBiometrics(cipher: Cipher) {
        viewModelScope.launch {
            val target = _unlockState.value.biometricTarget
            val decryptedPassword = biometricPasswordManager.decryptPassword(target, cipher)
            if (decryptedPassword == null) {
                _unlockState.update {
                    it.copy(errorMessage = "Failed to decrypt password. Please use password to unlock.")
                }
                return@launch
            }

            if (target == UserPreferencesRepository.BIOMETRIC_TARGET_DURESS) {
                // The duress slot holds a random token, not a password: a
                // successful biometric decrypt of it is the trigger.
                openDuressSession()
            } else {
                _unlockState.update { it.copy(password = decryptedPassword) }
                unlockWithPassword(fromBiometric = true)
            }
        }
    }

    /**
     * Duress unlock, by password or by fingerprint: destroy everything, then
     * open the fake session so the screen looks like an ordinary unlock.
     *
     * The session runs in decoy mode (its settings never mention decoy or
     * duress features), with a freshly generated Native SegWit wallet that
     * lives only in RAM (see [SecureStorage.enterDuressSession]). The next
     * lock ends the session, and with no main password left the app then
     * shows first-time setup.
     */
    private suspend fun openDuressSession() {
        _unlockState.update { it.copy(isAuthenticating = true, errorMessage = "") }

        withContext(Dispatchers.IO) {
            secureStorage.enterDuressSession()
        }
        // The wipe cleared the settings file; drop the stale in-memory flags
        // too (biometric target, wipe-on-failed-login, ...).
        userPreferencesRepository.reload()

        wallet.setSession(isDecoy = true)
        withContext(Dispatchers.IO) {
            val mnemonic = wallet.generateMnemonic(DURESS_WALLET_WORDS)
            val result = wallet.createWallet(
                name = DURESS_WALLET_NAME,
                mnemonic = mnemonic,
                derivationPath = DerivationPaths.NATIVE_SEGWIT
            )
            if (result !is WalletCreationResult.Success) {
                AppLog.e(TAG) { "Duress wallet creation failed: $result" }
            }
            wallet.loadWalletList()
        }

        _unlockState.update { it.copy(password = "", errorMessage = "") }
        System.gc()

        _events.emit(AuthEvent.UnlockSuccess(autoOpenRequested = false))
    }

    fun setBiometricError(error: String) {
        _unlockState.update { it.copy(errorMessage = error) }
    }

    fun getDecryptCipher(): Cipher? {
        return try {
            biometricPasswordManager.getDecryptCipher(_unlockState.value.biometricTarget)
        } catch (_: Exception) {
            null
        }
    }

    // ========== Setup Screen Actions ==========

    fun updateSetupPassword(password: String) {
        _setupState.update { it.copy(password = password, errorMessage = "") }
    }

    fun updateConfirmPassword(confirmPassword: String) {
        _setupState.update { it.copy(confirmPassword = confirmPassword, errorMessage = "") }
    }

    fun setupPassword() {
        val state = _setupState.value

        when {
            state.password.length < 8 -> {
                _setupState.update { it.copy(errorMessage = "Password must be at least 8 characters") }
            }
            state.password != state.confirmPassword -> {
                _setupState.update { it.copy(errorMessage = "Passwords do not match") }
            }
            else -> {
                viewModelScope.launch {
                    _setupState.update { it.copy(isProcessing = true) }

                    val success = withContext(Dispatchers.IO) {
                        secureStorage.setMainPassword(state.password)
                    }

                    if (success) {
                        withContext(Dispatchers.IO) {
                            secureStorage.verifyPassword(state.password)
                        }
                        wallet.setSession(isDecoy = false)

                        // Clear passwords from state
                        _setupState.update {
                            it.copy(password = "", confirmPassword = "", isProcessing = false)
                        }

                        _events.emit(AuthEvent.SetupComplete)
                    } else {
                        _setupState.update {
                            it.copy(isProcessing = false, errorMessage = "Failed to save password")
                        }
                    }
                }
            }
        }
    }
}
