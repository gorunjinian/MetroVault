package com.gorunjinian.metrovault.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.crypto.BiometricAuthManager
import com.gorunjinian.metrovault.core.crypto.BiometricPasswordManager
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.components.SettingsInfoCard
import com.gorunjinian.metrovault.core.ui.components.SettingsItem
import com.gorunjinian.metrovault.core.ui.dialogs.AddDecoyPasswordDialog
import com.gorunjinian.metrovault.core.ui.dialogs.BiometricSetupDialog
import com.gorunjinian.metrovault.core.ui.dialogs.ChangePasswordDialog
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet

@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    userPreferencesRepository: UserPreferencesRepository,
    activity: FragmentActivity?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val biometricManager = remember(context) { BiometricAuthManager(context) }
    val biometricPasswordManager = remember(context) { BiometricPasswordManager(context) }
    val scope = rememberCoroutineScope()

    val biometricsEnabled by userPreferencesRepository.biometricsEnabled.collectAsState()
    val biometricTarget by userPreferencesRepository.biometricTarget.collectAsState()
    val wipeOnFailedAttempts by userPreferencesRepository.wipeOnFailedAttempts.collectAsState()
    val tapToCopyEnabled by userPreferencesRepository.tapToCopyEnabled.collectAsState()

    var showBiometricSetupDialog by remember { mutableStateOf(false) }
    var showBiometricPasswordDialog by remember { mutableStateOf(false) }
    var selectedBiometricTarget by remember { mutableStateOf(UserPreferencesRepository.BIOMETRIC_TARGET_NONE) }
    var showDecoyDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showChangeDecoyPasswordDialog by remember { mutableStateOf(false) }
    var isChangingPassword by remember { mutableStateOf(false) }
    var isChangingDecoyPassword by remember { mutableStateOf(false) }
    var showWipeConfirmDialog by remember { mutableStateOf(false) }

    var hasDecoyPassword by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasDecoyPassword = withContext(kotlinx.coroutines.Dispatchers.IO) {
            secureStorage.hasDecoyPassword()
        }
        
        // Check if biometric key is still valid (may have been invalidated by new fingerprint enrollment)
        if (biometricsEnabled) {
            val isDecoy = biometricTarget == UserPreferencesRepository.BIOMETRIC_TARGET_DECOY
            val keyValid = withContext(kotlinx.coroutines.Dispatchers.IO) {
                biometricPasswordManager.isKeyValid(isDecoy)
            }
            
            if (!keyValid) {
                // Key was invalidated (e.g., new fingerprint enrolled)
                // Automatically disable biometrics and clean up
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    biometricPasswordManager.deleteKey(isDecoy)
                    biometricPasswordManager.removeBiometricData(isDecoy)
                }
                userPreferencesRepository.setBiometricsEnabled(false)
                userPreferencesRepository.setBiometricTarget(UserPreferencesRepository.BIOMETRIC_TARGET_NONE)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Change Password
            if (wallet.isDecoyMode) {
                SettingsItem(
                    icon = R.drawable.ic_lock,
                    title = "Change Password",
                    description = "Update your access password",
                    onClick = { showChangeDecoyPasswordDialog = true }
                )
            } else {
                SettingsItem(
                    icon = R.drawable.ic_lock,
                    title = "Change Main Password",
                    description = "Update your primary access password",
                    onClick = { showChangePasswordDialog = true }
                )
            }

            // Biometrics Toggle
            val isBiometricEnabledForCurrentVault = if (wallet.isDecoyMode) {
                biometricsEnabled && biometricTarget == UserPreferencesRepository.BIOMETRIC_TARGET_DECOY
            } else {
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
                        if (enabled) {
                            val available = biometricManager.isBiometricAvailable()
                            if (available) {
                                if (wallet.isDecoyMode) {
                                    selectedBiometricTarget = UserPreferencesRepository.BIOMETRIC_TARGET_DECOY
                                    showBiometricPasswordDialog = true
                                } else {
                                    showBiometricSetupDialog = true
                                }
                            } else {
                                val message = biometricManager.getBiometricStatusMessage()
                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                            }
                        } else {
                            if (wallet.isDecoyMode) {
                                biometricPasswordManager.removeBiometricData(isDecoy = true)
                                if (biometricTarget == UserPreferencesRepository.BIOMETRIC_TARGET_DECOY) {
                                    userPreferencesRepository.setBiometricsEnabled(false)
                                    userPreferencesRepository.setBiometricTarget(UserPreferencesRepository.BIOMETRIC_TARGET_NONE)
                                }
                            } else {
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

            // Tap to Copy Toggle
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
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "Enable Tap to Copy",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Tap QR codes to copy content",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = tapToCopyEnabled,
                    onCheckedChange = { enabled ->
                        userPreferencesRepository.setTapToCopyEnabled(enabled)
                    }
                )
            }

            // Wipe on Failed Attempts Toggle
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
                            text = "Delete all data after 4 wrong passwords",
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

            // Info Card after content
            SettingsInfoCard(
                icon = R.drawable.ic_shield_lock,
                title = "Security Settings",
                description = "Manage access controls and authentication methods to protect your wallets. Configure passwords, biometric unlock, and advanced security features like decoy passwords for plausible deniability."
            )
        }
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

    // Decoy password dialog
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

    // Change main password dialog
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
                        showChangePasswordDialog = false
                        isChangingPassword = false

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
                        android.widget.Toast.makeText(context, "Incorrect password", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // Change decoy password dialog
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
                        showChangeDecoyPasswordDialog = false
                        isChangingDecoyPassword = false

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
                        android.widget.Toast.makeText(context, "Incorrect password", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // Biometric setup dialog
    if (showBiometricSetupDialog) {
        BiometricSetupDialog(
            onDismiss = { showBiometricSetupDialog = false },
            onConfirm = { targetMode ->
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
                    val isValidPassword = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        if (isDecoy) {
                            secureStorage.isDecoyPassword(enteredPassword)
                        } else {
                            secureStorage.verifyPasswordSimple(enteredPassword) && !secureStorage.isDecoyPassword(enteredPassword)
                        }
                    }

                    if (isValidPassword) {
                        if (activity != null) {
                            val cipher = biometricPasswordManager.getEncryptCipher(isDecoy)

                            biometricManager.authenticateWithCrypto(
                                activity = activity,
                                cipher = cipher,
                                title = "Enable Biometric Unlock",
                                subtitle = "Authenticate to enable fingerprint unlock",
                                onSuccess = { cryptoObject ->
                                    scope.launch {
                                        val success = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            biometricPasswordManager.storeEncryptedPassword(
                                                enteredPassword,
                                                isDecoy,
                                                cryptoObject.cipher!!
                                            )
                                        }

                                        if (success) {
                                            userPreferencesRepository.setBiometricsEnabled(true)
                                            userPreferencesRepository.setBiometricTarget(targetMode)

                                            val successMsg = if (wallet.isDecoyMode) {
                                                "Biometric unlock enabled"
                                            } else {
                                                "Biometric unlock enabled for $targetName vault"
                                            }
                                            android.widget.Toast.makeText(context, successMsg, android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(context, "Failed to store encrypted password", android.widget.Toast.LENGTH_SHORT).show()
                                        }

                                        showBiometricPasswordDialog = false
                                        selectedBiometricTarget = UserPreferencesRepository.BIOMETRIC_TARGET_NONE
                                    }
                                },
                                onError = { error ->
                                    android.widget.Toast.makeText(context, "Failed to enable biometric: $error", android.widget.Toast.LENGTH_SHORT).show()
                                    showBiometricPasswordDialog = false
                                    selectedBiometricTarget = UserPreferencesRepository.BIOMETRIC_TARGET_NONE
                                }
                            )
                        } else {
                            android.widget.Toast.makeText(context, "Cannot access activity for biometric authentication", android.widget.Toast.LENGTH_SHORT).show()
                            showBiometricPasswordDialog = false
                            selectedBiometricTarget = UserPreferencesRepository.BIOMETRIC_TARGET_NONE
                        }
                    } else {
                        val errorMsg = if (wallet.isDecoyMode) {
                            "Incorrect password"
                        } else {
                            "Incorrect $targetName password"
                        }
                        android.widget.Toast.makeText(context, errorMsg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}
