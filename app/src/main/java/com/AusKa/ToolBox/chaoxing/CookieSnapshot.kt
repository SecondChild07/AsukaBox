package com.AusKa.ToolBox.chaoxing

/**
 * 会话快照的纯文本编解码。
 *
 * [android.webkit.CookieManager] 只能按 URL 一条条取 Cookie，没法整体导出。
 * 所以这里把「域 + Cookie 串」写成一行行 `host<TAB>cookie`，整块交给 SecretBox 加密落盘；
 * 想恢复会话时再按行拆回去，CookieManager.setCookie 逐域写回。
 *
 * 只做字符串搬运，不做 IO —— 方便单测（见 ChaoxingCookieTest）。
 */
internal object CookieSnapshot {

    const val VERSION_HEADER = "v1"

    /** entries: host -> "k=v; k2=v2" */
    fun encode(entries: List<Pair<String, String>>): String {
        val body = entries
            .map { (host, cookie) -> host.trim() to cookie.trim() }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
        if (body.isEmpty()) return ""
        return (listOf(VERSION_HEADER) + body.map { (host, cookie) -> host + "\t" + cookie })
            .joinToString("\n")
    }

    fun decode(raw: String): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        return raw.lines()
            .filter { it.isNotBlank() && it.trim() != VERSION_HEADER }
            .mapNotNull { line ->
                val at = line.indexOf('\t')
                if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
            }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
    }

    /** "k=v; k2=v2" -> ["k", "k2"]，用来在 UI 上只数个数 / 判断有没有 _uid 这类关键项 */
    fun names(cookie: String): List<String> {
        if (cookie.isBlank()) return emptyList()
        return cookie.split(';').mapNotNull { part ->
            val name = part.substringBefore('=', "").trim()
            if (name.isEmpty()) null else name
        }
    }

    fun totalNames(raw: String): Int = decode(raw).sumOf { names(it.second).size }

    fun hosts(raw: String): List<String> = decode(raw).map { it.first }

    /** 关键会话项：没有这个基本等于没登录 */
    fun looksSignedIn(raw: String): Boolean {
        val names = decode(raw).flatMap { names(it.second) }
        return names.any { it == "_uid" || it == "UID" || it == "fid" }
    }

    /**
     * 把几个域的 Cookie 串拼成一个请求头（HttpText 只有一个 Cookie 头的位置）。
     * 同名项只留第一次出现的，避免 mooc1 / mooc2-ans 各自版本互相覆盖。
     */
    fun mergeHead(parts: List<String>): String {
        val seen = LinkedHashMap<String, String>()
        parts.forEach { part ->
            part.split(';').forEach { piece ->
                val kv = piece.trim()
                if (kv.isEmpty()) return@forEach
                val name = kv.substringBefore('=', "")
                if (name.isEmpty() || seen.containsKey(name)) return@forEach
                seen[name] = kv
            }
        }
        return seen.values.joinToString("; ")
    }
}
