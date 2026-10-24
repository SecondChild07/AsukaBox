package com.tencent.ToolBox

import android.app.Application
import com.tencent.ToolBox.security.Guard

class BoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Guard.install(this)
    }
}
