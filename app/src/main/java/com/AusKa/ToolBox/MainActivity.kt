package com.AusKa.ToolBox

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.AusKa.ToolBox.data.TimetableStore
import com.AusKa.ToolBox.data.TimetableSync
import com.AusKa.ToolBox.data.UrlStore
import com.AusKa.ToolBox.glass.GlassRoot
import com.AusKa.ToolBox.security.Guard
import com.AusKa.ToolBox.ui.BlockedScreen
import com.AusKa.ToolBox.ui.ToolboxRoot
import com.AusKa.ToolBox.widget.CourseWidgetProvider

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val store = UrlStore(this)

        setContent {
            val context = LocalContext.current

            // 首帧之后再补一次同步，处理「冷启动时后台那趟还没好」的情况；
            // TimetableSync 自己有 30 分钟节流，转屏重建不会重复登录
            LaunchedEffect(Unit) {
                when (val r = TimetableSync.refresh(TimetableStore.get(context))) {
                    is TimetableSync.Result.NeedCaptcha ->
                        Toast.makeText(context, "课表这次没更新：学校要求输验证码，到课表页点「重新导入」", Toast.LENGTH_LONG).show()

                    is TimetableSync.Result.Failed ->
                        if (r.reason != TimetableSync.BUSY) {
                            Toast.makeText(context, "课表这次没更新：${r.reason}", Toast.LENGTH_LONG).show()
                        }

                    else -> Unit
                }
                // 同步完（成功或失败）都顺手把桌面小组件重画一次，跨天了也能对上
                CourseWidgetProvider.refreshAll(context)
            }

            // 自检没过（改包名重签 / 有调试器 / 有注入框架）时不杀进程也不动数据，
            // 只把界面换成一段说明 —— 误报最多是功能不可用，数据还在。
            // 用 StateFlow：后台线程后来才发现问题（hookChecks）时也能切过去
            val blockedReason by Guard.blockedFlow.collectAsState()

            GlassRoot(dark = isSystemInDarkTheme()) {
                val reason = blockedReason
                if (reason != null) {
                    BlockedScreen(reason = reason)
                } else {
                    ToolboxRoot(
                        activity = this@MainActivity,
                        store = store,
                    )
                }
            }
        }
    }
}
