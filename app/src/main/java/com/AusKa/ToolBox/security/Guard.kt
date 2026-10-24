package com.AusKa.ToolBox.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import com.AusKa.ToolBox.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

/**
 * 运行时自保护。
 *
 * 四道闸：
 *  1. 包名必须是我们自己的
 *  2. APK 签名证书 SHA-256 必须匹配
 *  3. 调试器检测 —— TracerPid / Debug.isDebuggerConnected
 *  4. 注入框架检测 —— frida / xposed / lsposed / substrate 的残留与内存映射
 *
 * 失败时不杀进程、不动数据，只把 [blockedFlow] 置成原因字符串 —— UI 层收集它，
 * 有值就换成说明页并停掉功能。刻意不删数据：误报（装过 LSPosed 的机器、ROM 自带 gum 字样、
 * /data/local/tmp 有残留）让用户凭空丢掉课表和签到链接，代价远大于它挡住的那点东西。
 *
 * 用 [BuildConfig.DEBUG] 而不是运行时读 `FLAG_DEBUGGABLE`：后者是 manifest 里一个随便能改的属性，
 * 改掉就能同时绕过签名校验和调试器检测。
 */
internal object Guard {

    @Volatile
    private var armed = false

    private val _blockedFlow = MutableStateFlow<String?>(null)

    /** 非 null 表示这个安装包不该继续用，值是给用户看的原因。 */
    val blockedFlow: StateFlow<String?> = _blockedFlow.asStateFlow()

    fun install(ctx: Context) {
        if (armed) return
        armed = true
        if (!fastChecks(ctx)) {
            block("这个安装包的签名或包名和官方发布的不一致")
            return
        }
        // maps / 文件系统扫描偏重，扔到后台线程，不阻塞首帧
        Thread {
            if (!hookChecks()) block("当前环境里有调试器或注入框架")
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun fastChecks(ctx: Context): Boolean {
        if (ctx.packageName != Crypt.decode(Crypt.PKG)) return false
        if (!signatureOk(ctx)) return false
        if (!BuildConfig.DEBUG && debuggerAttached()) return false
        return true
    }

    private fun signatureOk(ctx: Context): Boolean { return try {
        // debug 包一律放行：这样开发机不需要正式 keystore，debug 也能用自己的 debug 签名
        // （以前为了绕过这条校验，debug 被迫和 release 同签名，等于让 debug 包和正式版同级）
        if (BuildConfig.DEBUG) return true
        // 构建时由 keystore 算出并注入；为空说明没配签名，只放行 debug 构建
        val expected = BuildConfig.CERT_SHA256.lowercase()
        if (expected.isBlank()) {
            BuildConfig.DEBUG
        } else {
            val pm = ctx.packageManager
            val pkg = ctx.packageName
            val cert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
            }
            cert != null && digest(cert) == expected
        }
    } catch (_: Throwable) {
        false
    } }

    private fun digest(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(md.size * 2)
        for (b in md) sb.append("%02x".format(b))
        return sb.toString()
    }

    private fun debuggerAttached(): Boolean {
        // release 里也不允许被 attach；debug 构建由 fastChecks 直接跳过这一步
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true
        return tracerPid() > 0
    }

    private fun tracerPid(): Int {
        return try {
            val file = File(Crypt.decode(Crypt.STATUS))
            if (!file.canRead()) return 0
            val key = Crypt.decode(Crypt.TRACER)
            file.useLines { lines ->
                lines.firstOrNull { it.startsWith("$key:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.toIntOrNull()
                    ?: 0
            }
        } catch (_: Throwable) {
            0
        }
    }

    private fun hookChecks(): Boolean {
        if (hookFilePresent()) return false
        if (mapsContaminated()) return false
        if (xposedLoaded()) return false
        return true
    }

    private fun hookFilePresent(): Boolean {
        val paths = arrayOf(Crypt.FRIDAPATH, Crypt.XPFILE)
        for (raw in paths) {
            if (File(Crypt.decode(raw)).exists()) return true
        }
        return false
    }

    private fun mapsContaminated(): Boolean {
        return try {
            val file = File(Crypt.decode(Crypt.MAPS))
            if (!file.canRead()) return false
            val needles = listOf(Crypt.FRIDA, Crypt.GUM, Crypt.XPOSED, Crypt.LSPOSED, Crypt.SUBSTRATE)
                .map { Crypt.decode(it).lowercase() }
            var hit = false
            file.useLines { lines ->
                for (line in lines) {
                    val lower = line.lowercase()
                    for (n in needles) {
                        if (lower.contains(n)) {
                            hit = true
                            return@useLines
                        }
                    }
                }
            }
            hit
        } catch (_: Throwable) {
            false
        }
    }

    private fun xposedLoaded(): Boolean = try {
        Class.forName(Crypt.decode(Crypt.XPBRIDGE), false, javaClass.classLoader)
        true
    } catch (_: Throwable) {
        false
    }

    private fun block(reason: String) {
        if (_blockedFlow.value == null) _blockedFlow.value = reason
    }
}
