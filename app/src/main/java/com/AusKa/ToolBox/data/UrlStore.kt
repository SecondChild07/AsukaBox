package com.AusKa.ToolBox.data

import android.content.Context
import com.AusKa.ToolBox.security.Crypt
import com.AusKa.ToolBox.security.SecretBox

/**
 * 当前保存的签到链接（对应 Main.js 里的 checkin_url.txt）。
 *
 * 链接里可能带签到用的票据，所以落盘走 [SecretBox] 加密，不是明文。
 *
 * 这条链接丢了就得重新贴一次卡，所以和课表一样按「可恢复」处理：
 * 双副本密文（Keystore 重置也能读回）+ `files/checkin_mirror.bin` 镜像（prefs 被清掉也能读回）。
 */
class UrlStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(Crypt.decode(Crypt.PREFS), Context.MODE_PRIVATE)

    /** 上次读的时候是不是「有密文但解不开」——界面据此给一句实话，而不是装作没存过 */
    var unreadable: Boolean = false
        private set

    var url: String
        get() {
            val stored = prefs.getString(KEY_URL, "") ?: ""
            unreadable = false
            if (stored.isEmpty()) return restoreFromMirror()
            val opened = SecretBox.openOrNull(stored)
            if (opened != null && looksLikeUrl(opened)) {
                // 旧版本只写了 Keystore 副本：读到就顺手补上兜底副本 + 镜像
                if (stored.startsWith("ks1:")) put(opened)
                return opened
            }
            if (opened == null) {
                // 密文解不开（Keystore 被系统重置）：先去镜像捞一次，捞不到再如实上报
                val back = restoreFromMirror()
                if (back.isEmpty()) unreadable = true
                return back
            }
            // 老版本存的是明文，就地升级成密文
            if (!isSealed(stored) && looksLikeUrl(stored)) {
                put(stored)
                return stored
            }
            return ""
        }
        set(value) = put(value)

    fun clear() {
        prefs.edit().remove(KEY_URL).apply()
        LocalMirror.clear(appContext, MIRROR)
        unreadable = false
    }

    private fun put(value: String) {
        val edit = prefs.edit()
        if (value.isBlank()) {
            edit.remove(KEY_URL)
            LocalMirror.clear(appContext, MIRROR)
        } else {
            val sealed = SecretBox.seal(value, recoverable = true)
            edit.putString(KEY_URL, sealed)
            LocalMirror.write(appContext, MIRROR, SecretBox.fallbackPart(sealed) ?: sealed)
        }
        edit.apply()
        unreadable = false
    }

    /** 从镜像恢复；成功就顺手写回 prefs，下次不用再走这条路 */
    private fun restoreFromMirror(): String {
        val mirror = LocalMirror.read(appContext, MIRROR)
        if (mirror.isEmpty()) return ""
        val plain = SecretBox.openFallbackOnly(mirror) ?: SecretBox.openOrNull(mirror) ?: return ""
        if (!looksLikeUrl(plain)) return ""
        put(plain)
        return plain
    }

    private fun isSealed(s: String): Boolean =
        s.startsWith("ks2:") || s.startsWith("ks1:") || s.startsWith("fb1:") || s.startsWith("old:")

    private fun looksLikeUrl(s: String): Boolean =
        s.startsWith("http", ignoreCase = true) ||
            s.startsWith("alipay", ignoreCase = true) ||
            s.startsWith("taobao", ignoreCase = true)

    private companion object {
        const val KEY_URL = "checkin_url"
        const val MIRROR = "checkin_mirror.bin"
    }
}
