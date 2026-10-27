package com.AusKa.ToolBox

import com.AusKa.ToolBox.chaoxing.AiProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「贴 Key 自动识别模型」的挑选逻辑。
 *
 * 只测纯函数（不联网）：给一堆模型名，看它挑中哪个。挑错的后果很实际——
 * 挑到 embedding 或 tts 就整个报错，挑到纯文本模型就答不了图片题。
 */
class AiProbeTest {

    @Test
    fun `优先挑能读图的强模型`() {
        val models = listOf("deepseek-flash", "gpt-4o-mini", "gpt-4o", "text-embedding-3-large")
        assertEquals("gpt-4o", AiProbe.pickVision(models))
    }

    @Test
    fun `没有 gpt 时挑国产多模态`() {
        val models = listOf("deepseek-chat", "glm-4v-flash", "glm-4-flash")
        assertEquals("glm-4v-flash", AiProbe.pickVision(models))
    }

    @Test
    fun `全是纯文本时不给假答案`() {
        val models = listOf("deepseek-chat", "deepseek-flash", "qwen-plus")
        assertNull(AiProbe.pickVision(models))
    }

    @Test
    fun `不能把画图和语音模型当成聊天模型`() {
        val models = listOf(
            "dall-e-3", "gpt-image-1", "flux-pro", "whisper-1",
            "text-embedding-3-small", "tts-1", "gpt-4o",
        )
        assertEquals("gpt-4o", AiProbe.pickVision(models))
        // 退路也不能挑到这些
        val only = listOf("dall-e-3", "whisper-1", "text-embedding-3-small")
        assertNull(AiProbe.pickAny(only))
    }

    @Test
    fun `退路优先选常用聊天模型`() {
        val models = listOf("some-unknown-model", "deepseek-flash", "o3-mini")
        assertEquals("deepseek-flash", AiProbe.pickAny(models))
    }

    @Test
    fun `识别结果里带服务商标签和可用数量`() {
        // Hit 是纯数据结构，这里只验证字段语义没写反
        val hit = AiProbe.Hit("DeepSeek", "https://api.deepseek.com/v1", "deepseek-flash", false, 3)
        assertEquals("DeepSeek", hit.label)
        assertEquals(3, hit.total)
        assertTrue(!hit.vision)
    }
}
