package com.smartvision.gallery.ui.theme

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.os.Build

/**
 * LiquidGallery visual identity.
 *
 *  * Primary = brand blue (`#1F6FEB`) — anchor for the "tech" feel.
 *  * Secondary = purple (`#7B61FF`) — reserved for AI/HDR moments.
 *  * Tertiary = teal (`#00BFA5`) — used for next-gen format badges.
 *
 * Material 3 dynamic colour is enabled on Android 12+; otherwise we use the explicit
 * fallback palette below.
 */
@Composable
fun SmartVisionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SmartVisionTypography,
        content = content
    )
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF7B61FF),
    onSecondary = Color.White,
    tertiary = Color(0xFF00BFA5),
    onTertiary = Color.White,
    background = Color(0xFFFAFBFC),
    onBackground = Color(0xFF101317),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101317),
    surfaceVariant = Color(0xFFE2E7EE),
    onSurfaceVariant = Color(0xFF44474F),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFC9C0FF),
    onSecondary = Color(0xFF361E92),
    tertiary = Color(0xFF6FE6CD),
    onTertiary = Color(0xFF003733),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF14171D),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF1A1F27),
    onSurfaceVariant = Color(0xFFC4C7CF),
    error = Color(0xFFFFB4AB)
)

// ponytail: 字距/字重/行高精修而非换字体——Android 系统字体 Roboto/Sans 即可
// 背景产品级质感靠排版精度（负字距标题 + 紧行高正文 + 字重层次）。
// 如需品牌字体配对，后续接入 res/font + FontFamily。
private val SmartVisionTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.4).sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.15).sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.05).sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.15.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp)
)