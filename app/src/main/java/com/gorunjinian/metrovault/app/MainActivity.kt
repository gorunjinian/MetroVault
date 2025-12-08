package com.gorunjinian.metrovault.app

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.gorunjinian.metrovault.navigation.AppNavigation
import com.gorunjinian.metrovault.core.ui.theme.MetroVaultTheme
import com.gorunjinian.metrovault.core.util.SecurityUtils
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.feature.settings.SettingsViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var settingsViewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        installSplashScreen().apply {
            setKeepOnScreenCondition {
                settingsViewModel.UserPreferencesRepository.value == null
            }
        }

        enableEdgeToEdge()

        // Disable screenshots
        SecurityUtils.disableScreenshots(this)

        setContent {
            val userPreferencesRepository by settingsViewModel.UserPreferencesRepository.collectAsState()

            // Wait for UserPreferencesRepository to be initialized
            // The splash screen should keep showing until this is ready
            val prefs = userPreferencesRepository
            if (prefs != null) {
                val themeMode by prefs.themeMode.collectAsState()
                val darkTheme = when (themeMode) {
                    UserPreferencesRepository.THEME_LIGHT -> false
                    UserPreferencesRepository.THEME_DARK -> true
                    else -> isSystemInDarkTheme()
                }

                // Enable dynamic color (Material You) only when System theme is selected
                val dynamicColor = themeMode == UserPreferencesRepository.THEME_SYSTEM

                MetroVaultTheme(
                    darkTheme = darkTheme,
                    dynamicColor = dynamicColor
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AppNavigation(
                            userPreferencesRepository = prefs
                        )
                    }
                }
            } else {
                // Show themed background while preferences load to prevent blank screen
                MetroVaultTheme(
                    darkTheme = true,
                    dynamicColor = false
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        // Empty - splash screen handles the loading state
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onPause() {
        super.onPause()
        // Clear clipboard when app goes to background for security
        SecurityUtils.clearClipboard(this)
        // Lock the app and wipe all sensitive keys from memory when backgrounded
        // User will need to re-authenticate when returning to the app
        Wallet.getInstance(applicationContext).emergencyWipe()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only clear sensitive data when the activity is actually being destroyed
        // This happens when:
        // 1. User explicitly closes the app
        // 2. System kills the process due to memory pressure
        // 3. User swipes away the app from recents
        if (isFinishing) {
            // Activity is finishing (user closed it or swiped from recents)
            Wallet.getInstance(applicationContext).emergencyWipe()
        }
        // Note: If system kills process without calling onDestroy,
        // data is automatically cleared since the process is terminated
    }
}
