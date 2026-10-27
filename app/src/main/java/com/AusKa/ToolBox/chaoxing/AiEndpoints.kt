package com.AusKa.ToolBox.chaoxing

/**
 * 常见服务商的接口地址。用户只要填 Key 和模型名，地址由模型名前缀推出来，
 * 不用再去翻文档抄一遍 —— 抄错的代价是一次 404，还很难看出原因。
 *
 * 全部是 OpenAI 兼容的 /chat/completions 端点；不兼容的（比如 Anthropic 原生接口）不列。
 */
internal data class AiEndpoint(
    /** 模型名前缀，命中就认这家 */
    val prefixes: List<String>,
    val base: String,
    val label: String,
    val vision: Boolean = false,
)

internal object AiEndpoints {
    val all: List<AiEndpoint> = listOf(
        AiEndpoint(
            prefixes = listOf("deepseek"),
            base = "https://api.deepseek.com/v1",
            label = "DeepSeek",
        ),
        AiEndpoint(
            prefixes = listOf("moonshot", "kimi"),
            base = "https://api.moonshot.cn/v1",
            label = "Kimi / Moonshot",
        ),
        AiEndpoint(
            prefixes = listOf("qwen", "qwq", "qvq"),
            base = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            label = "通义千问",
            vision = true,
        ),
        AiEndpoint(
            prefixes = listOf("glm"),
            base = "https://open.bigmodel.cn/api/paas/v4",
            label = "智谱 GLM",
            vision = true,
        ),
        AiEndpoint(
            prefixes = listOf("gemini"),
            base = "https://generativelanguage.googleapis.com/v1beta/openai",
            label = "Google Gemini",
            vision = true,
        ),
        AiEndpoint(
            prefixes = listOf("gpt-", "chatgpt", "o1", "o3", "o4"),
            base = "https://api.openai.com/v1",
            label = "OpenAI",
            vision = true,
        ),
        AiEndpoint(
            prefixes = listOf("ernie"),
            base = "https://qianfan.baidubce.com/v2",
            label = "百度文心",
        ),
        AiEndpoint(
            prefixes = listOf("hunyuan"),
            base = "https://api.hunyuan.cloud.tencent.com/v1",
            label = "腾讯混元",
        ),
        AiEndpoint(
            prefixes = listOf("doubao", "ep-"),
            base = "https://ark.cn-beijing.volces.com/api/v3",
            label = "豆包 / 火山方舟",
            vision = true,
        ),
        AiEndpoint(
            prefixes = listOf("mistral", "pixtral", "codestral"),
            base = "https://api.mistral.ai/v1",
            label = "Mistral",
            vision = true,
        ),
        AiEndpoint(
            prefixes = listOf("grok"),
            base = "https://api.x.ai/v1",
            label = "xAI Grok",
            vision = true,
        ),
        AiEndpoint(
            prefixes = listOf("yi-", "yi_"),
            base = "https://api.lingyiwanwu.com/v1",
            label = "零一万物",
        ),
        AiEndpoint(
            prefixes = listOf("baichuan"),
            base = "https://api.baichuan-ai.com/v1",
            label = "百川",
        ),
        AiEndpoint(
            prefixes = listOf("spark"),
            base = "https://spark-api-open.xf-yun.com/v1",
            label = "讯飞星火",
        ),
        AiEndpoint(
            prefixes = listOf("sensenova"),
            base = "https://api.sensenova.cn/compatible-mode/v1",
            label = "商汤日日新",
        ),
        AiEndpoint(
            prefixes = listOf("llama", "meta-llama", "mistralai", "openrouter"),
            base = "https://openrouter.ai/api/v1",
            label = "OpenRouter",
            vision = true,
        ),
    )

    /** 截掉厂商前缀，比如 "openai/gpt-4o" 里的 openai/ 不影响判断 */
    private fun bare(model: String): String =
        model.substringAfterLast('/').trim().lowercase()

    fun endpointFor(model: String): AiEndpoint? {
        val m = bare(model)
        if (m.isEmpty()) return null
        // 长的前缀优先：否则 "gpt-" 会把 "gpt-4o" 抢走之外没影响，但 "o1" 会误伤 "o1-preview" 之外的词
        return all.firstOrNull { e -> e.prefixes.any { p -> m.startsWith(p) } }
    }

    fun baseFor(model: String): String? = endpointFor(model)?.base

    /**
     * 给界面用的一句话：这家叫什么。
     *
     * 刻意不判「能不能识图」—— 同一家的不同模型能力不一样（glm-4-flash 不能看图、glm-4v-flash 能），
     * 按模型名前缀猜会给出错的结论，还会让人以为自己填错了。填 Key 之前提示一次就够了。
     */
    fun hintFor(model: String): String? = endpointFor(model)?.label
}
