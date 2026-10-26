package com.AusKa.ToolBox.chaoxing

/**
 * 把模型给的答案「翻译」成页面能用的形态。
 *
 * 模型经常答非所问地给文本：判断题回「对」、单选题回「《马关条约》」，
 * 而页面上只有 A/B/C/D 的选项要点击。这一层负责把它们对齐，
 * 对不上的就返回空，让统计里如实记成「没拿到答案」，绝不硬点一个。
 */
internal object AnswerResolver {

    private val TRUE_WORDS = listOf("对", "正确", "是", "√", "true", "t", "yes", "right")
    private val FALSE_WORDS = listOf("错", "错误", "否", "×", "x", "false", "f", "no", "wrong")

    /** 最终要点击的选项字母（可能为空） */
    fun letters(q: QuizQuestion, ans: QuizAnswer): String {
        // 没有选项的题（填空/简答/连线）不能拿字母去点选项：模型回个「A」很可能是
        // 把答案压缩成了一个字母，这时候该走文本回填，而不是判定为「点不上」。
        if (q.options.isEmpty() && q.kind != "sort") return ""
        val raw = ans.letters.uppercase().filter { it in 'A'..'Z' }
        if (raw.isNotEmpty()) {
            // 排序题必须保持模型给的顺序，其他题型统一升序（点选项和顺序无关，升序更好读）
            val ordered = if (q.kind == "sort") raw else raw.toSortedSet().joinToString("")
            return if (q.options.isNotEmpty()) ordered.take(q.options.size) else ordered
        }
        if (ans.texts.isEmpty()) return ""
        val text = ans.texts.first().trim()
        if (text.isEmpty()) return ""

        if (q.kind == "judge" || looksJudgement(q)) {
            val mapped = judgeLetter(q, text)
            if (mapped.isNotEmpty()) return mapped
        }
        return matchOption(q, text)
    }

    /** 最终要写进输入框的文本（可能为空） */
    fun texts(q: QuizQuestion, ans: QuizAnswer): List<String> {
        if (ans.texts.isEmpty()) return emptyList()
        val cleaned = ans.texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return emptyList()
        if (q.kind == "essay" || q.kind == "connect") return cleaned
        if (q.inputs <= 1 && cleaned.size == 1) return cleaned

        // 一空多填：模型有时把「空气；水；土壤」塞在一条字符串里
        if (cleaned.size == 1) {
            val parts = cleaned[0]
                .split('\n', '；', ';', '、')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.size > 1) return parts
        }
        return cleaned
    }

    /** 判断题：把「对/错」映射到选项字母（A/B 或 T/F 排布都能处理） */
    private fun judgeLetter(q: QuizQuestion, text: String): String {
        val lower = text.lowercase()
        val wantTrue = TRUE_WORDS.any { lower.contains(it.lowercase()) }
        val wantFalse = FALSE_WORDS.any { lower.contains(it.lowercase()) }
        if (!wantTrue && !wantFalse) return ""
        // 既含「对」又含「错」时（模型写了「对，不是错」）以开头为准
        val head = lower.take(4)
        val isTrue = if (TRUE_WORDS.any { head.contains(it.lowercase()) }) {
            true
        } else if (FALSE_WORDS.any { head.contains(it.lowercase()) }) {
            false
        } else {
            wantTrue && !wantFalse
        }
        val index = q.options.indexOfFirst { opt ->
            val o = opt.lowercase()
            if (isTrue) {
                TRUE_WORDS.any { o.contains(it.lowercase()) }
            } else {
                FALSE_WORDS.any { o.contains(it.lowercase()) }
            }
        }
        if (index < 0) {
            // 选项就是「A. 对 / B. 错」时按位置兜底
            if (q.options.size == 2) return if (isTrue) "A" else "B"
            return ""
        }
        return ('A' + index).toString()
    }

    /** 文本答案 → 匹配度最高的选项字母 */
    private fun matchOption(q: QuizQuestion, text: String): String {
        if (q.options.isEmpty()) return ""
        val key = normalize(text)
        if (key.isEmpty()) return ""
        // 先找完全包含
        var hit = q.options.indexOfFirst { normalize(it).contains(key) }
        if (hit < 0) hit = q.options.indexOfFirst { key.contains(normalize(it)) }
        if (hit < 0) return ""
        return ('A' + hit).toString()
    }

    private fun looksJudgement(q: QuizQuestion): Boolean =
        q.stem.contains("判断") || (q.options.size == 2 && q.options.any { normalize(it).contains("错") })

    private fun normalize(raw: String): String =
        raw.replace(Regex("^[A-Za-z][.、:：)）\\s]+"), "")
            .replace(Regex("\\s+"), "")
            .trim()
}
