package com.AusKa.ToolBox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.AusKa.ToolBox.glass.LiquidGlass
import com.AusKa.ToolBox.glass.LocalGlassPalette

/**
 * 自检没过时显示的页面。
 *
 * 刻意不杀进程、不清数据：被检测到的原因里有一半是误报（装过 LSPosed 的机器、某些 ROM 的 maps 里
 * 带 gum 字样、/data/local/tmp 有残留文件），让用户凭空丢掉课表和签到链接不合适。
 */
@Composable
fun BlockedScreen(reason: String, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(60.dp))

        Text("这个包被改动过", fontSize = 26.sp, fontWeight = FontWeight.Black, color = palette.textPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            "明日箱的自检没有通过，功能已经停用。",
            fontSize = 13.sp,
            color = palette.textSecondary,
        )

        Spacer(Modifier.height(22.dp))

        LiquidGlass(
            modifier = Modifier.fillMaxWidth(),
            shadow = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("检测到", fontSize = 12.sp, color = palette.textTertiary)
                Spacer(Modifier.height(6.dp))
                Text(reason.ifBlank { "签名或运行环境和官方发布的不一致" }, fontSize = 14.sp, color = palette.danger)
                Spacer(Modifier.height(14.dp))
                Text(
                    "常见原因：拿别的证书重签过、改过包名、或者当前系统里挂着调试器 / 注入框架。",
                    fontSize = 12.sp,
                    color = palette.textSecondary,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "你的课表、账号、签到链接都还在本地，没有被动过。换回官方签名的安装包就能继续用。",
                    fontSize = 12.sp,
                    color = palette.textSecondary,
                    lineHeight = 18.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
