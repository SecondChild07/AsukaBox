package com.tencent.ToolBox.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.delay
import com.tencent.ToolBox.location.MockLocationService

/**
 * 虚拟定位页。
 *
 * 走系统 test provider，不需要 root。位置要么手填经纬度，要么在地图上点一个点。
 */
@Composable
fun LocationScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val palette = LocalGlassPalette.current
    val store = remember { LocationStore(context) }

    var lat by remember { mutableStateOf(store.lastLat) }
    var lon by remember { mutableStateOf(store.lastLon) }
    var latText by remember { mutableStateOf(fmt(lat)) }
    var lonText by remember { mutableStateOf(fmt(lon)) }
    var note by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(MockLocationService.isRunning) }
    var allowed by remember { mutableStateOf(MockLocationService.canMock(context)) }
    var showMap by remember { mutableStateOf(false) }
    var pushed by remember { mutableStateOf(MockLocationService.pushedCount) }
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    // 推了几次、有没有报错，每 800ms 刷一次给界面看
    LaunchedEffect(running) {
        while (running) {
            pushed = MockLocationService.pushedCount
            delay(800)
        }
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

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
        if (!locationGranted) {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    fun apply(newLat: Double, newLon: Double) {
        lat = newLat
        lon = newLon
        latText = fmt(newLat)
        lonText = fmt(newLon)
        store.lastLat = newLat
        store.lastLon = newLon
        if (running) MockLocationService.update(context, newLat, newLon)
    }

    fun start() {
        val vLat = latText.trim().toDoubleOrNull()
        val vLon = lonText.trim().toDoubleOrNull()
        if (vLat == null || vLon == null || vLat !in -90.0..90.0 || vLon !in -180.0..180.0) {
            note = "经纬度不对：纬度 -90~90，经度 -180~180"
            return
        }
        if (!MockLocationService.canMock(context)) {
            allowed = false
            note = "还没被选为「模拟位置信息应用」，先去开发者选项里选一下"
            return
        }
        lat = vLat
        lon = vLon
        store.lastLat = vLat
        store.lastLon = vLon
        MockLocationService.start(context, vLat, vLon, "")
        running = true
        note = "已开始，改坐标会立刻生效"
    }

    fun stop() {
        MockLocationService.stop(context)
        running = false
        note = "已停止，定位回到真实位置"
    }

    if (showMap) {
        MapPickerScreen(
            latitude = lat,
            longitude = lon,
            onPick = { pickedLat, pickedLon ->
                apply(pickedLat, pickedLon)
                note = "已从地图选点"
                showMap = false
            },
            onBack = { showMap = false },
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))

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
                        "已获得模拟位置权限 · 系统里所有定位来源一起改"
                    } else {
                        "还没有模拟位置权限，点下面的按钮去开发者选项里把明日箱选上"
                    },
                    color = palette.textTertiary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                )
                if (running) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append(if (pushed > 0) "已推送 $pushed 次" else "还没推送出去")
                            val last = MockLocationService.lastPushAt
                            if (last > 0) {
                                val secs = ((System.currentTimeMillis() - last) / 1000).coerceAtLeast(0)
                                append(" · 最后 ${secs}s 前")
                            }
                            val ow = MockLocationService.overwrittenCount
                            if (ow > 0) append(" · 被覆盖 $ow 次")
                        },
                        color = if (MockLocationService.overwrittenCount > 0) palette.warn else palette.textTertiary,
                        fontSize = 11.sp,
                    )
                }
                val err = MockLocationService.lastError
                if (err.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = err,
                        color = palette.danger,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                    )
                }
                if (!locationGranted) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "定位权限没给：部分系统会因此拒绝模拟位置。点下面的按钮授权",
                        color = palette.warn,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LiquidGlass(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            blurRadius = 18.dp,
            shadow = 6.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Column {
                GlassLabel("位置")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassTextField(
                        value = latText,
                        onValueChange = { latText = it; it.toDoubleOrNull()?.let { v -> lat = v } },
                        placeholder = "纬度",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    GlassTextField(
                        value = lonText,
                        onValueChange = { lonText = it; it.toDoubleOrNull()?.let { v -> lon = v } },
                        placeholder = "经度",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                GlassButton(
                    text = "在地图上选点",
                    onClick = { showMap = true },
                    height = 46.dp,
                )
                Spacer(Modifier.height(8.dp))
                GlassButton(
                    text = "粘贴坐标",
                    onClick = {
                        val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                            ?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString()
                        val parsed = parseCoordinate(text.orEmpty())
                        if (parsed == null) {
                            note = "剪贴板里没找到「纬度, 经度」这样的数字"
                        } else {
                            apply(parsed.first, parsed.second)
                            note = "已从剪贴板填入"
                        }
                    },
                    height = 42.dp,
                    kind = GlassButtonKind.Ghost,
                )
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

private fun fmt(value: Double): String = "%.6f".format(value)

/** 从一段文字里抠出「纬度, 经度」 */
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
