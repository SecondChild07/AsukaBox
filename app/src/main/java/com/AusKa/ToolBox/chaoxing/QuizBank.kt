package com.AusKa.ToolBox.chaoxing

import android.content.Context
import com.AusKa.ToolBox.data.ChaoxingStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 题库优先：先用题库对题干，命中就直接填，没命中再问模型。
 *
 * 题库有两种来源：
 * 1. 本地题库（自己粘一份 JSON 进来，离线可用、不依赖任何第三方存活）；
 * 2. 在线题库（填一个 `{q}` 占位符地址，App 用 GET 查，返回 JSON 里找 answer 字段）。
 *
 * 为什么默认是本地题库：网上的「免费题库 API」大多是别人搭的，随时关停、加验证码、
 * 或者要登录拿额度；本地这份不会失效，导入一次就一直是你的。
 */
internal object QuizBank {

    /** 一条命中：答案（字母或文本）+ 来源（本地题库 / 在线题库名字） */
    internal data class Hit(val answer: String, val source: String)

    private const val K_ENABLED = "bank_enabled"
    private const val K_LOCAL = "bank_local"
    private const val K_ENDPOINT = "bank_endpoint"

    // ---------------------------------------------------------------- 开关

    fun enabled(context: Context): Boolean =
        ChaoxingStore.prefsGet(context, K_ENABLED)?.toBoolean() ?: true

    fun setEnabled(context: Context, on: Boolean) =
        ChaoxingStore.prefsPut(context, K_ENABLED, on.toString())

    // ---------------------------------------------------------------- 本地题库

    fun localRaw(context: Context): String =
        ChaoxingStore.prefsGet(context, K_LOCAL).orEmpty()

    fun localCount(context: Context): Int = parseLocal(localRaw(context)).size

    /**
     * 导入本地题库，支持两种形状：
     * - 数组：`[{"question":"题干","answer":"C"}]`
     * - 对象：`{"题干":"C"}`
     */
    fun importLocal(context: Context, raw: String): Pair<Int, String> {
        val text = raw.trim()
        if (text.isEmpty()) return 0 to "内容是空的"
        val map = parseLocal(text)
        if (map.isEmpty()) return 0 to "没解析出题目，检查 JSON 格式"
        ChaoxingStore.prefsPut(context, K_LOCAL, JSONObject(map as Map<*, *>).toString())
        return map.size to "已导入 " + map.size + " 道题"
    }

    private fun parseLocal(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        runCatching {
            val text = raw.trim()
            if (text.startsWith("[")) {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val q = o.optString("question").ifBlank { o.optString("q") }
                    val a = o.optString("answer").ifBlank { o.optString("a") }
                    val key = normalize(q)
                    if (key.length >= 4 && a.isNotBlank()) out[key] = a.trim()
                }
            } else {
                val obj = JSONObject(text)
                val it = obj.keys()
                while (it.hasNext()) {
                    val q = it.next()
                    val a = obj.optString(q)
                    val key = normalize(q)
                    if (key.length >= 4 && a.isNotBlank()) out[key] = a.trim()
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 在线题库

    fun endpoint(context: Context): String =
        ChaoxingStore.prefsGet(context, K_ENDPOINT).orEmpty()

    fun setEndpoint(context: Context, url: String) =
        ChaoxingStore.prefsPut(context, K_ENDPOINT, url.trim())

    /**
     * 查一道题。先本地题库（精确 + 包含匹配），再在线题库（有配才查）。
     * 故意做成同步方法：调用方本来就在后台线程里。
     */
    fun lookup(context: Context, q: QuizQuestion): Hit? {
        if (!enabled(context)) return null
        val stem = normalize(q.stem + " " + q.options.joinToString(" "))
        val stemOnly = normalize(q.stem)
        if (stemOnly.length < 4) return null

        val local = parseLocal(localRaw(context))
        local[stemOnly]?.let { return Hit(it, "本地题库") }
        local[stem]?.let { return Hit(it, "本地题库") }
        // 题库里题干常带空格和选项，退一步按包含关系找最长命中的那条
        local.entries
            .filter { it.key.length >= 8 && stemOnly.contains(it.key) }
            .maxByOrNull { it.key.length }
            ?.let { return Hit(it.value, "本地题库") }

        val url = endpoint(context)
        if (url.isBlank()) return null
        val target = url.replace("{q}", java.net.URLEncoder.encode(q.stem, "UTF-8"))
        val raw = runCatching { HttpText.get(target, null, null) }.getOrNull().orEmpty()
        if (raw.isBlank()) return null
        return parseOnlineAnswer(raw)?.let { Hit(it, "在线题库") }
    }

    /** 从在线题库的返回里挖答案：常见字段 answer / data.answer / result */
    internal fun parseOnlineAnswer(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        runCatching {
            val obj = if (text.startsWith("[")) null else JSONObject(text)
            if (obj != null) {
                listOf("answer", "result", "data", "ans").forEach { k ->
                    val v = obj.opt(k)
                    when (v) {
                        is String -> if (v.isNotBlank()) return v.trim()
                        is JSONObject -> listOf("answer", "result", "ans").forEach { k2 ->
                            val v2 = v.optString(k2)
                            if (v2.isNotBlank()) return v2.trim()
                        }
                        is JSONArray -> if (v.length() > 0) {
                            val first = v.opt(0)
                            if (first is String && first.isNotBlank()) return first.trim()
                        }
                    }
                }
            }
        }
        // 不是 JSON 就当纯文本答案，但要求够短，避免把整页 HTML 当答案
        return if (!text.startsWith("<") && text.length in 1..60) text else null
    }

    // ---------------------------------------------------------------- 题干归一化

    /**
     * 把题干压成「只留中文、字母、数字」的形式，题库和页面的微小排版差异
     * （题号、括号、空格、A. 前缀）就都不影响了。
     */
    internal fun normalize(raw: String): String {
        var s = raw.replace(Regex("[(（]\\s*(单选题|多选题|判断题|填空题|简答题|问答题|论述题|计算题)\\s*[)）]"), "")
        s = s.replace(Regex("^\\s*\\d+\\s*[.、．]\\s*"), "")
        s = s.replace(Regex("[A-Fa-f]\\s*[.．、]\\s*"), "")
        s = s.replace(Regex("[\\s\\u3000]+"), "")
        s = s.replace(Regex("[，。；：、？！“”‘’（）【】《》,.;:?!\"'()\\[\\]<>]"), "")
        s = s.replace(Regex("^\\d+"), "")
        return s.trim().lowercase()
    }
}
