package com.familysafety.app.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal40 = Color(0xFF00696E)
private val Teal80 = Color(0xFF4FD8DF)
private val Coral40 = Color(0xFF9C4146)
private val Coral80 = Color(0xFFFFB3B3)
private val Slate40 = Color(0xFF4A6366)
private val Slate80 = Color(0xFFB1CBCE)

private val LightColors = lightColorScheme(
    primary = Teal40, onPrimary = Color.White, primaryContainer = Color(0xFF6FF6FD), onPrimaryContainer = Color(0xFF002022),
    secondary = Slate40, onSecondary = Color.White, secondaryContainer = Color(0xFFCCE8EA), onSecondaryContainer = Color(0xFF051F22),
    tertiary = Coral40, onTertiary = Color.White, tertiaryContainer = Color(0xFFFFDAD9), onTertiaryContainer = Color(0xFF40000A),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAFDFC), surface = Color(0xFFFAFDFC), surfaceVariant = Color(0xFFDAE4E5), onSurfaceVariant = Color(0xFF3F4949),
)

private val DarkColors = darkColorScheme(
    primary = Teal80, onPrimary = Color(0xFF003739), primaryContainer = Color(0xFF004F53), onPrimaryContainer = Color(0xFF6FF6FD),
    secondary = Slate80, onSecondary = Color(0xFF1C3437), secondaryContainer = Color(0xFF324B4E), onSecondaryContainer = Color(0xFFCCE8EA),
    tertiary = Coral80, onTertiary = Color(0xFF5F131B), tertiaryContainer = Color(0xFF7E2A30), onTertiaryContainer = Color(0xFFFFDAD9),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF191C1C), surface = Color(0xFF191C1C), surfaceVariant = Color(0xFF3F4949), onSurfaceVariant = Color(0xFFBEC8C9),
)

/** App theme with light/dark support and Material You dynamic colour on Android 12+. */
@Composable
fun FamilySafetyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
