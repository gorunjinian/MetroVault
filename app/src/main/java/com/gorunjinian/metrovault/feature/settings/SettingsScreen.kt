package com.gorunjinian.metrovault.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import com.gorunjinian.metrovault.core.ui.components.SettingsSectionCard

/**
 * Main settings screen content showing navigation cards to settings sub-screens.
 * Each card navigates to a dedicated settings screen for that category.
 */
@Composable
fun SettingsContent(
    onAppearanceSettings: () -> Unit,
    onSecuritySettings: () -> Unit,
    onAdvancedSettings: () -> Unit,
    onCompleteMnemonic: () -> Unit,
    onAbout: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsSectionCard(
                icon = R.drawable.ic_pallete,
                title = "Appearance",
                description = "Theme and quick shortcuts",
                onClick = onAppearanceSettings
            )
        }

        item {
            SettingsSectionCard(
                icon = R.drawable.ic_shield_lock,
                title = "Security",
                description = "Passwords, biometrics, and protection",
                onClick = onSecuritySettings
            )
        }

        item {
            SettingsSectionCard(
                icon = R.drawable.ic_tune,
                title = "Advanced",
                description = "Power user options and wallet management",
                onClick = onAdvancedSettings
            )
        }

        item {
            SettingsSectionCard(
                icon = R.drawable.ic_format_list_numbered,
                title = "Complete Mnemonic",
                description = "Calculate the last word (checksum)",
                onClick = onCompleteMnemonic
            )
        }

        item {
            SettingsSectionCard(
                icon = R.drawable.ic_info,
                title = "About",
                description = "Version info and app details",
                onClick = onAbout
            )
        }
    }
}

/**
 * Dialog for entering password to set up biometric authentication.
 */
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
                    visualTransformation = PasswordVisualTransformation(),
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
