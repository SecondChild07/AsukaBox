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
 * 课表是「丢了就得重新导一遍」的数据，所以比密码多两道保险：
 *  1. 双副本密文（`recoverable = true`）—— Keystore 被系统重置后还能用兜底钥匙读回来；
 *  2. `files/tt_mirror.bin` 再留一份密文 —— prefs 文件被清理/写坏时从镜像恢复。
 *
 * 单例 —— 启动时的后台刷新和课表页拿到的是同一份 [timetableFlow]，刷新完界面自己就变了。
 */
class TimetableStore private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 有密文、但两条密钥都解不开的字段。
     *
     * 这种情况以前被当成「没有数据」：界面显示空，下一次保存再把它覆盖掉 ——
     * 用户视角就是「手机重启一次，课表自己没了」。现在记下来，界面能说清楚。
     */
    private val broken: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private val _timetable = MutableStateFlow(readTimetable())
    val timetableFlow: StateFlow<Timetable?> = _timetable.asStateFlow()

    private val _syncOk = MutableStateFlow(readSyncOk())

    /** 最近一次自动同步的结果：null = 还没跑过，true = 成功，false = 失败 */
    val syncOkFlow: StateFlow<Boolean?> = _syncOk.asStateFlow()

    init {
        upgradePlaintext()
        // 老用户盘上的课表/账号是「只有 Keystore 副本」的，升级上来第一件事就是补上兜底副本
        upgradeToDualCopy()
    }

    var baseUrl: String
        get() = readField(KEY_BASE) { it.startsWith("http", ignoreCase = true) }
        set(value) = putRecoverable(KEY_BASE, value)

    var username: String
        get() = readField(KEY_USER) { it.isNotBlank() && it.length <= 64 }
        set(value) = putRecoverable(KEY_USER, value)

    /** 密码只走 Keystore（不写兜底副本）：宁可让用户重填一次，也不降级成能随手解开的盒子 */
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

    /**
     * 上次登录留下的 JSESSIONID（加密存）。
     *
     * 有它就优先复用、跳过登录 —— 正方 session 一般能活半小时到几小时，
     * 这样「每次打开自动同步」不必每次都拿密码登录一次（很多学校对频繁登录会互踢或限流）。
     * 失效了就正常登录，登录成功后再刷新这个值。
     */
    var sessionCookie: String
        get() = readField(KEY_SESSION) { it.contains('=') }
        set(value) = prefs.edit().putString(KEY_SESSION, SecretBox.seal(value)).apply()

    /** 上一次「尝试同步」的时间，不论成败。退避用它算，不然失败就等于没节流。 */
    var lastAttemptAt: Long
        get() = prefs.getLong(KEY_ATTEMPT, 0L)
        private set(value) = prefs.edit().putLong(KEY_ATTEMPT, value).apply()

    /**
     * 已经确认过「这个学期还没排课」的学期，格式同 [termKey]。
     *
     * 开学前一两周教务里那个学期就是空的：什么都不记的话，每过半小时就为它完整登录一次，
     * 白白烧掉学校的登录配额 —— 正方对频繁登录会互踢，甚至按连续失败锁号。
     */
    var emptyTermKey: String
        get() = prefs.getString(KEY_EMPTY_TERM, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EMPTY_TERM, value).apply()

    /** 上面那次确认的时间戳，静默同步用它决定隔多久再探一次 */
    var emptyCheckedAt: Long
        get() = prefs.getLong(KEY_EMPTY_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_EMPTY_AT, value).apply()

    /** 连续失败次数，用来做指数退避；成功一次清零。 */
    var failCount: Int
        get() = prefs.getInt(KEY_FAILS, 0)
        set(value) = prefs.edit().putInt(KEY_FAILS, value).apply()

    /**
     * 记一次同步结果。
     *
     * 失败也要推进 [lastAttemptAt] —— 否则「失败不节流」，
     * 密码改错的用户每开一次 App 就撞一次登录，正方普遍有连续失败锁定。
     */
    fun markSync(ok: Boolean) {
        val now = System.currentTimeMillis()
        val nextFails = if (ok) 0 else (failCount + 1).coerceAtMost(9)
        prefs.edit()
            .putBoolean(KEY_SYNC_OK, ok)
            .putLong(KEY_SYNCED, if (ok) now else lastSyncAt)
            .putLong(KEY_ATTEMPT, now)
            .putInt(KEY_FAILS, nextFails)
            .apply()
        _syncOk.value = ok
    }

    /**
     * 这次该不该跳过。成功过就 30 分钟一次；连续失败则 5 → 10 → 20 → 40 → 60 分钟退避。
     */
    fun shouldSkip(now: Long = System.currentTimeMillis()): Boolean {
        val fails = failCount
        val wait = if (fails == 0) {
            30 * 60 * 1000L
        } else {
            (5L * 60_000L shl (fails - 1).coerceAtMost(4)).coerceAtMost(60L * 60_000L)
        }
        return lastAttemptAt > 0 && now - lastAttemptAt < wait
    }

    /** 有没有可用的账号（三个字段都齐） */
    val hasAccount: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /** 读取缓存的课表；结果来自内存里那份，磁盘上存的是密文 */
    fun load(): Timetable? = _timetable.value

    /**
     * 存课表。
     *
     * **空课表不覆盖已有数据** —— 选了还没排课的学期、选错学期、学校 kbList 返回空数组都会得到空表，
     * 早期实现会直接把它写进盘，用户视角就是「课表自己没了」。
     *
     * @return 真的写了吗
     */
    fun save(t: Timetable): Boolean {
        if (t.courses.isEmpty() && _timetable.value != null) return false
        val normalized = if (t.startDate.isBlank()) {
            t.copy(startDate = TermCalendar.format(TermCalendar.estimateStart(t.year, t.term)))
        } else {
            t
        }
        val sealed = SecretBox.seal(toJson(normalized).toString(), recoverable = true)
        prefs.edit()
            .putString(KEY_TABLE, sealed)
            // 盘上有课表了，之前「这个学期还没排课」的记号就该作废（否则静默同步会一直以为它是空的）
            .remove(KEY_EMPTY_TERM)
            .remove(KEY_EMPTY_AT)
            .apply()
        // 镜像：prefs 文件被 ROM 清掉、或者写到一半进程被杀导致 xml 解析失败时，这是最后的来源
        LocalMirror.write(appContext, MIRROR_TABLE, SecretBox.fallbackPart(sealed) ?: sealed)
        broken.remove(KEY_TABLE)
        _timetable.value = normalized
        CourseWidgetProvider.refreshAll(appContext)
        return true
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_TABLE)
            // 课表被清掉了，「这个学期没排课」的判断也就不成立了，否则 4 小时内静默同步不会去拉
            .remove(KEY_EMPTY_TERM)
            .remove(KEY_EMPTY_AT)
            .apply()
        LocalMirror.clear(appContext, MIRROR_TABLE)
        broken.remove(KEY_TABLE)
        _timetable.value = null
        CourseWidgetProvider.refreshAll(appContext)
    }

    /**
     * 把「这个人是谁」相关的东西全删掉：地址、学号、密码、会话、课表、同步状态。
     *
     * 卸载当然更彻底，但很多人的做法是先把 App 丢着不用 ——
     * 存了别人教务密码的东西，得给一条不卸载也能删干净的路。
     */
    fun wipeAccount() {
        prefs.edit()
            .remove(KEY_BASE)
            .remove(KEY_USER)
            .remove(KEY_PASS)
            .remove(KEY_SESSION)
            .remove(KEY_TERM)
            .remove(KEY_TABLE)
            .remove(KEY_SYNCED)
            .remove(KEY_SYNC_OK)
            .remove(KEY_ATTEMPT)
            .remove(KEY_FAILS)
            .remove(KEY_EMPTY_TERM)
            .remove(KEY_EMPTY_AT)
            .apply()
        LocalMirror.clear(appContext, MIRROR_TABLE)
        broken.clear()
        _timetable.value = null
        _syncOk.value = null
        CourseWidgetProvider.refreshAll(appContext)
    }

    /** 从磁盘重新读一遍 */
    fun reload() {
        val fresh = readTimetable()
        // 这次读失败（密文暂时解不开 / 文件还没写完）不要把已有的表清成空：
        // 界面上一闪而过的「没有课表」，在用户眼里就是数据被删了
        if (fresh != null || _timetable.value == null) _timetable.value = fresh
    }

    // ---- 自检与镜像恢复 ----

    /** 有没有字段是「存了但解不开」的（Keystore 大概率被系统重置了） */
    fun hasUnreadable(): Boolean = broken.isNotEmpty()

    /** 给设置页「数据自检」用的一行摘要 */
    fun healthLine(): String {
        val stored = prefs.getString(KEY_TABLE, "").orEmpty()
        val state = when {
            stored.isEmpty() -> "未存"
            KEY_TABLE in broken -> "密文读不出来"
            else -> "已存"
        }
        return "课表 $state" + if (LocalMirror.exists(appContext, MIRROR_TABLE)) " · 镜像在" else " · 无镜像"
    }

    /**
     * 启动时兜一次底：prefs 里的课表丢了或者解不开，但本地镜像能解开 → 用镜像补回去。
     *
     * @return 补回来了吗
     */
    fun repairFromMirrorIfNeeded(): Boolean {
        val stored = prefs.getString(KEY_TABLE, "").orEmpty()
        if (stored.isNotEmpty() && SecretBox.openOrNull(stored) != null) return false
        val mirror = LocalMirror.read(appContext, MIRROR_TABLE)
        if (mirror.isEmpty()) return false
        val plain = SecretBox.openFallbackOnly(mirror) ?: SecretBox.openOrNull(mirror) ?: return false
        val parsed = runCatching { fromJson(JSONObject(plain)) }.getOrNull() ?: return false
        if (parsed.courses.isEmpty()) return false
        // 重新封一次再落盘：镜像里只有兜底副本，写回 prefs 时补上 Keystore 那一段
        prefs.edit().putString(KEY_TABLE, SecretBox.seal(plain, recoverable = true)).apply()
        broken.remove(KEY_TABLE)
        _timetable.value = parsed
        CourseWidgetProvider.refreshAll(appContext)
        return true
    }

    // ---- 读写 + 旧数据迁移 ----

    /**
     * 把旧版本写的 `ks1:`（只有 Keystore 副本）升级成双副本。
     *
     * 不做这一步的话，只有「升级之后重新保存过一次」的数据才有兜底 ——
     * 而课表恰恰是导入一次用一学期的东西，用户根本不会再去动它。
     */
    private fun upgradeToDualCopy() {
        val edit = prefs.edit()
        var dirty = false
        for (key in listOf(KEY_BASE, KEY_USER, KEY_TABLE)) {
            val stored = prefs.getString(key, "") ?: continue
            if (!stored.startsWith("ks1:")) continue
            val plain = SecretBox.openOrNull(stored) ?: continue
            val sealed = SecretBox.seal(plain, recoverable = true)
            edit.putString(key, sealed)
            dirty = true
            if (key == KEY_TABLE) {
                // 镜像和 prefs 用同一份密文，别各封一次
                LocalMirror.write(appContext, MIRROR_TABLE, SecretBox.fallbackPart(sealed) ?: sealed)
            }
        }
        if (dirty) edit.apply()
    }

    /** 落一条「丢了就得重来」的字段：双副本，Keystore 重置后还能读回来 */
    private fun putRecoverable(key: String, value: String) {
        prefs.edit().putString(key, SecretBox.seal(value, recoverable = true)).apply()
        broken.remove(key)
    }

    /**
     * 读一个本该是密文的字段。
     *
     * 老版本有明文 / 只有 xor 混淆的存量数据，所以解不开（或者解出来是乱码）时，
     * 还要把原值本身按明文再试一次，避免升级一次就把用户的账号弄没。
     */
    private fun readField(key: String, accept: (String) -> Boolean): String {
        val stored = prefs.getString(key, "") ?: ""
        if (stored.isEmpty()) return ""
        val opened = SecretBox.openOrNull(stored)
        if (opened != null && accept(opened)) return opened
        // 有密文却解不开：记一笔，界面能说清楚「不是没有，是读不出来」
        if (opened == null && isSealed(stored)) broken.add(key)
        val plainLike = !isSealed(stored) && !stored.startsWith("old:")
        if (plainLike && accept(stored)) return stored
        return ""
    }

    private fun isSealed(s: String): Boolean =
        s.startsWith("ks2:") || s.startsWith("ks1:") || s.startsWith("fb1:")

    /** 把还躺在明文里的旧字段原地加密，跑一次就都换成密文了 */
    private fun upgradePlaintext() {
        val encoders = listOf(
            Triple(KEY_BASE, { s: String -> s.startsWith("http", ignoreCase = true) }, true),
            Triple(KEY_USER, { s: String -> s.isNotBlank() && s.length <= 64 }, true),
            Triple(KEY_PASS, { s: String -> s.isNotBlank() }, false),
            Triple(KEY_TABLE, { s: String -> s.trimStart().startsWith("{") }, true),
            Triple(KEY_SESSION, { s: String -> s.contains('=') }, false),
        )
        val edit = prefs.edit()
        var dirty = false
        for ((key, accept, recoverable) in encoders) {
            val stored = prefs.getString(key, "") ?: continue
            if (stored.isEmpty() || isSealed(stored)) continue
            val plain = readField(key, accept)
            if (plain.isEmpty()) continue
            edit.putString(key, SecretBox.seal(plain, recoverable))
            dirty = true
        }
        if (dirty) edit.apply()
    }

    private fun readSyncOk(): Boolean? =
        if (prefs.contains(KEY_SYNC_OK)) prefs.getBoolean(KEY_SYNC_OK, false) else null

    private fun readTimetable(): Timetable? {
        val raw = readField(KEY_TABLE) { it.trimStart().startsWith("{") }
        if (raw.isBlank()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val t = runCatching { fromJson(root) }.getOrNull() ?: return null
        // v2 起「空课表」是合法状态（那个学期还没排课）；v1 的空表才是旧解析器的残渣
        val ver = root.optInt("v", 1)
        if (ver < 2 && t.courses.isEmpty()) {
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
            put("v", 2)
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
        private const val MIRROR_TABLE = "tt_mirror.bin"
        private const val KEY_BASE = "jw_base"
        private const val KEY_USER = "jw_user"
        private const val KEY_PASS = "jw_pass"
        private const val KEY_TERM = "jw_term"
        private const val KEY_TABLE = "tt_data"
        private const val KEY_SYNCED = "last_sync"
        private const val KEY_SYNC_OK = "sync_ok"
        private const val KEY_SESSION = "jw_session"
        private const val KEY_ATTEMPT = "sync_attempt_at"
        private const val KEY_FAILS = "sync_fails"
        private const val KEY_EMPTY_TERM = "tt_empty_term"
        private const val KEY_EMPTY_AT = "tt_empty_at"
    }
}
