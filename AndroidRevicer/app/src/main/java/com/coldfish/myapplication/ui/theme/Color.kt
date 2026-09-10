package com.coldfish.myapplication.ui.theme

import androidx.compose.ui.graphics.Color

// ========== 「极简温控仪表」亮色主题配色 tokens ==========
// 用户偏好：亮色、大字、蓝色强调，界面干净克制。

// 背景与表面（纯白，简洁明亮）
val BackgroundColor = Color(0xFFFFFFFF)   // 全局背景
val SurfaceColor = Color(0xFFFFFFFF)      // 卡片/面板表面

// 文字色
val TextPrimaryColor = Color(0xFF1A1A1A) // 主文字（onBackground / onSurface）
val TextSecondaryColor = Color(0xFF8A8A8E) // 次级文字（说明、辅助信息）

// 主题强调色（蓝色，用于按钮、选中态、滑块等交互元素）
val PrimaryColor = Color(0xFF007AFF)

// 状态色（供 UI 具名引用：温度等级、报警状态等）
val LevelGreen = Color(0xFF34C759)   // 正常/安全
val LevelYellow = Color(0xFFFFB800)  // 警戒/偏高
val LevelRed = Color(0xFFFF3B30)     // 危险/超限

// 分割线与边框
val DividerColor = Color(0xFFE5E5EA)
