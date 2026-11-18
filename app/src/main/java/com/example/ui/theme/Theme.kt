package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val TacticalDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF002026),
    primaryContainer = DeepCyanContainer,
    onPrimaryContainer = CyanSoftText,
    secondary = NeonEmerald,
    onSecondary = Color(0xFF002116),
    secondaryContainer = DeepEmeraldContainer,
    onSecondaryContainer = EmeraldSoftText,
    tertiary = TacticalAmber,
    onTertiary = Color(0xFF2B1900),
    tertiaryContainer = DeepAmberContainer,
    onTertiaryContainer = Color(0xFFFDE68A),
    background = ObsidianBackground,
    onBackground = CrispIceText,
    surface = DeepSlateSurface,
    onSurface = CrispIceText,
    surfaceVariant = ElevatedSlateSurface,
    onSurfaceVariant = MutedSteelText,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = DeepSlateSurface,
    surfaceContainer = ElevatedSlateSurface,
    surfaceContainerHigh = HighestSlateSurface,
    outline = TacticalOutline,
    outlineVariant = TacticalOutlineSubtle,
    error = AlertCoral,
    onError = Color.White,
    errorContainer = DeepCoralContainer,
    onErrorContainer = Color(0xFFFCA5A5),
)

val TacticalShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun KendaliP2PTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = TacticalDarkColorScheme,
        typography = Typography,
        shapes = TacticalShapes,
        content = content,
    )
}
