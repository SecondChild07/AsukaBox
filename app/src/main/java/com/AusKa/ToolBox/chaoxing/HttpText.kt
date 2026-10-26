package com.AusKa.ToolBox.chaoxing

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用 WebView 里的会话去取一个页面的 HTML。
 *
 * 为什么要绕这一步：作业列表在课程页里是 **跨域 iframe**（mooc1.chaoxing.com），
 * `evaluateJavascript` 只作用于顶层文档，进不去；而那个地址带 enc / cpi / stuenc / t，
 * 少一个参数就是「无权限的操作」，在 Kotlin 里拼不出来 —— 只能先让页面把地址点出来，
 * 再用同一套 Cookie 从原生侧把 HTML 拉下来自己解析。
 *
 * Cookie 直接问 `CookieManager` 要（和 WebView 共用一份，登录态天然同步），
 * 请求走 App 自己的进程，出口 IP 和 WebView 一致，服务端不会因为换 IP 掉登录。
 */
internal object HttpText {

    private const val MAX_BYTES = 1_500_000

    /**
     * 听力音频单独放宽上限：一段 3 分钟的 128kbps mp3 就接近 3MB，
     * 用 MAX_BYTES 截断会把文件切坏，转写出来是半截话。
     * Whisper 接口自己限制 25MB，这里留一点余量。
     */
    private const val MAX_AUDIO_BYTES = 20_000_000
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 18_000

    /** 取不到就返回空串（调用方按「没读到」处理，不抛异常） */
    fun get(url: String, cookie: String?, referer: String?): String {
        if (url.isBlank()) return ""
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", ChaoxingScripts.DESKTOP_UA)
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9," +
                        "image/avif,image/webp,*/*;q=0.8",
                )
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                setRequestProperty("Upgrade-Insecure-Requests", "1")
                setRequestProperty("sec-ch-ua", "\"Chromium\";v=\"126\", \"Not:A-Brand\";v=\"24\"")
                setRequestProperty("sec-ch-ua-mobile", "?0")
                setRequestProperty("sec-ch-ua-platform", "\"Windows\"")
                setRequestProperty("Sec-Fetch-Dest", "document")
                setRequestProperty("Sec-Fetch-Mode", "navigate")
                setRequestProperty("Sec-Fetch-Site", "same-site")
                setRequestProperty("Sec-Fetch-User", "?1")
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
            }
            val code = conn.responseCode
            if (code >= 400) return ""
            read(conn.inputStream)
        } catch (e: Exception) {
            ""
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // 关连接失败无所谓
            }
        }
    }

    /** 取二进制（听力音频），失败返回空数组 */
    fun bytes(url: String, cookie: String?, referer: String?): ByteArray {
        if (url.isBlank()) return ByteArray(0)
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", ChaoxingScripts.DESKTOP_UA)
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
            }
            if (conn.responseCode >= 400) return ByteArray(0)
            val out = ByteArrayOutputStream(256 * 1024)
            conn.inputStream.use { input ->
                val buf = ByteArray(32 * 1024)
                var total = 0
                while (total < MAX_AUDIO_BYTES) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
            }
            out.toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // 关连接失败无所谓
            }
        }
    }

    private fun read(input: InputStream): String {
        input.use { stream ->
            val out = ByteArrayOutputStream(64 * 1024)
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
                if (total > MAX_BYTES) break
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
