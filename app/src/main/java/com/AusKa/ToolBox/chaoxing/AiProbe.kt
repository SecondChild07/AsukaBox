package com.AusKa.ToolBox.chaoxing

/**
 * 贴一把 Key，自动认出它属于哪家、能用什么模型。
 *
 * 为什么要有这个：各家把「界面上的显示名」和「接口里的 model 字段」分得很开
 *（DeepSeek 现在叫 deepseek-flash，早先叫 deepseek-chat），填错就是 400。
 * 与其维护一张会过期的对照表，不如直接拿 Key 去问服务商要模型列表。
 *
 * 判据：`GET {base}/models` 返回 200 且有 data[].id。逐个候选地址试，第一个通的就采用。
 */
internal object AiProbe {

    class Hit(
        val label: String,
        val base: String,
        /** 选中的模型名（优先能看图的） */
        val model: String,
        /** 这个模型是否确定能读图 */
        val vision: Boolean,
        /** 这把 Key 一共能用的模型数 */
        val total: Int,
    )

    /** 候选顺序 = 常见程度；同一个 Key 只会命中一家，试到通的就停 */
    private val CANDIDATES = listOf(
        "DeepSeek" to "https://api.deepseek.com/v1",
        "硅基流动" to "https://api.siliconflow.cn/v1",
        "智谱" to "https://open.bigmodel.cn/api/paas/v4",
        "阿里百炼" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "月之暗面" to "https://api.moonshot.cn/v1",
        "阶跃星辰" to "https://api.stepfun.com/v1",
        "OpenRouter" to "https://openrouter.ai/api/v1",
        "Groq" to "https://api.groq.com/openai/v1",
    )

    fun probe(key: String, onTry: ((String) -> Unit)? = null): Hit? {
        if (key.isBlank()) return null
        for ((label, base) in CANDIDATES) {
            onTry?.invoke("正在试 " + label + "…")
            val (ids, _) = AiClient.listModels(base, key)
            if (ids.isEmpty()) continue
            val vision = pickVision(ids)
            val any = pickAny(ids)
            val chosen = vision ?: any ?: continue
            return Hit(label, base, chosen, vision != null, ids.size)
        }
        return null
    }

    /** 明显不是聊天模型的一律排掉（列出来只会让人选错） */
    private fun isChatModel(id: String): Boolean {
        val m = id.lowercase()
        if (m.contains("embed") || m.contains("rerank") || m.contains("moderation")) return false
        if (m.contains("tts") || m.contains("whisper") || m.contains("asr")) return false
        if (m.contains("audio-transcribe")) return false
        // 画图的不能当聊天用：gpt-image / dall-e / flux / stable-diffusion / kolors
        if (m.contains("dall-e") || m.contains("gpt-image") || m.contains("flux")) return false
        if (m.contains("stable-diffusion") || m.contains("kolors") || m.contains("sdxl")) return false
        return true
    }

    /**
     * 挑一个能读图的。听力题的题干和选项都是图片，纯文本模型等于白给。
     * 按「谁更稳」排优先级，不是按字母序。
     */
    fun pickVision(models: List<String>): String? {
        val candidates = models.filter { isChatModel(it) }
        val rules = listOf(
            Regex("^gpt-4o$", RegexOption.IGNORE_CASE),
            Regex("^gpt-4o-20\\d\\d", RegexOption.IGNORE_CASE),
            Regex("^gpt-4\\.1", RegexOption.IGNORE_CASE),
            Regex("^glm-4(\\.[0-9])?v", RegexOption.IGNORE_CASE),
            Regex("^gemini-.*(flash|pro)$", RegexOption.IGNORE_CASE),
            Regex("qwen.*-vl", RegexOption.IGNORE_CASE),
            Regex("^qvq", RegexOption.IGNORE_CASE),
            Regex("claude-(3|4)", RegexOption.IGNORE_CASE),
            Regex("deepseek.*(vl|vision)", RegexOption.IGNORE_CASE),
            Regex("vision", RegexOption.IGNORE_CASE),
            Regex("^gpt-4o-mini$", RegexOption.IGNORE_CASE),
        )
        for (rule in rules) {
            candidates.firstOrNull { rule.containsMatchIn(it) }?.let { return it }
        }
        return null
    }

    /** 一个能看图的都没有时，退而求其次：挑个常用的聊天模型，别让用户面对一堆 embedding */
    fun pickAny(models: List<String>): String? {
        val candidates = models.filter { isChatModel(it) }
        if (candidates.isEmpty()) return null
        val preferred = listOf(
            "deepseek-flash", "deepseek-chat", "deepseek-v4",
            "gpt-4o-mini", "glm-4-flash", "qwen-plus", "qwen-turbo",
        )
        for (p in preferred) {
            candidates.firstOrNull { it.equals(p, ignoreCase = true) }?.let { return it }
        }
        return candidates.firstOrNull { !it.contains("preview", true) && !it.contains("realtime", true) }
            ?: candidates.first()
    }
}
