package com.AusKa.ToolBox.data

import com.AusKa.ToolBox.net.JwglxtException
import com.AusKa.ToolBox.net.JwglxtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 静默同步：用本地存的账号在后台重新拉一次课表。
 *
 * 打开 App 时跑一次，拉到就覆盖本地缓存并把 [TimetableStore.timetableFlow] 推给界面。
 * 全程不打扰用户 —— 失败（没网、密码改了、学校换了登录方式、需要验证码）就安静地留旧数据，
 * 界面上的「上次更新」会露出时间，用户想手动来一次还是点「重新导入」。
 *
 * 两道防止「一天登录几十次」的闸：
 *  - **30 分钟节流**：上次成功同步不到 30 分钟就直接返回 [Result.Skipped]。
 *    正方普遍有登录频率限制和异地互踢，转屏重建时 Activity 会重新跑 LaunchedEffect，
 *    没有节流的话开几次就被踢下线。
 *  - **原子互斥**：check-then-set 用 [AtomicBoolean.compareAndSet]，两个协程同时进来只有一个能跑。
 */
object TimetableSync {

    /** 两次成功同步之间的最小间隔。 */
    private const val MIN_INTERVAL_MS = 30 * 60 * 1000L

    /** 确认过「这个学期还没排课」之后，隔多久再探一次（开学前学校随时可能把课放上去） */
    private const val EMPTY_RECHECK_MS = 4 * 60 * 60 * 1000L

    /** 「已经有人在同步」时给 UI 的原因串，MainActivity 靠它决定要不要弹 Toast。 */
    const val BUSY = "正在同步"

    sealed interface Result {
        /** 拉到了，已存好 */
        data object Updated : Result

        /** 拉到了但是这个学期还没有课，旧课表原样保留 */
        data object EmptyTerm : Result

        /** 没有可用账号，什么都不做 */
        data object NoAccount : Result

        /** 刚同步过，这次跳过（界面不需要提示） */
        data object Skipped : Result

        /** 这套部署要验证码，静默路径没法填，跳过 */
        data object NeedCaptcha : Result

        data class Failed(val reason: String) : Result
    }

    private val gate = AtomicBoolean(false)

    suspend fun refresh(store: TimetableStore, force: Boolean = false): Result = withContext(Dispatchers.IO) {
        if (!store.hasAccount) return@withContext Result.NoAccount

        // 成功过就 30 分钟一次；连续失败按 5/10/20/40/60 分钟退避，
        // 否则密码改错的用户每开一次 App 就撞一次登录，容易被学校的连续失败锁定
        if (!force && store.shouldSkip()) return@withContext Result.Skipped
        if (!force && store.failCount >= 3 && store.lastAttemptAt > 0 &&
            System.currentTimeMillis() - store.lastAttemptAt > 6 * 60 * 60 * 1000L
        ) {
            // 停了 6 小时以上，给一次机会再试
            store.failCount = 0
        }

        if (!gate.compareAndSet(false, true)) return@withContext Result.Failed(BUSY)

        try {
            val base = store.baseUrl
            val user = store.username
            val pass = store.password

            val cached = store.load()
            val year: Int
            val term: Int
            if (cached != null && !TermCalendar.isTermOver(cached)) {
                // 还在学期内，继续拉这一份 —— 用户手动导入的旧学期不该被自动改掉
                year = cached.year
                term = cached.term
            } else {
                // 学期已经过完（或者压根没导入过），按当前日期推一个
                val guess = TermCalendar.defaultTerm()
                val keyParts = store.termKey.split('-')
                val y = keyParts.getOrNull(0)?.toIntOrNull() ?: 0
                val t = keyParts.getOrNull(1)?.toIntOrNull() ?: 0
                val keyTermOver = if (y > 0 && t > 0) {
                    val probe = Timetable(courses = emptyList(), year = y, term = t)
                    TermCalendar.isTermOver(probe)
                } else {
                    true
                }
                if (!keyTermOver) {
                    year = y
                    term = t
                } else {
                    year = guess.first
                    term = guess.second
                }
            }

            // 这个学期上次已经确认过「还没排课」：别再拿密码去撞一次登录。
            // 开学前一周每天开好几次 App，每次都是一次完整登录 —— 学校的失败锁定和互踢是真会触发的。
            if (!force && store.emptyTermKey == "$year-$term" &&
                System.currentTimeMillis() - store.emptyCheckedAt < EMPTY_RECHECK_MS
            ) {
                return@withContext Result.EmptyTerm
            }

            // 先试上次留下的 session：能直接拉就不必拿密码登录（正方有频率限制和异地互踢）
            var session: JwglxtSession? = null
            var fresh: Timetable? = null
            val cachedCookie = store.sessionCookie
            if (cachedCookie.isNotBlank()) {
                val reused = JwglxtSession(base, initialCookie = cachedCookie)
                fresh = runCatching { reused.fetchTimetable(year, term) }.getOrNull()
                if (fresh != null) {
                    session = reused
                } else {
                    // 过期的 cookie 留着没用，清掉免得每次启动先打一次注定失败的请求
                    store.sessionCookie = ""
                }
            }

            if (fresh == null) {
                val s2 = JwglxtSession(base)
                val page = s2.openLoginPage()
                if (page.needCaptcha) {
                    store.markSync(false)
                    return@withContext Result.NeedCaptcha
                }
                s2.login(user, pass, null, page)
                session = s2
                fresh = s2.fetchTimetable(year, term)
                // 登录成功，把新会话存下来给下次用
                s2.sessionCookie?.let { store.sessionCookie = it }
            }

            // 开学日期：同一学期才沿用（那份可能是用户校准过的），换学期要按新学期重估
            val tt = fresh ?: throw JwglxtException("没拿到课表数据")
            val startDate = TermCalendar.startDateFor(tt, cached)

            val termKeyNow = "$year-$term"
            val wrote = store.save(tt.copy(startDate = startDate))
            // termKey 记的是「最后一次确认的学期」：空表也是一次有效确认，下次直接用它，不必重推
            store.termKey = termKeyNow
            // 只要这次拿到的是空表，就记下「这个学期还没排课」—— 包括第一次用、盘上本来就没课表的情况，
            // 否则每次启动都要为同一个空学期完整登录一次。拿到课时 TimetableStore.save() 会把这个记号清掉。
            if (tt.courses.isEmpty()) {
                store.emptyTermKey = termKeyNow
                store.emptyCheckedAt = System.currentTimeMillis()
            }
            store.markSync(true)
            if (!wrote) Result.EmptyTerm else Result.Updated
        } catch (e: JwglxtException) {
            store.markSync(false)
            Result.Failed(e.message ?: "同步失败")
        } catch (e: Throwable) {
            store.markSync(false)
            Result.Failed("网络不通，或者学校系统暂时没响应")
        } finally {
            gate.set(false)
        }
    }
}
