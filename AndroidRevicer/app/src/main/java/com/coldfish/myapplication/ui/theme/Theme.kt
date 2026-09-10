package com.coldfish.myapplication.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// 「极简温控仪表」亮色配色方案（固定使用自定义亮色，不随系统动态色变化）
private val LightColorScheme = lightColorScheme(
    primary = PrimaryColor,
    onPrimary = SurfaceColor,
    background = BackgroundColor,
    onBackground = TextPrimaryColor,
    surface = SurfaceColor,
    onSurface = TextPrimaryColor,
    surfaceVariant = DividerColor,
    onSurfaceVariant = TextSecondaryColor,
    outline = DividerColor
)

// 暗色方案（保留但简化：与亮色共用强调色，文字反色处理，以备未来扩展）
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryColor,
    onPrimary = SurfaceColor,
    background = TextPrimaryColor,
    onBackground = SurfaceColor,
    surface = TextPrimaryColor,
    onSurface = SurfaceColor,
    surfaceVariant = DividerColor,
    onSurfaceVariant = TextSecondaryColor,
    outline = DividerColor
)

@Composable
fun MyApplicationTheme(
    // 默认使用亮色主题；传入 true 可强制切到暗色方案
    darkTheme: Boolean = false,
    // 固定使用自定义亮色方案，默认关闭系统动态取色（Material You）
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
