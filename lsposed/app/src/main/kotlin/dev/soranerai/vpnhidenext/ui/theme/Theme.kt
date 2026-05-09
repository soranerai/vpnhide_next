package dev.soranerai.vpnhidenext.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Expressive "Telegram-style" Palette
val TelBlue = Color(0xFF50A2E9)
val TelPink = Color(0xFFF06292)
val TelGreen = Color(0xFF4CAF50)
val TelRed = Color(0xFFF44336)
val TelOrange = Color(0xFFFF9800)
val TelPurple = Color(0xFF9575CD)

// AMOLED Surfaces
val AmoledBackground = Color.Black
val AmoledSurface = Color(0xFF121212)
val AmoledSurfaceVariant = Color(0xFF1E1E1E)
val AmoledText = Color(0xFFE0E0E0)
val AmoledSubtext = Color(0xFFB0B0B0)

// Vibrant Light Palette
val TelLightPrimary = Color(0xFF0088CC) // Telegram Blue
val TelLightBackground = Color(0xFFF2F2F2)
val TelLightSurface = Color.White
val TelLightText = Color(0xFF222222)

private val ExpressiveDarkColorScheme =
    darkColorScheme(
        primary = TelGreen,
        secondary = TelBlue,
        tertiary = TelPink,
        background = Color(0xFF1C1C1C),
        surface = Color(0xFF242424),
        surfaceVariant = Color(0xFF2C2C2C),
        onPrimary = Color.Black,
        onSecondary = Color.White,
        onBackground = AmoledText,
        onSurface = AmoledText,
        onSurfaceVariant = AmoledSubtext,
        error = TelRed,
        outline = Color(0xFF333333),
    )

private val ExpressiveAmoledColorScheme =
    darkColorScheme(
        primary = TelGreen,
        secondary = TelBlue,
        tertiary = TelPink,
        background = Color.Black,
        surface = Color.Black,
        surfaceVariant = Color(0xFF161616),
        onPrimary = Color.Black,
        onSecondary = Color.White,
        onBackground = AmoledText,
        onSurface = AmoledText,
        onSurfaceVariant = AmoledSubtext,
        error = TelRed,
        outline = Color(0xFF222222),
    )

private val ExpressiveLightColorScheme =
    lightColorScheme(
        primary = TelGreen,
        secondary = TelBlue,
        tertiary = TelPink,
        background = TelLightBackground,
        surface = TelLightSurface,
        surfaceVariant = Color(0xFFE8E8E8),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = TelLightText,
        onSurface = TelLightText,
        onSurfaceVariant = Color(0xFF757575),
        error = TelRed,
    )

@Composable
fun VpnHideTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = true,
    dynamicColor: Boolean = false, // User complained about blidness/lack of punch, so we prefer our expressive palette over generic Monet
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme =
        when {
            // We override dynamicColor here because user specifically asked for EXPRESSIVE colors,
            // and Monet can often be too pastel/muted depending on wallpaper.
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                if (pureBlack) ExpressiveAmoledColorScheme else ExpressiveDarkColorScheme
            }

            else -> {
                ExpressiveLightColorScheme
            }
        }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
