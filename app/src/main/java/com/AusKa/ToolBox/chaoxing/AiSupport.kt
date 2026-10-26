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
}
