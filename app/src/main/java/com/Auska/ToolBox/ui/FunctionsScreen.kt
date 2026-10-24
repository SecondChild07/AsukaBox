package com.Auska.ToolBox.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Auska.ToolBox.glass.LiquidGlass
import com.Auska.ToolBox.glass.LocalGlassPalette

/**
 * 一个功能项的声明。
 *
 * 以后加新功能只需要往 [featureList] 里加一条 + 在 AppNav 里接上路由，
 * 列表会自动排布，不用动布局代码。
 */
data class Feature(
    val id: String,
    val name: String,
    val desc: String,
    val color: Color,
    val draw: DrawScope.(Color) -> Unit,
)

val featureList: List<Feature> = listOf(
    Feature(
        id = "nfc",
        name = "NFC 签到",
        desc = "贴着卡片点一下，直接拉起支付宝",
        color = Color(0xFF4F6BFF),
        draw = { nfcIcon(it) },
    ),
    Feature(
        id = "timetable",
        name = "课表",
        desc = "从正方教务导入这个学期的课",
        color = Color(0xFF14A8A0),
        draw = { calendarIcon(it) },
    ),
    Feature(
        id = "location",
        name = "虚拟定位",
        desc = "把手机定位固定在任意坐标",
        color = Color(0xFF7B61FF),
        draw = { locationIcon(it) },
    ),
)

/**
 * 功能页：一行一个，竖向排列。
 *
 * 每行左侧一块彩色图标，右侧名称 + 一句说明，末尾一个箭头。
 * 竖排的好处是功能再多也不会挤，说明文字也有地方放。
 */
@Composable
fun FunctionsScreen(
    onOpenFeature: (Feature) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))

        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(
                text = "明日箱",
                color = palette.textPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "把零碎的小事收进一个箱子",
                color = palette.textTertiary,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(26.dp))

        Text(
            text = "全部功能",
            color = palette.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 22.dp),
        )

        Spacer(Modifier.height(12.dp))

        Column(Modifier.padding(horizontal = 18.dp)) {
            featureList.forEach { feature ->
                FeatureRow(feature = feature, onClick = { onOpenFeature(feature) })
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FeatureRow(
    feature: Feature,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 720f),
        label = "featureRowScale",
    )

    LiquidGlass(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(20.dp),
        blurRadius = 18.dp,
        shadow = 6.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(feature.color, feature.color.copy(alpha = 0.72f))
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    size = 24.dp,
                    color = Color.White,
                    draw = feature.draw,
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = feature.name,
                    color = palette.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = feature.desc,
                    color = palette.textTertiary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                )
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                size = 13.dp,
                color = palette.textTertiary,
                draw = { chevronIcon(it) },
            )
        }
    }
}

/** 首页大标题下方的小圆点装饰，暂时留空用 */
@Composable
fun Dot(modifier: Modifier = Modifier, color: Color) {
    Box(
        modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color)
    )
}
