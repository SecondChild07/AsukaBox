package com.AusKa.ToolBox.chaoxing

/**
 * 常用服务商的「地址 + 模型名」快捷项。
 *
 * 说明：这里全部是各家**官方 API**（OpenAI 兼容格式），不是网页版。
 * 网页版（chat.deepseek.com 那类）没有公开接口，要靠逆向内部请求 + PoW 挑战 +
 * 用户 cookie，随时会失效，不适合内置，所以没做。
 *
 * Key 仍然由用户自己申请，App 只帮忙填对地址和模型名。
 */
internal data class AiProvider(
    val name: String,
    val base: String,
    val model: String,
    val vision: Boolean,
)

internal object AiProviders {

    val all: List<AiProvider> = listOf(
        AiProvider("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", false),
        AiProvider("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k", false),
        AiProvider("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", false),
        AiProvider("智谱 GLM-4-Flash", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", false),
        AiProvider("硅基流动", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct", false),
        AiProvider("智谱 GLM-4V-Flash", "https://open.bigmodel.cn/api/paas/v4", "glm-4v-flash", true),
        AiProvider("Gemini 2.0 Flash", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash", true),
        AiProvider("OpenRouter 视觉", "https://openrouter.ai/api/v1", "meta-llama/llama-3.2-11b-vision-instruct:free", true),
        AiProvider("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", true),
    )

    val text: List<AiProvider> get() = all
    val vision: List<AiProvider> get() = all.filter { it.vision }
}
