package com.AusKa.ToolBox.chaoxing

/**
 * 内置的 OpenAI 兼容服务商预设。
 *
 * 这里挑的都是「有免费额度或免费模型」的，且尽量带视觉（识图）能力 ——
 * 超星听力作业的题面是图片，纯文本模型读不了。
 * Key 仍然要用户自己去申请，App 不代持任何 Key。
 */
internal data class AiPreset(
    val name: String,
    val base: String,
    val model: String,
    val vision: Boolean,
    val note: String,
)

internal object AiPresets {

    val all: List<AiPreset> = listOf(
        AiPreset(
            name = "智谱 GLM-4V-Flash",
            base = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-4v-flash",
            vision = true,
            note = "免费视觉模型 · bigmodel.cn 注册即用",
        ),
        AiPreset(
            name = "Gemini 2.0 Flash",
            base = "https://generativelanguage.googleapis.com/v1beta/openai",
            model = "gemini-2.0-flash",
            vision = true,
            note = "谷歌免费额度 · aistudio.google.com 拿 Key",
        ),
        AiPreset(
            name = "OpenRouter 免费视觉",
            base = "https://openrouter.ai/api/v1",
            model = "meta-llama/llama-3.2-11b-vision-instruct:free",
            vision = true,
            note = "免费档 · openrouter.ai 申请 Key",
        ),
        AiPreset(
            name = "智谱 GLM-4-Flash",
            base = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-4-flash",
            vision = false,
            note = "免费文本模型 · 不能读图，图片题会跳过",
        ),
        AiPreset(
            name = "硅基流动 Qwen2.5",
            base = "https://api.siliconflow.cn/v1",
            model = "Qwen/Qwen2.5-7B-Instruct",
            vision = false,
            note = "免费文本模型 · siliconflow.cn 申请 Key",
        ),
        AiPreset(
            name = "OpenAI 官方",
            base = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
            vision = true,
            note = "收费 · 需自己充额度",
        ),
    )

    /** 模型名里带这些词，基本可以确定支持图片输入 */
    private val VISION_HINTS = listOf(
        "4o", "4.1", "vision", "vl", "gemini", "claude-3", "glm-4v", "llava", "pixtral", "qvq",
    )

    /** 无法确定时按「可能支持」处理：真不支持会由服务端报错，界面再提示换一个 */
    fun looksVisionModel(model: String): Boolean {
        val m = model.lowercase()
        return VISION_HINTS.any { m.contains(it) }
    }

    /**
     * 服务商拒绝图片输入时，错误信息里常见这些词。
     * 用它来判断「该 API 无识图能力」，而不是笼统地报网络错误。
     */
    fun meansNoVision(message: String): Boolean {
        val m = message.lowercase()
        if (m.isBlank()) return false
        val hints = listOf(
            "image", "vision", "multimodal", "content type", "unsupported content",
            "invalid content", "image_url", "does not support", "不支持", "无法识别图片",
            "unknown variant", "invalid_request_error",
        )
        return hints.any { m.contains(it) } && !m.contains("rate limit")
    }
}
