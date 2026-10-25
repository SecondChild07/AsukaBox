package com.AusKa.ToolBox.ui

import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceRequest
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
import com.AusKa.ToolBox.glass.GlassButton
import com.AusKa.ToolBox.glass.GlassButtonKind
import com.AusKa.ToolBox.glass.LocalGlassPalette
import com.AusKa.ToolBox.data.AmapKeyStore
import com.AusKa.ToolBox.location.CoordinateConverter
import com.AusKa.ToolBox.net.AmapSearch
import androidx.compose.ui.platform.LocalContext

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
    val ctx = LocalContext.current
    // 进页面时读一次；在设置里改完 key 再回来会重新读
    val amapKey = remember { AmapKeyStore.get(ctx) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
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
                        // allowFileAccess 是加载 file:///android_asset/ 所必需的，
                        // 但 allowContentAccess（content:// 随便读）用不上，关掉。
                        // 两样都开到最松 + addJavascriptInterface 是典型的危险组合。
                        settings.allowFileAccess = true
                        settings.allowContentAccess = false
                        settings.allowFileAccessFromFileURLs = false
                        settings.allowUniversalAccessFromFileURLs = false
                        // 用默认缓存：原来的 LOAD_NO_CACHE 让每次打开都重下全部瓦片
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = object : WebViewClient() {
                            // 只允许留在本地页：地图里任何外链都不该把整个 WebView 带走
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = !(request?.url?.toString()?.startsWith("file:///android_asset/") == true)

                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript(
                                    "window.setPoint(${gcjStart.first}, ${gcjStart.second}, 16);",
                                    null,
                                )
                            }
                        }
                        webViewRef = this
                        addJavascriptInterface(
                            Bridge(
                                keyProvider = { amapKey },
                                onPick = { la, lo -> picked = la to lo },
                                onResult = { json ->
                                    post {
                                        evaluateJavascript(
                                            "window.__onSearchResult($json);",
                                            null,
                                        )
                                    }
                                },
                            ),
                            "Android",
                        )
                        loadUrl("file:///android_asset/map.html")
                    }
                },
                onRelease = { wv ->
                    runCatching {
                        wv.stopLoading()
                        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                        wv.destroy()
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

/**
 * WebView 与 JS 之间的桥。
 *
 * - onPick：地图上点选 / 拖动标记后回传 GCJ-02 坐标
 * - searchPoi：在 WebView 里搜不出结果时，交给原生去调高德输入提示（避开 file:// 的跨域限制）
 *
 * 注意：@JavascriptInterface 的方法都在 WebView 的 JavaBridge 线程上执行，不能直接碰 View/Compose 状态，
 * 所以这里自己起线程，回调也自己切主线程。
 */
private class Bridge(
    private val keyProvider: () -> String,
    private val onPick: (Double, Double) -> Unit,
    private val onResult: (String) -> Unit,
) {
    @JavascriptInterface
    fun onPick(lat: String, lng: String) {
        val la = lat.toDoubleOrNull() ?: return
        val lo = lng.toDoubleOrNull() ?: return
        onPick(la, lo)
    }

    @JavascriptInterface
    fun searchPoi(keyword: String) {
        val key = keyProvider()
        if (key.isBlank()) {
            onResult("[]")
            return
        }
        Thread {
            val json = runCatching { AmapSearch.searchJson(key, keyword) }.getOrDefault("[]")
            onResult(json)
        }.apply { isDaemon = true }.start()
    }
}
