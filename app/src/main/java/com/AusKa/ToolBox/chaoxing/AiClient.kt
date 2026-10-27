package com.AusKa.ToolBox.chaoxing

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 一道题（从页面上抽出来的最小信息） */
internal data class QuizQuestion(
    val index: Int,
    val kind: String,
    val stem: String,
    val options: List<String>,
    val inputs: Int,
    val editor: Boolean,
    val audio: String,
    /** 题面被做成图片时（超星 ananas 图床），把这些地址交给视觉模型 */
    val images: List<String> = emptyList(),
)

/** 题目抽取结果解析 */
internal object QuizParse {

    fun questions(raw: String): List<QuizQuestion> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        return try {
            val obj = JSONObject(org.json.JSONTokener(text).nextValue().toString())
            val items = obj.optJSONArray("items") ?: return emptyList()
            ArrayList<QuizQuestion>(items.length()).also { list ->
                for (i in 0 until items.length()) {
                    val q = items.optJSONObject(i) ?: continue
                    val opts = q.optJSONArray("options") ?: JSONArray()
                    val options = ArrayList<String>(opts.length())
                    for (j in 0 until opts.length()) options.add(opts.optString(j))
                    list.add(
                        QuizQuestion(
                            index = q.optInt("i", list.size),
                            kind = q.optString("kind", "unknown"),
                            stem = q.optString("stem", ""),
                            options = options,
                            inputs = q.optInt("inputs", 0),
                            editor = q.optBoolean("editor", false),
                            audio = q.optString("audio", ""),
                            images = q.optJSONArray("images")?.let { arr ->
                                ArrayList<String>(arr.length()).also { out ->
                                    for (k in 0 until arr.length()) {
                                        val s = arr.optString(k, "")
                                        if (s.isNotBlank()) out.add(s)
                                    }
                                }
                            } ?: emptyList(),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** 模型给出的答案 */
internal data class QuizAnswer(
    val letters: String = "",
    val texts: List<String> = emptyList(),
    /** 模型自报的把握，0~1；没给就按 1.0 算（老提示词不影响） */
    val conf: Double = 1.0,
)

/** 一次调用的结果：正文 + 出错原因（界面据此判断「这个接口不能识图」） */
internal data class AiResult(val text: String, val error: String = "") {
    val ok: Boolean get() = text.isNotBlank()
}

/**
 * 调大模型。走 OpenAI 兼容的 /chat/completions，所以 DeepSeek、通义、Kimi 这些
 * 只要给个兼容地址都能用；Key 只在内存里过一遍，不落日志。
 */
internal object AiClient {

    const val DEFAULT_BASE = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-chat"

    private const val TIMEOUT_MS = 45_000

    /** 返回模型回答的正文；失败返回空串（调用方按「这题没答案」处理） */
    fun chat(apiBase: String, key: String, model: String, prompt: String): String =
        chatResult(apiBase, key, model, prompt).text

    /** 和 chat 一样，但把失败原因带回来，界面才能说清「Key 不对 / 网络不通 / 模型不存在」 */
    fun chatResult(apiBase: String, key: String, model: String, prompt: String): AiResult {
        if (key.isBlank()) return AiResult("", "还没填 Key")
        val body = JSONObject().apply {
            put("model", model.ifBlank { DEFAULT_MODEL })
            put("temperature", 0)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", SYSTEM_PROMPT) })
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                },
            )
        }.toString()
        val url = apiBase.trimEnd('/') + "/chat/completions"
        val payload = body.toByteArray(Charsets.UTF_8)
        var (raw, err) = postDetailed(url, key, payload, "application/json")
        // 429 / 5xx / 网络抖动重试一次：题量大的时候偶尔掉一两题很亏
        if (raw.isBlank()) {
            try {
                Thread.sleep(900)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val again = postDetailed(url, key, payload, "application/json")
            raw = again.first
            if (err.isBlank()) err = again.second
        }
        if (raw.isBlank()) return AiResult("", err.ifBlank { "请求失败" })
        return try {
            val obj = JSONObject(raw)
            val choices = obj.optJSONArray("choices") ?: return AiResult("", "响应里没有 choices：" + raw.take(160))
            val first = choices.optJSONObject(0) ?: return AiResult("", "choices 是空的")
            val message = first.optJSONObject("message") ?: return AiResult("", "响应里没有 message")
            val content = message.optString("content", "")
            if (content.isBlank()) AiResult("", "模型返回了空内容") else AiResult(content, "")
        } catch (e: Exception) {
            AiResult("", "解析响应失败：" + (e.message ?: ""))
        }
    }

    /**
     * 带图片的提问：题面被做成图片（听力作业常见）时，把图交给支持视觉的模型。
     * 走 OpenAI 兼容的多模态 content 数组，所以 gpt-4o / qwen-vl / glm-4v 这类都能用。
     */
    fun chatImages(
        apiBase: String,
        key: String,
        model: String,
        prompt: String,
        images: List<String>,
    ): AiResult {
        if (key.isBlank() || images.isEmpty()) return AiResult("", "")
        val parts = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", prompt))
            images.take(3).forEach { url ->
                put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", url)),
                )
            }
        }
        val body = JSONObject().apply {
            put("model", model.ifBlank { DEFAULT_MODEL })
            put("temperature", 0)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    put(JSONObject().put("role", "user").put("content", parts))
                },
            )
        }.toString()
        val url = apiBase.trimEnd('/') + "/chat/completions"
        val payload = body.toByteArray(Charsets.UTF_8)
        var (raw, err) = postDetailed(url, key, payload, "application/json")
        if (raw.isBlank() && err.isBlank()) {
            try {
                Thread.sleep(900)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val again = postDetailed(url, key, payload, "application/json")
            raw = again.first
            err = again.second
        }
        if (raw.isBlank()) return AiResult("", err)
        return try {
            val obj = JSONObject(raw)
            val choices = obj.optJSONArray("choices") ?: return AiResult("", "响应里没有 choices")
            val first = choices.optJSONObject(0) ?: return AiResult("", "响应里没有 choices[0]")
            val message = first.optJSONObject("message") ?: return AiResult("", "响应里没有 message")
            AiResult(message.optString("content", ""), "")
        } catch (e: Exception) {
            AiResult("", "解析响应失败：" + (e.message ?: ""))
        }
    }

    /** 听力转写：把音频字节发给 /audio/transcriptions（走 whisper 兼容接口） */
    fun transcribe(apiBase: String, key: String, audio: ByteArray, fileName: String = "a.mp3"): String {
        if (key.isBlank() || audio.isEmpty()) return ""
        val boundary = "----asuka" + UUID.randomUUID().toString().replace("-", "")
        val out = ByteArrayOutputStream(audio.size + 1024)
        fun field(name: String, value: String) {
            out.write(("--$boundary\r\n").toByteArray())
            out.write(("Content-Disposition: form-data; name=\"$name\"\r\n\r\n").toByteArray())
            out.write((value + "\r\n").toByteArray())
        }
        field("model", "whisper-1")
        out.write(("--$boundary\r\n").toByteArray())
        out.write(
            ("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
                .toByteArray(),
        )
        out.write("Content-Type: audio/mpeg\r\n\r\n".toByteArray())
        out.write(audio)
        out.write("\r\n".toByteArray())
        out.write(("--$boundary--\r\n").toByteArray())

        val url = apiBase.trimEnd('/') + "/audio/transcriptions"
        val raw = post(url, key, out.toByteArray(), "multipart/form-data; boundary=$boundary")
        if (raw.isBlank()) return ""
        return try {
            JSONObject(raw).optString("text", "")
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 拉这个 key 能用的模型名（GET {base}/models）。
     *
     * 各家把「显示名」和「接口里的 model 字段」分得很开，比如界面上叫
     * 「DeepSeek V4.1 Flash」，接口里要写 deepseek-flash。直接问服务商要列表
     * 就不会猜错——这比在本地维护一张对照表靠谱。
     */
    fun listModels(apiBase: String, key: String): Pair<List<String>, String> {
        if (key.isBlank()) return emptyList<String>() to "还没填 Key"
        val url = apiBase.trimEnd('/') + "/models"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val text = if (code in 200..299) read(conn.inputStream) else read(conn.errorStream ?: conn.inputStream)
            if (code !in 200..299) return emptyList<String>() to ("HTTP " + code + " " + text.take(200))
            val arr = JSONObject(text).optJSONArray("data") ?: JSONArray()
            val ids = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotBlank()) ids.add(id)
            }
            ids to ""
        } catch (e: Exception) {
            emptyList<String>() to (e.javaClass.simpleName + " " + (e.message ?: ""))
        } finally {
            conn?.disconnect()
        }
    }
    private fun post(url: String, key: String, payload: ByteArray, contentType: String): String =
        postDetailed(url, key, payload, contentType).first

    /**
     * 和 post 一样，但把出错原因也带回来（服务端返回的错误正文 + HTTP 码），
     * 这样界面能区分「网络不通」「Key 错」「这个模型不支持读图」。
     */
    private fun postDetailed(
        url: String,
        key: String,
        payload: ByteArray,
        contentType: String,
    ): Pair<String, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Accept", "application/json")
            }
            val stream: OutputStream = conn.outputStream
            stream.use { it.write(payload) }
            val code = conn.responseCode
            if (com.AusKa.ToolBox.BuildConfig.DEBUG && code !in 200..299) {
                android.util.Log.d("CSAi", "http " + code + " · " + url)
            }
            if (code in 200..299) {
                read(conn.inputStream) to ""
            } else {
                val err = try {
                    read(conn.errorStream ?: conn.inputStream)
                } catch (e: Exception) {
                    ""
                }
                "" to ("HTTP " + code + " " + err.take(400))
            }
        } catch (e: Exception) {
            "" to (e.javaClass.simpleName + " " + (e.message ?: ""))
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun read(input: java.io.InputStream): String = input.use { stream ->
        val out = ByteArrayOutputStream(32 * 1024)
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            if (out.size() > 2_000_000) break
        }
        String(out.toByteArray(), Charsets.UTF_8)
    }

    private val SYSTEM_PROMPT: String = QuizPrompt.SYSTEM
}
