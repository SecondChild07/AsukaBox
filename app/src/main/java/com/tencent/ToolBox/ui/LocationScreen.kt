package com.tencent.ToolBox.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos

/**
 * 虚拟定位页。
 *
 * 和「影梭」那类工具是同一套底层：往系统的 test provider 里灌坐标，不需要 root。
 * 这里多做了一层摇杆 —— 按住方向，坐标按设定速度连续移动，走出来的是一条轨迹，不是瞬移。
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

    var dirX by remember { mutableStateOf(0f) }
    var dirY by remember { mutableStateOf(0f) }
    var speedIndex by remember { mutableIntStateOf(0) }

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

    // 摇杆推动时按速度连续移动坐标
    // 注意：dirX/dirY 不能作为 key —— 拖动时它们每帧都在变，会把协程反复重启，delay 永远跑不完
    val dirXNow by rememberUpdatedState(dirX)
    val dirYNow by rememberUpdatedState(dirY)
    val speedNow by rememberUpdatedState(speedIndex)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            delay(TICK_MS.toLong())
            val dx = dirXNow
            val dy = dirYNow
            if (abs(dx) <= 0.02f && abs(dy) <= 0.02f) continue
            val metersPerSecond = SPEEDS[speedNow]
            val seconds = TICK_MS / 1000.0
            lat += dy * metersPerSecond * seconds / METERS_PER_DEG_LAT
            lon += dx * metersPerSecond * seconds / metersPerDegLon(lat)
            lat = lat.coerceIn(-89.9, 89.9)
            lon = ((lon + 540.0) % 360.0) - 180.0
            latText = fmt(lat)
            lonText = fmt(lon)
            MockLocationService.update(context, lat, lon)
        }
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
        note = "已开始，摇杆可以推着走"
    }

    fun stop() {
        MockLocationService.stop(context)
        running = false
        dirX = 0f
        dirY = 0f
        note = "已停止，定位回到真实位置"
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
                            lat = parsed.first
                            lon = parsed.second
                            latText = fmt(lat)
                            lonText = fmt(lon)
                            note = "已从剪贴板填入"
                        }
                    },
                    height = 44.dp,
                    kind = GlassButtonKind.Ghost,
                )
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GlassLabel("推着走")
                Spacer(Modifier.height(10.dp))
                Joystick(
                    enabled = running,
                    onChange = { dx, dy ->
                        dirX = dx
                        dirY = dy
                    },
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEED_LABELS.forEachIndexed { index, label ->
                        SpeedChip(
                            text = label,
                            selected = index == speedIndex,
                            onClick = { speedIndex = index },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (running) {
                        "按住摇杆拖动，坐标会按「${SPEED_LABELS[speedIndex]}」的速度连续移动"
                    } else {
                        "先点下面的「开始模拟」，摇杆才会生效"
                    },
                    color = palette.textTertiary,
                    fontSize = 10.5.sp,
                )
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
                GlassLabel("常用地点")
                Spacer(Modifier.height(4.dp))
                PRESETS.forEach { preset ->
                    PresetRow(preset) { p ->
                        lat = p.first
                        lon = p.second
                        latText = fmt(lat)
                        lonText = fmt(lon)
                        if (running) MockLocationService.update(context, lat, lon)
                    }
                }
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

/** 摇杆：外圈 + 可拖的把手，输出 -1..1 的方向（右为 +x，上为 +y） */
@Composable
private fun Joystick(
    enabled: Boolean,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current
    val maxRadius = with(density) { 44.dp.toPx() }
    var knob by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .size(124.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // 必须在 Initial 阶段就把事件扣住，否则外层的 verticalScroll 会先把它当成滚动吃掉
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val delta = change.position - last
                        last = change.position
                        val next = knob + delta
                        val len = next.getDistance()
                        knob = if (len > maxRadius) next * (maxRadius / len) else next
                        onChange(knob.x / maxRadius, -knob.y / maxRadius)
                    }
                    knob = Offset.Zero
                    onChange(0f, 0f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(palette.fieldFill, radius, center)
            drawCircle(palette.fieldStroke, radius, center, style = Stroke(1.dp.toPx()))
            val line = if (palette.isDark) Color(0x1FFFFFFF) else Color(0x14000000)
            drawLine(
                line,
                Offset(center.x - radius * 0.62f, center.y),
                Offset(center.x + radius * 0.62f, center.y),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                line,
                Offset(center.x, center.y - radius * 0.62f),
                Offset(center.x, center.y + radius * 0.62f),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(
                color = if (enabled) palette.accent else palette.textTertiary,
                radius = radius * 0.30f,
                center = center + knob,
            )
        }
    }
}

@Composable
private fun SpeedChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        )
    ) {
        LiquidGlass(
            modifier = Modifier.height(30.dp),
            shape = RoundedCornerShape(15.dp),
            blurRadius = 12.dp,
            tintTop = if (selected) palette.accent.copy(alpha = 0.22f) else null,
            tintBottom = if (selected) palette.accent.copy(alpha = 0.10f) else null,
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = text,
                    color = if (selected) palette.accent else palette.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: Pair<String, Pair<Double, Double>>,
    onClick: (Pair<Double, Double>) -> Unit,
) {
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = preset.first, color = palette.textPrimary, fontSize = 13.sp)
            Text(
                text = "%.4f, %.4f".format(preset.second.first, preset.second.second),
                color = palette.textTertiary,
                fontSize = 11.sp,
            )
        }
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

private fun fmt(value: Double): String = "%.6f".format(value)

private fun metersPerDegLon(lat: Double): Double =
    (METERS_PER_DEG_LAT * cos(Math.toRadians(lat))).coerceAtLeast(1.0)

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

private const val TICK_MS = 200
private const val METERS_PER_DEG_LAT = 111320.0

/** 速度档，单位 m/s */
private val SPEEDS = doubleArrayOf(1.4, 4.2, 13.9)
private val SPEED_LABELS = listOf("步行", "骑行", "开车")

private val PRESETS = listOf(
    "天安门" to (39.908823 to 116.397470),
    "上海外滩" to (31.239666 to 121.490246),
    "广州塔" to (23.106612 to 113.324520),
)
