package com.AusKa.ToolBox.chaoxing

import org.json.JSONArray
import org.json.JSONObject

/** 模型返回的 JSON 解析（模型偶尔会套 ``` 或加一句废话，这里都兜住） */
internal object AiParse {

    fun answer(raw: String): QuizAnswer {
        val json = extract(raw) ?: return QuizAnswer()
        return try {
            val obj = JSONObject(json)
            val letters = StringBuilder()
            val texts = ArrayList<String>()
            when (val answer = obj.opt("answer")) {
                is String -> {
                    val trimmed = answer.trim()
                    if (trimmed.matches(Regex("[A-Za-z]{1,10}"))) {
                        letters.append(trimmed.uppercase())
                        // 同一个值也留一份文本：填空题没有选项，字母/单词本身就是答案
                        texts.add(trimmed)
                    } else if (trimmed.isNotEmpty()) {
                        texts.add(trimmed)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until answer.length()) texts.add(answer.optString(i))
                }
                else -> Unit
            }
            val pairs = obj.optJSONArray("pairs")
            if (pairs != null) {
                for (i in 0 until pairs.length()) {
                    val p = pairs.optJSONObject(i) ?: continue
                    texts.add(p.optString("left") + "=" + p.optString("right"))
                }
            }
            QuizAnswer(letters.toString(), texts)
        } catch (e: Exception) {
            QuizAnswer()
        }
    }

    /**
     * 图片题（一题多个空、且每题答案就是一个字母）走这条：
     * 模型按题号顺序回 {"answers":["C","A","B",…]}，取最后一个含 answers 的对象。
     */
    fun answers(raw: String): List<String> {
        val json = extractArray(raw) ?: return emptyList()
        return try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("answers") ?: return emptyList()
            ArrayList<String>(arr.length()).also { out ->
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "").trim()
                    if (v.isNotEmpty()) out.add(v)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 抽 JSON：现在提示词允许模型先写一句判断依据再给 JSON，
     * 所以取**最后一个**带 answer/pairs 的对象；模型只给一个时行为不变。
     */
    private fun extract(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        var depth = 0
        var start = -1
        val found = ArrayList<String>()
        for (i in text.indices) {
            when (text[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            found.add(text.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
        }
        if (found.isEmpty()) return null
        return found.lastOrNull { it.contains("\"answer\"") || it.contains("\"pairs\"") } ?: found.last()
    }

    /** 同 extract，但优先取带 answers 的对象（图片题批量答案） */
    private fun extractArray(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        var depth = 0
        var start = -1
        val found = ArrayList<String>()
        for (i in text.indices) {
            when (text[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            found.add(text.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
        }
        if (found.isEmpty()) return null
        return found.lastOrNull { it.contains("\"answers\"") } ?: found.last()
    }
}
