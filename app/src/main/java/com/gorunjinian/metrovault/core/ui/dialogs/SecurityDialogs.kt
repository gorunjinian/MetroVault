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

/**
 * AlertDialog for password work that must not be interrupted (it runs PBKDF2): while
 * [isLoading] the body is a spinner with [loadingText] (and [loadingDetail] below it), the
 * buttons are hidden, and the dialog can't be dismissed.
 */
@Composable
private fun BusyAlertDialog(
    title: String,
    isLoading: Boolean,
    loadingText: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    loadingDetail: String? = null,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(title) },
        text = {
            if (isLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = loadingText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (loadingDetail != null) {
                        Text(
                            text = loadingDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                content()
            }
        },
        confirmButton = {
            if (!isLoading) {
                TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                    Text(confirmText)
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

    BusyAlertDialog(
        title = title,
        isLoading = isLoading,
        loadingText = "Changing password...",
        loadingDetail = "This may take a moment",
        confirmText = "Change",
        onConfirm = {
            if (newPassword.length < 8) {
                errorMessage = "New password must be at least 8 characters"
            } else if (newPassword != confirmPassword) {
                errorMessage = "New passwords do not match"
            } else {
                onConfirm(oldPassword, newPassword)
            }
        },
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
}

/**
 * "New password + confirm" dialog shared by the decoy and duress password flows.
 *
 * Length and match validation happens here; [errorMessage] carries the
 * caller's storage-level error (e.g. a collision with another password) and
 * is shown once the caller's work finishes. While [isLoading] the inputs are
 * replaced by a spinner and the dialog cannot be dismissed, because setting a
 * password runs PBKDF2 against every existing record.
 *
 * @param warning Optional extra line rendered in the error colour, for
 *   destructive flows.
 */
@Composable
fun SetPasswordDialog(
    title: String,
    description: String,
    passwordLabel: String,
    confirmText: String = "Set Password",
    warning: String? = null,
    isLoading: Boolean = false,
    errorMessage: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf("") }

    // Focus requesters for keyboard navigation
    val confirmPasswordFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun submit() {
        if (password.length < 8) {
            validationError = "Password must be at least 8 characters"
        } else if (password != confirmPassword) {
            validationError = "Passwords do not match"
        } else {
            validationError = ""
            onConfirm(password)
        }
    }

    BusyAlertDialog(
        title = title,
        isLoading = isLoading,
        loadingText = "Saving password...",
        confirmText = confirmText,
        onConfirm = { submit() },
        onDismiss = onDismiss
    ) {
        Column {
            Text(description)
            if (warning != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = warning,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            SecurePasswordTextField(
                value = password,
                onValueChange = { password = it; validationError = "" },
                label = { Text(passwordLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { confirmPasswordFocusRequester.requestFocus() }
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            SecurePasswordTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; validationError = "" },
                label = { Text("Confirm Password") },
                singleLine = true,
                modifier = Modifier.focusRequester(confirmPasswordFocusRequester),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboardController?.hide()
                        submit()
                    }
                )
            )
            val shownError = validationError.ifEmpty { errorMessage }
            if (shownError.isNotEmpty()) {
                Text(
                    text = shownError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun AddDecoyPasswordDialog(
    isLoading: Boolean = false,
    errorMessage: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    SetPasswordDialog(
        title = "Set Decoy Password",
        description = "This password will open a separate, empty vault. Use it to protect your main wallets under duress.",
        passwordLabel = "Decoy Password",
        isLoading = isLoading,
        errorMessage = errorMessage,
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

/**
 * Sets or changes the duress password. The same dialog serves both cases:
 * nothing is encrypted under the duress password, so changing it needs no
 * old-password step.
 */
@Composable
fun SetDuressPasswordDialog(
    isChange: Boolean,
    isLoading: Boolean = false,
    errorMessage: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    SetPasswordDialog(
        title = if (isChange) "Change Duress Password" else "Set Duress Password",
        description = "Entering this password on the unlock screen permanently wipes ALL app data, then opens a fresh throwaway wallet so the screen looks like a normal unlock. It must be different from your main and decoy passwords.",
        warning = "This cannot be undone. Make sure your seed phrases are backed up.",
        passwordLabel = "Duress Password",
        confirmText = if (isChange) "Change" else "Set Password",
        isLoading = isLoading,
        errorMessage = errorMessage,
        onDismiss = onDismiss,
        onConfirm = onConfirm
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
                Text("Choose what your fingerprint does on the unlock screen:")

                BiometricTargetOption(
                    target = UserPreferencesRepository.BIOMETRIC_TARGET_MAIN,
                    label = "Main Wallets",
                    selectedTarget = selectedTarget,
                    onSelect = { selectedTarget = it }
                )

                // Decoy wallet option (only if decoy password exists)
                if (hasDecoyPassword) {
                    BiometricTargetOption(
                        target = UserPreferencesRepository.BIOMETRIC_TARGET_DECOY,
                        label = "Decoy Wallets",
                        selectedTarget = selectedTarget,
                        onSelect = { selectedTarget = it }
                    )
                }

                // Duress wipe option: the fingerprint destroys everything and
                // opens a throwaway wallet instead of a vault
                BiometricTargetOption(
                    target = UserPreferencesRepository.BIOMETRIC_TARGET_DURESS,
                    label = "Duress Wipe",
                    description = "Erases all data instead of unlocking",
                    selectedTarget = selectedTarget,
                    onSelect = { selectedTarget = it }
                )
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

/** One radio row of [BiometricSetupDialog]; tapping anywhere on the row selects [target]. */
@Composable
private fun BiometricTargetOption(
    target: String,
    label: String,
    selectedTarget: String,
    onSelect: (String) -> Unit,
    description: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(target) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selectedTarget == target,
            onClick = { onSelect(target) }
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (description == null) {
            Text(label)
        } else {
            Column {
                Text(label)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
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
 * @param title Dialog title
 * @param message Prompt shown above the password field
 * @param isLoading Show loading state (disables inputs and shows spinner)
 * @param errorMessage Error message to display (e.g., "Incorrect password")
 */
@Composable
fun ConfirmPasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (password: String) -> Unit,
    title: String = "Confirm Password",
    message: String = "Enter password to continue",
    isLoading: Boolean = false,
    errorMessage: String = ""
) {
    var password by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    BusyAlertDialog(
        title = title,
        isLoading = isLoading,
        loadingText = "Please wait...",
        confirmText = "Confirm",
        confirmEnabled = password.isNotEmpty(),
        onConfirm = { onConfirm(password) },
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message)
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
}
