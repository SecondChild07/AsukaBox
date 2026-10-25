package com.AusKa.ToolBox.data

import android.content.Context
import com.AusKa.ToolBox.security.SecretBox

/**
 * 高德 Web 服务 Key 的本地存储。
 *
 * Key 算凭证（别人拿去就能刷你的免费额度），所以和教务密码一样用 SecretBox 加密存。
 * 空串表示没配 —— 这时地图只搜内置的离线地名库。
 */
internal object AmapKeyStore {

    private const val PREFS = "amap_key"
    private const val KEY = "k"

    fun get(context: Context): String {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (stored.isEmpty()) return ""
        val opened = SecretBox.open(stored)
        return if (opened.isNotEmpty()) opened else stored
    }

    fun set(context: Context, key: String) {
        val k = key.trim()
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (k.isEmpty()) e.remove(KEY) else e.putString(KEY, SecretBox.seal(k))
        e.apply()
    }
}
