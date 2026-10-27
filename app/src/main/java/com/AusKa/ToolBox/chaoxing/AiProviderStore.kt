package com.AusKa.ToolBox.chaoxing

import android.content.Context
import com.AusKa.ToolBox.data.ChaoxingStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个「供应商」= 名字 + 接口地址 + 模型名。
 *
 * 参照 RikkaHub 的做法：内置几条常用的（点一下就切过去），用户也能自己加
 * —— 中转站、自建 one-api、本地 Ollama 都走这条，格式只要是 OpenAI 兼容的就行。
 */
internal data class AiProvider(
    val id: String,
    val name: String,
    val base: String,
    val model: String,
)

internal object AiProviderStore {

    /** 内置的那几条；「识图」两个后缀是给用户看的能力提示，选的时候不参与逻辑 */
    val defaults: List<AiProvider> = listOf(
        AiProvider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-flash"),
        AiProvider("glm4v", "智谱 GLM-4V-Flash · 识图", "https://open.bigmodel.cn/api/paas/v4", "glm-4v-flash"),
        AiProvider("gemini", "Gemini 2.0 Flash · 识图", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash"),
        AiProvider("openrouter", "OpenRouter · 识图", "https://openrouter.ai/api/v1", "meta-llama/llama-3.2-11b-vision-instruct:free"),
        AiProvider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        AiProvider("glm", "智谱 GLM-4-Flash", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        AiProvider("kimi", "Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        AiProvider("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        AiProvider("silicon", "硅基流动", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
    )

    /** 用户自己加的，和内置的共用一份列表（内置的也能删，删了下次不再出现） */
    fun load(context: Context): List<AiProvider> {
        val raw = ChaoxingStore.providersJson(context)
        if (raw.isBlank()) return defaults
        val out = ArrayList<AiProvider>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val base = o.optString("base")
                val model = o.optString("model")
                val name = o.optString("name")
                if (base.isBlank() || model.isBlank() || name.isBlank()) continue
                out.add(AiProvider(o.optString("id", "p$i"), name, base, model))
            }
        }
        return out.ifEmpty { defaults }
    }

    fun save(context: Context, list: List<AiProvider>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("base", p.base)
                    .put("model", p.model)
            )
        }
        ChaoxingStore.setProvidersJson(context, arr.toString())
    }

    /** 现在用的是哪一条（按 地址+模型 匹配，手改过就匹配不上，返回 null） */
    fun currentId(context: Context, base: String, model: String): String? =
        load(context).firstOrNull { it.base == base && it.model == model }?.id
}
