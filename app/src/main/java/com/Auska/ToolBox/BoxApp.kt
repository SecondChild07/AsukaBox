package com.Auska.ToolBox

import android.app.Application
import com.Auska.ToolBox.security.Guard

class BoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Guard.install(this)
    }
}
