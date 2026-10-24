package com.tencent.ToolBox.ui

import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tencent.ToolBox.glass.GlassButton
import com.tencent.ToolBox.glass.GlassButtonKind
import com.tencent.ToolBox.glass.LocalGlassPalette
import com.tencent.ToolBox.location.CoordinateConverter

/**
 * 全屏选点地图（不是 Dialog —— WebView 放在 Dialog 窗口里会渲染不出瓦片，只能拿到逻辑层的回调）。
 *
 * 地图是打包在 assets 里的 Leaflet + 高德瓦片（GCJ-02），不用申请地图 SDK 的 key。
 * 点出来的坐标是 GCJ-02，交回去之前转成 WGS-84 —— 系统 GPS provider 认的是 WGS-84。
 */
@Composable
fun MapPickerScreen(
    latitude: Double,
    longitude: Double,
    onPick: (Double, Double) -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    var picked by remember { mutableStateOf(latitude to longitude) }

    val gcjStart = remember(latitude, longitude) {
        val g = CoordinateConverter.wgs84ToGcj02(latitude, longitude)
        g[0] to g[1]
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(52.dp)) {
                GlassButton(
                    text = "返回",
                    onClick = onBack,
                    height = 38.dp,
                    kind = GlassButtonKind.Ghost,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "在地图上选点",
                    color = palette.textPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "点一下地图，或者拖动蓝色标记",
                    color = palette.textTertiary,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp)),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript(
                                    "window.setPoint(${gcjStart.first}, ${gcjStart.second}, 16);",
                                    null,
                                )
                            }
                        }
                        addJavascriptInterface(Bridge { la, lo -> picked = la to lo }, "Android")
                        loadUrl("file:///android_asset/map.html")
                    }
                },
            )
        }

        Spacer(Modifier.height(10.dp))

        val wgs = CoordinateConverter.gcj02ToWgs84(picked.first, picked.second)
        Text(
            text = "地图坐标 %.6f, %.6f".format(picked.first, picked.second),
            color = palette.textTertiary,
            fontSize = 10.5.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "实际写入 %.6f, %.6f".format(wgs[0], wgs[1]),
            color = palette.textSecondary,
            fontSize = 11.5.sp,
        )

        Spacer(Modifier.height(10.dp))

        GlassButton(
            text = "用这个位置",
            onClick = {
                val p = CoordinateConverter.gcj02ToWgs84(picked.first, picked.second)
                onPick(p[0], p[1])
            },
            height = 50.dp,
        )

        Spacer(Modifier.height(14.dp))
    }
}

/** WebView 与 JS 之间的桥：地图点击后回调到这里 */
private class Bridge(private val onPick: (Double, Double) -> Unit) {
    @JavascriptInterface
    fun onPick(lat: String, lng: String) {
        val la = lat.toDoubleOrNull() ?: return
        val lo = lng.toDoubleOrNull() ?: return
        onPick(la, lo)
    }
}
