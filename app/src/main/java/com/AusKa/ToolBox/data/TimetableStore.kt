package com.AusKa.ToolBox.data

import android.content.Context
import com.AusKa.ToolBox.security.Crypt
import com.AusKa.ToolBox.security.SecretBox
import com.AusKa.ToolBox.widget.CourseWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 课表 + 教务账号的本地存储。
 *
 * 落盘的东西全部过一遍 [SecretBox]（Keystore 里的 AES-256 / GCM）：
 * 学号、密码、教务地址、整份课表都是密文。密钥不可导出，所以就算 prefs 文件被拷走也解不开。
 *
 * 单例 —— 启动时的后台刷新和课表页拿到的是同一份 [timetableFlow]，刷新完界面自己就变了。
 */
class TimetableStore private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private val _timetable = MutableStateFlow(readTimetable())
    val timetableFlow: StateFlow<Timetable?> = _timetable.asStateFlow()

    private val _syncOk = MutableStateFlow(readSyncOk())

    /** 最近一次自动同步的结果：null = 还没跑过，true = 成功，false = 失败 */
    val syncOkFlow: StateFlow<Boolean?> = _syncOk.asStateFlow()

    init {
        upgradePlaintext()
    }

    var baseUrl: String
        get() = readField(KEY_BASE) { it.startsWith("http", ignoreCase = true) }
        set(value) = prefs.edit().putString(KEY_BASE, SecretBox.seal(value)).apply()

    var username: String
        get() = readField(KEY_USER) { it.isNotBlank() && it.length <= 64 }
        set(value) = prefs.edit().putString(KEY_USER, SecretBox.seal(value)).apply()

    var password: String
        get() = readField(KEY_PASS) { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_PASS, SecretBox.seal(value)).apply()

    /** 记住的学年/学期，格式 `2026-3`；不含身份信息，明文即可 */
    var termKey: String
        get() = prefs.getString(KEY_TERM, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TERM, value).apply()

    /** 上一次成功同步的时间戳 */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_SYNCED, 0L)
        set(value) = prefs.edit().putLong(KEY_SYNCED, value).apply()

    /** 记一次自动同步的结果；成功才推进 lastSyncAt */
    fun markSync(ok: Boolean) {
        prefs.edit()
            .putBoolean(KEY_SYNC_OK, ok)
            .putLong(KEY_SYNCED, if (ok) System.currentTimeMillis() else lastSyncAt)
            .apply()
        _syncOk.value = ok
    }

    /** 有没有可用的账号（三个字段都齐） */
    val hasAccount: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /** 读取缓存的课表；结果来自内存里那份，磁盘上存的是密文 */
    fun load(): Timetable? = _timetable.value

    fun save(t: Timetable) {
        val normalized = if (t.startDate.isBlank()) {
            t.copy(startDate = TermCalendar.format(TermCalendar.estimateStart(t.year, t.term)))
        } else {
            t
        }
        prefs.edit().putString(KEY_TABLE, SecretBox.seal(toJson(normalized).toString())).apply()
        _timetable.value = normalized
        CourseWidgetProvider.refreshAll(appContext)
    }

    fun clear() {
        prefs.edit().remove(KEY_TABLE).apply()
        _timetable.value = null
        CourseWidgetProvider.refreshAll(appContext)
    }

    /** 从磁盘重新读一遍 */
    fun reload() {
        _timetable.value = readTimetable()
    }

    // ---- 读写 + 旧数据迁移 ----

    /**
     * 读一个本该是密文的字段。
     *
     * 老版本有明文 / 只有 xor 混淆的存量数据，所以解不开（或者解出来是乱码）时，
     * 还要把原值本身按明文再试一次，避免升级一次就把用户的账号弄没。
     */
    private fun readField(key: String, accept: (String) -> Boolean): String {
        val stored = prefs.getString(key, "") ?: ""
        if (stored.isEmpty()) return ""
        val opened = SecretBox.open(stored)
        if (accept(opened)) return opened
        val plainLike = !stored.startsWith("ks1:") && !stored.startsWith("old:")
        if (plainLike && accept(stored)) return stored
        return ""
    }

    /** 把还躺在明文里的旧字段原地加密，跑一次就都换成密文了 */
    private fun upgradePlaintext() {
        val encoders = listOf(
            KEY_BASE to { s: String -> s.startsWith("http", ignoreCase = true) },
            KEY_USER to { s: String -> s.isNotBlank() && s.length <= 64 },
            KEY_PASS to { s: String -> s.isNotBlank() },
            KEY_TABLE to { s: String -> s.trimStart().startsWith("{") },
        )
        val edit = prefs.edit()
        var dirty = false
        for ((key, accept) in encoders) {
            val stored = prefs.getString(key, "") ?: continue
            if (stored.isEmpty() || stored.startsWith("ks1:")) continue
            val plain = readField(key, accept)
            if (plain.isEmpty()) continue
            edit.putString(key, SecretBox.seal(plain))
            dirty = true
        }
        if (dirty) edit.apply()
    }

    private fun readSyncOk(): Boolean? =
        if (prefs.contains(KEY_SYNC_OK)) prefs.getBoolean(KEY_SYNC_OK, false) else null

    private fun readTimetable(): Timetable? {        val raw = readField(KEY_TABLE) { it.trimStart().startsWith("{") }
        if (raw.isBlank()) return null
        val t = runCatching { fromJson(JSONObject(raw)) }.getOrNull() ?: return null
        if (t.courses.isEmpty()) {
            // 旧格式（没存 zcd）解析不出课程 —— 那份 weeks 是旧解析器算的，不要了，让用户重新导入
            prefs.edit().remove(KEY_TABLE).apply()
            return null
        }
        return if (t.startDate.isBlank()) {
            t.copy(startDate = TermCalendar.format(TermCalendar.estimateStart(t.year, t.term)))
        } else {
            t
        }
    }

    private fun toJson(t: Timetable): JSONObject {
        val arr = JSONArray()
        t.courses.forEach { c ->
            arr.put(
                JSONObject().apply {
                    put("name", c.name)
                    put("teacher", c.teacher)
                    put("room", c.room)
                    put("day", c.day)
                    put("start", c.startSection)
                    put("end", c.endSection)
                    put("weeks", JSONArray(c.weeks.sorted()))
                    put("zcd", c.zcd)
                    put("campus", c.campus)
                },
            )
        }
        return JSONObject().apply {
            put("year", t.year)
            put("term", t.term)
            put("student", t.studentName)
            put("updatedAt", t.updatedAt)
            put("startDate", t.startDate)
            put("courses", arr)
        }
    }

    private fun fromJson(o: JSONObject): Timetable {
        val arr = o.optJSONArray("courses") ?: JSONArray()
        val courses = ArrayList<Course>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val zcd = c.optString("zcd")
            val weeks = if (zcd.isNotBlank()) {
                // 有原始周次串就用最新的解析规则重算，旧版本存下来的 weeks 可能是错的
                Course.parseWeeks(zcd)
            } else {
                sortedSetOf<Int>().also { set ->
                    val weeksArr = c.optJSONArray("weeks") ?: JSONArray()
                    for (k in 0 until weeksArr.length()) set.add(weeksArr.optInt(k))
                }
            }
            courses.add(
                Course(
                    name = c.optString("name"),
                    teacher = c.optString("teacher"),
                    room = c.optString("room"),
                    day = c.optInt("day", 1),
                    startSection = c.optInt("start", 1),
                    endSection = c.optInt("end", 1),
                    weeks = weeks,
                    campus = c.optString("campus"),
                    zcd = zcd,
                ),
            )
        }
        return Timetable(
            courses = courses,
            year = o.optInt("year", 2026),
            term = o.optInt("term", 3),
            studentName = o.optString("student"),
            updatedAt = o.optLong("updatedAt", 0L),
            startDate = o.optString("startDate"),
        )
    }

    companion object {
        @Volatile
        private var instance: TimetableStore? = null

        fun get(context: Context): TimetableStore =
            instance ?: synchronized(this) {
                instance ?: TimetableStore(context.applicationContext).also { instance = it }
            }

        private val PREF = Crypt.decode(Crypt.PREFS) + "_tt"
        private const val KEY_BASE = "jw_base"
        private const val KEY_USER = "jw_user"
        private const val KEY_PASS = "jw_pass"
        private const val KEY_TERM = "jw_term"
        private const val KEY_TABLE = "tt_data"
        private const val KEY_SYNCED = "last_sync"
        private const val KEY_SYNC_OK = "sync_ok"
    }
}
