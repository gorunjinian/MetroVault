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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.ui.components.SettingsInfoCard
import com.gorunjinian.metrovault.core.ui.components.ThemeOption
import com.gorunjinian.metrovault.data.model.QuickShortcut
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    val themeMode by userPreferencesRepository.themeMode.collectAsState()
    val quickShortcuts by userPreferencesRepository.quickShortcuts.collectAsState()
    var shortcutToReplace by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
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
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Theme Section
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

            // Quick Shortcuts Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Quick Shortcuts",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    "Tap to change shortcut",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    quickShortcuts.forEachIndexed { index, shortcut ->
                        FilledTonalButton(
                            onClick = { shortcutToReplace = index },
                            modifier = Modifier
                                .width(100.dp)
                                .height(60.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    painter = painterResource(shortcut.iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = shortcut.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Info Card after content
            SettingsInfoCard(
                icon = R.drawable.ic_pallete,
                title = "Appearance Settings",
                description = "Customize the look and feel of MetroVault. Choose your preferred theme and configure quick shortcuts that appear on wallet cards for fast access to common actions."
            )
        }
    }

    // Shortcut selection dialog
    if (shortcutToReplace != null) {
        val replaceIndex = shortcutToReplace!!
        val currentShortcut = quickShortcuts[replaceIndex]

        AlertDialog(
            onDismissRequest = { shortcutToReplace = null },
            title = { Text("Select Shortcut") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Replace \"${currentShortcut.label}\" with:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Grid of all shortcuts (2 columns)
                    QuickShortcut.entries.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { shortcut ->
                                val isCurrentlySelected = shortcut in quickShortcuts
                                val isThisSlot = shortcut == currentShortcut

                                OutlinedCard(
                                    onClick = {
                                        if (!isThisSlot) {
                                            val newList = quickShortcuts.toMutableList()

                                            if (isCurrentlySelected) {
                                                val otherIndex = quickShortcuts.indexOf(shortcut)
                                                newList[otherIndex] = currentShortcut
                                                newList[replaceIndex] = shortcut
                                            } else {
                                                newList[replaceIndex] = shortcut
                                            }

                                            userPreferencesRepository.setQuickShortcuts(newList)
                                            shortcutToReplace = null
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = when {
                                            isThisSlot -> MaterialTheme.colorScheme.tertiaryContainer
                                            isCurrentlySelected -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            else -> MaterialTheme.colorScheme.surface
                                        }
                                    ),
                                    border = CardDefaults.outlinedCardBorder().copy(
                                        width = if (isThisSlot) 2.dp else 1.dp
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(shortcut.iconRes),
                                            contentDescription = null,
                                            tint = if (isThisSlot)
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            else
                                                MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Text(
                                            text = shortcut.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isThisSlot)
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            else
                                                MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isCurrentlySelected && !isThisSlot) {
                                            Text(
                                                text = "swap",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { shortcutToReplace = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
