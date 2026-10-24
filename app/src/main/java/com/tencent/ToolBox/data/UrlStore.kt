package com.tencent.ToolBox.data

import android.content.Context
import com.tencent.ToolBox.security.Crypt
import com.tencent.ToolBox.security.SecretBox

/**
 * 当前保存的签到链接（对应 Main.js 里的 checkin_url.txt）。
 *
 * 链接里可能带签到用的票据，所以落盘走 [SecretBox] 加密，不是明文。
 */
class UrlStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(Crypt.decode(Crypt.PREFS), Context.MODE_PRIVATE)

    var url: String
        get() {
            val stored = prefs.getString(KEY_URL, "") ?: ""
            if (stored.isEmpty()) return ""
            val opened = SecretBox.open(stored)
            if (looksLikeUrl(opened)) return opened
            // 老版本存的是明文，就地升级成密文
            if (!stored.startsWith("ks1:") && !stored.startsWith("old:") && looksLikeUrl(stored)) {
                prefs.edit().putString(KEY_URL, SecretBox.seal(stored)).apply()
                return stored
            }
            return ""
        }
        set(value) {
            prefs.edit().apply {
                if (value.isBlank()) remove(KEY_URL) else putString(KEY_URL, SecretBox.seal(value))
            }.apply()
        }

    var autoCheckin: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    fun clear() = prefs.edit().remove(KEY_URL).apply()

    private fun looksLikeUrl(s: String): Boolean =
        s.startsWith("http", ignoreCase = true) ||
            s.startsWith("alipay", ignoreCase = true) ||
            s.startsWith("taobao", ignoreCase = true)

    private companion object {
        const val KEY_URL = "checkin_url"
        const val KEY_AUTO = "auto_checkin"
    }
}
