package com.AusKa.ToolBox

import com.AusKa.ToolBox.chaoxing.AiPresets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPresetsTest {

    @Test
    fun presetsAllHaveBaseAndModel() {
        assertTrue(AiPresets.all.isNotEmpty())
        AiPresets.all.forEach { p ->
            assertTrue(p.name.isNotBlank())
            assertTrue(p.base.startsWith("https://"))
            assertTrue(p.model.isNotBlank())
        }
    }

    @Test
    fun atLeastTwoVisionPresets() {
        assertTrue(AiPresets.all.count { it.vision } >= 2)
    }

    @Test
    fun visionModelsRecognised() {
        assertTrue(AiPresets.looksVisionModel("gpt-4o-mini"))
        assertTrue(AiPresets.looksVisionModel("glm-4v-flash"))
        assertTrue(AiPresets.looksVisionModel("gemini-2.0-flash"))
        assertTrue(AiPresets.looksVisionModel("Qwen/Qwen2.5-VL-7B-Instruct"))
        assertFalse(AiPresets.looksVisionModel("glm-4-flash"))
        assertFalse(AiPresets.looksVisionModel("deepseek-chat"))
    }

    @Test
    fun imageRejectionDetected() {
        assertTrue(
            AiPresets.meansNoVision(
                "HTTP 400 {\"error\":{\"message\":\"Invalid content type. image_url is only supported by certain models.\"}}",
            ),
        )
        assertTrue(AiPresets.meansNoVision("HTTP 400 unsupported content: image"))
        assertTrue(AiPresets.meansNoVision("HTTP 422 unknown variant `image_url`"))
        assertFalse(AiPresets.meansNoVision("HTTP 401 invalid api key"))
        // 限流不是「不支持识图」，别把用户引到换模型上
        assertFalse(AiPresets.meansNoVision("HTTP 429 rate limit reached for image requests"))
    }
}
