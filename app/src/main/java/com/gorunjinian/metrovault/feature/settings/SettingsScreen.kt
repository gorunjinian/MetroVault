package com.gorunjinian.metrovault.feature.settings

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.crypto.BiometricAuthManager
import com.gorunjinian.metrovault.core.crypto.BiometricPasswordManager
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.core.ui.components.*
import com.gorunjinian.metrovault.core.ui.dialogs.*
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalView

private fun Context.findActivity(): FragmentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is FragmentActivity) return context
        context = context.baseContext
    }
    return null
}

@Composable
fun getActivity(): FragmentActivity? {
    val view = LocalView.current
    return view.context.findActivity()
}

@Composable
fun SettingsContent(
    wallet: Wallet,
    secureStorage: SecureStorage,
    userPreferencesRepository: UserPreferencesRepository,
    activity: FragmentActivity?,
    onCompleteMnemonic: () -> Unit
) {
    // Use remember with stable keys to prevent recreation during pager animations
    val context = androidx.compose.ui.platform.LocalContext.current
    val biometricManager = remember(context) { BiometricAuthManager(context) }
    val biometricPasswordManager = remember(context) { BiometricPasswordManager(context) }
    val scope = rememberCoroutineScope()

    // Collect states - these are already optimized internally by Compose
    val themeMode by userPreferencesRepository.themeMode.collectAsState()
    val biometricsEnabled by userPreferencesRepository.biometricsEnabled.collectAsState()
    val biometricTarget by userPreferencesRepository.biometricTarget.collectAsState()
    val autoOpenSingleWalletMain by userPreferencesRepository.autoOpenSingleWalletMain.collectAsState()
    val autoOpenSingleWalletDecoy by userPreferencesRepository.autoOpenSingleWalletDecoy.collectAsState()

    // Dialog states grouped together
    var showBiometricSetupDialog by remember { mutableStateOf(false) }
    var showBiometricPasswordDialog by remember { mutableStateOf(false) }
    var selectedBiometricTarget by remember { mutableStateOf(UserPreferencesRepository.BIOMETRIC_TARGET_NONE) }
    var showDecoyDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showChangeDecoyPasswordDialog by remember { mutableStateOf(false) }
    var showDeleteAllWalletsDialog by remember { mutableStateOf(false) }
    var isChangingPassword by remember { mutableStateOf(false) }
    var isChangingDecoyPassword by remember { mutableStateOf(false) }
    
    // (hasDecoyPassword triggers lazy init of EncryptedSharedPreferences which is expensive)
    var hasDecoyPassword by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        hasDecoyPassword = withContext(kotlinx.coroutines.Dispatchers.IO) {
            secureStorage.hasDecoyPassword()
        }
    }

    // Disable vertical stretch overscroll for smoother scrolling
    CompositionLocalProvider(LocalOverscrollFactory provides null) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
        // Appearance Section
        item {
            SettingsSection(title = "Appearance") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Theme",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeOption(
                            text = "Light",
                            selected = themeMode == UserPreferencesRepository.THEME_LIGHT,
                            onClick = { userPreferencesRepository.setThemeMode(UserPreferencesRepository.THEME_LIGHT) },
                            modifier = Modifier.weight(1f)
                        )
                        ThemeOption(
                            text = "Dark",
                            selected = themeMode == UserPreferencesRepository.THEME_DARK,
                            onClick = { userPreferencesRepository.setThemeMode(UserPreferencesRepository.THEME_DARK) },
                            modifier = Modifier.weight(1f)
                        )
                        ThemeOption(
                            text = "System",
                            selected = themeMode == UserPreferencesRepository.THEME_SYSTEM,
                            onClick = { userPreferencesRepository.setThemeMode(UserPreferencesRepository.THEME_SYSTEM) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Security Section
        item {
            SettingsSection(title = "Security") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Change Password - context-aware based on mode
                    if (wallet.isDecoyMode) {
                        // In Decoy mode: neutral wording, changes decoy password
                        SettingsItem(
                            icon = R.drawable.ic_lock,
                            title = "Change Password",
                            description = "Update your access password",
                            onClick = { showChangeDecoyPasswordDialog = true }
                        )
                    } else {
                        // In Main mode: explicit main password option
                        SettingsItem(
                            icon = R.drawable.ic_lock,
                            title = "Change Main Password",
                            description = "Update your primary access password",
                            onClick = { showChangePasswordDialog = true }
                        )
                    }

                    // Biometrics Toggle
                    // Security model:
                    // - In main mode: Shows global state, can set for either vault
                    // - In decoy mode: Only shows/controls biometrics if it's configured for decoy vault
                    //   Never reveals or affects main vault biometric settings
                    val isBiometricEnabledForCurrentVault = if (wallet.isDecoyMode) {
                        // In decoy mode, only consider biometrics "enabled" if it's set for DECOY vault
                        biometricsEnabled && biometricTarget == UserPreferencesRepository.BIOMETRIC_TARGET_DECOY
                    } else {
                        // In main mode, show global state
                        biometricsEnabled
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fingerprint),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "Biometric Unlock",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (biometricsEnabled && !wallet.isDecoyMode) {
                                    // Only show target info in main mode
                                    Text(
                                        text = "Unlocks: ${if (biometricTarget == UserPreferencesRepository.BIOMETRIC_TARGET_MAIN) "Main Wallets" else "Decoy Wallets"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = "Use fingerprint or face unlock",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Switch(
                            checked = isBiometricEnabledForCurrentVault,
                            onCheckedChange = { enabled ->
                                android.util.Log.d("BiometricToggle", "Toggle changed to: $enabled, isDecoyMode: ${wallet.isDecoyMode}")
                                if (enabled) {
                                    val available = biometricManager.isBiometricAvailable()
                                    if (available) {
                                        if (wallet.isDecoyMode) {
                                            // In Decoy mode, automatically select Decoy target without asking
                                            selectedBiometricTarget = UserPreferencesRepository.BIOMETRIC_TARGET_DECOY
                                            showBiometricPasswordDialog = true
                                        } else {
                                            showBiometricSetupDialog = true
                                        }
                                    } else {
                                        val message = biometricManager.getBiometricStatusMessage()
                                        android.widget.Toast.makeText(
                                            context,
                                            message,
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } else {
                                    // Disable biometrics - only affect the current vault
                                    if (wallet.isDecoyMode) {
                                        // In decoy mode: only clear decoy biometric data
                                        // Never touch main vault settings
                                        biometricPasswordManager.removeBiometricData(isDecoy = true)
                                        // Only update global settings if decoy was the target
                                        if (biometricTarget == UserPreferencesRepository.BIOMETRIC_TARGET_DECOY) {
                                            userPreferencesRepository.setBiometricsEnabled(false)
                                            userPreferencesRepository.setBiometricTarget(UserPreferencesRepository.BIOMETRIC_TARGET_NONE)
                                        }
                                    } else {
                                        // In main mode: clear the currently targeted vault's data
                                        when (biometricTarget) {
                                            UserPreferencesRepository.BIOMETRIC_TARGET_MAIN -> {
                                                biometricPasswordManager.removeBiometricData(isDecoy = false)
                                            }
                                            UserPreferencesRepository.BIOMETRIC_TARGET_DECOY -> {
                                                biometricPasswordManager.removeBiometricData(isDecoy = true)
                                            }
                                        }
                                        userPreferencesRepository.setBiometricsEnabled(false)
                                        userPreferencesRepository.setBiometricTarget(UserPreferencesRepository.BIOMETRIC_TARGET_NONE)
                                    }
                                }
                            }
                        )
                    }

                    // Decoy Password - Only visible in Main Mode
                    if (!wallet.isDecoyMode) {
                        if (hasDecoyPassword) {
                            SettingsItem(
                                icon = R.drawable.ic_decoy,
                                title = "Change Decoy Password",
                                description = "Update your secondary password",
                                onClick = { showChangeDecoyPasswordDialog = true }
                            )
                        } else {
                            SettingsItem(
                                icon = R.drawable.ic_decoy,
                                title = "Add Decoy Password",
                                description = "Set a secondary password for plausible deniability",
                                onClick = { showDecoyDialog = true }
                            )
                        }
                    }

                    // Wipe on Failed Attempts Toggle
                    val wipeOnFailedAttempts by userPreferencesRepository.wipeOnFailedAttempts.collectAsState()
                    var showWipeConfirmDialog by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete_forever),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "Wipe Data on Failed Login",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Delete all data after 3 wrong passwords",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = wipeOnFailedAttempts,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    showWipeConfirmDialog = true
                                } else {
                                    userPreferencesRepository.setWipeOnFailedAttempts(false)
                                }
                            }
                        )
                    }

                    // Wipe confirmation dialog
                    if (showWipeConfirmDialog) {
                        AlertDialog(
                            onDismissRequest = { showWipeConfirmDialog = false },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            title = { Text("Enable Data Wipe?") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "This is a destructive security feature.",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Text("If someone enters the wrong password 3 times in a row, ALL app data will be permanently deleted:")
                                    Text("• All wallets")
                                    Text("• All passwords")
                                    Text("• All settings")
                                    Text(
                                        "This cannot be undone. Make sure you have backups of your seed phrases!",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        userPreferencesRepository.setWipeOnFailedAttempts(true)
                                        showWipeConfirmDialog = false
                                    }
                                ) {
                                    Text("Enable", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showWipeConfirmDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }
            }
        }

        // Advanced Section
        item {
            SettingsSection(title = "Advanced") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Auto-open single wallet (context-aware based on password mode)
                    val autoOpenEnabled = if (wallet.isDecoyMode) autoOpenSingleWalletDecoy else autoOpenSingleWalletMain

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_wallet),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "Auto-open Single Wallet",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Open wallet automatically if only 1 exists",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = autoOpenEnabled,
                            onCheckedChange = { enabled ->
                                if (wallet.isDecoyMode) {
                                    userPreferencesRepository.setAutoOpenSingleWalletDecoy(enabled)
                                } else {
                                    userPreferencesRepository.setAutoOpenSingleWalletMain(enabled)
                                }
                            }
                        )
                    }

                    // Auto-expand single wallet card
                    val autoExpandEnabled by userPreferencesRepository.autoExpandSingleWallet.collectAsState()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_expand),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "Auto-expand Single Wallet",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Expand wallet card if only 1 exists",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = autoExpandEnabled,
                            onCheckedChange = { enabled ->
                                userPreferencesRepository.setAutoExpandSingleWallet(enabled)
                            }
                        )
                    }

                    // Complete Mnemonic
                    SettingsItem(
                        icon = R.drawable.ic_format_list_numbered,
                        title = "Complete Mnemonic",
                        description = "Calculate the last word (checksum)",
                        onClick = onCompleteMnemonic
                    )

                    // Delete All Wallets
                    SettingsItem(
                        icon = R.drawable.ic_delete,
                        title = "Delete All Wallets",
                        description = "Remove all wallets from the vault",
                        onClick = { showDeleteAllWalletsDialog = true },
                        iconTint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        }
    }

    if (showDecoyDialog) {
        AddDecoyPasswordDialog(
            onDismiss = { showDecoyDialog = false },
            onConfirm = { password ->
                scope.launch {
                    val success = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        secureStorage.setDecoyPassword(password)
                    }
                    if (success) {
                        hasDecoyPassword = true
                        showDecoyDialog = false
                    }
                }
            }
        )
    }

    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            title = "Change Main Password",
            isLoading = isChangingPassword,
            onDismiss = { showChangePasswordDialog = false },
            onConfirm = { old, new ->
                scope.launch {
                    isChangingPassword = true
                    val success = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        secureStorage.changeMainPassword(old, new)
                    }
                    if (success) {
                        // Session is already re-initialized in SecureStorage.changeMainPassword()
                        showChangePasswordDialog = false
                        isChangingPassword = false

                        // If biometric was enabled for main vault, update the stored password
                        if (biometricsEnabled && biometricTarget == UserPreferencesRepository.BIOMETRIC_TARGET_MAIN && activity != null) {
                            val cipher = biometricPasswordManager.getEncryptCipher(isDecoy = false)
                            biometricManager.authenticateWithCrypto(
                                activity = activity,
                                cipher = cipher,
                                title = "Update Biometric Unlock",
                                subtitle = "Authenticate to update fingerprint unlock with new password",
                                onSuccess = { cryptoObject ->
                                    scope.launch {
                                        val updated = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            biometricPasswordManager.storeEncryptedPassword(
                                                new,
                                                isDecoy = false,
                                                cryptoObject.cipher!!
                                            )
                                        }
                                        if (updated) {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Biometric unlock updated",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                onError = { _ ->
                                    // User cancelled or error - disable biometrics
                                    biometricPasswordManager.removeBiometricData(isDecoy = false)
                                    userPreferencesRepository.setBiometricsEnabled(false)
                                    userPreferencesRepository.setBiometricTarget(UserPreferencesRepository.BIOMETRIC_TARGET_NONE)
                                    android.widget.Toast.makeText(
                                        context,
                                        "Biometric unlock disabled. Re-enable in settings.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            )
                        }
                    } else {
                        isChangingPassword = false
                        android.widget.Toast.makeText(
                            context,
                            "Incorrect password",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    if (showChangeDecoyPasswordDialog) {
        ChangePasswordDialog(
            title = if (wallet.isDecoyMode) "Change Password" else "Change Decoy Password",
            isLoading = isChangingDecoyPassword,
            onDismiss = { showChangeDecoyPasswordDialog = false },
            onConfirm = { old, new ->
                scope.launch {
                    isChangingDecoyPassword = true
                    val success = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        secureStorage.changeDecoyPassword(old, new)
                    }
                    if (success) {
                        // Session is already re-initialized in SecureStorage.changeDecoyPassword()
                        showChangeDecoyPasswordDialog = false
                        isChangingDecoyPassword = false

                        // If biometric was enabled for decoy vault, update the stored password
                        if (biometricsEnabled && biometricTarget == UserPreferencesRepository.BIOMETRIC_TARGET_DECOY && activity != null) {
                            val cipher = biometricPasswordManager.getEncryptCipher(isDecoy = true)
                            biometricManager.authenticateWithCrypto(
                                activity = activity,
                                cipher = cipher,
                                title = "Update Biometric Unlock",
                                subtitle = "Authenticate to update fingerprint unlock with new password",
                                onSuccess = { cryptoObject ->
                                    scope.launch {
                                        val updated = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            biometricPasswordManager.storeEncryptedPassword(
                                                new,
                                                isDecoy = true,
                                                cryptoObject.cipher!!
                                            )
                                        }
                                        if (updated) {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Biometric unlock updated",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                onError = { _ ->
                                    // User cancelled or error - disable biometrics
                                    biometricPasswordManager.removeBiometricData(isDecoy = true)
                                    userPreferencesRepository.setBiometricsEnabled(false)
                                    userPreferencesRepository.setBiometricTarget(UserPreferencesRepository.BIOMETRIC_TARGET_NONE)
                                    android.widget.Toast.makeText(
                                        context,
                                        "Biometric unlock disabled. Re-enable in settings.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            )
                        }
                    } else {
                        isChangingDecoyPassword = false
                        android.widget.Toast.makeText(
                            context,
                            "Incorrect password",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    // Delete All Wallets confirmation dialog
    if (showDeleteAllWalletsDialog) {
        var password by remember { mutableStateOf("") }
        var passwordError by remember { mutableStateOf("") }
        var isDeleting by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { 
                if (!isDeleting) {
                    showDeleteAllWalletsDialog = false 
                    passwordError = ""
                }
            },
            title = { Text("Delete All Wallets") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "⚠️ This will permanently delete all wallets from this vault. This action cannot be undone.",
                        color = MaterialTheme.colorScheme.error
                    )
                    Text("Enter your password to confirm:")
                    SecureOutlinedTextField(
                        value = password,
                        onValueChange = { 
                            password = it
                            passwordError = ""
                        },
                        label = { Text("Password") },
                        singleLine = true,
                        isPasswordField = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError = passwordError.isNotEmpty(),
                        enabled = !isDeleting,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (passwordError.isNotEmpty()) {
                        Text(
                            text = passwordError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val isDecoy = wallet.isDecoyMode
                        val isValidPassword = if (isDecoy) {
                            secureStorage.isDecoyPassword(password)
                        } else {
                            secureStorage.verifyPasswordSimple(password) && !secureStorage.isDecoyPassword(password)
                        }

                        if (isValidPassword) {
                            isDeleting = true
                            scope.launch {
                                // Get all wallet IDs and delete each one
                                val walletList = wallet.wallets.value
                                var allDeleted = true
                                for (w in walletList) {
                                    val deleted = wallet.deleteWallet(w.id)
                                    if (!deleted) {
                                        allDeleted = false
                                        break
                                    }
                                }
                                isDeleting = false
                                if (allDeleted) {
                                    showDeleteAllWalletsDialog = false
                                    android.widget.Toast.makeText(
                                        context,
                                        "All wallets deleted",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    passwordError = "Failed to delete all wallets"
                                }
                            }
                        } else {
                            passwordError = "Incorrect password"
                        }
                    },
                    enabled = password.isNotEmpty() && !isDeleting
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Delete All", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        @Suppress("AssignedValueIsNeverRead")
                        showDeleteAllWalletsDialog = false 
                        passwordError = ""
                    },
                    enabled = !isDeleting
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Biometric setup dialog
    if (showBiometricSetupDialog) {
        BiometricSetupDialog(
            onDismiss = {
                android.util.Log.d("BiometricSetup", "Dialog dismissed")
                showBiometricSetupDialog = false
            },
            onConfirm = { targetMode ->
                android.util.Log.d("BiometricSetup", "Dialog confirmed with targetMode: $targetMode")
                selectedBiometricTarget = targetMode
                showBiometricSetupDialog = false
                showBiometricPasswordDialog = true
            },
            hasDecoyPassword = secureStorage.hasDecoyPassword()
        )
    }

    // Password prompt for biometric setup
    if (showBiometricPasswordDialog) {
        val targetMode = selectedBiometricTarget
        val isDecoy = targetMode == UserPreferencesRepository.BIOMETRIC_TARGET_DECOY

        // Use neutral wording in decoy mode to avoid information leaks
        val targetName = if (isDecoy) "Decoy" else "Main"
        val dialogTitle = if (wallet.isDecoyMode) "Enter Password" else "Enter $targetName Password"
        val dialogMessage = if (wallet.isDecoyMode) {
            "Enter your password to enable biometric unlock"
        } else {
            "Enter the password for the vault you want to unlock with biometric authentication"
        }

        BiometricPasswordDialog(
            title = dialogTitle,
            message = dialogMessage,
            onDismiss = {
                showBiometricPasswordDialog = false
                selectedBiometricTarget = UserPreferencesRepository.BIOMETRIC_TARGET_NONE
            },
            onConfirm = { enteredPassword ->
                scope.launch {
                    // Verify the password matches the target vault
                    val isValidPassword = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        if (isDecoy) {
                            secureStorage.isDecoyPassword(enteredPassword)
                        } else {
                            secureStorage.verifyPasswordSimple(enteredPassword) && !secureStorage.isDecoyPassword(enteredPassword)
                        }
                    }

                    if (isValidPassword) {
                        // Password is valid for target vault, now authenticate with biometric
                        if (activity != null) {
                            // Get encryption cipher from BiometricPasswordManager
                            val cipher = biometricPasswordManager.getEncryptCipher(isDecoy)

                            biometricManager.authenticateWithCrypto(
                                activity = activity,
                                cipher = cipher,
                                title = "Enable Biometric Unlock",
                                subtitle = "Authenticate to enable fingerprint unlock",
                                onSuccess = { cryptoObject ->
                                    scope.launch {
                                        // Store the password for biometric unlock using the unlocked cipher
                                        val success = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            biometricPasswordManager.storeEncryptedPassword(
                                                enteredPassword,
                                                isDecoy,
                                                cryptoObject.cipher!!
                                            )
                                        }

                                        if (success) {
                                            // Update settings
                                            userPreferencesRepository.setBiometricsEnabled(true)
                                            userPreferencesRepository.setBiometricTarget(targetMode)

                                            // Use neutral message in decoy mode
                                            val successMsg = if (wallet.isDecoyMode) {
                                                "Biometric unlock enabled"
                                            } else {
                                                "Biometric unlock enabled for $targetName vault"
                                            }
                                            android.widget.Toast.makeText(
                                                context,
                                                successMsg,
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Failed to store encrypted password",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }

                                        showBiometricPasswordDialog = false
                                        selectedBiometricTarget = UserPreferencesRepository.BIOMETRIC_TARGET_NONE
                                    }
                                },
                                onError = { error ->
                                    android.widget.Toast.makeText(
                                        context,
                                        "Failed to enable biometric: $error",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    showBiometricPasswordDialog = false
                                    selectedBiometricTarget = UserPreferencesRepository.BIOMETRIC_TARGET_NONE
                                }
                            )
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                "Cannot access activity for biometric authentication",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            showBiometricPasswordDialog = false
                            selectedBiometricTarget = UserPreferencesRepository.BIOMETRIC_TARGET_NONE
                        }
                    } else {
                        // Use neutral error message in decoy mode
                        val errorMsg = if (wallet.isDecoyMode) {
                            "Incorrect password"
                        } else {
                            "Incorrect $targetName password"
                        }
                        android.widget.Toast.makeText(
                            context,
                            errorMsg,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }
}

@Composable
fun BiometricPasswordDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                SecureOutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                    isPasswordField = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty()
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
