package com.tencent.ToolBox

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.tencent.ToolBox.data.TimetableStore
import com.tencent.ToolBox.data.TimetableSync
import com.tencent.ToolBox.data.UrlStore
import com.tencent.ToolBox.glass.GlassRoot
import com.tencent.ToolBox.ui.ToolboxRoot
import com.tencent.ToolBox.widget.CourseWidgetProvider

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val store = UrlStore(this)

        setContent {
            val context = LocalContext.current

            // 每次冷启动在后台把课表刷一遍：成功了课表页自己就变，失败了安静地留旧数据
            LaunchedEffect(Unit) {
                when (val r = TimetableSync.refresh(TimetableStore.get(context))) {
                    is TimetableSync.Result.NeedCaptcha ->
                        Toast.makeText(context, "课表这次没更新：学校要求输验证码，到课表页点「重新导入」", Toast.LENGTH_LONG).show()

                    is TimetableSync.Result.Failed ->
                        if (r.reason != "正在同步") {
                            Toast.makeText(context, "课表这次没更新：${r.reason}", Toast.LENGTH_LONG).show()
                        }

                    else -> Unit
                }
                // 同步完（成功或失败）都顺手把桌面小组件重画一次，跨天了也能对上
                CourseWidgetProvider.refreshAll(context)
            }

            GlassRoot(dark = isSystemInDarkTheme()) {
                ToolboxRoot(
                    activity = this@MainActivity,
                    store = store,
                )
            }
        }
    }
}
