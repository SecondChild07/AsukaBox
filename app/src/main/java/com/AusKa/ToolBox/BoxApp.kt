package com.AusKa.ToolBox

import android.app.Application
import com.AusKa.ToolBox.data.TimetableStore
import com.AusKa.ToolBox.security.Guard
import com.AusKa.ToolBox.security.SecretBox

class BoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 加密盒子要先拿到 Context 才能准备兜底钥匙：课表/签到链接这类
        // 「丢了就没法用」的数据是双副本写的，Keystore 被系统重置也还能读回来
        SecretBox.init(this)
        Guard.install(this)
        // 上次进程被系统杀掉 / 手机重启时留下的半截状态，用本地镜像补一次
        runCatching { TimetableStore.get(this).repairFromMirrorIfNeeded() }
    }
}
