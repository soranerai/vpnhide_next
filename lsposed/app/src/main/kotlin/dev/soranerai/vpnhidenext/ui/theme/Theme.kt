package dev.soranerai.vpnhidenext.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat

// VPN-oriented palette: green for protection, blue/cyan for network layers.
val TelBlue = Color(0xFF4AA8E8)
val TelCyan = Color(0xFF28BCC8)
val TelGreen = Color(0xFF43C978)
val TelPink = Color(0xFFF06292)
val TelRed = Color(0xFFF44336)
val TelOrange = Color(0xFFFF9800)

// AMOLED Surfaces
val AmoledBackground = Color.Black
val AmoledSurface = Color(0xFF121212)
val AmoledSurfaceVariant = Color(0xFF1E1E1E)
val AmoledText = Color(0xFFE0E0E0)
val AmoledSubtext = Color(0xFFB0B0B0)

// Cool neutral surfaces avoid the previous beige tint.
val TelLightBackground = Color(0xFFF4F7F9)
val TelLightSurface = Color.White
val TelLightText = Color(0xFF172027)

private val ExpressiveDarkColorScheme =
    darkColorScheme(
        primary = TelGreen,
        primaryContainer = Color(0xFF124D2D),
        onPrimaryContainer = Color(0xFFBDF4D1),
        secondary = TelBlue,
        secondaryContainer = Color(0xFF173F59),
        onSecondaryContainer = Color(0xFFCBEAFF),
        tertiary = TelCyan,
        tertiaryContainer = Color(0xFF12454A),
        onTertiaryContainer = Color(0xFFC4F5F7),
        background = Color(0xFF0D1216),
        surface = Color(0xFF141B20),
        surfaceVariant = Color(0xFF202A31),
        onPrimary = Color.Black,
        onSecondary = Color.White,
        onTertiary = Color.Black,
        onBackground = AmoledText,
        onSurface = AmoledText,
        onSurfaceVariant = Color(0xFFB6C2CA),
        error = TelRed,
        outline = Color(0xFF65747E),
        outlineVariant = Color(0xFF35434C),
    )

private val ExpressiveAmoledColorScheme =
    darkColorScheme(
        primary = TelGreen,
        primaryContainer = Color(0xFF0D4225),
        onPrimaryContainer = Color(0xFFBDF4D1),
        secondary = TelBlue,
        secondaryContainer = Color(0xFF12384F),
        onSecondaryContainer = Color(0xFFCBEAFF),
        tertiary = TelCyan,
        tertiaryContainer = Color(0xFF0E3C40),
        onTertiaryContainer = Color(0xFFC4F5F7),
        background = Color.Black,
        surface = Color.Black,
        surfaceVariant = Color(0xFF161616),
        onPrimary = Color.Black,
        onSecondary = Color.White,
        onTertiary = Color.Black,
        onBackground = AmoledText,
        onSurface = AmoledText,
        onSurfaceVariant = AmoledSubtext,
        error = TelRed,
        outline = Color(0xFF607079),
        outlineVariant = Color(0xFF29343A),
    )

private val ExpressiveLightColorScheme =
    lightColorScheme(
        primary = Color(0xFF168A4A),
        primaryContainer = Color(0xFFD5F5E1),
        onPrimaryContainer = Color(0xFF083C21),
        secondary = Color(0xFF256FA6),
        secondaryContainer = Color(0xFFDCEEFF),
        onSecondaryContainer = Color(0xFF123A56),
        tertiary = Color(0xFF087F8C),
        tertiaryContainer = Color(0xFFC9F3F5),
        onTertiaryContainer = Color(0xFF083B40),
        background = TelLightBackground,
        surface = TelLightSurface,
        surfaceVariant = Color(0xFFE7EDF1),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onTertiary = Color.White,
        onBackground = TelLightText,
        onSurface = TelLightText,
        onSurfaceVariant = Color(0xFF53616B),
        error = TelRed,
        outline = Color(0xFF74818A),
        outlineVariant = Color(0xFFC7D0D7),
    )

@Composable
fun VpnHideTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = true,
    dynamicColor: Boolean = false, // Keep the app palette stable unless dynamic colors are explicitly requested.
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme =
        when {
            // Optional Monet colors remain available for callers that explicitly opt in.
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

    val currentDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides
            Density(
                density = currentDensity.density,
                fontScale = 1.0f,
            ),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content,
        )
    }
}
