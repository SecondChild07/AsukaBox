package com.tencent.ToolBox.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tencent.ToolBox.data.LocationStore
import com.tencent.ToolBox.glass.GlassButton
import com.tencent.ToolBox.glass.GlassButtonKind
import com.tencent.ToolBox.glass.GlassLabel
import com.tencent.ToolBox.glass.GlassTextField
import com.tencent.ToolBox.glass.LiquidGlass
import com.tencent.ToolBox.glass.LocalGlassPalette
import com.tencent.ToolBox.location.MockLocationService

/**
 * 虚拟定位页。
 *
 * 交互刻意做得素：两个输入框 + 一组常用坐标 + 一个开关。
 * 不放地图 —— 引一个地图 SDK 会给这个 2MB 的包塞进 10MB 和一堆权限，
 * 而真正要的只是「把坐标定在那儿」，粘一串经纬度就够了。
 */
@Composable
fun LocationScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val palette = LocalGlassPalette.current
    val store = remember { LocationStore(context) }

    var latText by remember { mutableStateOf(store.lastLat.toString()) }
    var lonText by remember { mutableStateOf(store.lastLon.toString()) }
    var note by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(MockLocationService.isRunning) }
    var allowed by remember { mutableStateOf(MockLocationService.canMock(context)) }

    // 从开发者选项切回来时重新判断一次授权状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = MockLocationService.canMock(context)
                running = MockLocationService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 前台服务要挂一条常驻通知，先在进页面时把权限问了，免得点「开始」时被打断
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun start() {
        val lat = latText.trim().toDoubleOrNull()
        val lon = lonText.trim().toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            note = "经纬度不对：维度 -90~90，经度 -180~180"
            return
        }
        if (!MockLocationService.canMock(context)) {
            allowed = false
            note = "还没被选为「模拟位置信息应用」，先去开发者选项里选一下"
            return
        }
        store.lastLat = lat
        store.lastLon = lon
        MockLocationService.start(context, lat, lon, "")
        running = true
        note = "已开始，坐标会每秒刷新一次"
    }

    fun stop() {
        MockLocationService.stop(context)
        running = false
        note = "已停止，定位回到真实位置"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))

        // 状态卡
        LiquidGlass(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            blurRadius = 18.dp,
            shadow = 6.dp,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column {
                Text(
                    text = if (running) "正在模拟" else "未开启",
                    color = if (running) palette.accent else palette.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (allowed) {
                        "已获得模拟位置权限 · gps 与 network 两个来源一起改"
                    } else {
                        "还没有模拟位置权限，点下面的按钮去开发者选项里把明日箱选上"
                    },
                    color = palette.textTertiary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 坐标输入
        LiquidGlass(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            blurRadius = 18.dp,
            shadow = 6.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Column {
                GlassLabel("坐标")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    GlassTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        placeholder = "纬度",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    GlassTextField(
                        value = lonText,
                        onValueChange = { lonText = it },
                        placeholder = "经度",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                GlassButton(
                    text = "从剪贴板粘贴坐标",
                    onClick = {
                        val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                            ?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString()
                        val parsed = parseCoordinate(text.orEmpty())
                        if (parsed == null) {
                            note = "剪贴板里没找到「纬度, 经度」这样的数字"
                        } else {
                            latText = parsed.first.toString()
                            lonText = parsed.second.toString()
                            note = "已从剪贴板填入"
                        }
                    },
                    height = 44.dp,
                    kind = GlassButtonKind.Ghost,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 常用坐标
        LiquidGlass(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            blurRadius = 18.dp,
            shadow = 6.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Column {
                GlassLabel("常用地点")
                Spacer(Modifier.height(4.dp))
                PresetRow(PRESETS[0]) { latText = it.first.toString(); lonText = it.second.toString() }
                PresetRow(PRESETS[1]) { latText = it.first.toString(); lonText = it.second.toString() }
                PresetRow(PRESETS[2]) { latText = it.first.toString(); lonText = it.second.toString() }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (running) {
            GlassButton(text = "停止模拟", onClick = { stop() }, height = 52.dp)
        } else {
            GlassButton(text = "开始模拟", onClick = { start() }, height = 52.dp)
        }

        Spacer(Modifier.height(10.dp))

        if (!allowed) {
            GlassButton(
                text = "去开发者选项选「模拟位置信息应用」",
                onClick = { MockLocationService.openDeveloperOptions(context) },
                height = 48.dp,
                kind = GlassButtonKind.Ghost,
            )
            Spacer(Modifier.height(10.dp))
        }

        if (note.isNotBlank()) {
            Text(
                text = note,
                color = palette.textTertiary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
        }

        Text(
            text = "只改「模拟位置」这一层，不碰系统设置。打车、打卡这类会检测模拟位置的 App 仍可能识别出来。",
            color = palette.textTertiary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PresetRow(preset: Pair<String, Pair<Double, Double>>, onClick: (Pair<Double, Double>) -> Unit) {
    val palette = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = preset.first,
                color = palette.textPrimary,
                fontSize = 13.sp,
            )
            Text(
                text = "%.4f, %.4f".format(preset.second.first, preset.second.second),
                color = palette.textTertiary,
                fontSize = 11.sp,
            )
        }
        // 覆盖一层可点区域
        Box(
            Modifier
                .matchParentSize()
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { onClick(preset.second) },
                )
        )
    }
}

/** 从一段文字里抠出「纬度, 经度」，支持逗号/空格/换行分隔 */
private fun parseCoordinate(raw: String): Pair<Double, Double>? {
    val nums = Regex("""-?\d+(?:\.\d+)?""").findAll(raw)
        .mapNotNull { it.value.toDoubleOrNull() }
        .toList()
    if (nums.size < 2) return null
    val lat = nums[0]
    val lon = nums[1]
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return lat to lon
}

private val PRESETS = listOf(
    "天安门" to (39.908823 to 116.397470),
    "上海外滩" to (31.239666 to 121.490246),
    "广州塔" to (23.106612 to 113.324520),
)
