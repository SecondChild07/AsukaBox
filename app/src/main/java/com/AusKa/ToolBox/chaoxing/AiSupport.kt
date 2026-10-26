package com.AusKa.ToolBox.chaoxing

/**
 * 接口能力判断。
 *
 * 这里原来放了一堆内置服务商预设，现在改成「用户自己填地址和模型」，
 * 只留两个判断：模型名看起来是不是视觉模型、服务端报错是不是「不接受图片」。
 * Key 一向由用户自己申请，App 不代持。
 */
internal object AiSupport {

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
    /**
     * 把底层异常翻译成人话。界面直接把 IOException / HTTP 400 甩给用户，只会让人以为
     * 「App 坏了」，而真正的问题通常是 Key、地址、模型名三者之一填错。
     */
    fun friendly(message: String): String {
        val m = message.lowercase()
        return when {
            m.isBlank() -> "接口没返回内容"
            m.contains("401") || m.contains("unauthorized") || m.contains("invalid api key") ||
                m.contains("invalid_api_key") || m.contains("authentication") -> "Key 不对或已失效"
            m.contains("403") || m.contains("forbidden") -> "这个 Key 没有这个模型的权限"
            m.contains("404") || m.contains("not found") || m.contains("no such model") ||
                m.contains("model_not_found") -> "接口地址或模型名不对"
            m.contains("429") || m.contains("rate limit") -> "请求太频繁，被限流了"
            m.contains("timeout") || m.contains("timed out") -> "接口超时，检查网络"
            m.contains("unknownhost") || m.contains("unable to resolve host") -> "域名解析失败，检查地址"
            m.contains("connection refused") || m.contains("econnrefused") -> "接口拒绝连接，检查地址和端口"
            m.contains("end of stream") || m.contains("connection reset") ||
                m.contains("unexpected end") -> "连不上接口，检查地址和网络"
            m.contains("insufficient") || m.contains("quota") || m.contains("balance") ->
                "账户余额或额度不够"
            else -> message.take(120)
        }
    }
}
