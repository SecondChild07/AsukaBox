package com.tencent.ToolBox.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.Process
import com.tencent.ToolBox.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * 运行时自保护。
 *
 * 四道闸：
 *  1. 包名必须是我们自己的 —— 改了包名重打包，直接不启动
 *  2. APK 签名证书 SHA-256 必须匹配 —— 换签名重签，直接不启动
 *  3. 调试器检测 —— TracerPid / Debug.isDebuggerConnected，被 attach 就退出
 *  4. 注入框架检测 —— frida / xposed / lsposed / substrate 的残留与内存映射
 *
 * 失败时不是弹窗报错，而是清掉本地数据再杀进程 —— 不给人调试的入口。
 */
internal object Guard {

    @Volatile
    private var armed = false

    fun install(ctx: Context) {
        if (armed) return
        armed = true
        if (!fastChecks(ctx)) {
            kill(ctx)
            return
        }
        // maps / 文件系统扫描偏重，扔到后台线程，不阻塞首帧
        Thread {
            if (!hookChecks()) kill(ctx)
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun fastChecks(ctx: Context): Boolean {
        if (ctx.packageName != Crypt.decode(Crypt.PKG)) return false
        if (!isDebugBuild(ctx) && !signatureOk(ctx)) return false
        if (debuggerAttached(ctx)) return false
        return true
    }

    private fun isDebugBuild(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun signatureOk(ctx: Context): Boolean = try {
        // 构建时由 keystore 算出并注入；为空说明是自己 build 的调试版本，跳过校验
        val expected = BuildConfig.CERT_SHA256.lowercase()
        if (expected.isBlank()) {
            true
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
    }

    private fun digest(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(md.size * 2)
        for (b in md) sb.append("%02x".format(b))
        return sb.toString()
    }

    private fun debuggerAttached(ctx: Context): Boolean {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true
        // release 里也不允许被 attach；debug 构建放行，方便自己用 IDE 调
        if (isDebugBuild(ctx)) return false
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

    private fun kill(ctx: Context) {
        try {
            ctx.getSharedPreferences(Crypt.decode(Crypt.PREFS), Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        } catch (_: Throwable) {
        }
        Process.killProcess(Process.myPid())
        kotlin.system.exitProcess(1)
    }
}
