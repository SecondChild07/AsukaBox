package com.AusKa.ToolBox.ui

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.AusKa.ToolBox.BuildConfig
import com.AusKa.ToolBox.data.AmapKeyStore
import com.AusKa.ToolBox.data.Author
import com.AusKa.ToolBox.data.TimetableStore
import com.AusKa.ToolBox.data.UrlStore
import com.AusKa.ToolBox.glass.LiquidGlass
import com.AusKa.ToolBox.glass.LocalGlassPalette

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
    val app = context.applicationContext
    val urlStore = remember { UrlStore(app) }
    val ttStore = remember { TimetableStore.get(app) }
    var autoCheckin by remember { mutableStateOf(urlStore.autoCheckin) }
    var confirmWipe by remember { mutableStateOf(false) }
    var amapKey by remember { mutableStateOf(AmapKeyStore.get(app)) }
    var editKey by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    val hasAccount = ttStore.hasAccount

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
                // 地图搜索：不填就只能搜内置的 188 条城市/地标
                GlassRow(
                    title = "高德 Key",
                    subtitle = if (amapKey.isBlank())
                        "填了才能在地图上搜任意地点（开放平台免费申请，选「Web服务」）"
                    else
                        "已配置 · 地图搜索可以搜到具体地点",
                    value = if (amapKey.isBlank()) "未填" else "已填",
                    onClick = { keyDraft = amapKey; editKey = true },
                    trailingArrow = true,
                )
                GlassDivider()
                // 31：贴卡自动拉起支付宝是个不可撤销的外发动作，得能关
                GlassRow(
                    title = "贴卡自动签到",
                    subtitle = if (autoCheckin) "读到链接就自动拉起支付宝" else "只显示链接，点了才拉起",
                    value = if (autoCheckin) "开" else "关",
                    onClick = {
                        autoCheckin = !autoCheckin
                        urlStore.autoCheckin = autoCheckin
                    },
                )
                GlassDivider()
                GlassRow(
                    title = "清除签到链接",
                    subtitle = "把存在本机的支付宝签到链接删掉",
                    onClick = { urlStore.clear() },
                )
                if (hasAccount) {
                    GlassDivider()
                    // 37：存了别人的教务密码，就得给一条能删干净的路
                    GlassRow(
                        title = "清除教务账号",
                        subtitle = "账号、密码、会话、课表一起删掉",
                        onClick = { confirmWipe = true },
                    )
                }
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

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("清除教务账号？") },
            text = { Text("学号、密码、登录会话和已导入的课表都会从这台手机删掉，桌面卡片也会变空。这个操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    ttStore.wipeAccount()
                    urlStore.autoCheckin = false
                    confirmWipe = false
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("取消") }
            },
        )
    }

    if (editKey) {
        AlertDialog(
            onDismissRequest = { editKey = false },
            title = { Text("高德 Key") },
            text = {
                Column {
                    Text(
                        "在 lbs.amap.com 控制台创建应用 → 添加 Key → 服务平台选「Web服务」，把那串 key 粘进来。",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        singleLine = true,
                        placeholder = { Text("例如 a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "留空表示不用联网搜索，地图里只能搜内置的 188 条城市/地标。",
                        fontSize = 12.sp,
                        color = palette.textTertiary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AmapKeyStore.set(app, keyDraft)
                    amapKey = keyDraft.trim()
                    editKey = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editKey = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 走系统的「请求固定小组件」流程：支持的桌面会直接弹确认框，
 * 不支持（少数第三方 launcher）就提示用户自己去桌面长按添加。
 */
private fun requestWidgetPin(context: android.content.Context) {
    val manager = android.appwidget.AppWidgetManager.getInstance(context)
    val provider = android.content.ComponentName(context, com.AusKa.ToolBox.widget.CourseWidgetProvider::class.java)
    val canPin = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
        manager.isRequestPinAppWidgetSupported
    if (canPin) {
        manager.requestPinAppWidget(provider, null, null)
    } else {
        android.widget.Toast
            .makeText(context, "这个桌面不支持一键添加，长按桌面 → 小组件 里找「明日箱」", android.widget.Toast.LENGTH_LONG)
            .show()
    }
}
