package com.gorunjinian.metrovault.feature.auth

import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.crypto.BiometricAuthManager
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import com.gorunjinian.metrovault.core.util.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    // Shake animation state for error feedback
    var triggerShake by remember { mutableStateOf(false) }
    val shakeOffset by animateFloatAsState(
        targetValue = if (triggerShake) 10f else 0f,
        animationSpec = spring(
            dampingRatio = 0.3f,
            stiffness = Spring.StiffnessHigh
        ),
        label = "shakeAnimation"
    )

    // Reset shake after animation
    LaunchedEffect(triggerShake) {
        if (triggerShake) {
            delay(100)
            triggerShake = false
        }
    }

    // Trigger shake when error message appears
    LaunchedEffect(uiState.errorMessage) {
        if (uiState.errorMessage.isNotEmpty()) {
            triggerShake = true
        }
    }

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

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .padding(top = 48.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Logo
            Icon(
                painter = painterResource(R.drawable.ic_metrovault),
                contentDescription = "Metro Vault Logo",
                modifier = Modifier.size(100.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // App Title with enhanced typography
            Text(
                text = "Metro Vault",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1).sp
                ),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Enter your password to unlock",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Card container for the login form
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (uiState.isAuthenticating) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
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
                        // Password field with shake animation
                        SecureOutlinedTextField(
                            value = uiState.password,
                            onValueChange = { viewModel.updatePassword(it) },
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(x = shakeOffset.dp),
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

                        // Enhanced unlock button
                        Button(
                            onClick = {
                                keyboardController?.hide()
                                viewModel.unlockWithPassword()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lock),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Unlock",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Biometric section outside the card
            if (!uiState.isAuthenticating &&
                uiState.biometricsEnabled && 
                isBiometricAvailable && 
                uiState.hasBiometricPassword &&
                uiState.biometricTarget != UserPreferencesRepository.BIOMETRIC_TARGET_NONE
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                // Fingerprint button with background circle
                IconButton(
                    onClick = { unlockWithBiometrics() },
                    modifier = Modifier
                        .size(72.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_fingerprint),
                        contentDescription = "Unlock with Fingerprint",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Tap to unlock with fingerprint",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
