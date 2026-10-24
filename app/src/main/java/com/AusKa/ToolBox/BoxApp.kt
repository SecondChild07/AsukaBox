package com.AusKa.ToolBox

import android.app.Application
import com.AusKa.ToolBox.security.Guard

class BoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Guard.install(this)
    }
}
