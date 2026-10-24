package com.Auska.ToolBox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Auska.ToolBox.BuildConfig
import com.Auska.ToolBox.data.Author
import com.Auska.ToolBox.glass.LiquidGlass
import com.Auska.ToolBox.glass.LocalGlassPalette

/**
 * 设置页。
 *
 * 目前只有一条「作者主页」，其余是版本信息。以后加开关（自动签到、清理缓存之类）
 * 直接往 [LiquidGlass] 里塞 [GlassRow] + [GlassDivider] 就行。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = "设置",
            color = palette.textPrimary,
            fontSize = 30.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "关于这个箱子",
            color = palette.textTertiary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 22.dp),
        )

        Spacer(Modifier.height(26.dp))

        LiquidGlass(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            shape = RoundedCornerShape(22.dp),
            blurRadius = 20.dp,
            shadow = 6.dp,
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            Column {
                GlassRow(
                    title = "放到桌面",
                    subtitle = "把今天要上的课做成一张小卡片，添到桌面",
                    onClick = { requestWidgetPin(context) },
                    trailingArrow = true,
                )
                GlassDivider()
                GlassRow(
                    title = "作者主页",
                    subtitle = Author.URL.removePrefix("https://"),
                    onClick = { openLink(context, Author.URL) },
                    trailingArrow = true,
                )
                GlassDivider()
                GlassRow(title = "版本", value = BuildConfig.VERSION_NAME)
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "开源项目 · MIT License",
            color = palette.textTertiary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 22.dp),
        )

        Spacer(Modifier.height(26.dp))
    }
}

/**
 * 走系统的「请求固定小组件」流程：支持的桌面会直接弹确认框，
 * 不支持（少数第三方 launcher）就提示用户自己去桌面长按添加。
 */
private fun requestWidgetPin(context: android.content.Context) {
    val manager = android.appwidget.AppWidgetManager.getInstance(context)
    val provider = android.content.ComponentName(context, com.Auska.ToolBox.widget.CourseWidgetProvider::class.java)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(provider, null, null)
    } else {
        android.widget.Toast
            .makeText(context, "这个桌面不支持一键添加，长按桌面 → 小组件 里找「明日箱」", android.widget.Toast.LENGTH_LONG)
            .show()
    }
}
