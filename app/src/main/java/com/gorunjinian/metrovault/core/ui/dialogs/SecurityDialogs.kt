package com.gorunjinian.metrovault.core.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.gorunjinian.metrovault.core.ui.components.SecurePasswordTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChangePasswordDialog(
    title: String,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    // Focus requesters for keyboard navigation
    val newPasswordFocusRequester = remember { FocusRequester() }
    val confirmPasswordFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isLoading) {
                    // Loading state
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Changing password...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "This may take a moment",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Input state
                    SecurePasswordTextField(
                        value = oldPassword,
                        onValueChange = { oldPassword = it },
                        label = { Text("Current Password") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { newPasswordFocusRequester.requestFocus() }
                        )
                    )
                    SecurePasswordTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("New Password") },
                        singleLine = true,
                        modifier = Modifier.focusRequester(newPasswordFocusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { confirmPasswordFocusRequester.requestFocus() }
                        )
                    )
                    SecurePasswordTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm New Password") },
                        singleLine = true,
                        modifier = Modifier.focusRequester(confirmPasswordFocusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { keyboardController?.hide() }
                        )
                    )
                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isLoading) {
                TextButton(
                    onClick = {
                        if (newPassword.length < 8) {
                            errorMessage = "New password must be at least 8 characters"
                        } else if (newPassword != confirmPassword) {
                            errorMessage = "New passwords do not match"
                        } else {
                            onConfirm(oldPassword, newPassword)
                        }
                    }
                ) {
                    Text("Change")
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
fun AddDecoyPasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    // Focus requesters for keyboard navigation
    val confirmPasswordFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Decoy Password") },
        text = {
            Column {
                Text("This password will open a separate, empty vault. Use it to protect your main wallets under duress.")
                Spacer(modifier = Modifier.height(16.dp))
                SecurePasswordTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Decoy Password") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { confirmPasswordFocusRequester.requestFocus() }
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                SecurePasswordTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Confirm Password") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(confirmPasswordFocusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { keyboardController?.hide() }
                    )
                )
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (password.length < 8) {
                        errorMessage = "Password must be at least 8 characters"
                    } else if (password != confirmPassword) {
                        errorMessage = "Passwords do not match"
                    } else {
                        onConfirm(password)
                    }
                }
            ) {
                Text("Set Password")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun BiometricSetupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    hasDecoyPassword: Boolean
) {
    var selectedTarget by remember { mutableStateOf(UserPreferencesRepository.BIOMETRIC_TARGET_MAIN) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Setup Biometric Unlock") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose which wallets to unlock with biometrics:")
                
                // Main wallet option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedTarget = UserPreferencesRepository.BIOMETRIC_TARGET_MAIN },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedTarget == UserPreferencesRepository.BIOMETRIC_TARGET_MAIN,
                        onClick = { selectedTarget = UserPreferencesRepository.BIOMETRIC_TARGET_MAIN }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Main Wallets")
                }
                
                // Decoy wallet option (only if decoy password exists)
                if (hasDecoyPassword) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedTarget = UserPreferencesRepository.BIOMETRIC_TARGET_DECOY },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedTarget == UserPreferencesRepository.BIOMETRIC_TARGET_DECOY,
                            onClick = { selectedTarget = UserPreferencesRepository.BIOMETRIC_TARGET_DECOY }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Decoy Wallets")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedTarget) }
            ) {
                Text("Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Password confirmation for sensitive operations, with verification handled internally.
 *
 * Runs [SecureStorage.classifyPassword] on a background dispatcher (PBKDF2 at 600k
 * iterations must never run on Main) while [ConfirmPasswordDialog]'s loading state is
 * shown, and accepts only the current vault's password: a decoy session accepts only the
 * decoy password, a main session only the main password.
 *
 * @param onVerified Called on Main after successful verification. The dialog stays on
 *   screen — the caller decides whether to dismiss it or start follow-up work.
 * @param isLoading Caller's post-verification work in progress (e.g. deleting wallets);
 *   OR-ed with the internal verification spinner.
 * @param errorMessage Caller's post-verification error to show in the dialog (verification
 *   failures are reported internally and take precedence).
 */
@Composable
fun VerifyPasswordDialog(
    secureStorage: SecureStorage,
    isDecoyMode: Boolean,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
    isLoading: Boolean = false,
    errorMessage: String = ""
) {
    var isVerifying by remember { mutableStateOf(false) }
    var verifyError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    ConfirmPasswordDialog(
        onDismiss = onDismiss,
        onConfirm = { password ->
            if (!isVerifying) {
                isVerifying = true
                verifyError = ""
                scope.launch {
                    val owner = withContext(Dispatchers.Default) {
                        secureStorage.classifyPassword(password)
                    }
                    val expected = if (isDecoyMode) {
                        SecureStorage.PasswordOwner.DECOY
                    } else {
                        SecureStorage.PasswordOwner.MAIN
                    }
                    isVerifying = false
                    if (owner == expected) {
                        onVerified()
                    } else {
                        verifyError = "Incorrect password"
                    }
                }
            }
        },
        isLoading = isVerifying || isLoading,
        errorMessage = verifyError.ifEmpty { errorMessage }
    )
}

/**
 * Generic password confirmation dialog for sensitive operations.
 * Prefer [VerifyPasswordDialog], which performs the verification itself off the main
 * thread; use this directly only when the caller needs the raw password.
 *
 * @param onDismiss Called when user cancels the dialog
 * @param onConfirm Called with the entered password when user confirms
 * @param isLoading Show loading state (disables inputs and shows spinner)
 * @param errorMessage Error message to display (e.g., "Incorrect password")
 */
@Composable
fun ConfirmPasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (password: String) -> Unit,
    isLoading: Boolean = false,
    errorMessage: String = ""
) {
    var password by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Confirm Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isLoading) {
                    // Loading state
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Please wait...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Text("Enter password to continue")
                    SecurePasswordTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        isError = errorMessage.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                keyboardController?.hide()
                                if (password.isNotEmpty()) onConfirm(password)
                            }
                        )
                    )
                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isLoading) {
                TextButton(
                    onClick = { onConfirm(password) },
                    enabled = password.isNotEmpty()
                ) {
                    Text("Confirm")
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
