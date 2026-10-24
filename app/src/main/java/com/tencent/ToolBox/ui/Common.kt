package com.tencent.ToolBox.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.ToolBox.glass.LiquidGlass
import com.tencent.ToolBox.glass.LocalGlassPalette

/**
 * 圆形玻璃按钮。
 *
 * 按下去时整块玻璃缩到 0.94 再弹回来 —— 和 GlassButton 用同一套 spring 参数，
 * 手感统一。
 */
@Composable
fun GlassCircleButton(
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tintTop: Color? = null,
    tintBottom: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 900f),
        label = "circleButtonScale",
    )

    LiquidGlass(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        shape = CircleShape,
        blurRadius = 16.dp,
        tintTop = tintTop,
        tintBottom = tintBottom,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/** 页面顶栏：一个圆形返回键 + 主标题 +（可选）副标题 */
@Composable
fun GlassTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val palette = LocalGlassPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassCircleButton(size = 40.dp, onClick = onBack) {
            Icon(size = 18.dp, color = palette.textPrimary) { backIcon(it) }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                color = palette.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}


/** 一组玻璃卡片里的单行：标题 / 说明 / 右侧箭头或数值 */
@Composable
fun GlassRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailingArrow: Boolean = false,
) {
    val palette = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.55f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 1200f),
        label = "rowAlpha",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.graphicsLayer { this.alpha = alpha },
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = palette.textTertiary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (value != null) {
            Text(text = value, color = palette.textTertiary, fontSize = 12.sp)
        }
        if (trailingArrow) {
            Spacer(Modifier.width(8.dp))
            Icon(size = 14.dp, color = palette.textTertiary) { chevronIcon(it) }
        }
    }
}

/** 卡片内分隔线 */
@Composable
fun GlassDivider(modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    val palette = LocalGlassPalette.current
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(1.dp)
            .background(
                if (palette.isDark) Color.White.copy(alpha = 0.12f)
                else Color.Black.copy(alpha = 0.08f)
            )
    )
}

