package com.AusKa.ToolBox

import com.AusKa.ToolBox.chaoxing.AiEndpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型名 → 接口地址的映射：填错一次就是 404，所以把它钉死在测试里 */
class AiEndpointsTest {

    @Test
    fun deepseekMapsToOfficialBase() {
        assertEquals("https://api.deepseek.com/v1", AiEndpoints.baseFor("deepseek-chat"))
        assertEquals("https://api.deepseek.com/v1", AiEndpoints.baseFor("deepseek-reasoner"))
    }

    @Test
    fun commonProvidersAreCovered() {
        assertEquals("https://api.moonshot.cn/v1", AiEndpoints.baseFor("moonshot-v1-8k"))
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            AiEndpoints.baseFor("qwen-plus"),
        )
        assertEquals("https://open.bigmodel.cn/api/paas/v4", AiEndpoints.baseFor("glm-4-flash"))
        assertEquals("https://api.openai.com/v1", AiEndpoints.baseFor("gpt-4o-mini"))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            AiEndpoints.baseFor("gemini-2.0-flash"),
        )
        assertEquals("https://api.moonshot.cn/v1", AiEndpoints.baseFor("kimi-latest"))
    }

    @Test
    fun vendorPrefixIsIgnored() {
        assertEquals("https://openrouter.ai/api/v1", AiEndpoints.baseFor("openrouter/llama-3.2-11b"))
        assertEquals("https://api.openai.com/v1", AiEndpoints.baseFor("openai/gpt-4o"))
    }

    @Test
    fun unknownModelGivesNull() {
        assertNull(AiEndpoints.baseFor("stub"))
        assertNull(AiEndpoints.baseFor(""))
        assertNull(AiEndpoints.baseFor("my-local-model"))
    }

    @Test
    fun hintSaysWhetherVisionWorks() {
        val glm = AiEndpoints.hintFor("glm-4v-flash")
        assertTrue(glm!!.contains("智谱"))
        assertTrue(glm.contains("识图"))
        val kimi = AiEndpoints.hintFor("moonshot-v1-8k")!!
        assertTrue(kimi.contains("纯文本"))
        assertNull(AiEndpoints.hintFor("stub"))
    }
}
