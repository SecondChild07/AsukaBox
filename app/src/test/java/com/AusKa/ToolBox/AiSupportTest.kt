package com.AusKa.ToolBox

import com.AusKa.ToolBox.chaoxing.AiSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接口能力判断的两个小函数：模型名像不像视觉模型、报错像不像「不接受图片」。
 * 内置预设已经删掉（改成用户自己填地址和模型），这里只测这两个判断。
 */
class AiSupportTest {

    @Test
    fun visionModelNamesAreRecognized() {
        assertTrue(AiSupport.looksVisionModel("gpt-4o-mini"))
        assertTrue(AiSupport.looksVisionModel("glm-4v-flash"))
        assertTrue(AiSupport.looksVisionModel("gemini-2.0-flash"))
        assertTrue(AiSupport.looksVisionModel("Qwen/Qwen2.5-VL-7B-Instruct"))
    }

    @Test
    fun textModelNamesAreNotMistaken() {
        assertFalse(AiSupport.looksVisionModel("deepseek-chat"))
        assertFalse(AiSupport.looksVisionModel("qwen-plus"))
        assertFalse(AiSupport.looksVisionModel(""))
    }

    @Test
    fun noVisionErrorsAreDetected() {
        assertTrue(AiSupport.meansNoVision("HTTP 400 unknown variant image_url, expected text"))
        assertTrue(AiSupport.meansNoVision("This model does not support image input"))
        assertTrue(AiSupport.meansNoVision("当前接口不支持图片输入"))
        assertTrue(AiSupport.meansNoVision("invalid_request_error: multimodal content is not allowed"))
    }

    @Test
    fun rateLimitIsNotMistakenForNoVision() {
        assertFalse(AiSupport.meansNoVision("HTTP 429 rate limit exceeded"))
        assertFalse(AiSupport.meansNoVision("HTTP 500 internal error"))
        assertFalse(AiSupport.meansNoVision(""))
    }
    @Test
    fun friendlyTranslatesCommonFailures() {
        assertEquals("Key 不对或已失效", AiSupport.friendly("HTTP 401 {\"error\":\"invalid api key\"}"))
        assertEquals("接口地址或模型名不对", AiSupport.friendly("HTTP 404 model_not_found"))
        assertEquals("接口超时，检查网络", AiSupport.friendly("java.net.SocketTimeoutException: timeout"))
        assertEquals("连不上接口，检查地址和网络", AiSupport.friendly("IOException unexpected end of stream"))
        assertTrue(AiSupport.friendly("HTTP 500 something odd").contains("500"))
    }

}