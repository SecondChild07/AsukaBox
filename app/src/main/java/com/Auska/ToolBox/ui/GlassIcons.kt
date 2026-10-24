package com.Auska.ToolBox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 手绘图标。
 *
 * 没有引 material-icons —— 那玩意儿一整套图标有几 MB，而这里只需要六个。
 * 所有形状都按 Canvas 尺寸归一化，换成 20dp 还是 32dp 都自动缩放。
 */
@Composable
fun Icon(
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    draw: DrawScope.(Color) -> Unit,
) {
    Canvas(modifier.size(size)) { draw(color) }
}

/** 功能：2×2 圆角方块 */
fun DrawScope.appsIcon(color: Color) {
    val s = size.minDimension * 0.90f
    val origin = (size.minDimension - s) / 2f
    val stroke = s * 0.135f
    val gap = s * 0.19f
    val cell = (s - gap) / 2f
    val radius = CornerRadius(cell * 0.32f)
    for (col in 0..1) {
        for (row in 0..1) {
            drawRoundRect(
                color = color,
                topLeft = Offset(origin + col * (cell + gap), origin + row * (cell + gap)),
                size = Size(cell, cell),
                cornerRadius = radius,
                style = Stroke(width = stroke),
            )
        }
    }
}

/** 设置：齿轮 */
fun DrawScope.gearIcon(color: Color) {
    val s = size.minDimension
    val c = Offset(s / 2f, s / 2f)
    val stroke = s * 0.115f
    val ring = s * 0.29f
    repeat(8) { i ->
        rotate(degrees = i * 45f, pivot = c) {
            drawLine(
                color = color,
                start = Offset(c.x, c.y - ring - s * 0.11f),
                end = Offset(c.x, c.y - ring + s * 0.06f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
    drawCircle(color = color, radius = ring, center = c, style = Stroke(width = stroke))
    drawCircle(color = color, radius = s * 0.115f, center = c)
}

/** 返回：左折线 */
fun DrawScope.backIcon(color: Color) {
    val s = size.minDimension
    val stroke = s * 0.10f
    val tip = Offset(s * 0.36f, s * 0.5f)
    drawLine(color, Offset(s * 0.62f, s * 0.24f), tip, stroke, StrokeCap.Round)
    drawLine(color, tip, Offset(s * 0.62f, s * 0.76f), stroke, StrokeCap.Round)
}

/** 列表项右侧箭头 */
fun DrawScope.chevronIcon(color: Color) {
    val s = size.minDimension
    val stroke = s * 0.10f
    val tip = Offset(s * 0.60f, s * 0.5f)
    drawLine(color, Offset(s * 0.40f, s * 0.28f), tip, stroke, StrokeCap.Round)
    drawLine(color, tip, Offset(s * 0.40f, s * 0.72f), stroke, StrokeCap.Round)
}

/** NFC / 无线：圆点 + 三道弧 */
fun DrawScope.nfcIcon(color: Color) {
    val s = size.minDimension
    val stroke = s * 0.085f
    val c = Offset(s * 0.26f, s * 0.5f)
    drawCircle(color = color, radius = s * 0.075f, center = c)
    for (k in 1..3) {
        val r = s * (0.13f + 0.17f * k)
        drawArc(
            color = color,
            startAngle = -52f,
            sweepAngle = 104f,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}



/** 课表：日历轮廓 + 顶部挂环 + 中间「排课」横条 */
fun DrawScope.calendarIcon(color: Color) {
    val s = size.minDimension
    val stroke = s * 0.082f
    val w = s * 0.68f
    val h = s * 0.62f
    val left = (s - w) / 2f
    val top = s * 0.24f

    // 外框
    drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(w, h),
        cornerRadius = CornerRadius(w * 0.14f),
        style = Stroke(width = stroke),
    )
    // 表头横线
    drawLine(
        color = color,
        start = Offset(left, top + h * 0.28f),
        end = Offset(left + w, top + h * 0.28f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    // 两个挂环
    drawLine(
        color = color,
        start = Offset(left + w * 0.26f, top - h * 0.14f),
        end = Offset(left + w * 0.26f, top + h * 0.06f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(left + w * 0.74f, top - h * 0.14f),
        end = Offset(left + w * 0.74f, top + h * 0.06f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    // 三个小节次点
    val dotR = w * 0.06f
    listOf(0.30f, 0.50f, 0.70f).forEach { fx ->
        drawCircle(
            color = color,
            radius = dotR,
            center = Offset(left + w * fx, top + h * 0.50f),
        )
    }
    listOf(0.30f, 0.50f, 0.70f).forEach { fx ->
        drawCircle(
            color = color,
            radius = dotR,
            center = Offset(left + w * fx, top + h * 0.74f),
        )
    }
}


/** 虚拟定位：定位针（针尖 + 外环 + 中心点） */
fun DrawScope.locationIcon(color: Color) {
    val s = size.minDimension
    val cx = size.width / 2f
    val cy = s * 0.40f
    val r = s * 0.26f
    val stroke = s * 0.105f
    val tipY = s * 0.93f
    drawLine(color, Offset(cx - r * 0.70f, cy + r * 0.64f), Offset(cx, tipY), stroke, StrokeCap.Round)
    drawLine(color, Offset(cx + r * 0.70f, cy + r * 0.64f), Offset(cx, tipY), stroke, StrokeCap.Round)
    drawCircle(color, r, Offset(cx, cy), style = Stroke(width = stroke))
    drawCircle(color, r * 0.30f, Offset(cx, cy))
}
