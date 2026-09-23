package com.gorunjinian.metrovault.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
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
