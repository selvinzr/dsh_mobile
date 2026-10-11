package com.dsh.mobile.ui.theme

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 主题模式：跟随系统 / 亮 / 暗。 */
enum class ThemeMode { System, Light, Dark }

/**
 * 强调色方案。
 *
 * Adlash（紫）按 D:\develop\Adlash\adlash-nuxt\docs\配色规范.md 的实测值对齐：
 * 主色 #6560F0 浅深同值，底色三层（页面/卡片/浮起）用其深紫灰体系。
 * 其余强调色保持中性灰白基底，仅强调色不同。
 */
enum class Accent(val id: String, val label: String) {
    Purple("purple", "紫"),
    Green("green", "绿"),
    Blue("blue", "蓝"),
    Black("black", "黑"),
}

private data class Palette(
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
)

/**
 * 气泡配色（规范原文）：
 *   我的气泡 = 主色底 + 浅字，**浅深同值**（`#6560f0` / `#f1f0f5`）
 *   对方气泡 = 浅紫底 + 灰字（浅）/ 深底 + 灰字（深）
 * 所以 primaryContainer 直接取主色，而不是"选中底"那种浅紫
 * （否则深色下会和卡片底撞色，气泡完全看不见）。
 */
private val BubbleText = Color(0xFFF1F0F5)

private val PurpleLight = Palette(Color(0xFF6560F0), Color(0xFF6560F0), BubbleText)
private val GreenLight = Palette(Color(0xFF0E7C5A), Color(0xFF0E7C5A), BubbleText)
private val BlueLight = Palette(Color(0xFF1FB1E6), Color(0xFF1FB1E6), BubbleText)
private val BlackLight = Palette(Color(0xFF2b2a3f), Color(0xFF2b2a3f), BubbleText)

private val PurpleDark = Palette(Color(0xFF6560F0), Color(0xFF6560F0), BubbleText)
private val GreenDark = Palette(Color(0xFF06b48a), Color(0xFF06b48a), BubbleText)
private val BlueDark = Palette(Color(0xFF1FB1E6), Color(0xFF1FB1E6), BubbleText)
private val BlackDark = Palette(Color(0xFF8e8da2), Color(0xFF8e8da2), BubbleText)

/**
 * 中性基底（Adlash 规范）：
 * 浅色 页面 #f2f1f9 / 卡片 #ffffff / 浅底 #f5f4fa / 正文 #2b2a3f / 次文字 #8e8da1
 * 深色 页面 #2b2a3f / 卡片 #34334a / 边框 #45445e / 主文字 #ffffff / 次文字 #BCBBC7
 */
private fun lightScheme(p: Palette) = lightColorScheme(
    primary = p.primary,
    onPrimary = Color.White,
    primaryContainer = p.primaryContainer,
    onPrimaryContainer = p.onPrimaryContainer,
    secondary = Color(0xFF8e8da2),
    secondaryContainer = Color(0xFFf5f4fa),
    onSecondaryContainer = Color(0xFF2b2a3f),
    tertiary = Color(0xFF8A6A00),
    tertiaryContainer = Color(0xFFFFE7A8),
    onTertiaryContainer = Color(0xFF2A1D00),
    error = Color(0xFFEF2929),
    errorContainer = Color(0xFFF3EBEB),
    onErrorContainer = Color(0xFF7A1212),
    background = Color(0xFFf2f1f9),          // 页面底
    onBackground = Color(0xFF2b2a3f),
    surface = Color.White,                    // 卡片底
    onSurface = Color(0xFF2b2a3f),
    surfaceVariant = Color(0xFFf5f4fa),       // 输入框/浅底
    onSurfaceVariant = Color(0xFF8e8da1),     // 次文字
    outline = Color(0xFFa2a1bb),
    outlineVariant = Color(0xFFdee2e6),       // 边框
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFf5f4fa),
    surfaceContainer = Color(0xFFefeef9),     // 选中底
    surfaceContainerHigh = Color(0xFFe9e8f3),
    surfaceContainerHighest = Color(0xFFe2e1ee),
)

private fun darkScheme(p: Palette) = darkColorScheme(
    primary = p.primary,
    onPrimary = Color.White,
    primaryContainer = p.primaryContainer,
    onPrimaryContainer = p.onPrimaryContainer,
    secondary = Color(0xFFBCBBC7),
    secondaryContainer = Color(0xFF34334a),
    onSecondaryContainer = Color(0xFFE6E6E6),
    tertiary = Color(0xFFD9B45A),
    tertiaryContainer = Color(0xFF3E3208),
    onTertiaryContainer = Color(0xFFFFE9AE),
    error = Color(0xFFEF2929),
    errorContainer = Color(0xFF3a2226),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF2b2a3f),          // 页面底（深紫灰）
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF34334a),             // 卡片/顶栏/浮起
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2b2a3f),      // 输入框底
    onSurfaceVariant = Color(0xFFBCBBC7),    // 次文字（统一值）
    outline = Color(0xFF9C9AB6),
    outlineVariant = Color(0xFF45445e),      // 边框/分隔
    surfaceContainerLowest = Color(0xFF26253a),
    surfaceContainerLow = Color(0xFF2b2a3f),
    surfaceContainer = Color(0xFF34334a),
    surfaceContainerHigh = Color(0xFF3c3b54),
    surfaceContainerHighest = Color(0xFF45445e),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
        bodySmall = base.bodySmall.copy(lineHeight = 18.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.4.sp),
    )
}

/** 代码 / 工具输出用的等宽样式。 */
val MonoTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)

@Composable
fun DshTheme(
    mode: ThemeMode = ThemeMode.System,
    accent: Accent = Accent.Green,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> {
            val p = if (dark) {
                when (accent) {
                    Accent.Purple -> PurpleDark
                    Accent.Green -> GreenDark
                    Accent.Blue -> BlueDark
                    Accent.Black -> BlackDark
                }
            } else {
                when (accent) {
                    Accent.Purple -> PurpleLight
                    Accent.Green -> GreenLight
                    Accent.Blue -> BlueLight
                    Accent.Black -> BlackLight
                }
            }
            if (dark) darkScheme(p) else lightScheme(p)
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
