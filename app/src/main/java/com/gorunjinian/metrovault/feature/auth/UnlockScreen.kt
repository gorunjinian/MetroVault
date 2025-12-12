package com.gorunjinian.metrovault.feature.auth

import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.crypto.BiometricAuthManager
import com.gorunjinian.metrovault.core.util.findActivity
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField

@Composable
fun UnlockScreen(
    viewModel: AuthViewModel = viewModel(),
    onUnlockSuccess: (autoOpenRequested: Boolean) -> Unit,
    onDataWiped: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val biometricManager = remember { BiometricAuthManager(context) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    // Collect state from ViewModel
    val uiState by viewModel.unlockState.collectAsStateWithLifecycle()

    // Handle events from ViewModel
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AuthViewModel.AuthEvent.UnlockSuccess -> {
                    onUnlockSuccess(event.autoOpenRequested)
                }
                is AuthViewModel.AuthEvent.SetupComplete -> {
                    // Not relevant for UnlockScreen
                }
                is AuthViewModel.AuthEvent.DataWiped -> {
                    onDataWiped()
                }
            }
        }
    }

    val isBiometricAvailable = remember(biometricManager) { biometricManager.isBiometricAvailable() }

    fun unlockWithBiometrics() {
        activity?.let { act ->
            val cipher = viewModel.getDecryptCipher()

            if (cipher != null) {
                biometricManager.authenticateWithCrypto(
                    activity = act,
                    cipher = cipher,
                    title = "Unlock Metro Vault",
                    subtitle = "Use your fingerprint to unlock",
                    onSuccess = { cryptoObject ->
                        scope.launch {
                            viewModel.unlockWithBiometrics(cryptoObject.cipher!!)
                        }
                    },
                    onError = { error ->
                        scope.launch {
                            viewModel.setBiometricError(error)
                        }
                    },
                    onFailed = {
                        // No action needed on failed attempt (user can retry)
                    }
                )
            } else {
                viewModel.setBiometricError("Biometric key invalidated. Please use password to unlock.")
            }
        } ?: run {
            viewModel.setBiometricError("Cannot access biometric authentication")
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .padding(top = 60.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Metro Vault",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Enter your password to unlock",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            if (uiState.isAuthenticating) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "Authenticating...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!uiState.isAuthenticating) {
                SecureOutlinedTextField(
                    value = uiState.password,
                    onValueChange = { viewModel.updatePassword(it) },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isPasswordField = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { 
                            keyboardController?.hide()
                            viewModel.unlockWithPassword() 
                        }
                    )
                )

                if (uiState.errorMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { 
                        keyboardController?.hide()
                        viewModel.unlockWithPassword() 
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Unlock")
                }

                if (uiState.biometricsEnabled && isBiometricAvailable && uiState.hasBiometricPassword && 
                    uiState.biometricTarget != UserPreferencesRepository.BIOMETRIC_TARGET_NONE) {
                    Spacer(modifier = Modifier.height(32.dp))

                    IconButton(
                        onClick = { unlockWithBiometrics() },
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_fingerprint),
                            contentDescription = "Unlock with Fingerprint",
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Tap to unlock with fingerprint",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
