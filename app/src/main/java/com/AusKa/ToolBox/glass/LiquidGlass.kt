package com.AusKa.ToolBox.glass

import android.os.Build
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Liquid Glass 面板。
 *
 * 结构（从下到上）：
 *  1. 折射层：把 GlassRoot 录制的整页背景平移贴进自己的裁剪区，再用 RenderEffect 做高斯模糊
 *  2. 质感层：半透明白色渐变 + 顶部内高光 + 1dp 渐变描边（顶亮底暗，模拟玻璃厚度）
 *  3. 内容层
 *
 * 背景贴图与页面共用同一个 GraphicsLayer，所以玻璃里的模糊图是**真实背景**，不是假色块。
 */
@Composable
fun LiquidGlass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    blurRadius: Dp = 18.dp,
    tintTop: Color? = null,
    tintBottom: Color? = null,
    strokeTop: Color? = null,
    strokeBottom: Color? = null,
    shadow: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalGlassPalette.current
    val backdrop = LocalBackdropLayer.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val blurPx = with(LocalDensity.current) { blurRadius.toPx() }
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val tint0 = tintTop ?: palette.glassTintTop
    val tint1 = tintBottom ?: palette.glassTintBottom
    val stroke0 = strokeTop ?: palette.strokeTop
    val stroke1 = strokeBottom ?: palette.strokeBottom

    Box(
        modifier = modifier
            .then(
                if (shadow > 0.dp) {
                    Modifier.shadow(
                        elevation = shadow,
                        shape = shape,
                        clip = false,
                        ambientColor = palette.shadowAmbient,
                        spotColor = palette.shadowSpot,
                    )
                } else {
                    Modifier
                }
            )
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape)
    ) {
        if (backdrop != null) {
            val refract = Modifier
                .matchParentSize()
                .drawBehind {
                    translate(-origin.x, -origin.y) { drawLayer(backdrop) }
                }
            if (canBlur && blurRadius > 0.dp) {
                Box(refract.graphicsLayer { renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp) })
            } else {
                Box(refract)
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(tint0, tint1)))
                .drawWithCache {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    val strokeWidth = 1.dp.toPx()
                    val highlight = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (palette.isDark) 0.12f else 0.60f),
                        0.5f to Color.Transparent,
                        endY = size.height,
                    )
                    val strokeBrush = Brush.verticalGradient(listOf(stroke0, stroke1))
                    onDrawBehind {
                        drawRect(highlight)
                        drawOutline(outline, brush = strokeBrush, style = Stroke(width = strokeWidth))
                    }
                }
        )

        // 内容层必须参与父 Box 的测量，否则所有子节点都是 matchParentSize 时
        // 玻璃面板会塌陷成 0 尺寸（卡片直接消失）。背景层才用 matchParentSize。
        Box(Modifier.padding(contentPadding)) {
            content()
        }
    }
}

enum class GlassButtonKind { Primary, Ghost }

/**
 * 玻璃按钮。按下时整块玻璃轻微下压回弹（spring），就是「液态」的手感来源。
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 54.dp,
    kind: GlassButtonKind = GlassButtonKind.Primary,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val palette = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.962f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 900f),
        label = "glassButtonScale",
    )

    val textColor = when (kind) {
        GlassButtonKind.Primary -> palette.onAccent
        GlassButtonKind.Ghost -> palette.textPrimary
    }

    LiquidGlass(
        modifier = modifier
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.42f
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(height / 2),
        blurRadius = 14.dp,
        tintTop = if (kind == GlassButtonKind.Primary) {
            palette.buttonTintTop.copy(alpha = 0.80f)
        } else {
            palette.ghostTintTop
        },
        tintBottom = if (kind == GlassButtonKind.Primary) {
            palette.buttonTintBottom.copy(alpha = 0.80f)
        } else {
            palette.ghostTintBottom
        },
        strokeTop = Color.White.copy(alpha = if (kind == GlassButtonKind.Primary) 0.65f else 0.45f),
        strokeBottom = Color.White.copy(alpha = 0.10f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 玻璃输入框 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    height: Dp = 48.dp,
    textSize: Int = 13,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val palette = LocalGlassPalette.current
    LiquidGlass(
        modifier = modifier.height(height),
        shape = RoundedCornerShape(14.dp),
        blurRadius = 8.dp,
        tintTop = palette.fieldFill,
        tintBottom = palette.fieldFill.copy(alpha = palette.fieldFill.alpha * 0.6f),
        strokeTop = palette.fieldStroke,
        strokeBottom = palette.fieldStroke.copy(alpha = 0.35f),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = TextStyle(
                    color = palette.textPrimary,
                    fontSize = textSize.sp,
                ),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
                ),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = palette.textTertiary,
                                fontSize = textSize.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}


/** 小标签（key/hint 文本） */
@Composable
fun GlassLabel(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val palette = LocalGlassPalette.current
    Text(
        text = text,
        modifier = modifier,
        color = color ?: palette.textTertiary,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
}

