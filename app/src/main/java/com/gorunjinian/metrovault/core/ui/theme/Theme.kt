package com.gorunjinian.metrovault.core.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Bitcoin Orange
private val BitcoinOrange = Color(0xFFF7931A)
private val BitcoinOrangeLight = Color(0xFFFF9E2C)
private val BitcoinOrangeDark = Color(0xFFE67E00)

// Dark Background
private val DarkBackground = Color(0xFF121212)
private val DarkSurface = Color(0xFF1E1E1E)
private val DarkSurfaceVariant = Color(0xFF2C2C2C)

private val DarkColorScheme = darkColorScheme(
    primary = BitcoinOrange,
    onPrimary = Color.Black,
    primaryContainer = BitcoinOrangeDark,
    onPrimaryContainer = Color.White,

    secondary = Color(0xFFFFB74D),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFFF57C00),
    onSecondaryContainer = Color.White,

    tertiary = Color(0xFF81C784),
    onTertiary = Color(0xFF003A00),
    tertiaryContainer = Color(0xFF00530A),
    onTertiaryContainer = Color(0xFF9DDB9D),

    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),

    background = DarkBackground,
    onBackground = Color(0xFFE6E1E5),

    surface = DarkSurface,
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFCAC4D0),

    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),

    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = DarkBackground,
    inversePrimary = Color(0xFF6750A4),

    surfaceTint = BitcoinOrange,
    scrim = Color.Black
)

// AMOLED Black Theme - Same as Dark but with pure black backgrounds
private val BlackColorScheme = DarkColorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF121212), // Slightly lighter than black to differentiate cards
    inverseOnSurface = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = BitcoinOrange,
    onPrimary = Color.White,
    primaryContainer = BitcoinOrangeLight,
    onPrimaryContainer = Color.Black,

    secondary = Color(0xFFF57C00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFB74D),
    onSecondaryContainer = Color.Black,

    tertiary = Color(0xFF388E3C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF81C784),
    onTertiaryContainer = Color.Black,

    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onError = Color.White,
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),

    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),

    outline = Color(0xFF79747E),
    inverseOnSurface = Color(0xFFF4EFF4),
    inverseSurface = Color(0xFF313033),
    inversePrimary = Color(0xFFFFB74D),
)

@Composable
fun MetroVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    blackTheme: Boolean = false,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val baseScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            // Apply black backgrounds on top of dynamic colors if blackTheme is enabled
            if (darkTheme && blackTheme) {
                baseScheme.copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF121212),
                    inverseOnSurface = Color.Black
                )
            } else {
                baseScheme
            }
        }
        darkTheme && blackTheme -> BlackColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // window.statusBarColor is handled by enableEdgeToEdge in MainActivity
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
