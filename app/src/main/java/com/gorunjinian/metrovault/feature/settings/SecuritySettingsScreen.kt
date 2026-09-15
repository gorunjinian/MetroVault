package com.gorunjinian.metrovault.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import android.util.Base64
import java.security.SecureRandom
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gorunjinian.metrovault.core.crypto.BiometricAuthManager
import com.gorunjinian.metrovault.core.crypto.BiometricPasswordManager
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.components.SettingsInfoCard
import com.gorunjinian.metrovault.core.ui.components.SettingsItem
import com.gorunjinian.metrovault.core.ui.dialogs.AddDecoyPasswordDialog
import com.gorunjinian.metrovault.core.ui.dialogs.BiometricSetupDialog
import com.gorunjinian.metrovault.core.ui.dialogs.ChangePasswordDialog
import com.gorunjinian.metrovault.core.ui.dialogs.SetDuressPasswordDialog
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet

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
    var showDuressBiometricConfirmDialog by remember { mutableStateOf(false) }
    var selectedBiometricTarget by remember { mutableStateOf(UserPreferencesRepository.BIOMETRIC_TARGET_NONE) }
    var showDecoyDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showChangeDecoyPasswordDialog by remember { mutableStateOf(false) }
    var isChangingPassword by remember { mutableStateOf(false) }
    var isChangingDecoyPassword by remember { mutableStateOf(false) }
    var showWipeConfirmDialog by remember { mutableStateOf(false) }
    var isSavingDecoyPassword by remember { mutableStateOf(false) }
    var decoyPasswordError by remember { mutableStateOf("") }
    var showDuressDialog by remember { mutableStateOf(false) }
    var showRemoveDuressDialog by remember { mutableStateOf(false) }
    var isSavingDuressPassword by remember { mutableStateOf(false) }
    var duressPasswordError by remember { mutableStateOf("") }

    var hasDecoyPassword by remember { mutableStateOf(false) }
    var hasDuressPassword by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (decoySet, duressSet) = withContext(kotlinx.coroutines.Dispatchers.IO) {
            secureStorage.hasDecoyPassword() to secureStorage.hasDuressPassword()
        }
        hasDecoyPassword = decoySet
        hasDuressPassword = duressSet

        // Check if biometric key is still valid (may have been invalidated by new fingerprint enrollment)
        if (biometricsEnabled) {
            val target = biometricTarget
            val keyValid = withContext(kotlinx.coroutines.Dispatchers.IO) {
                biometricPasswordManager.isKeyValid(target)
            }
            
            if (!keyValid) {
                // Key was invalidated (e.g., new fingerprint enrolled)
                // Automatically disable biometrics and clean up
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    biometricPasswordManager.deleteKey(target)
                    biometricPasswordManager.removeBiometricData(target)
                }
                userPreferencesRepository.setBiometricsEnabled(false)
                userPreferencesRepository.setBiometricTarget(UserPreferencesRepository.BIOMETRIC_TARGET_NONE)
            }
        }
    }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Security",
                onBack = onBack
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
                                text = when (biometricTarget) {
                                    UserPreferencesRepository.BIOMETRIC_TARGET_MAIN -> "Unlocks: Main Wallets"
                                    UserPreferencesRepository.BIOMETRIC_TARGET_DECOY -> "Unlocks: Decoy Wallets"
                                    UserPreferencesRepository.BIOMETRIC_TARGET_DURESS -> "Duress Wipe: erases all data"
                                    else -> "Use fingerprint or face unlock"
                                },
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
                                biometricPasswordManager.removeBiometricData(UserPreferencesRepository.BIOMETRIC_TARGET_DECOY)
                                if (biometricTarget == UserPreferencesRepository.BIOMETRIC_TARGET_DECOY) {
                                    userPreferencesRepository.setBiometricsEnabled(false)
                                    userPreferencesRepository.setBiometricTarget(UserPreferencesRepository.BIOMETRIC_TARGET_NONE)
                                }
                            } else {
                                biometricPasswordManager.removeBiometricData(biometricTarget)
                                userPreferencesRepository.setBiometricsEnabled(false)
                                userPreferencesRepository.setBiometricTarget(UserPreferencesRepository.BIOMETRIC_TARGET_NONE)
                            }
                        }
                    }
                )
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

            // Duress Password - Only visible in Main Mode. Destructive, so it
            // sits next to the wipe-on-failed-login row and shares its error tint.
            if (!wallet.isDecoyMode) {
                if (hasDuressPassword) {
                    SettingsItem(
                        icon = R.drawable.ic_security,
                        title = "Change Duress Password",
                        description = "Update the password that wipes all data",
                        onClick = {
                            duressPasswordError = ""
                            showDuressDialog = true
                        },
                        iconTint = MaterialTheme.colorScheme.error
                    )
                    SettingsItem(
                        icon = R.drawable.ic_security,
                        title = "Remove Duress Password",
                        description = "Entering it will no longer wipe your data",
                        onClick = { showRemoveDuressDialog = true },
                        iconTint = MaterialTheme.colorScheme.error
                    )
                } else {
                    SettingsItem(
                        icon = R.drawable.ic_security,
                        title = "Add Duress Password",
                        description = "Wipe all data when this password is entered at unlock",
                        onClick = {
                            duressPasswordError = ""
                            showDuressDialog = true
                        },
                        iconTint = MaterialTheme.colorScheme.error
                    )
                }
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
                            text = "Wipe all data after 4 wrong passwords",
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
                description = "Manage access controls and authentication methods to protect your wallets. Configure passwords, biometric unlock, and advanced security features like decoy passwords for plausible deniability or a duress password that wipes the app."
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
                    Text("If someone enters the wrong password 4 times in a row, ALL app data will be permanently deleted:")
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
            isLoading = isSavingDecoyPassword,
            errorMessage = decoyPasswordError,
            onDismiss = {
                showDecoyDialog = false
                decoyPasswordError = ""
            },
            onConfirm = { password ->
                scope.launch {
                    isSavingDecoyPassword = true
                    decoyPasswordError = ""
                    val (success, error) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        secureStorage.setDecoyPassword(password)
                    }
                    isSavingDecoyPassword = false
                    if (success) {
                        hasDecoyPassword = true
                        showDecoyDialog = false
                    } else {
                        decoyPasswordError = error ?: "Failed to save decoy password"
                    }
                }
            }
        )
    }

    // Duress password dialog (add and change)
    if (showDuressDialog) {
        SetDuressPasswordDialog(
            isChange = hasDuressPassword,
            isLoading = isSavingDuressPassword,
            errorMessage = duressPasswordError,
            onDismiss = {
                showDuressDialog = false
                duressPasswordError = ""
            },
            onConfirm = { password ->
                scope.launch {
                    isSavingDuressPassword = true
                    duressPasswordError = ""
                    val (success, error) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        secureStorage.setDuressPassword(password)
                    }
                    isSavingDuressPassword = false
                    if (success) {
                        val wasChange = hasDuressPassword
                        hasDuressPassword = true
                        showDuressDialog = false
                        android.widget.Toast.makeText(
                            context,
                            if (wasChange) "Duress password changed" else "Duress password set",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        duressPasswordError = error ?: "Failed to save duress password"
                    }
                }
            }
        )
    }

    // Remove duress password confirmation
    if (showRemoveDuressDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDuressDialog = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_security),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Remove Duress Password?") },
            text = {
                Text("Entering it on the unlock screen will no longer wipe your data. You can set a new one at any time.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val removed = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                secureStorage.removeDuressPassword()
                            }
                            showRemoveDuressDialog = false
                            if (removed) {
                                hasDuressPassword = false
                                android.widget.Toast.makeText(
                                    context,
                                    "Duress password removed",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "Failed to remove duress password",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDuressDialog = false }) {
                    Text("Cancel")
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
                    val (success, changeError) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        secureStorage.changeMainPassword(old, new)
                    }
                    if (success) {
                        showChangePasswordDialog = false
                        isChangingPassword = false

                        if (biometricsEnabled && biometricTarget == UserPreferencesRepository.BIOMETRIC_TARGET_MAIN && activity != null) {
                            // The stored biometric password is now stale. Cancelling
                            // or failing this prompt must disable biometric unlock -
                            // otherwise the old password keeps feeding failed logins.
                            val disableStaleBiometrics = {
                                biometricPasswordManager.removeBiometricData(UserPreferencesRepository.BIOMETRIC_TARGET_MAIN)
                                userPreferencesRepository.setBiometricsEnabled(false)
                                userPreferencesRepository.setBiometricTarget(UserPreferencesRepository.BIOMETRIC_TARGET_NONE)
                                android.widget.Toast.makeText(
                                    context,
                                    "Biometric unlock disabled. Re-enable in settings.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                            val cipher = biometricPasswordManager.getEncryptCipher(UserPreferencesRepository.BIOMETRIC_TARGET_MAIN)
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
                                                UserPreferencesRepository.BIOMETRIC_TARGET_MAIN,
                                                cryptoObject.cipher!!
                                            )
                                        }
                                        if (updated) {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Biometric unlock updated",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            disableStaleBiometrics()
                                        }
                                    }
                                },
                                onError = { _ -> disableStaleBiometrics() },
                                onCancel = { disableStaleBiometrics() }
                            )
                        }
                    } else {
                        isChangingPassword = false
                        android.widget.Toast.makeText(context, changeError ?: "Password change failed", android.widget.Toast.LENGTH_SHORT).show()
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
                    val (success, changeError) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        secureStorage.changeDecoyPassword(old, new)
                    }
                    if (success) {
                        showChangeDecoyPasswordDialog = false
                        isChangingDecoyPassword = false

                        if (biometricsEnabled && biometricTarget == UserPreferencesRepository.BIOMETRIC_TARGET_DECOY && activity != null) {
                            // Same stale-password hazard as the main flow: any outcome
                            // other than a successful update must disable biometrics.
                            val disableStaleBiometrics = {
                                biometricPasswordManager.removeBiometricData(UserPreferencesRepository.BIOMETRIC_TARGET_DECOY)
                                userPreferencesRepository.setBiometricsEnabled(false)
                                userPreferencesRepository.setBiometricTarget(UserPreferencesRepository.BIOMETRIC_TARGET_NONE)
                                android.widget.Toast.makeText(
                                    context,
                                    "Biometric unlock disabled. Re-enable in settings.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                            val cipher = biometricPasswordManager.getEncryptCipher(UserPreferencesRepository.BIOMETRIC_TARGET_DECOY)
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
                                                UserPreferencesRepository.BIOMETRIC_TARGET_DECOY,
                                                cryptoObject.cipher!!
                                            )
                                        }
                                        if (updated) {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Biometric unlock updated",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            disableStaleBiometrics()
                                        }
                                    }
                                },
                                onError = { _ -> disableStaleBiometrics() },
                                onCancel = { disableStaleBiometrics() }
                            )
                        }
                    } else {
                        isChangingDecoyPassword = false
                        android.widget.Toast.makeText(context, changeError ?: "Password change failed", android.widget.Toast.LENGTH_SHORT).show()
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
                if (targetMode == UserPreferencesRepository.BIOMETRIC_TARGET_DURESS) {
                    showDuressBiometricConfirmDialog = true
                } else {
                    showBiometricPasswordDialog = true
                }
            },
            hasDecoyPassword = secureStorage.hasDecoyPassword()
        )
    }

    // Duress Wipe confirmation for biometric setup. No password is stored for
    // this target, so a destructive warning replaces the password prompt; the
    // fingerprint prompt then binds the Keystore key exactly like the others.
    if (showDuressBiometricConfirmDialog) {
        val dismiss = {
            showDuressBiometricConfirmDialog = false
            selectedBiometricTarget = UserPreferencesRepository.BIOMETRIC_TARGET_NONE
        }
        AlertDialog(
            onDismissRequest = dismiss,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_security),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Enable Biometric Duress Wipe?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This is a destructive security feature.",
                        color = MaterialTheme.colorScheme.error
                    )
                    Text("Unlocking with your fingerprint will permanently wipe ALL app data, then open a fresh throwaway wallet so the screen looks normal.")
                    Text("Your fingerprint will no longer open your real vaults.")
                    Text(
                        "This cannot be undone. Make sure you have backups of your seed phrases!",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDuressBiometricConfirmDialog = false
                        val target = UserPreferencesRepository.BIOMETRIC_TARGET_DURESS
                        val cipher = try {
                            biometricPasswordManager.getEncryptCipher(target)
                        } catch (_: Exception) {
                            null
                        }
                        if (activity == null || cipher == null) {
                            android.widget.Toast.makeText(context, "Cannot access biometric authentication", android.widget.Toast.LENGTH_SHORT).show()
                            selectedBiometricTarget = UserPreferencesRepository.BIOMETRIC_TARGET_NONE
                        } else {
                            biometricManager.authenticateWithCrypto(
                                activity = activity,
                                cipher = cipher,
                                title = "Enable Biometric Duress Wipe",
                                subtitle = "Authenticate to enable fingerprint duress wipe",
                                onSuccess = { cryptoObject ->
                                    scope.launch {
                                        val success = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            // The slot holds a random token: a successful
                                            // biometric decrypt of it is the trigger.
                                            val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
                                            val stored = biometricPasswordManager.storeEncryptedPassword(
                                                Base64.encodeToString(token, Base64.NO_WRAP),
                                                target,
                                                cryptoObject.cipher!!
                                            )
                                            if (stored) biometricPasswordManager.removeAllExcept(target)
                                            stored
                                        }
                                        if (success) {
                                            userPreferencesRepository.setBiometricsEnabled(true)
                                            userPreferencesRepository.setBiometricTarget(target)
                                            android.widget.Toast.makeText(context, "Biometric duress wipe enabled", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(context, "Failed to enable biometric duress wipe", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        selectedBiometricTarget = UserPreferencesRepository.BIOMETRIC_TARGET_NONE
                                    }
                                },
                                onError = { error ->
                                    android.widget.Toast.makeText(context, "Failed to enable biometric: $error", android.widget.Toast.LENGTH_SHORT).show()
                                    selectedBiometricTarget = UserPreferencesRepository.BIOMETRIC_TARGET_NONE
                                },
                                onCancel = {
                                    selectedBiometricTarget = UserPreferencesRepository.BIOMETRIC_TARGET_NONE
                                }
                            )
                        }
                    }
                ) {
                    Text("Enable", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss) {
                    Text("Cancel")
                }
            }
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
                    val expectedOwner = if (isDecoy) {
                        SecureStorage.PasswordOwner.DECOY
                    } else {
                        SecureStorage.PasswordOwner.MAIN
                    }
                    val isValidPassword = withContext(kotlinx.coroutines.Dispatchers.Default) {
                        secureStorage.classifyPassword(enteredPassword) == expectedOwner
                    }

                    if (isValidPassword) {
                        if (activity != null) {
                            val cipher = biometricPasswordManager.getEncryptCipher(targetMode)

                            biometricManager.authenticateWithCrypto(
                                activity = activity,
                                cipher = cipher,
                                title = "Enable Biometric Unlock",
                                subtitle = "Authenticate to enable fingerprint unlock",
                                onSuccess = { cryptoObject ->
                                    scope.launch {
                                        val success = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            val stored = biometricPasswordManager.storeEncryptedPassword(
                                                enteredPassword,
                                                targetMode,
                                                cryptoObject.cipher!!
                                            )
                                            // Only one target is ever active: drop the
                                            // previous target's ciphertext and key
                                            if (stored) biometricPasswordManager.removeAllExcept(targetMode)
                                            stored
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
                                },
                                onCancel = {
                                    // Nothing stored yet - just close the setup dialog
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
