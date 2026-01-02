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


private val DarkColorScheme = darkColorScheme(
    // Primary colors
    primary = Color(0xFFFCB975),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF693C00),
    onPrimaryContainer = Color(0xFFFFDCBD),

    // Secondary colors
    secondary = Color(0xFFE1C1A4),
    onSecondary = Color(0xFF402C18),
    secondaryContainer = Color(0xFF59422C),
    onSecondaryContainer = Color(0xFFFEDCBE),

    // Tertiary colors
    tertiary = Color(0xFFBFCC9A),
    onTertiary = Color(0xFF2A3410),
    tertiaryContainer = Color(0xFF404B25),
    onTertiaryContainer = Color(0xFFDBE8B5),

    // Error colors
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    // Background & Surface colors
    background = Color(0xFF19120C),
    onBackground = Color(0xFFEFE0D5),

    surface = Color(0xFF19120C),
    onSurface = Color(0xFFEFE0D5),
    surfaceVariant = Color(0xFF51453A),
    onSurfaceVariant = Color(0xFFD5C3B5),

    // Surface containers
    surfaceTint = Color(0xFFFCB975),
    surfaceContainerLowest = Color(0xFF130D07),
    surfaceContainerLow = Color(0xFF211A14),
    surfaceContainer = Color(0xFF261E18),
    surfaceContainerHigh = Color(0xFF302921),
    surfaceContainerHighest = Color(0xFF3C332C),
    surfaceDim = Color(0xFF19120C),
    surfaceBright = Color(0xFF403830),

    // Outline colors
    outline = Color(0xFF9D8E81),
    outlineVariant = Color(0xFF51453A),

    // Inverse colors
    inverseSurface = Color(0xFFEFE0D5),
    inverseOnSurface = Color(0xFF372F28),
    inversePrimary = Color(0xFF855318),

    // Scrim
    scrim = Color(0xFF000000),
)

// AMOLED Black Theme - Same as Dark but with pure black backgrounds
private val BlackColorScheme = DarkColorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF1A1A1A),
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF2A2A2A),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF212121),
    inverseOnSurface = Color.Black
)

private val LightColorScheme = lightColorScheme(
    // Primary colors
    primary = Color(0xFF855318),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCBD),
    onPrimaryContainer = Color(0xFF693C00),

    // Secondary colors
    secondary = Color(0xFF725A42),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEDCBE),
    onSecondaryContainer = Color(0xFF59422C),

    // Tertiary colors
    tertiary = Color(0xFF58633A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDBE8B5),
    onTertiaryContainer = Color(0xFF404B25),

    // Error colors
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),

    // Background & Surface colors
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF211A14),

    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF211A14),
    surfaceVariant = Color(0xFFF4E6DB),
    onSurfaceVariant = Color(0xFF51453A),

    // Surface containers (using surfaceDim, surfaceBright mapped to available roles)
    surfaceTint = Color(0xFF855318),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1E7),
    surfaceContainer = Color(0xFFFAEBE0),
    surfaceContainerHigh = Color(0xFFF4E6DB),
    surfaceContainerHighest = Color(0xFFEFE0D5),
    surfaceDim = Color(0xFFE6D7CD),
    surfaceBright = Color(0xFFFFF8F5),

    // Outline colors
    outline = Color(0xFF837468),
    outlineVariant = Color(0xFFD5C3B5),

    // Inverse colors
    inverseSurface = Color(0xFF372F28),
    inverseOnSurface = Color(0xFFFDEEE3),
    inversePrimary = Color(0xFFFCB975),

    // Scrim
    scrim = Color(0xFF000000),
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
