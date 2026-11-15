package app.memoryvault.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Opaque surfaces and explicit foreground colours keep the same reading hierarchy in both modes.
internal val P2LightColors = lightColorScheme(
    primary = Color(0xFF173E48),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEDEF),
    onPrimaryContainer = Color(0xFF173E48),
    secondary = Color(0xFF345B64),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0EFEC),
    onSecondaryContainer = Color(0xFF29464B),
    tertiary = Color(0xFF4B5F55),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6EFE6),
    onTertiaryContainer = Color(0xFF2E453D),
    background = Color(0xFFF7F9F7),
    onBackground = Color(0xFF20343A),
    surface = Color.White,
    onSurface = Color(0xFF20343A),
    surfaceVariant = Color(0xFFEAF0EF),
    onSurfaceVariant = Color(0xFF53666B),
    outline = Color(0xFF415B61),
    outlineVariant = Color(0xFF6E8588),
    surfaceContainer = Color(0xFFF0F5F3),
    surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFEAF0EF),
)

internal val P2DarkColors = darkColorScheme(
    primary = Color(0xFF9DD2D5),
    onPrimary = Color(0xFF0C2C33),
    primaryContainer = Color(0xFF294D54),
    onPrimaryContainer = Color(0xFFE4F5F4),
    secondary = Color(0xFFBAD5D4),
    onSecondary = Color(0xFF153137),
    secondaryContainer = Color(0xFF314A4E),
    onSecondaryContainer = Color(0xFFD7EFEF),
    tertiary = Color(0xFFAFD3B9),
    onTertiary = Color(0xFF193528),
    tertiaryContainer = Color(0xFF31463A),
    onTertiaryContainer = Color(0xFFE4F2E6),
    background = Color(0xFF121D20),
    onBackground = Color(0xFFF2F7F6),
    surface = Color(0xFF1D2C30),
    onSurface = Color(0xFFF2F7F6),
    surfaceVariant = Color(0xFF294044),
    onSurfaceVariant = Color(0xFFBAD5D4),
    outline = Color(0xFF9AB7BB),
    outlineVariant = Color(0xFF6D8D92),
    surfaceContainer = Color(0xFF24363A),
    surfaceContainerLow = Color(0xFF1D2C30),
    surfaceContainerHigh = Color(0xFF294044),
)

internal val P2Typography = Typography(
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 29.sp),
    bodyMedium = TextStyle(fontSize = 20.sp, lineHeight = 29.sp),
    bodySmall = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    labelLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 22.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold),
)
