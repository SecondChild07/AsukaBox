package com.Auska.ToolBox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.Auska.ToolBox.glass.GlassTextField
import com.Auska.ToolBox.glass.LocalGlassPalette

/**
 * 弹窗里的「假玻璃」：Dialog 是独立窗口，拿不到主窗口录制的背景层，
 * 所以这里用同一套配色自己画一层半透明 + 描边，观感和页面里的玻璃基本一致。
 */
@Composable
fun SolidGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalGlassPalette.current
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        palette.glassTintTop.copy(alpha = if (palette.isDark) 0.92f else 0.96f),
                        palette.glassTintBottom.copy(alpha = if (palette.isDark) 0.92f else 0.92f),
                    )
                )
            )
            .drawWithCache {
                val w = 1.dp.toPx()
                val brush = Brush.verticalGradient(
                    listOf(palette.strokeTop, palette.strokeBottom)
                )
                onDrawBehind {
                    drawRoundRect(
                        brush = brush,
                        cornerRadius = CornerRadius(26.dp.toPx()),
                        style = Stroke(width = w),
                    )
                }
            }
    ) {
        content()
    }
}

@Composable
fun LinkInputDialog(
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val palette = LocalGlassPalette.current
    var text by remember { mutableStateOf(initial) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.padding(horizontal = 30.dp)) {
            SolidGlassPanel(Modifier.fillMaxWidth().padding(top = 1.dp)) {
                Column(Modifier.padding(22.dp)) {
                    Text(
                        text = "手动输入链接",
                        color = palette.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(16.dp))
                    GlassTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = "请输入或粘贴链接…",
                        modifier = Modifier.fillMaxWidth(),
                        height = 48.dp,
                        textSize = 13,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = "取消",
                            color = palette.textSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onDismiss,
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "确定",
                            color = palette.accent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onConfirm(text) },
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 输入里识别出多条链接时，让用户挑一条 */
@Composable
fun LinkChoiceDialog(
    urls: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val palette = LocalGlassPalette.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.padding(horizontal = 26.dp)) {
            SolidGlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp)) {
                    Text(
                        text = "发现多条链接，选一条签到",
                        color = palette.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(urls) { url ->
                            Text(
                                text = url,
                                color = palette.textSecondary,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onPick(url) },
                                    )
                                    .padding(vertical = 10.dp, horizontal = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = "取消",
                            color = palette.textSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onDismiss,
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

