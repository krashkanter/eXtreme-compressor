package com.krashkanter.extc

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private fun gray(value: Long) = Color(value)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExperimentalCompressorTheme(dark: Boolean, content: @Composable () -> Unit) {
    val foreground = if(dark) Color.White else Color.Black
    val background = if(dark) Color.Black else Color.White
    val quiet = if(dark) gray(0xFFB9B9B9) else gray(0xFF595959)
    val layer = if(dark) gray(0xFF151515) else gray(0xFFF2F2F2)
    val scheme = if(dark) darkColorScheme() else lightColorScheme()
    MaterialExpressiveTheme(
        colorScheme = scheme.copy(
            primary = foreground, onPrimary = background,
            primaryContainer = foreground, onPrimaryContainer = background,
            secondary = foreground, onSecondary = background,
            secondaryContainer = layer, onSecondaryContainer = foreground,
            tertiary = foreground, onTertiary = background,
            tertiaryContainer = layer, onTertiaryContainer = foreground,
            background = background, onBackground = foreground,
            surface = background, onSurface = foreground,
            surfaceVariant = layer, onSurfaceVariant = quiet,
            surfaceTint = foreground,
            surfaceDim = layer, surfaceBright = background,
            surfaceContainerLowest = background,
            surfaceContainerLow = layer,
            surfaceContainer = layer,
            surfaceContainerHigh = if(dark) gray(0xFF232323) else gray(0xFFEAEAEA),
            surfaceContainerHighest = if(dark) gray(0xFF303030) else gray(0xFFDDDDDD),
            outline = quiet,
            outlineVariant = if(dark) gray(0xFF353535) else gray(0xFFD8D8D8),
            inverseSurface = foreground, inverseOnSurface = background,
            inversePrimary = background,
            error = foreground, onError = background,
            errorContainer = layer, onErrorContainer = foreground,
            scrim = Color.Black
        ),
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
