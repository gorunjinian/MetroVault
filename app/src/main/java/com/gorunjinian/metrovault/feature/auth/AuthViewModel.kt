package com.gorunjinian.metrovault.feature.auth

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorunjinian.metrovault.core.crypto.BiometricPasswordManager
import com.gorunjinian.metrovault.core.storage.SecureStorage
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
                val hasBioPwd = when (target) {
                    UserPreferencesRepository.BIOMETRIC_TARGET_MAIN -> 
                        biometricPasswordManager.hasEncodedPassword(false)
                    UserPreferencesRepository.BIOMETRIC_TARGET_DECOY -> 
                        biometricPasswordManager.hasEncodedPassword(true)
                    else -> false
                }
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

    fun unlockWithPassword() {
        val password = _unlockState.value.password
        if (password.isEmpty()) {
            _unlockState.update { it.copy(errorMessage = "Password cannot be empty") }
            return
        }

        viewModelScope.launch {
            _unlockState.update { it.copy(isAuthenticating = true) }

            val (isValid, error) = withContext(Dispatchers.IO) {
                secureStorage.verifyPassword(password)
            }

            if (isValid) {
                val isDecoy = withContext(Dispatchers.IO) {
                    secureStorage.isDecoyPassword(password)
                }

                wallet.setSession(isDecoy)
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
            } else {
                // Check if wipe on failed attempts is enabled
                val wipeEnabled = userPreferencesRepository.wipeOnFailedAttempts.value
                val failedAttempts = secureStorage.getFailedAttemptCount()
                
                if (wipeEnabled && failedAttempts >= 3) {
                    // Wipe all data and navigate to setup
                    withContext(Dispatchers.IO) {
                        secureStorage.wipeAllData()
                    }
                    _unlockState.update { it.copy(isAuthenticating = false, password = "", errorMessage = "") }
                    _events.emit(AuthEvent.DataWiped)
                } else {
                    _unlockState.update { 
                        it.copy(
                            isAuthenticating = false, 
                            password = "",
                            errorMessage = error ?: "Incorrect password"
                        ) 
                    }
                }
            }
        }
    }

    fun unlockWithBiometrics(cipher: Cipher) {
        viewModelScope.launch {
            val useDecoy = _unlockState.value.biometricTarget == 
                UserPreferencesRepository.BIOMETRIC_TARGET_DECOY

            val decryptedPassword = biometricPasswordManager.decryptPassword(useDecoy, cipher)

            if (decryptedPassword != null) {
                _unlockState.update { it.copy(password = decryptedPassword) }
                unlockWithPassword()
            } else {
                _unlockState.update { 
                    it.copy(errorMessage = "Failed to decrypt password. Please use password to unlock.") 
                }
            }
        }
    }

    fun setBiometricError(error: String) {
        _unlockState.update { it.copy(errorMessage = error) }
    }

    fun getDecryptCipher(): Cipher? {
        val useDecoy = _unlockState.value.biometricTarget == 
            UserPreferencesRepository.BIOMETRIC_TARGET_DECOY
        return try {
            biometricPasswordManager.getDecryptCipher(useDecoy)
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
