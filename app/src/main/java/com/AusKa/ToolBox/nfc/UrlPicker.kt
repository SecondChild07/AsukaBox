package com.AusKa.ToolBox.nfc

import android.util.Base64
import com.AusKa.ToolBox.security.Crypt

/**
 * 从任意文本里找支付宝签到链接（Main.js 的 extractUrls / scoreUrl / rankUrls 移植）。
 *
 * 卡片里塞的往往不是一条干净的 URL，而是一段源码、一段 JSON，甚至 base64。
 * 所以这里用多级正则 + 打分：先粗扫所有像链接的东西，再按「像不像支付宝签到」排序。
 *
 * 域名与签到路径不写死在字符串里，运行时从 Crypt 解出来，避免 dex 明文暴露业务。
 */
object UrlPicker {

    private val HOST: String get() = Crypt.decode(Crypt.AR_HOST)
    private val QD_PATH: String get() = Crypt.decode(Crypt.AR_QD)
    private val DOMAIN: String get() = Crypt.decode(Crypt.AR_DOMAIN)

    private val TAIL = """[^\s"'`<>\\\]\),，。；]*"""

    private val QD_RE by lazy {
        Regex(
            """((?:https?://)?(?:www\.)?${Regex.escape(HOST)}/(?:p/s/)?${Regex.escape(QD_PATH)}$TAIL)""",
            RegexOption.IGNORE_CASE,
        )
    }
    private val RENDER_RE by lazy {
        Regex(
            """((?:https?://)?(?:www\.)?${Regex.escape(HOST)}/$TAIL)""",
            RegexOption.IGNORE_CASE,
        )
    }
    private val KEYED_RE = Regex(
        """(?:createUri|setData|Uri\.parse|link|Link|url|Url|URL|uri|Uri|href|jumpUrl|redirect|targetUrl|scheme|跳转|链接)\s*[:=(]\s*["'`]([^"'`\n<>]{8,})["'`]"""
    )
    private val QUOTED_RE = Regex(
        """["'`]((?:https?://|alipays?://|alipayqr://)[^"'`\s<>]{6,})["'`]""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_RE = Regex(
        """((?:https?://|alipays?://|alipayqr://)[^\s"'`<>\\\]\),，。；]{6,})""",
        RegexOption.IGNORE_CASE,
    )
    private val ENCODED_RE = Regex(
        """((?:https?|alipays?)%3A%2F%2F[^\s"'`<>]{6,})""",
        RegexOption.IGNORE_CASE,
    )
    private val URL_PARAM_RE = Regex("""[?&]url=([^&\s]+)""", RegexOption.IGNORE_CASE)
    private val BASE64_RE = Regex("""["'`]([A-Za-z0-9+/]{28,}={0,2})["'`]""")
    private val LOOKS_LIKE_URL = Regex(
        """^(https?://|alipays?://|alipayqr://|alipay://|taobao://|tb://|intent://)""",
        RegexOption.IGNORE_CASE,
    )

    fun looksLikeUrl(u: String): Boolean = u.length >= 12 && LOOKS_LIKE_URL.containsMatchIn(u)

    /** 是否就是「签到」入口那种 render.alipay.com/.../ulink/qd 链接 */
    fun isCheckinLink(u: String): Boolean = QD_RE.containsMatchIn(u)

    private fun unescapeSource(s: String): String = s
        .replace(Regex("""\\u002[fF]"""), "/")
        .replace(Regex("""\\u003[aA]"""), ":")
        .replace("\\u0026", "&")
        .replace("\\/", "/")
        .replace(Regex("""\\x2[fF]"""), "/")
        .replace(Regex("""&amp;""", RegexOption.IGNORE_CASE), "&")
        .replace("&#38;", "&")
        .replace(Regex("""&quot;""", RegexOption.IGNORE_CASE), "\"")

    private fun tidy(u: String): String = u.trim()
        .replace(Regex("""^[\s"'`（(【\[<]+"""), "")
        .replace(Regex("""[\s"'`）)】\]>，。；;、,]+$"""), "")

    fun normalize(u: String): String {
        var s = tidy(u)
        if (s.isEmpty()) return ""
        if (Regex("""^(?:www\.)?${Regex.escape(HOST)}/""", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
            s = "https://$s"
        } else if (Regex(
                """^(?:www\.)?(?:qr|ur|m|dsapp|dypay|openapi)\.${Regex.escape(DOMAIN)}/""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(s)
        ) {
            s = "https://$s"
        }
        return s
    }

    fun score(u: String): Int {
        var s = 0
        s += when {
            QD_RE.containsMatchIn(u) -> 200
            RENDER_RE.containsMatchIn(u) -> 80
            Regex(
                """^(https?://)?(qr|ur|m|dsapp|dypay|openapi)\.${Regex.escape(DOMAIN)}""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(u) -> 60
            else -> 0
        }
        if (u.contains(DOMAIN.substringBefore('.'), true)) s += 30
        if (Regex("""^alipays?://""", RegexOption.IGNORE_CASE).containsMatchIn(u)) s += 20
        s -= minOf(25, u.length / 40)
        return s
    }

    fun rank(urls: List<String>): List<String> = urls
        .asSequence()
        .map { normalize(it) }
        .filter { looksLikeUrl(it) }
        .distinct()
        .sortedByDescending { score(it) }
        .toList()

    fun extract(source: String): List<String> {
        if (source.isBlank()) return emptyList()
        val text = unescapeSource(source)
        val pool = ArrayList<Pair<String, Int>>()

        fun push(raw: String, bonus: Int) {
            val u = normalize(raw)
            if (!looksLikeUrl(u)) return
            pool.add(u to bonus + score(u))
        }

        QD_RE.findAll(text).forEach { push(it.groupValues[1], 500) }
        RENDER_RE.findAll(text).forEach { push(it.groupValues[1], 300) }
        KEYED_RE.findAll(text).forEach { push(it.groupValues[1], 100) }
        QUOTED_RE.findAll(text).forEach { push(it.groupValues[1], 80) }
        BARE_RE.findAll(text).forEach { push(it.groupValues[1], 50) }

        ENCODED_RE.findAll(text).forEach { m ->
            runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }
                .getOrNull()
                ?.let { push(it, 70) }
        }

        // 嵌套：?url=<真实链接>
        pool.toList().forEach { (url, sc) ->
            URL_PARAM_RE.find(url)?.let { m ->
                runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }
                    .getOrNull()
                    ?.let { push(it, sc + 5) }
            }
        }

        // base64 里藏链接
        BASE64_RE.findAll(text).forEach { m ->
            runCatching {
                String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()?.let { dec ->
                if (dec.startsWith("http://", true) || dec.startsWith("https://", true) ||
                    dec.startsWith("alipay", true)
                ) {
                    push(dec, 90)
                }
            }
        }

        return pool.sortedByDescending { it.second }.map { it.first }.distinct()
    }

    fun best(source: String): String = extract(source).firstOrNull() ?: ""

    fun shortUrl(u: String, limit: Int = 44): String =
        if (u.length > limit) u.substring(0, limit) + "…" else u
}
