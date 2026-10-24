package com.Auska.ToolBox.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 页面根：把「背景」录进一个 GraphicsLayer，供任意玻璃面板复用。
 * 所有 LiquidGlass 面板都必须处在 GlassRoot 内部才能取到背景。
 */
@Composable
fun GlassRoot(
    dark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = if (dark) DarkGlassPalette else LightGlassPalette
    val backdrop = rememberGraphicsLayer()

    CompositionLocalProvider(
        LocalGlassPalette provides palette,
        LocalBackdropLayer provides backdrop,
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            // 背景必须画在玻璃面板的「兄弟」节点上。
            // 如果把它和 content 一起录制，玻璃面板就会引用到自己所在的图层，渲染时无限递归。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithContent {
                        backdrop.record {
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(backdrop)
                    }
            ) {
                AnimatedBackdrop(palette)
            }

            content()
        }
    }
}

/**
 * 背景：一整块纵向渐变 + 三颗缓慢漂移的径向光斑。
 * 光斑随无限动画轻微游动，玻璃面板边缘经过时就能看到「液态」的流动感。
 */
@Composable
fun AnimatedBackdrop(palette: GlassPalette, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "backdrop")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 26_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(palette.backdropTop, palette.backdropBottom)))

        val t = phase * 2f * PI.toFloat()

        blob(
            center = Offset(
                x = size.width * 0.18f + sin(t) * size.width * 0.07f,
                y = size.height * 0.14f + cos(t * 0.8f) * size.height * 0.04f,
            ),
            radius = size.width * 0.78f,
            color = palette.blobA,
        )
        blob(
            center = Offset(
                x = size.width * 0.92f + cos(t * 0.7f) * size.width * 0.08f,
                y = size.height * 0.42f + sin(t * 1.1f) * size.height * 0.05f,
            ),
            radius = size.width * 0.70f,
            color = palette.blobB,
        )
        blob(
            center = Offset(
                x = size.width * 0.42f + sin(t * 0.9f) * size.width * 0.06f,
                y = size.height * 0.92f + cos(t * 0.6f) * size.height * 0.03f,
            ),
            radius = size.width * 0.66f,
            color = palette.blobC,
        )

        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (palette.isDark) 0.04f else 0.16f),
                0.35f to Color.Transparent,
                endY = size.height,
            )
        )
    }
}

private fun DrawScope.blob(center: Offset, radius: Float, color: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, color.copy(alpha = color.alpha * 0.35f), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
