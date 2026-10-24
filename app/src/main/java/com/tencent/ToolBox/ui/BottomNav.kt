package com.tencent.ToolBox.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.ToolBox.glass.LiquidGlass
import com.tencent.ToolBox.glass.LocalGlassPalette

/**
 * 底部导航：左「功能」右「设置」，各占一半。
 *
 * 选中态是一块会滑过去的玻璃，跟整体质感一致 —— 没有用 Material 的 NavigationBar，
 * 那套底部土司色块跟这层玻璃放一起很违和。
 */
@Composable
fun BottomNav(
    current: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    val slide by animateFloatAsState(
        targetValue = if (current == NavTab.Functions) 0f else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
        label = "navSlide",
    )

    LiquidGlass(
        modifier = modifier.height(58.dp),
        shape = RoundedCornerShape(29.dp),
        blurRadius = 22.dp,
        shadow = 8.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val segWidth = maxWidth / 2

            Box(
                Modifier
                    .offset(x = segWidth * slide)
                    .width(segWidth)
                    .fillMaxSize()
                    .padding(5.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (palette.isDark) 0.22f else 0.92f),
                                Color.White.copy(alpha = if (palette.isDark) 0.12f else 0.72f),
                            )
                        )
                    )
            )

            Row(Modifier.fillMaxSize()) {
                NavCell(
                    label = "功能",
                    selected = current == NavTab.Functions,
                    draw = { appsIcon(it) },
                    onClick = { onSelect(NavTab.Functions) },
                    modifier = Modifier.weight(1f),
                )
                NavCell(
                    label = "设置",
                    selected = current == NavTab.Settings,
                    draw = { gearIcon(it) },
                    onClick = { onSelect(NavTab.Settings) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavCell(
    label: String,
    selected: Boolean,
    draw: DrawScope.(Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    val color = if (selected) palette.textPrimary else palette.textSecondary

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(size = 21.dp, color = color, draw = draw)
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                color = color,
                fontSize = 10.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
