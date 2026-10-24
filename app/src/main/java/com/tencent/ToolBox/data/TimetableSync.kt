package com.tencent.ToolBox.data

import com.tencent.ToolBox.net.JwglxtException
import com.tencent.ToolBox.net.JwglxtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 静默同步：用本地存的账号在后台重新拉一次课表。
 *
 * 打开 App 时跑一次，拉到就覆盖本地缓存并把 [TimetableStore.timetableFlow] 推给界面。
 * 全程不打扰用户 —— 失败（没网、密码改了、学校换了登录方式、需要验证码）就安静地留旧数据，
 * 界面上的「上次更新」会露出时间，用户想手动来一次还是点「重新导入」。
 */
object TimetableSync {

    sealed interface Result {
        /** 拉到了，已存好 */
        data object Updated : Result

        /** 没有可用账号，什么都不做 */
        data object NoAccount : Result

        /** 这套 deployments 要验证码，静默路径没法填，跳过 */
        data object NeedCaptcha : Result

        data class Failed(val reason: String) : Result
    }

    @Volatile
    private var running = false

    suspend fun refresh(store: TimetableStore): Result = withContext(Dispatchers.IO) {
        if (running) return@withContext Result.Failed("正在同步")
        if (!store.hasAccount) return@withContext Result.NoAccount

        running = true
        try {
            val base = store.baseUrl
            val user = store.username
            val pass = store.password

            val cached = store.load()
            val year: Int
            val term: Int
            if (cached != null) {
                year = cached.year
                term = cached.term
            } else {
                val (y, t) = store.termKey.split('-').let { parts ->
                    (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
                }
                if (y > 0 && t > 0) {
                    year = y
                    term = t
                } else {
                    // 没导入过就按当前月份猜一个学期，和导入弹窗的默认值保持一致
                    val cal = java.util.Calendar.getInstance()
                    val m = cal.get(java.util.Calendar.MONTH) + 1
                    if (m >= 8) {
                        year = cal.get(java.util.Calendar.YEAR)
                        term = 3
                    } else {
                        year = cal.get(java.util.Calendar.YEAR) - 1
                        term = 12
                    }
                }
            }

            val session = JwglxtSession(base)
            val page = session.openLoginPage()
            if (page.needCaptcha) {
                store.markSync(false)
                return@withContext Result.NeedCaptcha
            }

            session.login(user, pass, null, page)
            val fresh = session.fetchTimetable(year, term)

            // 开学日期沿用用户校准过的；没有就按学期估
            val startDate = cached?.startDate?.takeIf { it.isNotBlank() }
                ?: TermCalendar.format(TermCalendar.estimateStart(year, term))

            store.save(fresh.copy(startDate = startDate))
            store.termKey = "$year-$term"
            store.markSync(true)
            Result.Updated
        } catch (e: JwglxtException) {
            store.markSync(false)
            Result.Failed(e.message ?: "同步失败")
        } catch (e: Throwable) {
            store.markSync(false)
            Result.Failed("网络不通，或者学校系统暂时没响应")
        } finally {
            running = false
        }
    }
}
