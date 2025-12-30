@file:Suppress("DEPRECATION")

package com.gorunjinian.metrovault.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit
import com.gorunjinian.metrovault.data.model.QuickShortcut

/**
 * Repository for user preferences.
 * Marked as @Stable for Compose: identity doesn't change, prevents unnecessary recomposition.
 * 
 * Note: EncryptedSharedPreferences is deprecated but still functional.
 * Migration to DataStore + Tink is planned for a future release.
 */
@Stable
class UserPreferencesRepository(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _themeMode = MutableStateFlow(prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _biometricsEnabled = MutableStateFlow(prefs.getBoolean(KEY_BIOMETRICS_ENABLED, false))
    val biometricsEnabled: StateFlow<Boolean> = _biometricsEnabled.asStateFlow()

    private val _autoOpenSingleWalletMain = MutableStateFlow(prefs.getBoolean(KEY_AUTO_OPEN_SINGLE_WALLET_MAIN, false))
    val autoOpenSingleWalletMain: StateFlow<Boolean> = _autoOpenSingleWalletMain.asStateFlow()

    private val _autoOpenSingleWalletDecoy = MutableStateFlow(prefs.getBoolean(KEY_AUTO_OPEN_SINGLE_WALLET_DECOY, false))
    val autoOpenSingleWalletDecoy: StateFlow<Boolean> = _autoOpenSingleWalletDecoy.asStateFlow()

    private val _biometricTarget = MutableStateFlow(prefs.getString(KEY_BIOMETRIC_TARGET, BIOMETRIC_TARGET_NONE) ?: BIOMETRIC_TARGET_NONE)
    val biometricTarget: StateFlow<String> = _biometricTarget.asStateFlow()

    private val _wipeOnFailedAttempts = MutableStateFlow(prefs.getBoolean(KEY_WIPE_ON_FAILED_ATTEMPTS, false))
    val wipeOnFailedAttempts: StateFlow<Boolean> = _wipeOnFailedAttempts.asStateFlow()

    private val _autoExpandSingleWallet = MutableStateFlow(prefs.getBoolean(KEY_AUTO_EXPAND_SINGLE_WALLET, false))
    val autoExpandSingleWallet: StateFlow<Boolean> = _autoExpandSingleWallet.asStateFlow()

    private val _quickShortcuts = MutableStateFlow(
        QuickShortcut.fromStorageString(prefs.getString(KEY_QUICK_SHORTCUTS, null))
    )
    val quickShortcuts: StateFlow<List<QuickShortcut>> = _quickShortcuts.asStateFlow()

    private val _differentAccountsEnabled = MutableStateFlow(prefs.getBoolean(KEY_DIFFERENT_ACCOUNTS_ENABLED, true))
    val differentAccountsEnabled: StateFlow<Boolean> = _differentAccountsEnabled.asStateFlow()

    private val _bip85Enabled = MutableStateFlow(prefs.getBoolean(KEY_BIP85_ENABLED, true))
    val bip85Enabled: StateFlow<Boolean> = _bip85Enabled.asStateFlow()

    private val _customUnlockTitle = MutableStateFlow(prefs.getBoolean(KEY_CUSTOM_UNLOCK_TITLE, false))
    val customUnlockTitle: StateFlow<Boolean> = _customUnlockTitle.asStateFlow()

    fun setThemeMode(mode: String) {
        if (mode in listOf(THEME_LIGHT, THEME_DARK, THEME_SYSTEM)) {
            prefs.edit { putString(KEY_THEME_MODE, mode) }
            _themeMode.value = mode
        }
    }

    fun setBiometricsEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_BIOMETRICS_ENABLED, enabled) }
        _biometricsEnabled.value = enabled
    }

    fun setBiometricTarget(target: String) {
        if (target in listOf(BIOMETRIC_TARGET_MAIN, BIOMETRIC_TARGET_DECOY, BIOMETRIC_TARGET_NONE)) {
            prefs.edit { putString(KEY_BIOMETRIC_TARGET, target) }
            _biometricTarget.value = target
        }
    }

    fun setAutoOpenSingleWalletMain(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_OPEN_SINGLE_WALLET_MAIN, enabled) }
        _autoOpenSingleWalletMain.value = enabled
    }

    fun setAutoOpenSingleWalletDecoy(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_OPEN_SINGLE_WALLET_DECOY, enabled) }
        _autoOpenSingleWalletDecoy.value = enabled
    }

    fun setWipeOnFailedAttempts(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WIPE_ON_FAILED_ATTEMPTS, enabled) }
        _wipeOnFailedAttempts.value = enabled
    }

    fun setAutoExpandSingleWallet(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_EXPAND_SINGLE_WALLET, enabled) }
        _autoExpandSingleWallet.value = enabled
    }

    fun setDifferentAccountsEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DIFFERENT_ACCOUNTS_ENABLED, enabled) }
        _differentAccountsEnabled.value = enabled
    }

    /**
     * Sets the quick shortcuts for the expanded wallet card.
     * Must be exactly 3 shortcuts.
     */
    fun setQuickShortcuts(shortcuts: List<QuickShortcut>) {
        if (shortcuts.size != 3) return
        prefs.edit { putString(KEY_QUICK_SHORTCUTS, QuickShortcut.toStorageString(shortcuts)) }
        _quickShortcuts.value = shortcuts
    }

    /**
     * Enable or disable BIP-85 derivation feature.
     * When disabled, auto-replaces BIP85 quick shortcut if assigned.
     */
    fun setBip85Enabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_BIP85_ENABLED, enabled) }
        _bip85Enabled.value = enabled
        
        if (!enabled) {
            // Auto-replace BIP85 shortcut if assigned
            val current = _quickShortcuts.value
            if (QuickShortcut.BIP85 in current) {
                val available = QuickShortcut.entries.filter { 
                    it != QuickShortcut.BIP85 && it !in current 
                }
                if (available.isNotEmpty()) {
                    val replacement = available.first()
                    val newList = current.map { 
                        if (it == QuickShortcut.BIP85) replacement else it 
                    }
                    setQuickShortcuts(newList)
                }
            }
        }
    }

    fun setCustomUnlockTitle(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_CUSTOM_UNLOCK_TITLE, enabled) }
        _customUnlockTitle.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "metrovault_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BIOMETRICS_ENABLED = "biometrics_enabled"
        private const val KEY_BIOMETRIC_TARGET = "biometric_target"
        private const val KEY_AUTO_OPEN_SINGLE_WALLET_MAIN = "auto_open_single_wallet_main"
        private const val KEY_AUTO_OPEN_SINGLE_WALLET_DECOY = "auto_open_single_wallet_decoy"
        private const val KEY_WIPE_ON_FAILED_ATTEMPTS = "wipe_on_failed_attempts"
        private const val KEY_AUTO_EXPAND_SINGLE_WALLET = "auto_expand_single_wallet"
        private const val KEY_QUICK_SHORTCUTS = "quick_shortcuts"
        private const val KEY_DIFFERENT_ACCOUNTS_ENABLED = "different_accounts_enabled"
        private const val KEY_BIP85_ENABLED = "bip85_enabled"
        private const val KEY_CUSTOM_UNLOCK_TITLE = "custom_unlock_title"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"
        const val BIOMETRIC_TARGET_MAIN = "main"
        const val BIOMETRIC_TARGET_DECOY = "decoy"
        const val BIOMETRIC_TARGET_NONE = "none"
    }
}
