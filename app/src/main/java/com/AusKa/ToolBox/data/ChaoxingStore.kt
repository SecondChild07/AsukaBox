package com.AusKa.ToolBox.data

import android.content.Context
import com.AusKa.ToolBox.security.SecretBox

/**
 * 学习通助手的本地状态。
 *
 * 只存会话（Cookie 快照），不存账号密码 —— 登录一律走 WebView，账密由浏览器自己处理，
 * 也顺带解决首次登录的短信验证码：那一步交给用户在网页里点。
 *
 * Cookie 快照和 AI Key 都过 [SecretBox]（Keystore 里的 AES-256-GCM），
 * `adb pull shared_prefs` 拿到的只有密文。
 */
internal object ChaoxingStore {

    private const val PREFS = "chaoxing"
    private const val K_COOKIE = "cookie_snapshot"
    private const val K_COOKIE_AT = "cookie_at"
    private const val K_AI_KEY = "ai_key"
    private const val K_AI_MODEL = "ai_model"
    private const val K_STT_MODEL = "ai_stt_model"
    private const val K_AI_BASE = "ai_base"
    private const val K_PROVIDERS = "ai_providers"
    // 图片题（听力作业把题面做成图）单独一套配置，可以和第二套指向不同服务商
    private const val K_SUBMIT = "submit_mode"
    private const val K_TASKS = "tasks_json"
    private const val K_TASKS_AT = "tasks_at"

    /** 只做题，不提交（默认，也是唯一安全的默认值） */
    const val SUBMIT_MANUAL = "manual"

    /** 回填完自动点提交（只尝试一次，核验失败就交回人工） */
    const val SUBMIT_AUTO = "auto"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 会话

    fun cookieSnapshot(context: Context): String {
        val sealed = prefs(context).getString(K_COOKIE, "").orEmpty()
        if (sealed.isEmpty()) return ""
        return SecretBox.open(sealed)
    }

    fun saveCookieSnapshot(context: Context, raw: String) {
        val e = prefs(context).edit()
        if (raw.isBlank()) {
            e.putString(K_COOKIE, "")
            e.putLong(K_COOKIE_AT, 0L)
        } else {
            e.putString(K_COOKIE, SecretBox.seal(raw))
            e.putLong(K_COOKIE_AT, System.currentTimeMillis())
        }
        e.apply()
    }

    fun savedAt(context: Context): Long = prefs(context).getLong(K_COOKIE_AT, 0L)

    fun clearSession(context: Context) {
        saveCookieSnapshot(context, "")
    }

    // ---------------------------------------------------------------- AI

    fun aiKey(context: Context): String {
        val sealed = prefs(context).getString(K_AI_KEY, "").orEmpty()
        if (sealed.isEmpty()) return ""
        return SecretBox.open(sealed)
    }

    fun setAiKey(context: Context, value: String) {
        val k = value.trim()
        val e = prefs(context).edit()
        if (k.isEmpty()) e.remove(K_AI_KEY) else e.putString(K_AI_KEY, SecretBox.seal(k))
        e.apply()
    }

    fun sttModel(context: Context): String =
        prefs(context).getString(K_STT_MODEL, "").orEmpty()

    fun setSttModel(context: Context, m: String) {
        val e = prefs(context).edit()
        if (m.isEmpty()) e.remove(K_STT_MODEL) else e.putString(K_STT_MODEL, m)
        e.apply()
    }

    fun aiModel(context: Context): String =
        prefs(context).getString(K_AI_MODEL, "").orEmpty()

    fun setAiModel(context: Context, value: String) {
        val m = value.trim()
        val e = prefs(context).edit()
        if (m.isEmpty()) e.remove(K_AI_MODEL) else e.putString(K_AI_MODEL, m)
        e.apply()
    }

    /** OpenAI 兼容的接口地址（换成 DeepSeek / 通义 / 中转都能用） */
    fun aiBase(context: Context): String =
        prefs(context).getString(K_AI_BASE, "").orEmpty()

    fun setAiBase(context: Context, value: String) {
        val b = value.trim()
        val e = prefs(context).edit()
        if (b.isEmpty()) e.remove(K_AI_BASE) else e.putString(K_AI_BASE, b)
        e.apply()
    }

    /** 题库相关的小设置（开关、本地题库、在线地址），给 QuizBank 用 */
    fun prefsGet(context: Context, key: String): String? =
        prefs(context).getString(key, null)

    fun prefsPut(context: Context, key: String, value: String) {
        if (value.isEmpty()) prefs(context).edit().remove(key).apply()
        else prefs(context).edit().putString(key, value).apply()
    }
    /** 供应商列表（名字 + 地址 + 模型），明文存，不含 Key */
    fun providersJson(context: Context): String =
        prefs(context).getString(K_PROVIDERS, "").orEmpty()

    fun setProvidersJson(context: Context, raw: String) {
        prefs(context).edit().putString(K_PROVIDERS, raw).apply()
    }

    // ---------------------------------------------------------------- 上次扫到的清单

    /** 不含账号信息，纯任务清单，不用加密；存它只为了打开就能看到列表 */
    fun tasksJson(context: Context): String =
        prefs(context).getString(K_TASKS, "").orEmpty()

    fun tasksAt(context: Context): Long = prefs(context).getLong(K_TASKS_AT, 0L)

    fun setTasksJson(context: Context, raw: String) {
        val e = prefs(context).edit()
        if (raw.isBlank()) {
            e.remove(K_TASKS)
            e.putLong(K_TASKS_AT, 0L)
        } else {
            e.putString(K_TASKS, raw)
            e.putLong(K_TASKS_AT, System.currentTimeMillis())
        }
        e.apply()
    }

    // ---------------------------------------------------------------- 提交策略

    /** 没设置过就当「只做题不提交」，绝不给自动提交当默认值 */
    fun submitMode(context: Context): String {
        val stored = prefs(context).getString(K_SUBMIT, SUBMIT_MANUAL).orEmpty()
        return if (stored == SUBMIT_AUTO) SUBMIT_AUTO else SUBMIT_MANUAL
    }

    fun setSubmitMode(context: Context, mode: String) {
        prefs(context).edit()
            .putString(K_SUBMIT, if (mode == SUBMIT_AUTO) SUBMIT_AUTO else SUBMIT_MANUAL)
            .apply()
    }
}
