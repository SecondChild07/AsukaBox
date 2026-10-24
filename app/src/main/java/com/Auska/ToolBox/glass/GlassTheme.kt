package com.Auska.ToolBox.glass

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer

/**
 * Liquid Glass 配色。
 *
 * 底子走「近白 / 近黑」的中性色，光斑只做很淡的一层晕染 —— 玻璃面板靠折射这些
 * 极淡的色差 + 自身的高光描边和投影来成立。底色一旦上饱和度，铺满整屏的彩色会
 * 抢掉内容本身，看久了累。
 */
data class GlassPalette(
    val isDark: Boolean,
    val backdropTop: Color,
    val backdropBottom: Color,
    val blobA: Color,
    val blobB: Color,
    val blobC: Color,
    val glassTintTop: Color,
    val glassTintBottom: Color,
    val strokeTop: Color,
    val strokeBottom: Color,
    val shade: Color,
    val shadowAmbient: Color,
    val shadowSpot: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentDeep: Color,
    val onAccent: Color,
    val fieldFill: Color,
    val fieldStroke: Color,
    val success: Color,
    val warn: Color,
    val danger: Color,
    val scrim: Color,
    val buttonTintTop: Color,
    val buttonTintBottom: Color,
    val ghostTintTop: Color,
    val ghostTintBottom: Color,
)

val DarkGlassPalette = GlassPalette(
    isDark = true,
    backdropTop = Color(0xFF0B0D12),
    backdropBottom = Color(0xFF13161D),
    blobA = Color(0xFF2F52D8).copy(alpha = 0.24f),
    blobB = Color(0xFF6E3FD8).copy(alpha = 0.20f),
    blobC = Color(0xFF117F7A).copy(alpha = 0.17f),
    glassTintTop = Color(0x1FFFFFFF),
    glassTintBottom = Color(0x0AFFFFFF),
    strokeTop = Color(0x8CFFFFFF),
    strokeBottom = Color(0x14FFFFFF),
    shade = Color(0x66000000),
    shadowAmbient = Color(0x4D000000),
    shadowSpot = Color(0x66000000),
    textPrimary = Color(0xFFF3F6FB),
    textSecondary = Color(0xFFA3AEBF),
    textTertiary = Color(0xFF6E7A8C),
    accent = Color(0xFF86B7FF),
    accentDeep = Color(0xFF3B7CFF),
    onAccent = Color(0xFFFFFFFF),
    fieldFill = Color(0x16FFFFFF),
    fieldStroke = Color(0x38FFFFFF),
    success = Color(0xFF5BE3A0),
    warn = Color(0xFFFFC46B),
    danger = Color(0xFFFF7A8A),
    scrim = Color(0xB8000000),
    buttonTintTop = Color(0xFF4A8BFF),
    buttonTintBottom = Color(0xFF2861E8),
    ghostTintTop = Color(0x22FFFFFF),
    ghostTintBottom = Color(0x0DFFFFFF),
)

val LightGlassPalette = GlassPalette(
    isDark = false,
    backdropTop = Color(0xFFFFFFFF),
    backdropBottom = Color(0xFFF3F5F9),
    blobA = Color(0xFF9DB4FF).copy(alpha = 0.16f),
    blobB = Color(0xFFC4AEFF).copy(alpha = 0.13f),
    blobC = Color(0xFF9BE3DC).copy(alpha = 0.11f),
    glassTintTop = Color(0xB3FFFFFF),
    glassTintBottom = Color(0x59FFFFFF),
    strokeTop = Color(0xFFFFFFFF),
    strokeBottom = Color(0x1F8A94A8),
    shade = Color(0x0A000000),
    shadowAmbient = Color(0x0F000000),
    shadowSpot = Color(0x1F000000),
    textPrimary = Color(0xFF0E1622),
    textSecondary = Color(0xFF5C6A7E),
    textTertiary = Color(0xFF8B97A8),
    accent = Color(0xFF2E7BFF),
    accentDeep = Color(0xFF1B5BE0),
    onAccent = Color(0xFFFFFFFF),
    fieldFill = Color(0xCCFFFFFF),
    fieldStroke = Color(0xFFFFFFFF),
    success = Color(0xFF14A46B),
    warn = Color(0xFFC77800),
    danger = Color(0xFFE03A50),
    scrim = Color(0x66000000),
    buttonTintTop = Color(0xFF4C90FF),
    buttonTintBottom = Color(0xFF1F63E8),
    ghostTintTop = Color(0xE6FFFFFF),
    ghostTintBottom = Color(0xB3FFFFFF),
)

/** 当前页面的玻璃配色 */
val LocalGlassPalette = staticCompositionLocalOf { DarkGlassPalette }

/**
 * 背景图层。玻璃面板不重新绘制背景，而是把整页录制下来的这一层贴到自己的裁剪区内，
 * 再配一个 RenderEffect 模糊 —— 这就是「背景模糊」和背景严格对齐的原因。
 */
val LocalBackdropLayer = staticCompositionLocalOf<GraphicsLayer?> { null }
