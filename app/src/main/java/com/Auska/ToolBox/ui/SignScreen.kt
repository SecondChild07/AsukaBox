package com.Auska.ToolBox.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.ComponentActivity
import com.Auska.ToolBox.alipay.AlipayCheckin
import com.Auska.ToolBox.data.UrlStore
import com.Auska.ToolBox.glass.GlassButton
import com.Auska.ToolBox.glass.GlassButtonKind
import com.Auska.ToolBox.glass.GlassLabel
import com.Auska.ToolBox.glass.LiquidGlass
import com.Auska.ToolBox.glass.LocalGlassPalette
import com.Auska.ToolBox.nfc.NfcReader
import com.Auska.ToolBox.nfc.UrlPicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SignScreen(
    activity: ComponentActivity,
    store: UrlStore,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val reader = remember { NfcReader(activity) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var savedUrl by remember { mutableStateOf(store.url) }
    var record by remember { mutableStateOf("正在检测 NFC…") }
    var status by remember {
        mutableStateOf(
            if (store.url.isBlank()) "等待贴卡，或长按「签到」手动输入"
            else "已载入本地保存的链接，点「签到」即可，无需重新贴卡"
        )
    }
    var showInput by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<String>?>(null) }

    fun doCheckin(target: String) {
        savedUrl = target
        store.url = target
        status = when (AlipayCheckin.launch(activity, target)) {
            AlipayCheckin.Result.Success -> "已发送签到 ✓ " + UrlPicker.shortUrl(target)
            AlipayCheckin.Result.NoAlipay -> "跳转失败，请确认已装支付宝"
            AlipayCheckin.Result.NoUrl -> "没有可用的链接"
            AlipayCheckin.Result.Failed -> "跳转失败"
        }
    }

    fun consume(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) {
            status = "未输入内容"
            return
        }
        val normalized = UrlPicker.normalize(value)
        if (UrlPicker.looksLikeUrl(normalized)) {
            doCheckin(normalized)
            return
        }
        val list = UrlPicker.extract(value)
        when {
            list.isEmpty() -> status = "没识别到链接，请检查输入"
            list.size == 1 -> doCheckin(list[0])
            else -> candidates = list
        }
    }

    DisposableEffect(reader) {
        reader.onReading = { reading ->
            record = reading.detail
            val ranked = UrlPicker.rank(reading.urls)
            val best = ranked.firstOrNull()
            if (best == null) {
                status = "读到了卡，但记录里没有可用链接"
            } else {
                savedUrl = best
                store.url = best
                status = if (UrlPicker.isCheckinLink(best)) {
                    "已识别到目标链接并自动填入 ✓"
                } else {
                    "已自动填入链接 ✓ " + UrlPicker.shortUrl(best)
                }
                if (store.autoCheckin) {
                    scope.launch {
                        delay(700)
                        doCheckin(best)
                    }
                }
            }
        }

        when (val st = reader.start()) {
            NfcReader.Status.Ready -> record = "等待贴卡…"
            NfcReader.Status.Disabled -> {
                record = "NFC 已关闭"
                status = "请在系统设置里打开 NFC"
            }

            NfcReader.Status.Unsupported -> {
                record = "NFC 不可用：本机没有 NFC 硬件\n长按「签到」可手动输入链接"
            }
        }

        onDispose { reader.stop() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> reader.start()
                Lifecycle.Event.ON_PAUSE -> reader.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            blurRadius = 20.dp,
            shadow = 6.dp,
            contentPadding = PaddingValues(16.dp),
        ) {
            Column {
                GlassLabel("当前链接")
                Spacer(Modifier.height(7.dp))
                Text(
                    text = if (savedUrl.isBlank()) "等待识别中" else "已录入",
                    color = if (savedUrl.isBlank()) palette.textTertiary else palette.success,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        LiquidGlass(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            blurRadius = 20.dp,
            shadow = 6.dp,
            contentPadding = PaddingValues(16.dp),
        ) {
            Column {
                GlassLabel("读卡记录")
                Spacer(Modifier.height(7.dp))
                Text(
                    text = record,
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    maxLines = 10,
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        GlassButton(
            text = "签  到",
            onClick = {
                if (savedUrl.isBlank()) showInput = true else doCheckin(savedUrl)
            },
            onLongClick = { showInput = true },
            modifier = Modifier.fillMaxWidth(),
            height = 56.dp,
        )

        Spacer(Modifier.height(12.dp))

        GlassButton(
            text = "清空已保存链接",
            kind = GlassButtonKind.Ghost,
            onClick = {
                store.clear()
                savedUrl = ""
                record = "等待贴卡…"
                status = "已清空，贴卡或长按「签到」重新输入"
            },
            modifier = Modifier.fillMaxWidth(),
            height = 50.dp,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = status,
            color = palette.textSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "贴卡自动识别 · 长按「签到」手动输入",
            color = palette.textTertiary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
    }

    if (showInput) {
        LinkInputDialog(
            initial = "",
            onDismiss = { showInput = false },
            onConfirm = { value ->
                showInput = false
                consume(value)
            },
        )
    }

    candidates?.let { list ->
        LinkChoiceDialog(
            urls = list,
            onDismiss = { candidates = null },
            onPick = { picked ->
                candidates = null
                doCheckin(picked)
            },
        )
    }
}
